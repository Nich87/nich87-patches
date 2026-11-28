package app.voidhack.extension.applemusic.quality;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements Android 14+ official USB Exclusive Bit-Perfect audio output
 * (AudioManager.setPreferredMixerAttributes with MIXER_BEHAVIOR_BIT_PERFECT,
 * or MIXER_BEHAVIOR_DEFAULT high-res USB mixer when bit-perfect is unavailable),
 * including bit-exact integer PCM delivery (16-bit, 24-bit packed, 32-bit)
 * and safe seamless fallback to Apple Music's standard AudioTrack.
 */
public final class AudioQualityBitPerfect {
    private static final String TAG = "VoidhackBitPerfect";
    private static final String PREFS_NAME = "voidhack_audio_quality";
    private static final String KEY_USB_EXCLUSIVE = "usb_exclusive_bit_perfect";
    private static final int MAX_CHUNK_FRAMES = 4096;

    private static volatile Context appContext;
    private static volatile boolean prefLoaded;
    private static volatile boolean exclusiveUsbEnabled;

    private static final AtomicBoolean RECONFIGURE_REQUESTED = new AtomicBoolean(false);
    private static volatile ActiveTrackSession activeSession;

    // Last configured preferred mixer state for clean teardown on disable / unplug
    private static volatile AudioAttributes lastConfiguredAttrs;
    private static volatile AudioDeviceInfo lastConfiguredUsbDevice;

    private AudioQualityBitPerfect() {}

    static void initialize(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        loadPreferenceIfNeeded(appContext);
    }

    static boolean isExclusiveUsbEnabled(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
        }
        loadPreferenceIfNeeded(appContext);
        return exclusiveUsbEnabled;
    }

    static void setExclusiveUsbEnabled(Context context, boolean enabled) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        exclusiveUsbEnabled = enabled;
        prefLoaded = true;
        if (appContext != null) {
            try {
                SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sp.edit().putBoolean(KEY_USB_EXCLUSIVE, enabled).apply();
            } catch (Throwable ignored) {
            }
        }
        if (!enabled) {
            clearPreferredMixerSafely();
        }
        requestReconfigure();
    }

    static void requestReconfigure() {
        RECONFIGURE_REQUESTED.set(true);
    }

    /**
     * Called on ExoPlayer's playback thread at the start of DefaultAudioSink.handleBuffer().
     */
    public static boolean consumeReconfigureRequest() {
        return RECONFIGURE_REQUESTED.compareAndSet(true, false);
    }

    static boolean isBitPerfectTrackActive(AudioTrack track) {
        ActiveTrackSession s = activeSession;
        return s != null && track != null && s.track == track && s.bitPerfect;
    }

    static boolean isPreferredMixerTrackActive(AudioTrack track) {
        ActiveTrackSession s = activeSession;
        return s != null && track != null && s.track == track && !s.bitPerfect;
    }

    static void onAudioTrackReleased(AudioTrack released) {
        ActiveTrackSession s = activeSession;
        if (s != null && (released == null || s.track == released)) {
            activeSession = null;
        }
    }

    static void onUsbDeviceRemoved(AudioDeviceInfo removed) {
        AudioDeviceInfo prev = lastConfiguredUsbDevice;
        if (prev != null && (removed == null || prev.getId() == removed.getId())) {
            clearPreferredMixerSafely();
            requestReconfigure();
        }
    }

    static void onUsbDeviceAdded(AudioDeviceInfo added) {
        if (added != null && AudioQualityModels.isUsbDeviceType(added.getType())
                && isExclusiveUsbEnabled(appContext)) {
            requestReconfigure();
        }
    }

    static List<AudioQualityModels.UsbMixerOption> querySupportedUsbMixers(AudioManager am, AudioDeviceInfo device) {
        if (Build.VERSION.SDK_INT < 34 || am == null || device == null
                || !AudioQualityModels.isUsbDeviceType(device.getType())) {
            return Collections.emptyList();
        }
        try {
            List<AudioMixerAttributes> attrsList = am.getSupportedMixerAttributes(device);
            if (attrsList == null || attrsList.isEmpty()) {
                return Collections.emptyList();
            }
            List<AudioQualityModels.UsbMixerOption> result = new ArrayList<>(attrsList.size());
            for (AudioMixerAttributes attrs : attrsList) {
                if (attrs == null) continue;
                AudioFormat fmt = attrs.getFormat();
                if (fmt == null) continue;
                result.add(new AudioQualityModels.UsbMixerOption(
                        attrs.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                        fmt.getEncoding(),
                        fmt.getSampleRate(),
                        fmt.getChannelMask()
                ));
            }
            return result;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to query supported USB mixer attributes", t);
            return Collections.emptyList();
        }
    }

    /**
     * Hooked into DefaultAudioSink$Configuration.createAudioTrackV21().
     * Attempts to configure Android 14+ MIXER_BEHAVIOR_BIT_PERFECT (or high-res USB preferred mixer)
     * on the active USB DAC and open an exact-matching integer PCM AudioTrack.
     * Returns null on any unsupported condition or error so Apple Music falls back cleanly.
     */
    public static AudioTrack tryCreateBitPerfectAudioTrack(
            AudioAttributes audioAttributes,
            int sampleRateHz,
            int channelMask,
            int exoOutputEncoding,
            int exoBufferSizeBytes,
            int audioSessionId) {
        if (Build.VERSION.SDK_INT < 34) return null;
        Context ctx = appContext;
        if (ctx == null || !isExclusiveUsbEnabled(ctx) || UsbDirectAudioEngine.isEnabled(ctx)) {
            clearPreferredMixerSafely();
            return null;
        }
        if (audioAttributes == null || audioAttributes.getUsage() != AudioAttributes.USAGE_MEDIA) {
            return null;
        }
        if (sampleRateHz <= 0 || channelMask == 0) return null;

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;

        AudioDeviceInfo usbDevice = findActiveUsbOutputDevice(am, audioAttributes);
        if (usbDevice == null) {
            clearPreferredMixerSafely();
            return null;
        }

        List<AudioMixerAttributes> rawSupported;
        try {
            rawSupported = am.getSupportedMixerAttributes(usbDevice);
        } catch (Throwable t) {
            Log.w(TAG, "getSupportedMixerAttributes failed", t);
            return null;
        }
        if (rawSupported == null || rawSupported.isEmpty()) {
            clearPreferredMixerSafely();
            return null;
        }

        List<AudioQualityModels.UsbMixerOption> options = new ArrayList<>(rawSupported.size());
        for (AudioMixerAttributes attr : rawSupported) {
            if (attr == null || attr.getFormat() == null) continue;
            AudioFormat f = attr.getFormat();
            options.add(new AudioQualityModels.UsbMixerOption(
                    attr.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                    f.getEncoding(),
                    f.getSampleRate(),
                    f.getChannelMask()
            ));
        }

        int sourceBits = AudioQualityMonitor.getCurrentSourceBitDepth(exoOutputEncoding);
        AudioQualityModels.UsbMixerOption chosenOpt = AudioQualityModels.selectBestUsbMixerOption(
                options, sampleRateHz, channelMask, sourceBits, true);
        if (chosenOpt == null) {
            clearPreferredMixerSafely();
            return null;
        }

        if (!AudioQualityModels.canConvertPcmLosslessly(exoOutputEncoding, chosenOpt.encoding)) {
            clearPreferredMixerSafely();
            return null;
        }

        AudioMixerAttributes matchedPlatformAttr = findExactPlatformAttribute(rawSupported, chosenOpt);
        if (matchedPlatformAttr == null) {
            AudioFormat builtFmt = new AudioFormat.Builder()
                    .setEncoding(chosenOpt.encoding)
                    .setSampleRate(chosenOpt.sampleRateHz)
                    .setChannelMask(chosenOpt.channelMask != 0 ? chosenOpt.channelMask : channelMask)
                    .build();
            matchedPlatformAttr = new AudioMixerAttributes.Builder(builtFmt)
                    .setMixerBehavior(chosenOpt.bitPerfect
                            ? AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                            : AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT)
                    .build();
        }

        boolean applied = false;
        try {
            applied = am.setPreferredMixerAttributes(audioAttributes, usbDevice, matchedPlatformAttr);
        } catch (Throwable t) {
            Log.w(TAG, "setPreferredMixerAttributes threw", t);
        }
        if (!applied) {
            clearPreferredMixerSafely();
            return null;
        }

        lastConfiguredAttrs = audioAttributes;
        lastConfiguredUsbDevice = usbDevice;

        AudioFormat targetFormat = matchedPlatformAttr.getFormat();
        int channelCount = Integer.bitCount(targetFormat.getChannelMask());
        if (channelCount <= 0) channelCount = 2;
        int srcSampleBytes = AudioQualityModels.bytesPerSample(exoOutputEncoding);
        int dstSampleBytes = AudioQualityModels.bytesPerSample(targetFormat.getEncoding());
        if (srcSampleBytes <= 0 || dstSampleBytes <= 0) {
            clearPreferredMixerSafely();
            return null;
        }
        int srcFrameSize = srcSampleBytes * channelCount;
        int dstFrameSize = dstSampleBytes * channelCount;

        int minBuf = AudioTrack.getMinBufferSize(
                targetFormat.getSampleRate(),
                targetFormat.getChannelMask(),
                targetFormat.getEncoding());
        if (minBuf <= 0) {
            clearPreferredMixerSafely();
            return null;
        }
        long scaledBuf = ((long) Math.max(exoBufferSizeBytes, srcFrameSize) * dstFrameSize) / srcFrameSize;
        int targetBuf = (int) Math.max((long) minBuf * 2L, scaledBuf);
        targetBuf = ((targetBuf + dstFrameSize - 1) / dstFrameSize) * dstFrameSize;

        AudioTrack track = null;
        try {
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(targetFormat)
                    .setBufferSizeInBytes(targetBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (audioSessionId > 0) {
                builder.setSessionId(audioSessionId);
            }
            track = builder.build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                clearPreferredMixerSafely();
                return null;
            }
            activeSession = new ActiveTrackSession(
                    track,
                    exoOutputEncoding,
                    targetFormat.getEncoding(),
                    channelCount,
                    chosenOpt.bitPerfect
            );
            Log.i(TAG, "Opened USB " + (chosenOpt.bitPerfect ? "BIT-PERFECT" : "PREFERRED-MIXER")
                    + " AudioTrack: " + targetFormat.getSampleRate() + " Hz, enc=" + targetFormat.getEncoding());
            return track;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to build bit-perfect AudioTrack; falling back to default", t);
            if (track != null) {
                try {
                    track.release();
                } catch (Throwable ignored) {
                }
            }
            clearPreferredMixerSafely();
            return null;
        }
    }

    /**
     * Hooked into DefaultAudioSink.writeNonBlockingV21().
     * Passes through untouched for normal tracks or same-encoding tracks; performs lossless
     * bit-exact integer PCM conversion with partial-write safety when active USB Bit-Perfect
     * track uses a different PCM container width (e.g., Float -> 24-bit packed / 32-bit int).
     */
    public static int writeNonBlocking(AudioTrack track, ByteBuffer buffer, int size) {
        ActiveTrackSession session = activeSession;
        if (session == null || session.track != track || !session.needsConversion) {
            return track.write(buffer, size, AudioTrack.WRITE_NON_BLOCKING);
        }
        return session.writeConverted(buffer, size);
    }

    private static AudioDeviceInfo findActiveUsbOutputDevice(AudioManager am, AudioAttributes attrs) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                List<AudioDeviceInfo> routed = am.getAudioDevicesForAttributes(attrs);
                if (routed != null) {
                    for (AudioDeviceInfo dev : routed) {
                        if (dev != null && dev.isSink() && AudioQualityModels.isUsbDeviceType(dev.getType())) {
                            return dev;
                        }
                    }
                    // If Android explicitly routes media to a non-USB device (e.g. Bluetooth), do not hijack USB.
                    if (!routed.isEmpty()) {
                        return null;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo dev : outputs) {
                if (dev != null && dev.isSink() && AudioQualityModels.isUsbDeviceType(dev.getType())) {
                    return dev;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static AudioMixerAttributes findExactPlatformAttribute(
            List<AudioMixerAttributes> list,
            AudioQualityModels.UsbMixerOption opt) {
        int desiredBehavior = opt.bitPerfect
                ? AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                : AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT;
        for (AudioMixerAttributes attr : list) {
            if (attr == null || attr.getFormat() == null) continue;
            AudioFormat f = attr.getFormat();
            if (attr.getMixerBehavior() == desiredBehavior
                    && f.getEncoding() == opt.encoding
                    && f.getSampleRate() == opt.sampleRateHz
                    && f.getChannelMask() == opt.channelMask) {
                return attr;
            }
        }
        return null;
    }

    private static void clearPreferredMixerSafely() {
        AudioAttributes attrs = lastConfiguredAttrs;
        AudioDeviceInfo dev = lastConfiguredUsbDevice;
        lastConfiguredAttrs = null;
        lastConfiguredUsbDevice = null;
        if (Build.VERSION.SDK_INT < 34 || attrs == null || dev == null || appContext == null) return;
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.clearPreferredMixerAttributes(attrs, dev);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void loadPreferenceIfNeeded(Context ctx) {
        if (prefLoaded || ctx == null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            exclusiveUsbEnabled = sp.getBoolean(KEY_USB_EXCLUSIVE, false);
        } catch (Throwable ignored) {
            exclusiveUsbEnabled = false;
        }
        prefLoaded = true;
    }

    private static final class ActiveTrackSession {
        final AudioTrack track;
        final int srcEncoding;
        final int dstEncoding;
        final int channelCount;
        final int srcFrameSize;
        final int dstFrameSize;
        final boolean bitPerfect;
        final boolean needsConversion;

        private ByteBuffer pendingOut;

        ActiveTrackSession(
                AudioTrack track,
                int srcEncoding,
                int dstEncoding,
                int channelCount,
                boolean bitPerfect) {
            this.track = track;
            this.srcEncoding = srcEncoding;
            this.dstEncoding = dstEncoding;
            this.channelCount = channelCount;
            this.srcFrameSize = AudioQualityModels.bytesPerSample(srcEncoding) * channelCount;
            this.dstFrameSize = AudioQualityModels.bytesPerSample(dstEncoding) * channelCount;
            this.bitPerfect = bitPerfect;
            this.needsConversion = srcEncoding != dstEncoding;
        }

        int writeConverted(ByteBuffer src, int sizeBytes) {
            // 1. Drain any tail left from a prior partial non-blocking write before consuming new input.
            if (pendingOut != null && pendingOut.hasRemaining()) {
                int drained = track.write(pendingOut, pendingOut.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                if (drained < 0) return drained;
                if (pendingOut.hasRemaining()) return 0;
            }

            int availableBytes = Math.min(sizeBytes, src.remaining());
            int frames = Math.min(availableBytes / srcFrameSize, MAX_CHUNK_FRAMES);
            if (frames <= 0) return 0;

            int dstBytes = frames * dstFrameSize;
            if (pendingOut == null || pendingOut.capacity() < dstBytes) {
                pendingOut = ByteBuffer.allocateDirect(dstBytes).order(ByteOrder.LITTLE_ENDIAN);
            }
            pendingOut.clear();
            int writtenConverted = AudioQualityModels.convertPcmSamples(
                    src, src.position(), srcEncoding, pendingOut, dstEncoding, frames * channelCount);
            pendingOut.position(0);
            pendingOut.limit(writtenConverted);

            int written = track.write(pendingOut, writtenConverted, AudioTrack.WRITE_NON_BLOCKING);
            if (written < 0) {
                pendingOut.limit(0);
                return written;
            }
            if (written == 0) {
                pendingOut.limit(0);
                return 0;
            }
            int fullFrames = written / dstFrameSize;
            int partialTail = written % dstFrameSize;
            if (partialTail == 0) {
                // Exact frame boundary: discard unconverted remainder so DefaultAudioSink tracks exact written frames.
                pendingOut.limit(0);
                int consumedSrcBytes = fullFrames * srcFrameSize;
                src.position(src.position() + consumedSrcBytes);
                return consumedSrcBytes;
            }
            // Rare partial-frame write: keep only the remaining bytes of that single partial frame in pendingOut.
            int frameEndByte = (fullFrames + 1) * dstFrameSize;
            pendingOut.position(written);
            pendingOut.limit(frameEndByte);
            int consumedSrcBytes = (fullFrames + 1) * srcFrameSize;
            src.position(src.position() + consumedSrcBytes);
            return consumedSrcBytes;
        }
    }
}
