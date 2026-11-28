package app.voidhack.extension.applemusic.quality;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Userspace USB Audio Class (UAC 1.0 / UAC 2.0) exclusive bit-perfect audio engine.
 *
 * Routes music PCM around Android's AudioTrack, AudioFlinger, and vendor USB Audio HAL
 * via Linux usbdevfs isochronous transfers (libvoidhack_usb_audio.so), enabling true
 * bit-perfect playback even on phones whose HAL does not expose Android 14's
 * MIXER_BEHAVIOR_BIT_PERFECT.
 */
public final class UsbDirectAudioEngine {
    private static final String TAG = "VoidhackUsbDirect";
    private static final String PREFS_NAME = "voidhack_audio_quality";
    private static final String KEY_USB_DIRECT_ENABLED = "usb_direct_driver_enabled";
    private static final String ACTION_USB_PERMISSION_SUFFIX = ".VOIDHACK_USB_AUDIO_PERMISSION";
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    public static final long POSITION_NOT_HANDLED = Long.MIN_VALUE + 1L;
    public static final String ERR_NONE = "";
    public static final String ERR_NO_NATIVE = "NO_NATIVE";
    public static final String ERR_NO_PERMISSION = "NO_PERMISSION";
    public static final String ERR_RATE_UNSUPPORTED = "RATE_UNSUPPORTED";
    public static final String ERR_CHANNEL_UNSUPPORTED = "CHANNEL_UNSUPPORTED";
    public static final String ERR_OPEN_FAILED = "OPEN_FAILED";

    private static final int QUEUE_CAPACITY = 128;
    private static final int QUEUE_BACKPRESSURE_THRESHOLD = 16;

    private static final Object LOCK = new Object();
    private static final Object CTRL_LOCK = new Object();
    private static final Object NATIVE_HANDLE_LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService CTRL_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoidhackUsbControl");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Object, SinkFormat> SINK_FORMATS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> SINK_PLAYING =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Context appContext;
    private static volatile boolean prefLoaded;
    private static volatile boolean usbDirectEnabled;
    private static volatile boolean usbReceiverRegistered;
    private static volatile boolean volumeObserverRegistered;

    // Hardware UAC Feature Unit volume state (zero PCM modification!)
    private static volatile boolean hwVolumeSupported;
    private static volatile boolean hwVolumeUsePerChannel;
    private static volatile int hwVolumeMinDb256;
    private static volatile int hwVolumeMaxDb256;
    private static volatile int hwVolumeResDb256 = 256;
    private static volatile int hwVolumeCurrentDb256;
    private static volatile boolean hwVolumeMuted;
    private static volatile float hwVolumePercent = 50f;
    private static volatile int lastSyncedStreamVol = -1;
    private static volatile boolean lastSyncedStreamMuted;
    private static final AtomicBoolean VOLUME_SYNC_SCHEDULED = new AtomicBoolean(false);
    private static volatile int pendingHwDb256;
    private static volatile boolean pendingHwMute;

    // Active USB hardware connection & stream state (guarded by LOCK for mutation)
    private static volatile WeakReference<Object> activeSinkRef = new WeakReference<>(null);
    private static UsbDevice openDevice;
    private static UsbDeviceConnection openConnection;
    private static UsbInterface claimedAcInterface;
    private static UsbInterface claimedAsInterface;
    private static UsbAudioDescriptorParser.ParsedUsbAudioDevice parsedDevice;
    private static int[] queriedUac2Rates = new int[0];
    private static UsbAudioDescriptorParser.AltSettingInfo activeAlt;
    // JNI readers hold NATIVE_HANDLE_LOCK. Teardown unpublishes the handle before stopping the worker.
    private static long nativeHandle;
    private static volatile StreamingWorker worker;

    // Live telemetry & playback clock state
    private static volatile boolean claimingOrActive;
    private static volatile boolean reconnectingKernelDriver;
    private static volatile boolean streamActive;
    private static volatile boolean sinkPlaying;
    private static volatile int activeSampleRateHz;
    private static volatile int activeChannelCount;
    private static volatile int activeBitDepth;
    private static volatile int activeSubslotBytes;
    private static volatile int activeUacVersion;
    private static volatile int activeMaxBitDepth;
    private static volatile int activeMaxSampleRateHz;
    private static volatile String activeDeviceName = "";
    private static volatile String lastErrorReason = ERR_NONE;
    private static volatile int lastErrorRateHz;

    private static volatile boolean startMediaTimeNeedsInit = true;
    private static volatile long startMediaTimeUs;
    private static volatile long lastReportedFrames;
    private static volatile boolean handledEndOfStream;
    private static volatile boolean eosDrained;
    private static final AtomicInteger EXPECTED_FLUSH_SEQ = new AtomicInteger(0);
    private static final AtomicInteger COMPLETED_FLUSH_SEQ = new AtomicInteger(0);

    // Rate-limit repeated failed open attempts on the same format
    private static volatile long nextRetryAtMs;
    private static volatile int failedAttemptRate;
    private static volatile int failedAttemptChannels;

    private enum StreamPreparation {
        READY,
        RETRY,
        UAC2_SETUP_REJECTED
    }

    private UsbDirectAudioEngine() {}

    public static final class StatusSnapshot {
        public final boolean nativeLoaded;
        public final boolean deviceAttached;
        public final boolean permissionGranted;
        public final boolean settingEnabled;
        public final boolean streamActive;
        public final String deviceName;
        public final int uacVersion;
        public final int sampleRateHz;
        public final int bitDepth;
        public final int subslotBytes;
        public final int measuredRateHz;
        public final int maxBitDepth;
        public final int maxSampleRateHz;
        public final String errorReason;
        public final int errorRateHz;
        public final boolean hwVolumeSupported;
        public final boolean hwVolumeMuted;
        public final int hwVolumeDb256;
        public final int hwVolumeMinDb256;
        public final int hwVolumeMaxDb256;
        public final float hwVolumePercent;

        StatusSnapshot(
                boolean nativeLoaded,
                boolean deviceAttached,
                boolean permissionGranted,
                boolean settingEnabled,
                boolean streamActive,
                String deviceName,
                int uacVersion,
                int sampleRateHz,
                int bitDepth,
                int subslotBytes,
                int measuredRateHz,
                int maxBitDepth,
                int maxSampleRateHz,
                String errorReason,
                int errorRateHz,
                boolean hwVolumeSupported,
                boolean hwVolumeMuted,
                int hwVolumeDb256,
                int hwVolumeMinDb256,
                int hwVolumeMaxDb256,
                float hwVolumePercent) {
            this.nativeLoaded = nativeLoaded;
            this.deviceAttached = deviceAttached;
            this.permissionGranted = permissionGranted;
            this.settingEnabled = settingEnabled;
            this.streamActive = streamActive;
            this.deviceName = deviceName;
            this.uacVersion = uacVersion;
            this.sampleRateHz = sampleRateHz;
            this.bitDepth = bitDepth;
            this.subslotBytes = subslotBytes;
            this.measuredRateHz = measuredRateHz;
            this.maxBitDepth = maxBitDepth;
            this.maxSampleRateHz = maxSampleRateHz;
            this.errorReason = errorReason;
            this.errorRateHz = errorRateHz;
            this.hwVolumeSupported = hwVolumeSupported;
            this.hwVolumeMuted = hwVolumeMuted;
            this.hwVolumeDb256 = hwVolumeDb256;
            this.hwVolumeMinDb256 = hwVolumeMinDb256;
            this.hwVolumeMaxDb256 = hwVolumeMaxDb256;
            this.hwVolumePercent = hwVolumePercent;
        }
    }

    // =========================================================================
    // Initialization, Preferences & Permissions
    // =========================================================================

    static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        appContext = app;
        loadPreferenceIfNeeded(app);
        ensureUsbBroadcastReceiverRegistered(app);
        ensureVolumeObserverRegistered(app);
    }

    static boolean isEnabled(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
        }
        loadPreferenceIfNeeded(appContext);
        return usbDirectEnabled;
    }

    static void setEnabled(Context context, boolean enabled) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        usbDirectEnabled = enabled;
        prefLoaded = true;
        if (appContext != null) {
            try {
                SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sp.edit().putBoolean(KEY_USB_DIRECT_ENABLED, enabled).apply();
            } catch (Throwable ignored) {
            }
        }
        nextRetryAtMs = 0L;
        lastErrorReason = ERR_NONE;
        lastErrorRateHz = 0;

        if (enabled) {
            // Avoid conflict with Android 14+ OS HAL preferred mixer when USB Direct is chosen.
            if (AudioQualityBitPerfect.isExclusiveUsbEnabled(appContext)) {
                AudioQualityBitPerfect.setExclusiveUsbEnabled(appContext, false);
            }
            AudioQualityBitPerfect.requestReconfigure();
            AudioQualityMonitor.notifyUsbDirectStateChanged();
        } else {
            // Release userspace claim off the UI thread and briefly hold AudioTrack recreation
            // while kernel snd-usb-audio re-binds so playback resumes on the USB DAC without
            // leaking to the phone speaker.
            reconnectingKernelDriver = true;
            new Thread(() -> {
                try {
                    releaseAll(true);
                    sleepQuietly(250);
                } finally {
                    reconnectingKernelDriver = false;
                    AudioQualityBitPerfect.requestReconfigure();
                    AudioQualityMonitor.notifyUsbDirectStateChanged();
                }
            }, "VoidhackUsbRelease").start();
        }
    }

    static UsbDevice findConnectedUsbAudioDevice(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return null;
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um == null) return null;
            Map<String, UsbDevice> map = um.getDeviceList();
            if (map == null || map.isEmpty()) return null;
            for (UsbDevice dev : map.values()) {
                if (isUsbAudioPlaybackDevice(dev)) {
                    return dev;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean hasUsbPermission(Context context, UsbDevice device) {
        if (device == null) return false;
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return false;
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            return um != null && um.hasPermission(device);
        } catch (Throwable ignored) {
            return false;
        }
    }

    interface PermissionCallback {
        void onResult(boolean granted);
    }

    static void requestUsbPermission(Context context, UsbDevice device, PermissionCallback callback) {
        if (context == null || device == null) {
            if (callback != null) callback.onResult(false);
            return;
        }
        Context app = context.getApplicationContext();
        appContext = app;
        UsbManager um = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        if (um == null) {
            if (callback != null) callback.onResult(false);
            return;
        }
        if (um.hasPermission(device)) {
            if (callback != null) callback.onResult(true);
            return;
        }

        final String action = app.getPackageName() + ACTION_USB_PERMISSION_SUFFIX;
        final AtomicBoolean completed = new AtomicBoolean(false);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !action.equals(intent.getAction())) return;
                if (!completed.compareAndSet(false, true)) return;
                try {
                    app.unregisterReceiver(this);
                } catch (Throwable ignored) {
                }
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        || um.hasPermission(device);
                Log.i(TAG, "USB permission result: granted=" + granted + " device=" + safeDeviceName(device));
                nextRetryAtMs = 0L;
                if (callback != null) {
                    MAIN.post(() -> callback.onResult(granted));
                }
                AudioQualityMonitor.notifyUsbDirectStateChanged();
            }
        };

        try {
            IntentFilter filter = new IntentFilter(action);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }

            Intent permIntent = new Intent(action);
            permIntent.setPackage(app.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(app, 0x5649, permIntent, flags);
            um.requestPermission(device, pi);

            // Safety cleanup if user dismisses system dialog without result after 60s
            MAIN.postDelayed(() -> {
                if (completed.compareAndSet(false, true)) {
                    try {
                        app.unregisterReceiver(receiver);
                    } catch (Throwable ignored) {
                    }
                    boolean granted = um.hasPermission(device);
                    if (callback != null) callback.onResult(granted);
                }
            }, 60_000L);
        } catch (Throwable t) {
            Log.w(TAG, "requestUsbPermission failed", t);
            if (completed.compareAndSet(false, true)) {
                try {
                    app.unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                }
            }
            if (callback != null) callback.onResult(false);
        }
    }

    static StatusSnapshot captureStatus(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        loadPreferenceIfNeeded(ctx);
        boolean nativeOk = UsbDirectNative.isAvailable();
        UsbDevice attached = findConnectedUsbAudioDevice(ctx);
        boolean hasDevice = attached != null;
        boolean hasPerm = hasDevice && hasUsbPermission(ctx, attached);
        boolean active;
        int measuredHz;
        synchronized (NATIVE_HANDLE_LOCK) {
            active = nativeHandle != 0L && UsbDirectNative.nativeIsRunning(nativeHandle);
            measuredHz = active ? UsbDirectNative.nativeGetMeasuredRateHz(nativeHandle) : 0;
        }
        UsbAudioDescriptorParser.ParsedUsbAudioDevice parsed = parsedDevice;

        String devName = active && !activeDeviceName.isEmpty()
                ? activeDeviceName
                : (attached != null ? safeDeviceName(attached) : "");

        return new StatusSnapshot(
                nativeOk,
                hasDevice,
                hasPerm,
                usbDirectEnabled,
                active,
                devName,
                active ? activeUacVersion : (parsed != null ? parsed.uacVersion : 0),
                active ? activeSampleRateHz : 0,
                active ? activeBitDepth : 0,
                active ? activeSubslotBytes : 0,
                measuredHz,
                activeMaxBitDepth,
                activeMaxSampleRateHz,
                lastErrorReason,
                lastErrorRateHz,
                active && hwVolumeSupported,
                active && hwVolumeMuted,
                active ? hwVolumeCurrentDb256 : 0,
                active ? hwVolumeMinDb256 : 0,
                active ? hwVolumeMaxDb256 : 0,
                active ? hwVolumePercent : 0f
        );
    }

    /**
     * Adjusts the USB DAC's hardware UAC Feature Unit volume (0..100%) without
     * modifying PCM audio samples. Keeps Android STREAM_MUSIC in sync cleanly.
     */
    static void setHardwareVolumePercent(Context context, float percent) {
        if (!streamActive || !hwVolumeSupported) return;
        float clamped = Math.max(0f, Math.min(100f, percent));
        boolean mute = clamped <= 0.01f;
        int targetDb256 = UsbAudioDescriptorParser.percentToVolumeDb256(
                clamped, hwVolumeMinDb256, hwVolumeMaxDb256, hwVolumeResDb256);

        hwVolumePercent = clamped;
        hwVolumeMuted = mute;
        hwVolumeCurrentDb256 = targetDb256;

        // Sync Android STREAM_MUSIC quietly so physical volume keys pick up from this level
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx != null) {
            try {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    int minV = Build.VERSION.SDK_INT >= 28 ? am.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0;
                    int maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    if (maxV > minV) {
                        int step = mute ? minV : Math.max(minV + 1, minV + Math.round((clamped / 100f) * (maxV - minV)));
                        step = Math.max(minV, Math.min(maxV, step));
                        lastSyncedStreamVol = step;
                        lastSyncedStreamMuted = mute;
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        scheduleHardwareVolumeTransfer(targetDb256, mute);
    }

    // =========================================================================
    // Bytecode Hook Entry Points (DefaultAudioSink & NoisyAudioReceiver)
    // =========================================================================

    /**
     * Hooked into NoisyAudioReceiver.onReceive().
     * Suppresses the spurious ACTION_AUDIO_BECOMING_NOISY triggered when
     * claimInterface(force=true) detaches Linux snd-usb-audio while the physical
     * USB DAC is still attached. Real physical unplugs are NOT suppressed.
     */
    public static boolean shouldSuppressBecomingNoisy() {
        if (!claimingOrActive && !streamActive && !reconnectingKernelDriver) return false;
        return findConnectedUsbAudioDevice(appContext) != null;
    }

    public static boolean isActiveForSink(Object sink) {
        return isOwnedBy(sink) && (streamActive || reconnectingKernelDriver);
    }

    public static void onSinkConfigure(Object sink, int inputEncoding, int channelCount, int sampleRate) {
        if (sink == null) return;
        SINK_FORMATS.put(sink, new SinkFormat(inputEncoding, channelCount, sampleRate));
        nextRetryAtMs = 0L;
    }

    /**
     * Hooked into DefaultAudioSink.handleBuffer(ByteBuffer, long).
     * Returns:
     *   1  -> buffer consumed by USB Direct (DefaultAudioSink should return true)
     *   0  -> backpressure (DefaultAudioSink should return false)
     *  -1  -> not handled (fall back to DefaultAudioSink's standard AudioTrack path)
     */
    public static int tryHandleBuffer(Object sink, ByteBuffer buffer, long presentationTimeUs) {
        if (sink == null || buffer == null) return -1;
        if (reconnectingKernelDriver) {
            return 0; // Hold playback briefly while kernel snd-usb-audio re-binds
        }
        Context ctx = appContext;
        if (ctx == null || !isEnabled(ctx)) {
            if (isOwnedBy(sink)) {
                releaseAll(true);
            }
            return -1;
        }
        if (!UsbDirectNative.isAvailable()) {
            lastErrorReason = ERR_NO_NATIVE;
            return -1;
        }

        SinkFormat fmt = SINK_FORMATS.get(sink);
        if (fmt == null || !isSupportedLinearPcmEncoding(fmt.encoding)
                || fmt.sampleRate <= 0 || fmt.channelCount <= 0) {
            if (isOwnedBy(sink)) {
                releaseStreamOnly();
            }
            return -1;
        }

        // If the worker died unexpectedly (e.g. DAC unplugged mid-stream), clean up and fall back.
        StreamingWorker currentWorker = worker;
        if (isOwnedBy(sink) && streamActive && (currentWorker == null || currentWorker.hasFailed())) {
            Log.w(TAG, "USB Direct streaming worker stopped/failed; falling back to standard AudioTrack");
            releaseAll(false);
            AudioQualityMonitor.notifyUsbDirectStateChanged();
            return -1;
        }

        // Ensure native USB stream is open and configured for this format.
        if (!isOwnedBy(sink) || !streamActive || !isFormatCompatibleWithActiveStream(fmt)) {
            long now = System.currentTimeMillis();
            if (now < nextRetryAtMs && fmt.sampleRate == failedAttemptRate
                    && fmt.channelCount == failedAttemptChannels) {
                return -1;
            }
            StreamPreparation result = prepareStreamForSink(sink, fmt);
            if (result != StreamPreparation.READY) {
                failedAttemptRate = fmt.sampleRate;
                failedAttemptChannels = fmt.channelCount;
                // Keep the Android route active until the format, USB connection, or preference changes.
                nextRetryAtMs = result == StreamPreparation.UAC2_SETUP_REJECTED
                        ? Long.MAX_VALUE : System.currentTimeMillis() + 2000L;
                return -1;
            }
        }

        StreamingWorker w = worker;
        if (w == null || !streamActive) return -1;

        if (!buffer.hasRemaining()) {
            return 1;
        }

        // Backpressure: pace ExoPlayer's render thread to the USB DAC crystal clock.
        if (w.audioQueueSize() >= QUEUE_BACKPRESSURE_THRESHOLD) {
            return 0;
        }

        if (startMediaTimeNeedsInit) {
            startMediaTimeUs = Math.max(0L, presentationTimeUs);
            lastReportedFrames = 0L;
            startMediaTimeNeedsInit = false;
            handledEndOfStream = false;
            eosDrained = false;
        }

        byte[] copy = new byte[buffer.remaining()];
        int position = buffer.position();
        buffer.get(copy);
        if (!w.enqueuePcm(copy, fmt.encoding)) {
            buffer.position(position);
            return 0;
        }
        boolean isPlayingNow = Boolean.TRUE.equals(SINK_PLAYING.get(sink)) || sinkPlaying;
        w.setPaused(!isPlayingNow);
        return 1;
    }

    public static long getCurrentPositionUs(Object sink, boolean sourceEnded) {
        synchronized (NATIVE_HANDLE_LOCK) {
            long h = nativeHandle;
            int rate = activeSampleRateHz;
            if (!isOwnedBy(sink) || h == 0L || rate <= 0 || startMediaTimeNeedsInit
                    || COMPLETED_FLUSH_SEQ.get() != EXPECTED_FLUSH_SEQ.get()) {
                return Long.MIN_VALUE; // AudioSink.CURRENT_POSITION_NOT_SET
            }
            long written = UsbDirectNative.nativeGetFramesWritten(h);
            int inFlight = UsbDirectNative.nativeGetUrbsInFlight(h);
            int framesPerUrb = UsbDirectNative.nativeGetFramesPerUrb(h);
            long played = written - ((long) inFlight * framesPerUrb);
            lastReportedFrames = Math.max(lastReportedFrames, played);
            return startMediaTimeUs + (lastReportedFrames * 1_000_000L) / rate;
        }
    }

    public static int tryHasPendingData(Object sink) {
        synchronized (NATIVE_HANDLE_LOCK) {
            if (!isOwnedBy(sink) || nativeHandle == 0L) return -1;
            if (handledEndOfStream) return eosDrained ? 0 : 1;
            StreamingWorker w = worker;
            if (w != null && w.hasPendingAudio()) return 1;
            return UsbDirectNative.nativeGetUrbsInFlight(nativeHandle) > 0 ? 1 : 0;
        }
    }

    public static int tryIsEnded(Object sink) {
        if (!isOwnedBy(sink) || !streamActive) return -1;
        return (handledEndOfStream && eosDrained) ? 1 : 0;
    }

    public static boolean tryPlayToEndOfStream(Object sink) {
        if (!isOwnedBy(sink) || !streamActive) return false;
        if (!handledEndOfStream) {
            handledEndOfStream = true;
            eosDrained = false;
            StreamingWorker w = worker;
            if (w != null) {
                w.enqueueEosDrain();
            } else {
                eosDrained = true;
            }
        }
        return true;
    }

    public static void onSinkPlay(Object sink) {
        if (sink == null) return;
        SINK_PLAYING.put(sink, Boolean.TRUE);
        if (isOwnedBy(sink) || activeSinkRef.get() == null) {
            sinkPlaying = true;
        }
        if (isOwnedBy(sink)) {
            StreamingWorker w = worker;
            if (w != null) w.setPaused(false);
        }
    }

    public static void onSinkPause(Object sink) {
        if (sink == null) return;
        SINK_PLAYING.put(sink, Boolean.FALSE);
        if (isOwnedBy(sink)) {
            sinkPlaying = false;
            StreamingWorker w = worker;
            if (w != null) w.setPaused(true);
        }
    }

    public static void onSinkFlush(Object sink) {
        if (!isOwnedBy(sink)) return;
        startMediaTimeNeedsInit = true;
        lastReportedFrames = 0L;
        handledEndOfStream = false;
        eosDrained = false;
        int seq = EXPECTED_FLUSH_SEQ.incrementAndGet();
        StreamingWorker w = worker;
        if (w != null) {
            w.enqueueFlush(seq);
        } else {
            COMPLETED_FLUSH_SEQ.set(seq);
        }
    }

    public static void onSinkReset(Object sink) {
        if (sink != null) {
            SINK_PLAYING.put(sink, Boolean.FALSE);
        }
        if (!isOwnedBy(sink)) return;
        sinkPlaying = false;
        onSinkFlush(sink);
        if (!usbDirectEnabled) {
            releaseAll(true);
        } else {
            // Pause & drain URBs between tracks while keeping USB device claimed for fast switch
            StreamingWorker w = worker;
            if (w != null) w.setPaused(true);
        }
    }

    // =========================================================================
    // USB Device Setup, UAC 1.0 / UAC 2.0 Control Transfers & Lifecycle
    // =========================================================================

    private static StreamPreparation prepareStreamForSink(Object sink, SinkFormat fmt) {
        synchronized (LOCK) {
            Context ctx = appContext;
            if (ctx == null || !usbDirectEnabled) return StreamPreparation.RETRY;

            UsbDevice device = findConnectedUsbAudioDevice(ctx);
            if (device == null) {
                releaseAllLocked(false);
                lastErrorReason = ERR_NONE;
                return StreamPreparation.RETRY;
            }
            if (!hasUsbPermission(ctx, device)) {
                lastErrorReason = ERR_NO_PERMISSION;
                AudioQualityMonitor.notifyUsbDirectStateChanged();
                return StreamPreparation.RETRY;
            }

            // Re-check inside lock if already compatible
            synchronized (NATIVE_HANDLE_LOCK) {
                if (nativeHandle != 0L && UsbDirectNative.nativeIsRunning(nativeHandle)
                        && openDevice != null && openDevice.getDeviceId() == device.getDeviceId()
                        && isFormatCompatibleWithActiveStream(fmt)) {
                    activeSinkRef = new WeakReference<>(sink);
                    return StreamPreparation.READY;
                }
            }

            // Stop previous ISO stream first (must drain URBs before setAlt(0) / rate change)
            stopAndDestroyStreamLocked();

            // Open USB device if not already open (or if different device plugged in)
            if (openConnection == null || openDevice == null
                    || openDevice.getDeviceId() != device.getDeviceId()
                    || parsedDevice == null) {
                closeConnectionLocked(false);
                if (!openAndClaimDeviceLocked(ctx, device)) {
                    lastErrorReason = ERR_OPEN_FAILED;
                    AudioQualityMonitor.notifyUsbDirectStateChanged();
                    return StreamPreparation.RETRY;
                }
            }

            int sourceBits = AudioQualityModels.bitDepthFromEncoding(fmt.encoding);
            if (sourceBits <= 0) sourceBits = AudioQualityMonitor.getCurrentSourceBitDepth(fmt.encoding);

            // Verify DAC supports the requested sample rate before touching hardware clock
            if (!isSampleRateSupportedByOpenDeviceLocked(fmt.sampleRate)) {
                Log.w(TAG, "USB DAC does not support " + fmt.sampleRate + " Hz; falling back to standard path");
                lastErrorReason = ERR_RATE_UNSUPPORTED;
                lastErrorRateHz = fmt.sampleRate;
                releaseAllLocked(true);
                AudioQualityMonitor.notifyUsbDirectStateChanged();
                return StreamPreparation.RETRY;
            }

            UsbAudioDescriptorParser.AltSettingInfo chosenAlt =
                    UsbAudioDescriptorParser.selectBestAltSetting(
                            parsedDevice, sourceBits, fmt.sampleRate, fmt.channelCount);
            if (chosenAlt == null) {
                Log.w(TAG, "USB DAC has no matching PCM alt setting for " + fmt.channelCount + "ch / " + fmt.sampleRate + " Hz");
                lastErrorReason = ERR_CHANNEL_UNSUPPORTED;
                lastErrorRateHz = fmt.sampleRate;
                releaseAllLocked(true);
                AudioQualityMonitor.notifyUsbDirectStateChanged();
                return StreamPreparation.RETRY;
            }

            // Claim the chosen AudioStreaming interface if it differs from the previously claimed one
            if (!ensureStreamingInterfaceClaimedLocked(chosenAlt.interfaceNumber)) {
                lastErrorReason = ERR_OPEN_FAILED;
                releaseAllLocked(false);
                AudioQualityMonitor.notifyUsbDirectStateChanged();
                return StreamPreparation.RETRY;
            }

            // Configure UAC 1.0 or UAC 2.0 sample rate + alt setting (with Java setInterface for xHCI ISO BW)
            int confirmedRate = configureSampleRateAndAltLocked(chosenAlt, fmt.sampleRate);
            if (confirmedRate <= 0 && chosenAlt.uacVersion == 2) {
                lastErrorReason = ERR_OPEN_FAILED;
                releaseAllLocked(true);
                AudioQualityMonitor.notifyUsbDirectStateChanged();
                return StreamPreparation.UAC2_SETUP_REJECTED;
            }
            if (confirmedRate <= 0) {
                // Retry once by reopening the USB device in case of stale fd
                Log.w(TAG, "Initial rate/alt setup failed; reopening USB device once");
                closeConnectionLocked(true);
                if (!openAndClaimDeviceLocked(ctx, device)
                        || !ensureStreamingInterfaceClaimedLocked(chosenAlt.interfaceNumber)) {
                    lastErrorReason = ERR_OPEN_FAILED;
                    releaseAllLocked(true);
                    AudioQualityMonitor.notifyUsbDirectStateChanged();
                    return StreamPreparation.RETRY;
                }
                confirmedRate = configureSampleRateAndAltLocked(chosenAlt, fmt.sampleRate);
                if (confirmedRate <= 0) {
                    lastErrorReason = ERR_OPEN_FAILED;
                    releaseAllLocked(true);
                    AudioQualityMonitor.notifyUsbDirectStateChanged();
                    return StreamPreparation.RETRY;
                }
            }

            // Probe & initialize bit-perfect UAC Feature Unit hardware volume (and unmute)
            probeAndInitHardwareVolumeLocked(ctx, fmt.channelCount);

            int fd = openConnection.getFileDescriptor();
            long h = UsbDirectNative.nativeCreate(
                    fd,
                    chosenAlt.interfaceNumber,
                    chosenAlt.endpointOut,
                    chosenAlt.endpointFeedback,
                    chosenAlt.feedbackMaxPacketSize,
                    confirmedRate,
                    fmt.channelCount,
                    chosenAlt.subslotBytes,
                    chosenAlt.bitResolution,
                    chosenAlt.maxPacketSize,
                    chosenAlt.isHighSpeedTransport(),
                    chosenAlt.bInterval
            );
            if (h == 0L || !UsbDirectNative.nativeStart(h)) {
                if (h != 0L) UsbDirectNative.nativeDestroy(h);
                lastErrorReason = ERR_OPEN_FAILED;
                releaseAllLocked(false);
                AudioQualityMonitor.notifyUsbDirectStateChanged();
                return StreamPreparation.RETRY;
            }

            activeAlt = chosenAlt;
            activeSinkRef = new WeakReference<>(sink);
            activeSampleRateHz = confirmedRate;
            activeChannelCount = fmt.channelCount;
            activeBitDepth = chosenAlt.bitResolution;
            activeSubslotBytes = chosenAlt.subslotBytes;
            activeUacVersion = chosenAlt.uacVersion;
            activeDeviceName = safeDeviceName(device);
            lastErrorReason = ERR_NONE;
            lastErrorRateHz = 0;

            startMediaTimeNeedsInit = true;
            lastReportedFrames = 0L;
            handledEndOfStream = false;
            eosDrained = false;
            int seq = EXPECTED_FLUSH_SEQ.incrementAndGet();
            COMPLETED_FLUSH_SEQ.set(seq);

            StreamingWorker w = new StreamingWorker(h);
            boolean playingNow = Boolean.TRUE.equals(SINK_PLAYING.get(sink)) || sinkPlaying;
            sinkPlaying = playingNow;
            w.setPaused(!playingNow);
            w.start();
            synchronized (NATIVE_HANDLE_LOCK) {
                nativeHandle = h;
                worker = w;
                streamActive = true;
            }

            Log.i(TAG, "USB Direct ACTIVE: " + activeDeviceName
                    + " UAC" + activeUacVersion + ".0 "
                    + activeBitDepth + "-bit (subslot=" + activeSubslotBytes + "B) / "
                    + activeSampleRateHz + " Hz alt=" + chosenAlt.altSetting
                    + " epOut=0x" + Integer.toHexString(chosenAlt.endpointOut)
                    + " epFb=0x" + Integer.toHexString(chosenAlt.endpointFeedback));

            AudioQualityMonitor.notifyUsbDirectStateChanged();
            return StreamPreparation.READY;
        }
    }

    private static boolean openAndClaimDeviceLocked(Context ctx, UsbDevice device) {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) return false;

        claimingOrActive = true;
        UsbDeviceConnection conn = null;
        try {
            conn = um.openDevice(device);
            if (conn == null) {
                claimingOrActive = false;
                return false;
            }
            byte[] raw = conn.getRawDescriptors();
            UsbAudioDescriptorParser.ParsedUsbAudioDevice parsed = UsbAudioDescriptorParser.parse(raw);
            if (!parsed.isValidPlaybackDevice()) {
                Log.w(TAG, "Parsed USB descriptors contain no PCM playback alt settings");
                conn.close();
                claimingOrActive = false;
                return false;
            }

            // Claim AudioControl interface with force=true to detach kernel snd-usb-audio
            UsbInterface acIface = findUsbInterface(device, parsed.audioControlInterfaceId, 0);
            if (acIface == null) {
                acIface = findUsbInterfaceBySubclass(device, 1);
            }
            if (acIface != null) {
                boolean acClaimed = conn.claimInterface(acIface, true);
                Log.i(TAG, "Claimed AudioControl iface=" + acIface.getId() + " force=true: " + acClaimed);
                if (acClaimed) claimedAcInterface = acIface;
            }

            // Claim first AudioStreaming playback interface with force=true
            int firstAsId = parsed.playbackAltSettings.get(0).interfaceNumber;
            UsbInterface asIface = findUsbInterface(device, firstAsId, 0);
            if (asIface == null) {
                asIface = findUsbInterfaceById(device, firstAsId);
            }
            if (asIface == null || !conn.claimInterface(asIface, true)) {
                Log.e(TAG, "Failed to claim AudioStreaming iface=" + firstAsId);
                if (claimedAcInterface != null) {
                    conn.releaseInterface(claimedAcInterface);
                    claimedAcInterface = null;
                }
                conn.close();
                claimingOrActive = false;
                return false;
            }
            claimedAsInterface = asIface;

            // Reset to alt=0 (zero bandwidth) to stop any lingering kernel stream
            UsbInterface zeroAlt = findUsbInterface(device, firstAsId, 0);
            if (zeroAlt != null) {
                conn.setInterface(zeroAlt);
            }
            sleepQuietly(60);

            openDevice = device;
            openConnection = conn;
            parsedDevice = parsed;

            // Query UAC2 supported sample rates via RANGE control transfer
            Uac2Clock commonClock = parsed.commonPlaybackClock();
            queriedUac2Rates = commonClock != null ? commonClock.queryRates(conn::controlTransfer) : new int[0];

            activeMaxBitDepth = parsed.getMaxBitDepth();
            int maxRate = queriedUac2Rates.length > 0
                    ? AudioQualityModels.maxSampleRate(queriedUac2Rates)
                    : parsed.getMaxDescriptorSampleRateHz();
            activeMaxSampleRateHz = maxRate;
            activeDeviceName = safeDeviceName(device);

            Log.i(TAG, "Opened USB DAC: " + activeDeviceName + " UAC" + parsed.uacVersion
                    + ".0 acIface=" + parsed.audioControlInterfaceId
                    + " clockId=0x" + Integer.toHexString(commonClock != null ? commonClock.sourceId : 0)
                    + " fuId=" + parsed.featureUnitId
                    + " alts=" + parsed.playbackAltSettings.size()
                    + " maxBits=" + activeMaxBitDepth + " maxRate=" + activeMaxSampleRateHz);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "openAndClaimDeviceLocked failed", t);
            if (conn != null) {
                try {
                    if (claimedAsInterface != null) conn.releaseInterface(claimedAsInterface);
                    if (claimedAcInterface != null) conn.releaseInterface(claimedAcInterface);
                    conn.close();
                } catch (Throwable ignored) {
                }
            }
            claimedAcInterface = null;
            claimedAsInterface = null;
            claimingOrActive = false;
            return false;
        }
    }

    private static boolean ensureStreamingInterfaceClaimedLocked(int targetAsIfaceId) {
        if (openConnection == null || openDevice == null) return false;
        if (claimedAsInterface != null && claimedAsInterface.getId() == targetAsIfaceId) {
            return true;
        }
        if (claimedAsInterface != null) {
            try {
                openConnection.releaseInterface(claimedAsInterface);
            } catch (Throwable ignored) {
            }
            claimedAsInterface = null;
        }
        UsbInterface next = findUsbInterface(openDevice, targetAsIfaceId, 0);
        if (next == null) next = findUsbInterfaceById(openDevice, targetAsIfaceId);
        if (next == null || !openConnection.claimInterface(next, true)) {
            return false;
        }
        claimedAsInterface = next;
        return true;
    }

    private static int configureSampleRateAndAltLocked(
            UsbAudioDescriptorParser.AltSettingInfo alt,
            int sampleRateHz) {
        UsbDeviceConnection conn = openConnection;
        UsbDevice dev = openDevice;
        UsbAudioDescriptorParser.ParsedUsbAudioDevice parsed = parsedDevice;
        if (conn == null || dev == null || parsed == null) return -1;

        UsbInterface zeroAlt = findUsbInterface(dev, alt.interfaceNumber, 0);
        UsbInterface targetAlt = findUsbInterface(dev, alt.interfaceNumber, alt.altSetting);
        if (targetAlt == null) {
            Log.e(TAG, "No UsbInterface found for iface=" + alt.interfaceNumber + " alt=" + alt.altSetting);
            return -1;
        }

        if (alt.uacVersion == 2) {
            Uac2Clock clock = parsed.clockFor(alt);
            if (clock == null) {
                Log.w(TAG, "Cannot resolve the UAC2 clock for playback terminal " + alt.terminalLink);
                return -1;
            }
            if (zeroAlt != null && !conn.setInterface(zeroAlt)) {
                return -1;
            }
            if (!clock.setRateIfWritable(conn::controlTransfer, sampleRateHz)) {
                Log.w(TAG, "UAC2 clock " + clock.sourceId + " could not be set to " + sampleRateHz + " Hz");
                return -1;
            }
            if (zeroAlt != null && !conn.setInterface(zeroAlt)) {
                return -1;
            }
            if (!conn.setInterface(targetAlt)) {
                Log.e(TAG, "UAC2 setInterface(alt=" + alt.altSetting + ") failed");
                return -1;
            }
            // Check the clock after activating the endpoint and allowing the DAC PLL to settle.
            sleepQuietly(50);
            if (!clock.confirmsRate(conn::controlTransfer, sampleRateHz)) {
                Log.w(TAG, "UAC2 clock " + clock.sourceId + " did not confirm " + sampleRateHz + " Hz");
                return -1;
            }
            return sampleRateHz;
        } else {
            // UAC 1.0 Full-Speed sequence:
            // 1. setAlt(0) -> clear old state
            if (zeroAlt != null && !conn.setInterface(zeroAlt)) {
                return -1;
            }
            // 2. Activate target alt setting FIRST (endpoint exists in alt > 0) via Java API for ISO BW
            if (!conn.setInterface(targetAlt)) {
                Log.e(TAG, "UAC1 setInterface(alt=" + alt.altSetting + ") failed");
                return -1;
            }
            // 3. Send 3-byte SET_CUR SAMPLING_FREQ_CONTROL to ISO OUT endpoint
            if (alt.epSupportsFreqControl || alt.discreteSampleRates.length != 1) {
                byte[] rate3 = new byte[]{
                        (byte) (sampleRateHz & 0xFF),
                        (byte) ((sampleRateHz >>> 8) & 0xFF),
                        (byte) ((sampleRateHz >>> 16) & 0xFF)
                };
                int ret = conn.controlTransfer(
                        0x22,   // Host-to-Device | Class | Endpoint
                        0x01,   // SET_CUR
                        0x0100, // SAMPLING_FREQ_CONTROL
                        alt.endpointOut,
                        rate3,
                        3,
                        1000
                );
                Log.i(TAG, "UAC1 SET_CUR ep=0x" + Integer.toHexString(alt.endpointOut)
                        + " rate=" + sampleRateHz + " ret=" + ret);
                if (ret >= 3) {
                    byte[] verify = new byte[3];
                    int vret = conn.controlTransfer(
                            0xA2,   // Device-to-Host | Class | Endpoint
                            0x81,   // GET_CUR
                            0x0100,
                            alt.endpointOut,
                            verify,
                            3,
                            1000
                    );
                    if (vret >= 3) {
                        int readBack = (verify[0] & 0xFF)
                                | ((verify[1] & 0xFF) << 8)
                                | ((verify[2] & 0xFF) << 16);
                        Log.i(TAG, "UAC1 GET_CUR readBack=" + readBack);
                        if (readBack > 0 && readBack != sampleRateHz) {
                            return -1;
                        }
                    }
                }
            }
            sleepQuietly(50);
            return sampleRateHz;
        }
    }

    private static void probeAndInitHardwareVolumeLocked(Context ctx, int channels) {
        hwVolumeSupported = false;
        hwVolumeUsePerChannel = false;
        hwVolumeMinDb256 = 0;
        hwVolumeMaxDb256 = 0;
        hwVolumeResDb256 = 256;
        hwVolumeCurrentDb256 = 0;
        hwVolumeMuted = false;

        UsbDeviceConnection conn = openConnection;
        UsbAudioDescriptorParser.ParsedUsbAudioDevice parsed = parsedDevice;
        if (conn == null || parsed == null || parsed.featureUnitId <= 0) return;

        int acIface = Math.max(0, parsed.audioControlInterfaceId);
        int wIndex = (parsed.featureUnitId << 8) | acIface;

        // First probe which channel mode answers volume range queries (Master ch=0 vs Per-Channel ch=1,2).
        boolean[] tryPerChannelOrder = parsed.featureUnitMasterVolume
                ? new boolean[]{false, true}
                : (parsed.featureUnitPerChannelVolume ? new boolean[]{true, false} : new boolean[]{false, true});

        UsbAudioDescriptorParser.VolumeRange foundRange = null;
        boolean usePerCh = false;
        for (boolean perCh : tryPerChannelOrder) {
            int probeCh = perCh ? 1 : 0;
            UsbAudioDescriptorParser.VolumeRange r = queryVolumeRange(conn, parsed.uacVersion, wIndex, probeCh);
            if (r != null && r.isAdjustable()) {
                foundRange = r;
                usePerCh = perCh;
                break;
            }
        }

        if (foundRange != null) {
            // Read current phone STREAM_MUSIC level so USB Direct matches user's volume safely
            float initialPercent = readStreamMusicPercent(ctx);
            boolean initialMute = initialPercent <= 0.01f;
            int targetDb256 = UsbAudioDescriptorParser.percentToVolumeDb256(
                    initialPercent, foundRange.minDb256, foundRange.maxDb256, foundRange.resDb256);

            boolean writeOk = writeHardwareVolumeRaw(
                    conn, parsed, wIndex, usePerCh, Math.max(2, channels), targetDb256, initialMute);
            if (writeOk) {
                int readBack = readVolumeCur(conn, parsed.uacVersion, wIndex, usePerCh ? 1 : 0);
                int effectiveDb = (readBack != Integer.MIN_VALUE) ? readBack : targetDb256;
                hwVolumeSupported = true;
                hwVolumeUsePerChannel = usePerCh;
                hwVolumeMinDb256 = foundRange.minDb256;
                hwVolumeMaxDb256 = foundRange.maxDb256;
                hwVolumeResDb256 = foundRange.resDb256;
                hwVolumeCurrentDb256 = effectiveDb;
                hwVolumeMuted = initialMute;
                hwVolumePercent = initialMute ? 0f : initialPercent;
                Log.i(TAG, "UAC hardware volume READY: fu=" + parsed.featureUnitId
                        + " mode=" + (usePerCh ? "L+R" : "master")
                        + " range=[" + UsbAudioDescriptorParser.formatVolumeDb(foundRange.minDb256)
                        + " .. " + UsbAudioDescriptorParser.formatVolumeDb(foundRange.maxDb256)
                        + "] step=" + UsbAudioDescriptorParser.formatVolumeDb(foundRange.resDb256)
                        + " initial=" + UsbAudioDescriptorParser.formatVolumeDb(effectiveDb)
                        + " (" + Math.round(initialPercent) + "%)");
                return;
            }
        }

        // Fallback if DAC lacks adjustable hardware volume: still unmute Feature Unit if muted at boot
        if (parsed.featureUnitSupportsMute) {
            byte[] unmute = new byte[]{0x00};
            for (int ch = 0; ch <= Math.min(Math.max(1, channels), 2); ch++) {
                conn.controlTransfer(0x21, 0x01, (0x01 << 8) | ch, wIndex, unmute, 1, 300);
            }
        }
    }

    private static UsbAudioDescriptorParser.VolumeRange queryVolumeRange(
            UsbDeviceConnection conn, int uacVersion, int wIndex, int channel) {
        int wValue = (0x02 << 8) | (channel & 0xFF);
        if (uacVersion == 2) {
            // Try 64-byte buffer first, then exact 8-byte buffer for strict UAC2 firmwares
            byte[] buf64 = new byte[64];
            int ret = conn.controlTransfer(0xA1, 0x02, wValue, wIndex, buf64, buf64.length, 400);
            if (ret >= 8) {
                UsbAudioDescriptorParser.VolumeRange r = UsbAudioDescriptorParser.parseUac2VolumeRange(buf64, ret);
                if (r != null) return r;
            }
            byte[] buf8 = new byte[8];
            ret = conn.controlTransfer(0xA1, 0x02, wValue, wIndex, buf8, buf8.length, 400);
            if (ret >= 8) {
                return UsbAudioDescriptorParser.parseUac2VolumeRange(buf8, ret);
            }
            return null;
        } else {
            byte[] minB = new byte[2];
            byte[] maxB = new byte[2];
            byte[] resB = new byte[2];
            int rMin = conn.controlTransfer(0xA1, 0x82, wValue, wIndex, minB, 2, 400); // GET_MIN
            int rMax = conn.controlTransfer(0xA1, 0x83, wValue, wIndex, maxB, 2, 400); // GET_MAX
            if (rMin < 2 || rMax < 2) return null;
            int minVal = (short) ((minB[0] & 0xFF) | ((minB[1] & 0xFF) << 8));
            int maxVal = (short) ((maxB[0] & 0xFF) | ((maxB[1] & 0xFF) << 8));
            int rRes = conn.controlTransfer(0xA1, 0x84, wValue, wIndex, resB, 2, 400); // GET_RES
            int resVal = (rRes >= 2) ? ((resB[0] & 0xFF) | ((resB[1] & 0xFF) << 8)) : 256;
            if (maxVal <= minVal) return null;
            return new UsbAudioDescriptorParser.VolumeRange(minVal, maxVal, Math.max(1, resVal));
        }
    }

    private static int readVolumeCur(UsbDeviceConnection conn, int uacVersion, int wIndex, int channel) {
        byte[] buf = new byte[2];
        int req = (uacVersion == 2) ? 0x01 : 0x81;
        int ret = conn.controlTransfer(0xA1, req, (0x02 << 8) | (channel & 0xFF), wIndex, buf, 2, 300);
        if (ret < 2) return Integer.MIN_VALUE;
        return (short) ((buf[0] & 0xFF) | ((buf[1] & 0xFF) << 8));
    }

    private static boolean writeHardwareVolumeRaw(
            UsbDeviceConnection conn,
            UsbAudioDescriptorParser.ParsedUsbAudioDevice parsed,
            int wIndex,
            boolean usePerChannel,
            int channels,
            int db256,
            boolean mute) {
        if (conn == null || parsed == null) return false;
        byte[] volBytes = new byte[]{
                (byte) (db256 & 0xFF),
                (byte) ((db256 >>> 8) & 0xFF)
        };
        boolean ok = false;
        if (usePerChannel) {
            int chCount = Math.max(1, Math.min(channels, 2));
            for (int ch = 1; ch <= chCount; ch++) {
                int ret = conn.controlTransfer(0x21, 0x01, (0x02 << 8) | ch, wIndex, volBytes, 2, 300);
                if (ret >= 2) ok = true;
            }
        } else {
            int ret = conn.controlTransfer(0x21, 0x01, 0x0200, wIndex, volBytes, 2, 300);
            ok = ret >= 2;
        }

        if (parsed.featureUnitSupportsMute) {
            byte[] muteByte = new byte[]{(byte) (mute ? 0x01 : 0x00)};
            if (parsed.featureUnitMasterMute) {
                conn.controlTransfer(0x21, 0x01, 0x0100, wIndex, muteByte, 1, 250);
            }
            if (parsed.featureUnitPerChannelMute) {
                int chCount = Math.max(1, Math.min(channels, 2));
                for (int ch = 1; ch <= chCount; ch++) {
                    conn.controlTransfer(0x21, 0x01, (0x01 << 8) | ch, wIndex, muteByte, 1, 250);
                }
            }
        }
        return ok;
    }

    private static void scheduleHardwareVolumeTransfer(int db256, boolean mute) {
        pendingHwDb256 = db256;
        pendingHwMute = mute;
        if (!VOLUME_SYNC_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        CTRL_EXECUTOR.execute(() -> {
            while (true) {
                VOLUME_SYNC_SCHEDULED.set(false);
                int targetDb = pendingHwDb256;
                boolean targetMute = pendingHwMute;
                synchronized (CTRL_LOCK) {
                    UsbDeviceConnection conn = openConnection;
                    UsbAudioDescriptorParser.ParsedUsbAudioDevice parsed = parsedDevice;
                    if (conn != null && parsed != null && streamActive && hwVolumeSupported && parsed.featureUnitId > 0) {
                        int acIface = Math.max(0, parsed.audioControlInterfaceId);
                        int wIndex = (parsed.featureUnitId << 8) | acIface;
                        writeHardwareVolumeRaw(
                                conn, parsed, wIndex, hwVolumeUsePerChannel,
                                Math.max(2, activeChannelCount), targetDb, targetMute);
                    }
                }
                if (!VOLUME_SYNC_SCHEDULED.get()) {
                    break;
                }
            }
        });
    }

    private static float readStreamMusicPercent(Context ctx) {
        if (ctx == null) return 50f;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return 50f;
            int minV = Build.VERSION.SDK_INT >= 28 ? am.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0;
            int maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int curV = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
            lastSyncedStreamVol = curV;
            lastSyncedStreamMuted = muted;
            if (muted || maxV <= minV || curV <= minV) return 0f;
            return Math.max(0f, Math.min(100f, 100f * (curV - minV) / (float) (maxV - minV)));
        } catch (Throwable ignored) {
            return 50f;
        }
    }

    private static synchronized void ensureVolumeObserverRegistered(Context app) {
        if (volumeObserverRegistered || app == null) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_VOLUME_CHANGED);
            filter.addAction(ACTION_STREAM_MUTE_CHANGED);
            BroadcastReceiver volReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null || !streamActive || !hwVolumeSupported) return;
                    int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
                    if (stream == AudioManager.STREAM_MUSIC) {
                        syncFromAndroidStreamVolume(app);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(volReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(volReceiver, filter);
            }

            // Backup ContentObserver in case OEM throttles VOLUME_CHANGED_ACTION while screen is off
            ContentObserver obs = new ContentObserver(MAIN) {
                @Override
                public void onChange(boolean selfChange) {
                    if (streamActive && hwVolumeSupported) {
                        syncFromAndroidStreamVolume(app);
                    }
                }
            };
            app.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, obs);
            volumeObserverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private static void syncFromAndroidStreamVolume(Context ctx) {
        if (ctx == null || !streamActive || !hwVolumeSupported) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int minV = Build.VERSION.SDK_INT >= 28 ? am.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0;
            int maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int curV = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
            if (curV == lastSyncedStreamVol && muted == lastSyncedStreamMuted) {
                return;
            }
            lastSyncedStreamVol = curV;
            lastSyncedStreamMuted = muted;

            float pct = (muted || maxV <= minV || curV <= minV)
                    ? 0f
                    : Math.max(0f, Math.min(100f, 100f * (curV - minV) / (float) (maxV - minV)));
            boolean mute = pct <= 0.01f;
            int targetDb = UsbAudioDescriptorParser.percentToVolumeDb256(
                    pct, hwVolumeMinDb256, hwVolumeMaxDb256, hwVolumeResDb256);

            hwVolumePercent = pct;
            hwVolumeMuted = mute;
            hwVolumeCurrentDb256 = targetDb;
            scheduleHardwareVolumeTransfer(targetDb, mute);
            AudioQualityMonitor.notifyUsbDirectStateChanged();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSampleRateSupportedByOpenDeviceLocked(int sampleRateHz) {
        if (parsedDevice == null || sampleRateHz <= 0) return false;
        if (parsedDevice.uacVersion == 2) {
            if (queriedUac2Rates.length == 0) return true; // RANGE unsupported -> try SET_CUR + verify
            return AudioQualityModels.supportsSampleRate(queriedUac2Rates, sampleRateHz);
        }
        for (UsbAudioDescriptorParser.AltSettingInfo alt : parsedDevice.playbackAltSettings) {
            if (alt.isPcm && alt.supportsRateInDescriptor(sampleRateHz)) {
                return true;
            }
        }
        return false;
    }

    private static void releaseStreamOnly() {
        synchronized (LOCK) {
            stopAndDestroyStreamLocked();
        }
        AudioQualityMonitor.notifyUsbDirectStateChanged();
    }

    private static void releaseAll(boolean reconnectKernelDriver) {
        synchronized (LOCK) {
            releaseAllLocked(reconnectKernelDriver);
        }
        AudioQualityMonitor.notifyUsbDirectStateChanged();
    }

    private static void releaseAllLocked(boolean reconnectKernelDriver) {
        stopAndDestroyStreamLocked();
        closeConnectionLocked(reconnectKernelDriver);
        activeSinkRef = new WeakReference<>(null);
        activeSampleRateHz = 0;
        activeChannelCount = 0;
        activeBitDepth = 0;
        activeSubslotBytes = 0;
        activeUacVersion = 0;
        activeAlt = null;
        claimingOrActive = false;
    }

    private static void stopAndDestroyStreamLocked() {
        long h;
        StreamingWorker w;
        synchronized (NATIVE_HANDLE_LOCK) {
            streamActive = false;
            h = nativeHandle;
            nativeHandle = 0L;
            w = worker;
            worker = null;
        }
        if (w != null) w.requestStop();
        if (h != 0L) {
            UsbDirectNative.nativeStop(h);
        }
        if (w != null) w.awaitTermination();
        if (h != 0L) {
            UsbDirectNative.nativeDestroy(h);
        }
        startMediaTimeNeedsInit = true;
        lastReportedFrames = 0L;
        handledEndOfStream = false;
        eosDrained = true;
    }

    private static void closeConnectionLocked(boolean reconnectKernelDriver) {
        UsbDeviceConnection conn;
        UsbDevice dev;
        UsbInterface ac;
        UsbInterface as;
        synchronized (CTRL_LOCK) {
            hwVolumeSupported = false;
            hwVolumeMuted = false;
            conn = openConnection;
            dev = openDevice;
            ac = claimedAcInterface;
            as = claimedAsInterface;
            openConnection = null;
            openDevice = null;
            claimedAcInterface = null;
            claimedAsInterface = null;
            parsedDevice = null;
            queriedUac2Rates = new int[0];
        }

        if (conn != null) {
            int acId = ac != null ? ac.getId() : -1;
            int asId = as != null ? as.getId() : -1;
            try {
                if (dev != null && asId >= 0) {
                    UsbInterface zeroAlt = findUsbInterface(dev, asId, 0);
                    if (zeroAlt != null) conn.setInterface(zeroAlt);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (as != null) conn.releaseInterface(as);
            } catch (Throwable ignored) {
            }
            try {
                if (ac != null) conn.releaseInterface(ac);
            } catch (Throwable ignored) {
            }
            if (reconnectKernelDriver && UsbDirectNative.isAvailable()) {
                try {
                    int fd = conn.getFileDescriptor();
                    if (fd >= 0) {
                        boolean reattached = UsbDirectNative.nativeReconnectKernelDriver(fd, acId, asId);
                        Log.i(TAG, "Reattached kernel snd-usb-audio driver: " + reattached);
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                conn.close();
            } catch (Throwable ignored) {
            }
        }
        claimingOrActive = false;
    }

    // =========================================================================
    // Hot-Plug Broadcast Observer & Helpers
    // =========================================================================

    private static synchronized void ensureUsbBroadcastReceiverRegistered(Context app) {
        if (usbReceiverRegistered || app == null) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                        UsbDevice detached = Build.VERSION.SDK_INT >= 33
                                ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                                : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        CTRL_EXECUTOR.execute(() -> {
                            synchronized (LOCK) {
                                if (openDevice != null && (detached == null
                                        || openDevice.getDeviceId() == detached.getDeviceId())) {
                                    Log.i(TAG, "USB DAC detached; releasing USB Direct stream");
                                    releaseAllLocked(false);
                                    AudioQualityBitPerfect.requestReconfigure();
                                }
                            }
                            AudioQualityMonitor.notifyUsbDirectStateChanged();
                        });
                    } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                        nextRetryAtMs = 0L;
                        lastErrorReason = ERR_NONE;
                        if (usbDirectEnabled) {
                            AudioQualityBitPerfect.requestReconfigure();
                        }
                        AudioQualityMonitor.notifyUsbDirectStateChanged();
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            usbReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private static boolean isOwnedBy(Object sink) {
        return sink != null && activeSinkRef.get() == sink;
    }

    private static boolean isFormatCompatibleWithActiveStream(SinkFormat fmt) {
        if (fmt == null || activeAlt == null) return false;
        if (fmt.sampleRate != activeSampleRateHz || fmt.channelCount != activeChannelCount) {
            return false;
        }
        int srcBits = AudioQualityModels.bitDepthFromEncoding(fmt.encoding);
        // Reuse live stream if the active alt setting accommodates the source bit depth losslessly
        return srcBits <= 0 || activeBitDepth >= srcBits;
    }

    private static boolean isSupportedLinearPcmEncoding(int enc) {
        return enc == AudioQualityModels.ENCODING_PCM_16BIT
                || enc == AudioQualityModels.ENCODING_PCM_24BIT_PACKED
                || enc == AudioQualityModels.ENCODING_PCM_32BIT
                || enc == AudioQualityModels.ENCODING_PCM_FLOAT
                || enc == AudioQualityModels.EXO_ENCODING_PCM_24BIT
                || enc == AudioQualityModels.EXO_ENCODING_PCM_32BIT;
    }

    private static boolean isUsbAudioPlaybackDevice(UsbDevice device) {
        if (device == null) return false;
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO
                    && iface.getInterfaceSubclass() == 2) {
                int epCount = iface.getEndpointCount();
                for (int e = 0; e < epCount; e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC
                            && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static UsbInterface findUsbInterface(UsbDevice device, int ifaceId, int altSetting) {
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getId() == ifaceId && iface.getAlternateSetting() == altSetting) {
                return iface;
            }
        }
        return null;
    }

    private static UsbInterface findUsbInterfaceById(UsbDevice device, int ifaceId) {
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getId() == ifaceId) return iface;
        }
        return null;
    }

    private static UsbInterface findUsbInterfaceBySubclass(UsbDevice device, int subclass) {
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO
                    && iface.getInterfaceSubclass() == subclass) {
                return iface;
            }
        }
        return null;
    }

    private static String safeDeviceName(UsbDevice device) {
        if (device == null) return "";
        try {
            String prod = device.getProductName();
            if (prod != null && !prod.trim().isEmpty()) return prod.trim();
            String mfg = device.getManufacturerName();
            if (mfg != null && !mfg.trim().isEmpty()) return mfg.trim();
        } catch (Throwable ignored) {
        }
        return "USB Audio DAC";
    }

    private static void loadPreferenceIfNeeded(Context ctx) {
        if (prefLoaded || ctx == null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            usbDirectEnabled = sp.getBoolean(KEY_USB_DIRECT_ENABLED, false);
        } catch (Throwable ignored) {
            usbDirectEnabled = false;
        }
        prefLoaded = true;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================
    // Single-Owner High-Priority USB Streaming Worker Thread
    // =========================================================================

    private static final class SinkFormat {
        final int encoding;
        final int channelCount;
        final int sampleRate;

        SinkFormat(int encoding, int channelCount, int sampleRate) {
            this.encoding = encoding;
            this.channelCount = channelCount;
            this.sampleRate = sampleRate;
        }
    }

    private static final class QueueItem {
        static final int KIND_PCM = 0;
        static final int KIND_FLUSH = 1;
        static final int KIND_EOS_DRAIN = 2;
        static final int KIND_STOP = 3;

        final int kind;
        final byte[] data;
        final int encoding;
        final int seq;

        QueueItem(int kind, byte[] data, int encoding, int seq) {
            this.kind = kind;
            this.data = data;
            this.encoding = encoding;
            this.seq = seq;
        }
    }

    private static final class StreamingWorker {
        private final long handle;
        private final ArrayBlockingQueue<QueueItem> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicInteger pendingPcmCount = new AtomicInteger(0);
        private final Object pauseLock = new Object();
        private volatile boolean running = true;
        private volatile boolean paused = false;
        private volatile boolean failed = false;
        private Thread thread;

        StreamingWorker(long handle) {
            this.handle = handle;
        }

        void start() {
            thread = new Thread(this::runLoop, "VoidhackUsbStream");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }

        boolean enqueuePcm(byte[] bytes, int encoding) {
            synchronized (pauseLock) {
                if (!running || failed) return false;
                pendingPcmCount.incrementAndGet();
                if (queue.offer(new QueueItem(QueueItem.KIND_PCM, bytes, encoding, 0))) return true;
                pendingPcmCount.decrementAndGet();
                return false;
            }
        }

        void enqueueFlush(int seq) {
            synchronized (pauseLock) {
                if (!running) return;
                queue.clear();
                pendingPcmCount.set(0);
                queue.offer(new QueueItem(QueueItem.KIND_FLUSH, null, 0, seq));
                pauseLock.notifyAll();
            }
        }

        void enqueueEosDrain() {
            synchronized (pauseLock) {
                if (!running) return;
                queue.offer(new QueueItem(QueueItem.KIND_EOS_DRAIN, null, 0, 0));
                pauseLock.notifyAll();
            }
        }

        void setPaused(boolean pause) {
            synchronized (pauseLock) {
                this.paused = pause;
                pauseLock.notifyAll();
            }
        }

        int audioQueueSize() {
            return Math.max(0, pendingPcmCount.get());
        }

        boolean hasPendingAudio() {
            return pendingPcmCount.get() > 0;
        }

        boolean hasFailed() {
            return failed;
        }

        void requestStop() {
            synchronized (pauseLock) {
                running = false;
                queue.clear();
                pendingPcmCount.set(0);
                queue.offer(new QueueItem(QueueItem.KIND_STOP, null, 0, 0));
                pauseLock.notifyAll();
            }
        }

        void awaitTermination() {
            // Only the connection owner joins the worker; even an interrupted caller must wait.
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        thread.join();
                        return;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void runLoop() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (Throwable ignored) {
            }

            UsbDirectPlaybackActivity playbackActivity = new UsbDirectPlaybackActivity();
            try {
                stream(playbackActivity);
            } finally {
                playbackActivity.release();
            }
        }

        private QueueItem awaitFlushWhilePaused() {
            synchronized (pauseLock) {
                while (running && paused) {
                    QueueItem head = queue.peek();
                    if (head != null && head.kind == QueueItem.KIND_FLUSH) {
                        return queue.poll();
                    }
                    try {
                        pauseLock.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            return null;
        }

        private void stream(UsbDirectPlaybackActivity playbackActivity) {
            boolean drainedWhilePaused = false;
            while (running) {
                if (paused) {
                    if (!drainedWhilePaused) {
                        UsbDirectNative.nativeDrainUrbs(handle);
                        playbackActivity.setPlaying(false);
                        drainedWhilePaused = true;
                    }
                    QueueItem flush = awaitFlushWhilePaused();
                    if (flush != null) {
                        UsbDirectNative.nativeFlush(handle);
                        COMPLETED_FLUSH_SEQ.set(flush.seq);
                    }
                    continue;
                }
                drainedWhilePaused = false;

                QueueItem item;
                try {
                    item = queue.poll(60L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    continue;
                }
                if (!running) return;
                if (item == null) continue;

                switch (item.kind) {
                    case QueueItem.KIND_STOP:
                        running = false;
                        return;
                    case QueueItem.KIND_FLUSH:
                        UsbDirectNative.nativeDrainUrbs(handle);
                        UsbDirectNative.nativeFlush(handle);
                        COMPLETED_FLUSH_SEQ.set(item.seq);
                        break;
                    case QueueItem.KIND_EOS_DRAIN:
                        UsbDirectNative.nativeDrainUrbs(handle);
                        playbackActivity.setPlaying(false);
                        eosDrained = true;
                        break;
                    case QueueItem.KIND_PCM:
                        pendingPcmCount.decrementAndGet();
                        playbackActivity.setPlaying(true);
                        int written = UsbDirectNative.nativeWritePcm(
                                handle, item.data, 0, item.data.length, item.encoding);
                        if (written < 0) {
                            if (!running) return;
                            Log.e(TAG, "nativeWritePcm failed with err=" + written);
                            failed = true;
                            eosDrained = true;
                            running = false;
                            return;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }
}
