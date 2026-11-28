package app.voidhack.extension.applemusic.quality;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.media.AudioProfile;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Observes live Apple Music playback state (ExoMediaPlayer, SVAudioRendererV2,
 * DefaultAudioSink, AudioTrack), real-time Android hardware routing
 * (AudioManager, AudioDeviceInfo, AudioMixerAttributes), and Bluetooth A2DP
 * codec configuration broadcasts (when BLUETOOTH_CONNECT is granted).
 */
public final class AudioQualityMonitor {
    static final String PERM_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private static final String ACTION_A2DP_CODEC_CONFIG_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AudioAttributes MUSIC_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    private static final CopyOnWriteArraySet<Runnable> LIVE_LISTENERS = new CopyOnWriteArraySet<>();

    // Cached Bluetooth A2DP codec configs received from ACTION_CODEC_CONFIG_CHANGED (keyed by address & name/alias)
    private static final ConcurrentHashMap<String, BluetoothCodecProbe> BT_CODEC_BY_KEY =
            new ConcurrentHashMap<>();
    private static volatile BluetoothCodecProbe lastActiveBtCodec = BluetoothCodecProbe.EMPTY;
    private static volatile String lastActiveBtKey = "";
    private static volatile boolean btReceiverRegistered = false;
    private static volatile Boolean btPermissionDeclaredCache = null;

    // Captured from ExoMediaPlayer.sendAudioFormatChangeEvent
    private static volatile int exoVariant = AudioQualityModels.VARIANT_UNKNOWN;
    private static volatile int exoBitDepth = -1;
    private static volatile int exoSampleRate = -1;
    private static volatile int exoChannelCount = -1;
    private static volatile int exoBitrateBps = -1;
    private static volatile String exoCodec = "";

    // Captured from SVAudioRendererV2.initializeAudioSink
    private static volatile int decoderBitDepth = -1;
    private static volatile int decoderSampleRate = -1;
    private static volatile int decoderChannelCount = -1;
    private static volatile int decoderBitrateBps = -1;
    private static volatile String decoderMimeType = "";
    private static volatile String decoderCodec = "";
    private static volatile boolean decoderSoundCheckEnabled = false;

    // Captured from DefaultAudioSink.configure / initialize / flush
    private static volatile int sinkInputEncoding = 0;
    private static volatile int sinkInputChannelCount = 0;
    private static volatile int sinkInputSampleRate = 0;
    private static volatile boolean sinkIsInputPcm = true;
    private static volatile int sinkConfigInputSampleRate = 0;
    private static volatile int sinkConfigOutputSampleRate = 0;
    private static volatile int sinkConfigOutputEncoding = 0;
    private static volatile int sinkConfigOutputChannelConfig = 0;
    private static volatile WeakReference<AudioTrack> activeTrackRef = new WeakReference<>(null);

    // Process-wide USB hot-plug observer + sheet-scoped live routing observer
    private static volatile boolean globalDeviceCallbackRegistered = false;
    private static AudioManager subscribedAudioManager;
    private static AudioDeviceCallback activeDeviceCallback;
    private static WeakReference<AudioTrack> subscribedTrackRef = new WeakReference<>(null);
    private static AudioRouting.OnRoutingChangedListener activeRoutingListener;

    private AudioQualityMonitor() {}

    // =========================================================================
    // Bytecode Hook Entry Points (called directly from patched Apple Music)
    // =========================================================================

    public static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        try {
            if (AudioQualityFeatures.usbDacOutput()) {
                AudioQualityBitPerfect.initialize(app);
                UsbDirectAudioEngine.initialize(app);
                ensureGlobalUsbDeviceObserverRegistered(app);
            }
            ensureBluetoothCodecReceiverRegistered(app);
        } catch (Throwable ignored) {
        }
    }

    static void notifyUsbDirectStateChanged() {
        notifyListeners();
    }

    public static void onExoFormatChanged(
            int trackVariant,
            int bitDepth,
            int sampleRate,
            int channelCount,
            int bitrateBps,
            String mimeType,
            String codecs) {
        exoVariant = trackVariant;
        exoBitDepth = bitDepth;
        exoSampleRate = sampleRate;
        exoChannelCount = channelCount;
        exoBitrateBps = bitrateBps;
        exoCodec = (mimeType != null && !mimeType.isEmpty()) ? mimeType : (codecs != null ? codecs : "");
        notifyListeners();
    }

    public static void onDecoderConfigured(
            int bitsPerChannel,
            int sampleRate,
            int channelCount,
            int bitRate,
            String mimeType,
            String codec,
            boolean soundCheckEnabled) {
        decoderBitDepth = bitsPerChannel;
        decoderSampleRate = sampleRate;
        decoderChannelCount = channelCount;
        decoderBitrateBps = bitRate;
        decoderMimeType = mimeType != null ? mimeType : "";
        decoderCodec = codec != null ? codec : "";
        decoderSoundCheckEnabled = soundCheckEnabled;
        notifyListeners();
    }

    public static void onSinkConfigureInput(int inputEncoding, int inputChannelCount, int inputSampleRate) {
        sinkInputEncoding = inputEncoding;
        sinkInputChannelCount = inputChannelCount;
        sinkInputSampleRate = inputSampleRate;
    }

    public static void onAudioTrackInitialized(
            AudioTrack audioTrack,
            boolean isInputPcm,
            int configInputSampleRate,
            int configOutputSampleRate,
            int configOutputEncoding,
            int configOutputChannelConfig) {
        sinkIsInputPcm = isInputPcm;
        sinkConfigInputSampleRate = configInputSampleRate;
        sinkConfigOutputSampleRate = configOutputSampleRate;
        sinkConfigOutputEncoding = configOutputEncoding;
        sinkConfigOutputChannelConfig = configOutputChannelConfig;
        activeTrackRef = new WeakReference<>(audioTrack);
        rebindTrackRoutingListener(audioTrack);
        notifyListeners();
    }

    public static void onAudioTrackReleased(AudioTrack released) {
        AudioTrack current = activeTrackRef.get();
        if (current != null && (released == null || current == released)) {
            activeTrackRef = new WeakReference<>(null);
        }
        if (AudioQualityFeatures.usbDacOutput()) {
            AudioQualityBitPerfect.onAudioTrackReleased(released);
        }
    }

    static int getCurrentSourceBitDepth(int fallbackExoEncoding) {
        int fromSink = AudioQualityModels.bitDepthFromEncoding(sinkInputEncoding);
        if (fromSink > 0) return fromSink;
        if (decoderBitDepth > 0) return decoderBitDepth;
        if (exoBitDepth > 0) return exoBitDepth;
        int fromFallback = AudioQualityModels.bitDepthFromEncoding(fallbackExoEncoding);
        return fromFallback > 0 ? fromFallback : 24;
    }

    // =========================================================================
    // Global USB Hot-Plug Observer & Bluetooth Codec Broadcast Observer
    // =========================================================================

    private static synchronized void ensureGlobalUsbDeviceObserverRegistered(Context appContext) {
        if (globalDeviceCallbackRegistered || appContext == null) return;
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    if (addedDevices != null) {
                        for (AudioDeviceInfo dev : addedDevices) {
                            if (dev != null && dev.isSink() && AudioQualityModels.isUsbDeviceType(dev.getType())) {
                                AudioQualityBitPerfect.onUsbDeviceAdded(dev);
                            }
                        }
                    }
                    notifyListeners();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    if (removedDevices != null) {
                        for (AudioDeviceInfo dev : removedDevices) {
                            if (dev != null && dev.isSink() && AudioQualityModels.isUsbDeviceType(dev.getType())) {
                                AudioQualityBitPerfect.onUsbDeviceRemoved(dev);
                            }
                        }
                    }
                    notifyListeners();
                }
            }, MAIN);
            globalDeviceCallbackRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    static boolean hasBluetoothConnectPermission(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            return context.checkSelfPermission(PERM_BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean canRequestBluetoothCodecPermission(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 33) return false;
        if (hasBluetoothConnectPermission(context)) return false;
        return isBluetoothConnectDeclared(context);
    }

    private static boolean isBluetoothConnectDeclared(Context context) {
        Boolean cached = btPermissionDeclaredCache;
        if (cached != null) return cached;
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (pi != null && pi.requestedPermissions != null) {
                for (String p : pi.requestedPermissions) {
                    if (PERM_BLUETOOTH_CONNECT.equals(p)) {
                        btPermissionDeclaredCache = true;
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        btPermissionDeclaredCache = false;
        return false;
    }

    static synchronized void ensureBluetoothCodecReceiverRegistered(Context context) {
        if (btReceiverRegistered || context == null || Build.VERSION.SDK_INT < 33) return;
        if (!hasBluetoothConnectPermission(context)) return;

        Context app = context.getApplicationContext();
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_A2DP_CODEC_CONFIG_CHANGED);
            filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    handleBluetoothBroadcast(intent);
                }
            };
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            btReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private static void handleBluetoothBroadcast(Intent intent) {
        if (intent == null || Build.VERSION.SDK_INT < 33) return;
        String action = intent.getAction();
        try {
            if (ACTION_A2DP_CODEC_CONFIG_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                BluetoothCodecStatus status = intent.getParcelableExtra(
                        BluetoothCodecStatus.EXTRA_CODEC_STATUS, BluetoothCodecStatus.class);
                if (status == null) return;
                BluetoothCodecConfig config = status.getCodecConfig();
                if (config == null) return;

                String extendedName = "";
                if (Build.VERSION.SDK_INT >= 35) {
                    try {
                        BluetoothCodecType ext = config.getExtendedCodecType();
                        if (ext != null && ext.getCodecName() != null) {
                            extendedName = ext.getCodecName();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                String baseName = AudioQualityModels.resolveBluetoothCodecName(
                        config.getCodecType(), extendedName, config.toString());
                int sampleRateHz = AudioQualityModels.decodeBluetoothSampleRateHz(config.getSampleRate());
                int bitsPerSample = AudioQualityModels.decodeBluetoothBitsPerSample(config.getBitsPerSample());
                String displayCodec = AudioQualityModels.formatBluetoothCodecLabel(
                        baseName, sampleRateHz, config.getCodecSpecific1());

                if (displayCodec.isEmpty() && sampleRateHz <= 0 && bitsPerSample <= 0) {
                    return;
                }

                BluetoothCodecProbe probe = new BluetoothCodecProbe(displayCodec, bitsPerSample, sampleRateHz);
                String primaryKey = "";
                for (String key : extractDeviceKeys(device)) {
                    BT_CODEC_BY_KEY.put(key, probe);
                    if (primaryKey.isEmpty()) primaryKey = key;
                }
                lastActiveBtKey = primaryKey;
                lastActiveBtCodec = probe;
                notifyListeners();
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    BluetoothDevice device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    List<String> keys = extractDeviceKeys(device);
                    if (!keys.isEmpty()) {
                        for (String key : keys) {
                            BT_CODEC_BY_KEY.remove(key);
                            if (key.equals(lastActiveBtKey)) {
                                lastActiveBtKey = "";
                                lastActiveBtCodec = BluetoothCodecProbe.EMPTY;
                            }
                        }
                    } else {
                        lastActiveBtKey = "";
                        lastActiveBtCodec = BluetoothCodecProbe.EMPTY;
                    }
                    notifyListeners();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static List<String> extractDeviceKeys(BluetoothDevice device) {
        List<String> keys = new ArrayList<>(3);
        if (device == null) return keys;
        try {
            String addr = device.getAddress();
            if (addr != null && !addr.trim().isEmpty()) {
                keys.add("addr:" + addr.trim().toUpperCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {
        }
        try {
            String alias = device.getAlias();
            if (alias != null && !alias.trim().isEmpty()) {
                keys.add("name:" + alias.trim().toLowerCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {
        }
        try {
            String name = device.getName();
            if (name != null && !name.trim().isEmpty()) {
                String k = "name:" + name.trim().toLowerCase(Locale.ROOT);
                if (!keys.contains(k)) keys.add(k);
            }
        } catch (Throwable ignored) {
        }
        return keys;
    }

    // =========================================================================
    // Live Listener Management (only active while the UI sheet is open)
    // =========================================================================

    static void addLiveListener(Context context, Runnable listener) {
        if (listener == null) return;
        boolean wasEmpty = LIVE_LISTENERS.isEmpty();
        LIVE_LISTENERS.add(listener);
        if (wasEmpty && context != null) {
            startHardwareSubscriptions(context.getApplicationContext());
        }
    }

    static void removeLiveListener(Runnable listener) {
        if (listener == null) return;
        LIVE_LISTENERS.remove(listener);
        if (LIVE_LISTENERS.isEmpty()) {
            stopHardwareSubscriptions();
        }
    }

    private static void startHardwareSubscriptions(Context appContext) {
        if (AudioQualityFeatures.usbDacOutput()) {
            AudioQualityBitPerfect.initialize(appContext);
            UsbDirectAudioEngine.initialize(appContext);
            ensureGlobalUsbDeviceObserverRegistered(appContext);
        }
        ensureBluetoothCodecReceiverRegistered(appContext);
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                activeDeviceCallback = new AudioDeviceCallback() {
                    @Override
                    public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                        notifyListeners();
                    }

                    @Override
                    public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                        notifyListeners();
                    }
                };
                subscribedAudioManager = am;
                am.registerAudioDeviceCallback(activeDeviceCallback, MAIN);
            }
        } catch (Throwable ignored) {
        }
        rebindTrackRoutingListener(activeTrackRef.get());
    }

    private static void rebindTrackRoutingListener(AudioTrack track) {
        if (LIVE_LISTENERS.isEmpty()) return;
        try {
            AudioTrack prev = subscribedTrackRef.get();
            if (prev != null && activeRoutingListener != null) {
                try {
                    prev.removeOnRoutingChangedListener(activeRoutingListener);
                } catch (Throwable ignored) {
                }
            }
            subscribedTrackRef = new WeakReference<>(null);
            activeRoutingListener = null;
            if (track != null && isTrackAlive(track)) {
                AudioRouting.OnRoutingChangedListener l = routing -> notifyListeners();
                track.addOnRoutingChangedListener(l, MAIN);
                subscribedTrackRef = new WeakReference<>(track);
                activeRoutingListener = l;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void stopHardwareSubscriptions() {
        try {
            if (subscribedAudioManager != null && activeDeviceCallback != null) {
                subscribedAudioManager.unregisterAudioDeviceCallback(activeDeviceCallback);
            }
        } catch (Throwable ignored) {
        }
        subscribedAudioManager = null;
        activeDeviceCallback = null;

        try {
            AudioTrack prev = subscribedTrackRef.get();
            if (prev != null && activeRoutingListener != null) {
                prev.removeOnRoutingChangedListener(activeRoutingListener);
            }
        } catch (Throwable ignored) {
        }
        subscribedTrackRef = new WeakReference<>(null);
        activeRoutingListener = null;
    }

    private static void notifyListeners() {
        if (LIVE_LISTENERS.isEmpty()) return;
        MAIN.post(() -> {
            for (Runnable r : LIVE_LISTENERS) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // =========================================================================
    // Snapshot Builder
    // =========================================================================

    static AudioQualityModels.ResolvedSignalPath captureSnapshot(
            Context context,
            int uiVariant,
            int uiBitDepth,
            int uiSampleRate,
            String uiCodec,
            int uiCapabilities,
            String trackTitle,
            String trackSubtitle) {
        Context app = context.getApplicationContext();
        boolean usbDacEnabled = AudioQualityFeatures.usbDacOutput();
        if (usbDacEnabled) {
            AudioQualityBitPerfect.initialize(app);
            UsbDirectAudioEngine.initialize(app);
        }
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);

        // 1. Source info
        int variant = uiVariant >= 0 ? uiVariant : exoVariant;
        boolean matchesExo = (exoVariant == variant) || (uiVariant < 0);
        boolean isAlac = variant == AudioQualityModels.VARIANT_LOSSLESS
                || variant == AudioQualityModels.VARIANT_HI_RES_LOSSLESS;

        int srcBitDepth = uiBitDepth;
        if (srcBitDepth <= 0 && isAlac && decoderBitDepth > 0) srcBitDepth = decoderBitDepth;
        if (srcBitDepth <= 0 && matchesExo && exoBitDepth > 0) srcBitDepth = exoBitDepth;

        int srcSampleRate = uiSampleRate;
        if (srcSampleRate <= 0 && isAlac && decoderSampleRate > 0) srcSampleRate = decoderSampleRate;
        if (srcSampleRate <= 0 && matchesExo && exoSampleRate > 0) srcSampleRate = exoSampleRate;

        String srcCodec = (uiCodec != null && !uiCodec.isEmpty()) ? uiCodec : "";
        if (srcCodec.isEmpty() && matchesExo && !exoCodec.isEmpty()) srcCodec = exoCodec;
        if (srcCodec.isEmpty() && isAlac && !decoderMimeType.isEmpty()) srcCodec = decoderMimeType;

        int srcChannels = -1;
        if (matchesExo && exoChannelCount > 0) srcChannels = exoChannelCount;
        else if (isAlac && decoderChannelCount > 0) srcChannels = decoderChannelCount;

        int srcBitrate = -1;
        if (matchesExo && exoBitrateBps > 0) srcBitrate = exoBitrateBps;

        AudioQualityModels.SourceInput sourceInput = new AudioQualityModels.SourceInput(
                variant,
                srcBitDepth,
                srcSampleRate,
                srcChannels,
                srcBitrate,
                srcCodec,
                uiCapabilities,
                trackTitle,
                trackSubtitle
        );

        // 2. Live AudioTrack & routed AudioDeviceInfo
        AudioTrack liveTrack = activeTrackRef.get();
        if (liveTrack != null && !isTrackAlive(liveTrack)) {
            liveTrack = null;
        }
        AudioDeviceInfo routedDevice = resolveActiveOutputDevice(am, liveTrack);

        int trackSampleRate = 0;
        int trackEncoding = 0;
        int trackChannels = 0;
        boolean trackPlaying = false;
        AudioFormat liveAudioFormat = null;
        if (liveTrack != null) {
            try {
                trackSampleRate = liveTrack.getSampleRate();
                trackEncoding = liveTrack.getAudioFormat();
                trackChannels = liveTrack.getChannelCount();
                trackPlaying = liveTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
                liveAudioFormat = liveTrack.getFormat();
            } catch (Throwable ignored) {
            }
        }
        if (trackSampleRate <= 0 && sinkConfigOutputSampleRate > 0) {
            trackSampleRate = sinkConfigOutputSampleRate;
        }
        if (trackEncoding <= 0 && sinkConfigOutputEncoding > 0) {
            trackEncoding = sinkConfigOutputEncoding;
        }
        if (!trackPlaying && am != null) {
            try {
                trackPlaying = am.isMusicActive();
            } catch (Throwable ignored) {
            }
        }

        int nativeHalRate = 0;
        try {
            nativeHalRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        } catch (Throwable ignored) {
        }
        int systemMixerRate = parseIntProperty(am, AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);

        boolean directSupported = false;
        if (Build.VERSION.SDK_INT >= 33 && liveAudioFormat != null) {
            try {
                int support = AudioManager.getDirectPlaybackSupport(liveAudioFormat, MUSIC_ATTRIBUTES);
                directSupported = support != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED;
            } catch (Throwable ignored) {
            }
        } else if (Build.VERSION.SDK_INT >= 29 && liveAudioFormat != null) {
            try {
                directSupported = AudioTrack.isDirectPlaybackSupported(liveAudioFormat, MUSIC_ATTRIBUTES);
            } catch (Throwable ignored) {
            }
        }

        // Android 14+ Supported & Active USB Mixer Attributes
        List<AudioQualityModels.UsbMixerOption> supportedUsbMixers = usbDacEnabled
                ? AudioQualityBitPerfect.querySupportedUsbMixers(am, routedDevice)
                : Collections.emptyList();

        boolean prefBitPerfect = false;
        int prefSampleRate = 0;
        int prefEncoding = 0;
        if (Build.VERSION.SDK_INT >= 34 && am != null && routedDevice != null
                && AudioQualityModels.isUsbDeviceType(routedDevice.getType())) {
            try {
                AudioMixerAttributes mixerAttrs = am.getPreferredMixerAttributes(MUSIC_ATTRIBUTES, routedDevice);
                if (mixerAttrs != null) {
                    prefBitPerfect = mixerAttrs.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT;
                    AudioFormat mf = mixerAttrs.getFormat();
                    if (mf != null) {
                        prefSampleRate = mf.getSampleRate();
                        prefEncoding = mf.getEncoding();
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        boolean bpTrackActive = usbDacEnabled && AudioQualityBitPerfect.isBitPerfectTrackActive(liveTrack);
        boolean prefTrackActive = usbDacEnabled && AudioQualityBitPerfect.isPreferredMixerTrackActive(liveTrack);
        boolean usbExclusiveSetting = usbDacEnabled && AudioQualityBitPerfect.isExclusiveUsbEnabled(app);

        AudioQualityModels.UsbDirectState usbDirectState = AudioQualityModels.UsbDirectState.NONE;
        if (usbDacEnabled) {
            UsbDirectAudioEngine.StatusSnapshot directSnap = UsbDirectAudioEngine.captureStatus(app);
            usbDirectState = new AudioQualityModels.UsbDirectState(
                    directSnap.nativeLoaded,
                    directSnap.deviceAttached,
                    directSnap.permissionGranted,
                    directSnap.settingEnabled,
                    directSnap.streamActive,
                    directSnap.deviceName,
                    directSnap.uacVersion,
                    directSnap.sampleRateHz,
                    directSnap.bitDepth,
                    directSnap.subslotBytes,
                    directSnap.measuredRateHz,
                    directSnap.maxBitDepth,
                    directSnap.maxSampleRateHz,
                    directSnap.errorReason,
                    directSnap.errorRateHz,
                    directSnap.hwVolumeSupported,
                    directSnap.hwVolumeMuted,
                    directSnap.hwVolumeDb256,
                    directSnap.hwVolumeMinDb256,
                    directSnap.hwVolumeMaxDb256,
                    directSnap.hwVolumePercent
            );
        }

        boolean soundCheck = decoderSoundCheckEnabled || readBoolPref("isVolumeNormalizationEnabled", false);
        boolean eqEnabled = readBoolPref("isEQEnabled", false);

        AudioQualityModels.PipelineInput pipelineInput = new AudioQualityModels.PipelineInput(
                resolvePlaybackDeviceName(app),
                liveTrack != null || sinkConfigOutputSampleRate > 0,
                sinkIsInputPcm,
                sinkConfigInputSampleRate > 0 ? sinkConfigInputSampleRate : sinkInputSampleRate,
                sinkInputEncoding,
                trackSampleRate,
                trackEncoding,
                trackChannels,
                nativeHalRate,
                systemMixerRate,
                prefBitPerfect,
                prefSampleRate,
                prefEncoding,
                directSupported,
                soundCheck,
                eqEnabled,
                trackPlaying,
                usbExclusiveSetting,
                bpTrackActive,
                prefTrackActive,
                supportedUsbMixers,
                usbDirectState,
                usbDacEnabled
        );

        // 3. Hardware output device capabilities
        int deviceType = routedDevice != null ? routedDevice.getType() : AudioQualityModels.DEV_BUILTIN_SPEAKER;
        String productName = "";
        String deviceAddress = "";
        int[] supportedRates = new int[0];
        int[] supportedEncodings = new int[0];
        if (routedDevice != null) {
            CharSequence pn = routedDevice.getProductName();
            if (pn != null) productName = pn.toString().trim();
            try {
                String addr = routedDevice.getAddress();
                if (addr != null) deviceAddress = addr.trim();
            } catch (Throwable ignored) {
            }
            supportedRates = collectSupportedSampleRates(routedDevice);
            supportedEncodings = collectSupportedEncodings(routedDevice);
        }

        BluetoothCodecProbe btProbe = (deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                ? probeBluetoothCodecSafely(app, deviceAddress, productName)
                : BluetoothCodecProbe.EMPTY;

        CastRouteInfo castInfo = probeRemoteCastRoute(app);
        if (castInfo.active && !castInfo.name.isEmpty()) {
            productName = castInfo.name;
        }

        AudioQualityModels.HardwareOutputInput hardwareInput = new AudioQualityModels.HardwareOutputInput(
                deviceType,
                productName,
                supportedRates,
                supportedEncodings,
                btProbe.codecName,
                btProbe.bitsPerSample,
                btProbe.sampleRateHz,
                castInfo.active,
                castInfo.name
        );

        return AudioQualityModels.resolve(sourceInput, pipelineInput, hardwareInput);
    }

    private static boolean isTrackAlive(AudioTrack track) {
        try {
            return track.getState() == AudioTrack.STATE_INITIALIZED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static AudioDeviceInfo resolveActiveOutputDevice(AudioManager am, AudioTrack liveTrack) {
        if (liveTrack != null) {
            try {
                AudioDeviceInfo routed = liveTrack.getRoutedDevice();
                if (routed != null) return routed;
            } catch (Throwable ignored) {
            }
        }
        if (am == null) return null;

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                List<AudioDeviceInfo> routedList = am.getAudioDevicesForAttributes(MUSIC_ATTRIBUTES);
                if (routedList != null && !routedList.isEmpty()) {
                    AudioDeviceInfo best = null;
                    int bestPrio = -1;
                    for (AudioDeviceInfo dev : routedList) {
                        if (dev == null || !dev.isSink()) continue;
                        int p = AudioQualityModels.outputDevicePriority(dev.getType());
                        if (p > bestPrio) {
                            bestPrio = p;
                            best = dev;
                        }
                    }
                    if (best != null) return best;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            AudioDeviceInfo best = null;
            int bestPrio = -1;
            for (AudioDeviceInfo dev : outputs) {
                if (dev == null || !dev.isSink()) continue;
                int p = AudioQualityModels.outputDevicePriority(dev.getType());
                if (p > bestPrio) {
                    bestPrio = p;
                    best = dev;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int[] collectSupportedSampleRates(AudioDeviceInfo device) {
        List<Integer> list = new ArrayList<>();
        try {
            int[] direct = device.getSampleRates();
            if (direct != null) {
                for (int r : direct) {
                    if (r > 0) list.add(r);
                }
            }
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profiles = device.getAudioProfiles();
                if (profiles != null) {
                    for (AudioProfile p : profiles) {
                        if (p == null) continue;
                        int[] pr = p.getSampleRates();
                        if (pr != null) {
                            for (int r : pr) {
                                if (r > 0) list.add(r);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        int[] raw = new int[list.size()];
        for (int i = 0; i < list.size(); i++) raw[i] = list.get(i);
        return AudioQualityModels.sanitizeSampleRates(raw);
    }

    private static int[] collectSupportedEncodings(AudioDeviceInfo device) {
        List<Integer> list = new ArrayList<>();
        try {
            int[] direct = device.getEncodings();
            if (direct != null) {
                for (int e : direct) {
                    if (e > 0) list.add(e);
                }
            }
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profiles = device.getAudioProfiles();
                if (profiles != null) {
                    for (AudioProfile p : profiles) {
                        if (p == null) continue;
                        int fmt = p.getFormat();
                        if (fmt > 0 && !list.contains(fmt)) {
                            list.add(fmt);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        int[] raw = new int[list.size()];
        for (int i = 0; i < list.size(); i++) raw[i] = list.get(i);
        return raw;
    }

    private static int parseIntProperty(AudioManager am, String key) {
        if (am == null || key == null) return 0;
        try {
            String val = am.getProperty(key);
            if (val != null && !val.isEmpty()) {
                return Integer.parseInt(val.trim());
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String resolvePlaybackDeviceName(Context appContext) {
        String friendly = null;
        try {
            friendly = Settings.Global.getString(appContext.getContentResolver(), "device_name");
        } catch (Throwable ignored) {
        }
        return AudioQualityModels.formatPlaybackDeviceName(friendly, Build.MANUFACTURER, Build.MODEL);
    }

    private static boolean readBoolPref(String methodName, boolean fallback) {
        try {
            Class<?> cls = Class.forName("com.apple.android.music.playback.preferences.MediaPlaybackPreferences");
            Method getInstance = cls.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            if (instance == null) return fallback;
            Method m = cls.getMethod(methodName);
            Object res = m.invoke(instance);
            return res instanceof Boolean ? (Boolean) res : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static final class CastRouteInfo {
        static final CastRouteInfo NONE = new CastRouteInfo(false, "");
        final boolean active;
        final String name;

        CastRouteInfo(boolean active, String name) {
            this.active = active;
            this.name = name;
        }
    }

    @SuppressWarnings("deprecation")
    private static CastRouteInfo probeRemoteCastRoute(Context context) {
        try {
            android.media.MediaRouter router = (android.media.MediaRouter)
                    context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
            if (router == null) return CastRouteInfo.NONE;
            android.media.MediaRouter.RouteInfo route =
                    router.getSelectedRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
            if (route != null && route.getPlaybackType() == android.media.MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE) {
                CharSequence name = route.getName(context);
                return new CastRouteInfo(true, name != null ? name.toString().trim() : "");
            }
        } catch (Throwable ignored) {
        }
        return CastRouteInfo.NONE;
    }

    private static final class BluetoothCodecProbe {
        static final BluetoothCodecProbe EMPTY = new BluetoothCodecProbe("", 0, 0);
        final String codecName;
        final int bitsPerSample;
        final int sampleRateHz;

        BluetoothCodecProbe(String codecName, int bitsPerSample, int sampleRateHz) {
            this.codecName = codecName;
            this.bitsPerSample = bitsPerSample;
            this.sampleRateHz = sampleRateHz;
        }
    }

    private static BluetoothCodecProbe probeBluetoothCodecSafely(
            Context context, String deviceAddress, String productName) {
        ensureBluetoothCodecReceiverRegistered(context);
        if (!hasBluetoothConnectPermission(context)) {
            return BluetoothCodecProbe.EMPTY;
        }
        if (deviceAddress != null && !deviceAddress.isEmpty()) {
            BluetoothCodecProbe byAddr = BT_CODEC_BY_KEY.get("addr:" + deviceAddress.toUpperCase(Locale.ROOT));
            if (byAddr != null) return byAddr;
        }
        if (productName != null && !productName.isEmpty()) {
            BluetoothCodecProbe byName = BT_CODEC_BY_KEY.get("name:" + productName.toLowerCase(Locale.ROOT));
            if (byName != null) return byName;
        }
        BluetoothCodecProbe recent = lastActiveBtCodec;
        if (recent != null && (!recent.codecName.isEmpty() || recent.sampleRateHz > 0)) {
            if ((deviceAddress == null || deviceAddress.isEmpty())
                    && (productName == null || productName.isEmpty())) {
                return recent;
            }
            if (lastActiveBtKey.isEmpty()) {
                return recent;
            }
        }
        return BluetoothCodecProbe.EMPTY;
    }
}
