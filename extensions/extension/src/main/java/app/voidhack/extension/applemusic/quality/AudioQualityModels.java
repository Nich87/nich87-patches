package app.voidhack.extension.applemusic.quality;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java data models, signal-path resolution, Android 14+ USB Bit-Perfect
 * mixer selection, and lossless integer PCM conversion helpers. Kept completely
 * Android-independent so it is exhaustively unit-tested in :patches:test.
 */
public final class AudioQualityModels {
    private AudioQualityModels() {}

    // Mirrors com.apple.android.music.playback.player.AudioConstants
    public static final int VARIANT_UNKNOWN = -1;
    public static final int VARIANT_HIGH_EFFICIENCY = 0;
    public static final int VARIANT_HIGH_QUALITY = 1;
    public static final int VARIANT_LOSSLESS = 2;
    public static final int VARIANT_HI_RES_LOSSLESS = 3;
    public static final int VARIANT_DOLBY_ATMOS = 4;
    public static final int VARIANT_DOLBY_AUDIO = 5;

    // Mirrors com.apple.android.music.model.AudioCapability bitmask
    public static final int CAP_DOLBY_ATMOS = 1;
    public static final int CAP_SURROUND = 2;
    public static final int CAP_LOSSLESS = 4;
    public static final int CAP_STEREO_LOSSY = 8;
    public static final int CAP_HI_RES_LOSSLESS = 16;
    public static final int CAP_SPATIAL = 32;

    // Mirrors android.media.AudioFormat.ENCODING_*
    public static final int ENCODING_PCM_16BIT = 2;
    public static final int ENCODING_PCM_8BIT = 3;
    public static final int ENCODING_PCM_FLOAT = 4;
    public static final int ENCODING_AC3 = 5;
    public static final int ENCODING_E_AC3 = 6;
    public static final int ENCODING_AC4 = 17;
    public static final int ENCODING_E_AC3_JOC = 18;
    public static final int ENCODING_DOLBY_MAT = 19;
    public static final int ENCODING_PCM_24BIT_PACKED = 21;
    public static final int ENCODING_PCM_32BIT = 22;
    // ExoPlayer2 internal PCM encoding constants
    public static final int EXO_ENCODING_PCM_24BIT = 0x20000000;
    public static final int EXO_ENCODING_PCM_32BIT = 0x30000000;

    // Mirrors android.media.AudioDeviceInfo.TYPE_*
    public static final int DEV_BUILTIN_EARPIECE = 1;
    public static final int DEV_BUILTIN_SPEAKER = 2;
    public static final int DEV_WIRED_HEADSET = 3;
    public static final int DEV_WIRED_HEADPHONES = 4;
    public static final int DEV_LINE_ANALOG = 5;
    public static final int DEV_LINE_DIGITAL = 6;
    public static final int DEV_BLUETOOTH_SCO = 7;
    public static final int DEV_BLUETOOTH_A2DP = 8;
    public static final int DEV_HDMI = 9;
    public static final int DEV_HDMI_ARC = 10;
    public static final int DEV_USB_DEVICE = 11;
    public static final int DEV_USB_ACCESSORY = 12;
    public static final int DEV_DOCK = 13;
    public static final int DEV_AUX_LINE = 19;
    public static final int DEV_IP = 20;
    public static final int DEV_BUS = 21;
    public static final int DEV_USB_HEADSET = 22;
    public static final int DEV_HEARING_AID = 23;
    public static final int DEV_BUILTIN_SPEAKER_SAFE = 24;
    public static final int DEV_REMOTE_SUBMIX = 25;
    public static final int DEV_BLE_HEADSET = 26;
    public static final int DEV_BLE_SPEAKER = 27;
    public static final int DEV_HDMI_EARC = 29;
    public static final int DEV_BLE_BROADCAST = 30;
    public static final int DEV_DOCK_ANALOG = 31;

    public enum AudioTier {
        HI_RES_LOSSLESS,
        LOSSLESS,
        DOLBY_ATMOS,
        DOLBY_AUDIO,
        HIGH_QUALITY,
        HIGH_EFFICIENCY,
        UNKNOWN
    }

    public enum OutputKind {
        USB,
        BLUETOOTH,
        BLUETOOTH_LE,
        WIRED_35MM,
        HDMI,
        HDMI_ARC,
        HDMI_EARC,
        OPTICAL,
        SPEAKER,
        CAST,
        OTHER
    }

    public enum PipelineMode {
        USB_DIRECT_DRIVER,
        BIT_PERFECT,
        USB_PREFERRED_MIXER,
        DIRECT,
        PASSTHROUGH,
        MIXER,
        DSP_RESAMPLED
    }

    public enum SignalIntegrity {
        BIT_PERFECT,
        HI_RES_LOSSLESS,
        LOSSLESS,
        SPATIAL,
        RESAMPLED,
        BLUETOOTH_COMPRESSED,
        CAST_REMOTE,
        STANDARD
    }

    public enum OutputPathId {
        USB_DIRECT_DRIVER,
        USB_BIT_PERFECT,
        USB_PREFERRED_MIXER,
        DIRECT_PLAYBACK,
        DOLBY_PASSTHROUGH,
        ANDROID_MIXER,
        BLUETOOTH_A2DP,
        BLUETOOTH_LE,
        CAST_REMOTE
    }

    public enum PathAvailability {
        ACTIVE,
        AVAILABLE,
        PERMISSION_REQUIRED,
        STANDBY,
        UNAVAILABLE
    }

    public static final class UsbMixerOption {
        public final boolean bitPerfect;
        public final int encoding;
        public final int sampleRateHz;
        public final int channelMask;

        public UsbMixerOption(boolean bitPerfect, int encoding, int sampleRateHz, int channelMask) {
            this.bitPerfect = bitPerfect;
            this.encoding = encoding;
            this.sampleRateHz = sampleRateHz;
            this.channelMask = channelMask;
        }
    }

    public static final class OutputPathEntry {
        public final OutputPathId id;
        public final PathAvailability availability;
        public final int maxBits;
        public final int maxRateHz;
        public final int activeBits;
        public final int activeRateHz;
        public final int activeEncoding;

        public OutputPathEntry(
                OutputPathId id,
                PathAvailability availability,
                int maxBits,
                int maxRateHz,
                int activeBits,
                int activeRateHz,
                int activeEncoding) {
            this.id = id;
            this.availability = availability;
            this.maxBits = maxBits;
            this.maxRateHz = maxRateHz;
            this.activeBits = activeBits;
            this.activeRateHz = activeRateHz;
            this.activeEncoding = activeEncoding;
        }
    }

    public static final class SourceInput {
        public final int audioVariant;
        public final int bitDepth;
        public final int sampleRateHz;
        public final int channelCount;
        public final int bitrateBps;
        public final String codec;
        public final int audioCapabilities;
        public final String trackTitle;
        public final String trackSubtitle;

        public SourceInput(
                int audioVariant,
                int bitDepth,
                int sampleRateHz,
                int channelCount,
                int bitrateBps,
                String codec,
                int audioCapabilities,
                String trackTitle,
                String trackSubtitle) {
            this.audioVariant = audioVariant;
            this.bitDepth = bitDepth;
            this.sampleRateHz = sampleRateHz;
            this.channelCount = channelCount;
            this.bitrateBps = bitrateBps;
            this.codec = codec;
            this.audioCapabilities = audioCapabilities;
            this.trackTitle = trackTitle;
            this.trackSubtitle = trackSubtitle;
        }
    }

    public static final class UsbDirectState {
        public static final UsbDirectState NONE = new UsbDirectState(
                false, false, false, false, false, "", 0, 0, 0, 0, 0, 0, 0, "", 0,
                false, false, 0, 0, 0, 0f);

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

        public UsbDirectState(
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
                int errorRateHz) {
            this(
                    nativeLoaded, deviceAttached, permissionGranted, settingEnabled, streamActive,
                    deviceName, uacVersion, sampleRateHz, bitDepth, subslotBytes, measuredRateHz,
                    maxBitDepth, maxSampleRateHz, errorReason, errorRateHz,
                    false, false, 0, 0, 0, 0f
            );
        }

        public UsbDirectState(
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
            this.deviceName = deviceName == null ? "" : deviceName.trim();
            this.uacVersion = uacVersion;
            this.sampleRateHz = sampleRateHz;
            this.bitDepth = bitDepth;
            this.subslotBytes = subslotBytes;
            this.measuredRateHz = measuredRateHz;
            this.maxBitDepth = maxBitDepth;
            this.maxSampleRateHz = maxSampleRateHz;
            this.errorReason = errorReason == null ? "" : errorReason;
            this.errorRateHz = errorRateHz;
            this.hwVolumeSupported = hwVolumeSupported;
            this.hwVolumeMuted = hwVolumeMuted;
            this.hwVolumeDb256 = hwVolumeDb256;
            this.hwVolumeMinDb256 = hwVolumeMinDb256;
            this.hwVolumeMaxDb256 = hwVolumeMaxDb256;
            this.hwVolumePercent = hwVolumePercent;
        }
    }

    public static final class PipelineInput {
        public final String deviceName;
        public final boolean hasLiveTrack;
        public final boolean isInputPcm;
        public final int sinkInputSampleRateHz;
        public final int sinkInputEncoding;
        public final int trackSampleRateHz;
        public final int trackEncoding;
        public final int trackChannelCount;
        public final int nativeHalSampleRateHz;
        public final int systemMixerSampleRateHz;
        public final boolean preferredMixerBitPerfect;
        public final int preferredMixerSampleRateHz;
        public final int preferredMixerEncoding;
        public final boolean directPlaybackSupported;
        public final boolean soundCheckEnabled;
        public final boolean eqEnabled;
        public final boolean isPlaying;
        public final boolean usbExclusiveSettingEnabled;
        public final boolean usbBitPerfectTrackActive;
        public final boolean usbPreferredMixerTrackActive;
        public final List<UsbMixerOption> supportedUsbMixers;
        public final UsbDirectState usbDirect;
        public final boolean usbDacOutputEnabled;

        public PipelineInput(
                String deviceName,
                boolean hasLiveTrack,
                boolean isInputPcm,
                int sinkInputSampleRateHz,
                int sinkInputEncoding,
                int trackSampleRateHz,
                int trackEncoding,
                int trackChannelCount,
                int nativeHalSampleRateHz,
                int systemMixerSampleRateHz,
                boolean preferredMixerBitPerfect,
                int preferredMixerSampleRateHz,
                int preferredMixerEncoding,
                boolean directPlaybackSupported,
                boolean soundCheckEnabled,
                boolean eqEnabled,
                boolean isPlaying) {
            this(
                    deviceName,
                    hasLiveTrack,
                    isInputPcm,
                    sinkInputSampleRateHz,
                    sinkInputEncoding,
                    trackSampleRateHz,
                    trackEncoding,
                    trackChannelCount,
                    nativeHalSampleRateHz,
                    systemMixerSampleRateHz,
                    preferredMixerBitPerfect,
                    preferredMixerSampleRateHz,
                    preferredMixerEncoding,
                    directPlaybackSupported,
                    soundCheckEnabled,
                    eqEnabled,
                    isPlaying,
                    false,
                    preferredMixerBitPerfect && preferredMixerSampleRateHz > 0,
                    !preferredMixerBitPerfect && preferredMixerSampleRateHz > 0,
                    Collections.emptyList(),
                    UsbDirectState.NONE,
                    true
            );
        }

        public PipelineInput(
                String deviceName,
                boolean hasLiveTrack,
                boolean isInputPcm,
                int sinkInputSampleRateHz,
                int sinkInputEncoding,
                int trackSampleRateHz,
                int trackEncoding,
                int trackChannelCount,
                int nativeHalSampleRateHz,
                int systemMixerSampleRateHz,
                boolean preferredMixerBitPerfect,
                int preferredMixerSampleRateHz,
                int preferredMixerEncoding,
                boolean directPlaybackSupported,
                boolean soundCheckEnabled,
                boolean eqEnabled,
                boolean isPlaying,
                boolean usbExclusiveSettingEnabled,
                boolean usbBitPerfectTrackActive,
                boolean usbPreferredMixerTrackActive,
                List<UsbMixerOption> supportedUsbMixers) {
            this(
                    deviceName,
                    hasLiveTrack,
                    isInputPcm,
                    sinkInputSampleRateHz,
                    sinkInputEncoding,
                    trackSampleRateHz,
                    trackEncoding,
                    trackChannelCount,
                    nativeHalSampleRateHz,
                    systemMixerSampleRateHz,
                    preferredMixerBitPerfect,
                    preferredMixerSampleRateHz,
                    preferredMixerEncoding,
                    directPlaybackSupported,
                    soundCheckEnabled,
                    eqEnabled,
                    isPlaying,
                    usbExclusiveSettingEnabled,
                    usbBitPerfectTrackActive,
                    usbPreferredMixerTrackActive,
                    supportedUsbMixers,
                    UsbDirectState.NONE,
                    true
            );
        }

        public PipelineInput(
                String deviceName,
                boolean hasLiveTrack,
                boolean isInputPcm,
                int sinkInputSampleRateHz,
                int sinkInputEncoding,
                int trackSampleRateHz,
                int trackEncoding,
                int trackChannelCount,
                int nativeHalSampleRateHz,
                int systemMixerSampleRateHz,
                boolean preferredMixerBitPerfect,
                int preferredMixerSampleRateHz,
                int preferredMixerEncoding,
                boolean directPlaybackSupported,
                boolean soundCheckEnabled,
                boolean eqEnabled,
                boolean isPlaying,
                boolean usbExclusiveSettingEnabled,
                boolean usbBitPerfectTrackActive,
                boolean usbPreferredMixerTrackActive,
                List<UsbMixerOption> supportedUsbMixers,
                UsbDirectState usbDirect) {
            this(
                    deviceName,
                    hasLiveTrack,
                    isInputPcm,
                    sinkInputSampleRateHz,
                    sinkInputEncoding,
                    trackSampleRateHz,
                    trackEncoding,
                    trackChannelCount,
                    nativeHalSampleRateHz,
                    systemMixerSampleRateHz,
                    preferredMixerBitPerfect,
                    preferredMixerSampleRateHz,
                    preferredMixerEncoding,
                    directPlaybackSupported,
                    soundCheckEnabled,
                    eqEnabled,
                    isPlaying,
                    usbExclusiveSettingEnabled,
                    usbBitPerfectTrackActive,
                    usbPreferredMixerTrackActive,
                    supportedUsbMixers,
                    usbDirect,
                    true
            );
        }

        public PipelineInput(
                String deviceName,
                boolean hasLiveTrack,
                boolean isInputPcm,
                int sinkInputSampleRateHz,
                int sinkInputEncoding,
                int trackSampleRateHz,
                int trackEncoding,
                int trackChannelCount,
                int nativeHalSampleRateHz,
                int systemMixerSampleRateHz,
                boolean preferredMixerBitPerfect,
                int preferredMixerSampleRateHz,
                int preferredMixerEncoding,
                boolean directPlaybackSupported,
                boolean soundCheckEnabled,
                boolean eqEnabled,
                boolean isPlaying,
                boolean usbExclusiveSettingEnabled,
                boolean usbBitPerfectTrackActive,
                boolean usbPreferredMixerTrackActive,
                List<UsbMixerOption> supportedUsbMixers,
                UsbDirectState usbDirect,
                boolean usbDacOutputEnabled) {
            this.deviceName = deviceName;
            this.hasLiveTrack = hasLiveTrack;
            this.isInputPcm = isInputPcm;
            this.sinkInputSampleRateHz = sinkInputSampleRateHz;
            this.sinkInputEncoding = sinkInputEncoding;
            this.trackSampleRateHz = trackSampleRateHz;
            this.trackEncoding = trackEncoding;
            this.trackChannelCount = trackChannelCount;
            this.nativeHalSampleRateHz = nativeHalSampleRateHz;
            this.systemMixerSampleRateHz = systemMixerSampleRateHz;
            this.preferredMixerBitPerfect = preferredMixerBitPerfect;
            this.preferredMixerSampleRateHz = preferredMixerSampleRateHz;
            this.preferredMixerEncoding = preferredMixerEncoding;
            this.directPlaybackSupported = directPlaybackSupported;
            this.soundCheckEnabled = soundCheckEnabled;
            this.eqEnabled = eqEnabled;
            this.isPlaying = isPlaying;
            this.usbExclusiveSettingEnabled = usbExclusiveSettingEnabled;
            this.usbBitPerfectTrackActive = usbBitPerfectTrackActive;
            this.usbPreferredMixerTrackActive = usbPreferredMixerTrackActive;
            this.supportedUsbMixers = supportedUsbMixers == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(supportedUsbMixers));
            this.usbDirect = usbDirect == null ? UsbDirectState.NONE : usbDirect;
            this.usbDacOutputEnabled = usbDacOutputEnabled;
        }
    }

    public static final class HardwareOutputInput {
        public final int deviceType;
        public final String productName;
        public final int[] supportedSampleRates;
        public final int[] supportedEncodings;
        public final String bluetoothCodecName;
        public final int bluetoothBitsPerSample;
        public final int bluetoothSampleRateHz;
        public final boolean isCastActive;
        public final String castDeviceName;

        public HardwareOutputInput(
                int deviceType,
                String productName,
                int[] supportedSampleRates,
                int[] supportedEncodings,
                String bluetoothCodecName,
                int bluetoothBitsPerSample,
                int bluetoothSampleRateHz,
                boolean isCastActive,
                String castDeviceName) {
            this.deviceType = deviceType;
            this.productName = productName;
            this.supportedSampleRates = supportedSampleRates == null ? new int[0] : supportedSampleRates.clone();
            this.supportedEncodings = supportedEncodings == null ? new int[0] : supportedEncodings.clone();
            this.bluetoothCodecName = bluetoothCodecName;
            this.bluetoothBitsPerSample = bluetoothBitsPerSample;
            this.bluetoothSampleRateHz = bluetoothSampleRateHz;
            this.isCastActive = isCastActive;
            this.castDeviceName = castDeviceName;
        }
    }

    public static final class ResolvedSignalPath {
        public final AudioTier tier;
        public final SignalIntegrity integrity;

        // Stage 1: Source
        public final int sourceBitDepth;
        public final int sourceSampleRateHz;
        public final int sourceChannelCount;
        public final int sourceBitrateBps;
        public final String sourceCodecLabel;
        public final boolean hiResAvailableInCatalog;
        public final String trackTitle;
        public final String trackSubtitle;

        // Stage 2: Playback Device
        public final String deviceName;
        public final int deviceBitDepth;
        public final int deviceSampleRateHz;
        public final int audioTrackSampleRateHz;
        public final int activeTrackEncoding;
        public final boolean deviceUsesFloatPcm;
        public final boolean deviceBitstreamPassthrough;
        public final boolean resampledInDevice;
        public final PipelineMode pipelineMode;
        public final boolean soundCheckEnabled;
        public final boolean eqEnabled;
        public final boolean isPlaying;

        // Stage 3: Output Device
        public final OutputKind outputKind;
        public final int rawDeviceType;
        public final String outputProductName;
        public final int outputBitDepth;
        public final int outputSampleRateHz;
        public final int outputMaxSampleRateHz;
        public final int outputMaxBitDepth;
        public final boolean outputSupportsAtmos;
        public final String bluetoothCodecName;

        // Output path capabilities & active route inventory
        public final boolean usbBitPerfectSupportedByHal;
        public final boolean usbPreferredMixerSupportedByHal;
        public final boolean usbExclusiveSettingEnabled;
        public final UsbDirectState usbDirect;
        public final List<OutputPathEntry> outputPaths;

        public ResolvedSignalPath(
                AudioTier tier,
                SignalIntegrity integrity,
                int sourceBitDepth,
                int sourceSampleRateHz,
                int sourceChannelCount,
                int sourceBitrateBps,
                String sourceCodecLabel,
                boolean hiResAvailableInCatalog,
                String trackTitle,
                String trackSubtitle,
                String deviceName,
                int deviceBitDepth,
                int deviceSampleRateHz,
                int audioTrackSampleRateHz,
                int activeTrackEncoding,
                boolean deviceUsesFloatPcm,
                boolean deviceBitstreamPassthrough,
                boolean resampledInDevice,
                PipelineMode pipelineMode,
                boolean soundCheckEnabled,
                boolean eqEnabled,
                boolean isPlaying,
                OutputKind outputKind,
                int rawDeviceType,
                String outputProductName,
                int outputBitDepth,
                int outputSampleRateHz,
                int outputMaxSampleRateHz,
                int outputMaxBitDepth,
                boolean outputSupportsAtmos,
                String bluetoothCodecName,
                boolean usbBitPerfectSupportedByHal,
                boolean usbPreferredMixerSupportedByHal,
                boolean usbExclusiveSettingEnabled,
                UsbDirectState usbDirect,
                List<OutputPathEntry> outputPaths) {
            this.tier = tier;
            this.integrity = integrity;
            this.sourceBitDepth = sourceBitDepth;
            this.sourceSampleRateHz = sourceSampleRateHz;
            this.sourceChannelCount = sourceChannelCount;
            this.sourceBitrateBps = sourceBitrateBps;
            this.sourceCodecLabel = sourceCodecLabel;
            this.hiResAvailableInCatalog = hiResAvailableInCatalog;
            this.trackTitle = trackTitle;
            this.trackSubtitle = trackSubtitle;
            this.deviceName = deviceName;
            this.deviceBitDepth = deviceBitDepth;
            this.deviceSampleRateHz = deviceSampleRateHz;
            this.audioTrackSampleRateHz = audioTrackSampleRateHz;
            this.activeTrackEncoding = activeTrackEncoding;
            this.deviceUsesFloatPcm = deviceUsesFloatPcm;
            this.deviceBitstreamPassthrough = deviceBitstreamPassthrough;
            this.resampledInDevice = resampledInDevice;
            this.pipelineMode = pipelineMode;
            this.soundCheckEnabled = soundCheckEnabled;
            this.eqEnabled = eqEnabled;
            this.isPlaying = isPlaying;
            this.outputKind = outputKind;
            this.rawDeviceType = rawDeviceType;
            this.outputProductName = outputProductName;
            this.outputBitDepth = outputBitDepth;
            this.outputSampleRateHz = outputSampleRateHz;
            this.outputMaxSampleRateHz = outputMaxSampleRateHz;
            this.outputMaxBitDepth = outputMaxBitDepth;
            this.outputSupportsAtmos = outputSupportsAtmos;
            this.bluetoothCodecName = bluetoothCodecName;
            this.usbBitPerfectSupportedByHal = usbBitPerfectSupportedByHal;
            this.usbPreferredMixerSupportedByHal = usbPreferredMixerSupportedByHal;
            this.usbExclusiveSettingEnabled = usbExclusiveSettingEnabled;
            this.usbDirect = usbDirect == null ? UsbDirectState.NONE : usbDirect;
            this.outputPaths = outputPaths == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(outputPaths);
        }
    }

    public static AudioTier resolveTier(int variant, String codec, int sampleRateHz) {
        switch (variant) {
            case VARIANT_HI_RES_LOSSLESS:
                return AudioTier.HI_RES_LOSSLESS;
            case VARIANT_LOSSLESS:
                return AudioTier.LOSSLESS;
            case VARIANT_DOLBY_ATMOS:
                return AudioTier.DOLBY_ATMOS;
            case VARIANT_DOLBY_AUDIO:
                return AudioTier.DOLBY_AUDIO;
            case VARIANT_HIGH_QUALITY:
                return AudioTier.HIGH_QUALITY;
            case VARIANT_HIGH_EFFICIENCY:
                return AudioTier.HIGH_EFFICIENCY;
            default:
                break;
        }
        String norm = normalizeCodecLabel(codec);
        if ("ALAC".equals(norm)) {
            return sampleRateHz > 48000 ? AudioTier.HI_RES_LOSSLESS : AudioTier.LOSSLESS;
        }
        if ("E-AC-3 JOC".equals(norm)) {
            return AudioTier.DOLBY_ATMOS;
        }
        if ("E-AC-3".equals(norm) || "AC-3".equals(norm) || "AC-4".equals(norm)) {
            return AudioTier.DOLBY_AUDIO;
        }
        if ("AAC".equals(norm)) {
            return AudioTier.HIGH_QUALITY;
        }
        return AudioTier.UNKNOWN;
    }

    public static OutputKind resolveOutputKind(int deviceType, boolean isCastActive) {
        if (isCastActive) {
            return OutputKind.CAST;
        }
        switch (deviceType) {
            case DEV_USB_DEVICE:
            case DEV_USB_ACCESSORY:
            case DEV_USB_HEADSET:
                return OutputKind.USB;
            case DEV_BLUETOOTH_A2DP:
            case DEV_BLUETOOTH_SCO:
            case DEV_HEARING_AID:
                return OutputKind.BLUETOOTH;
            case DEV_BLE_HEADSET:
            case DEV_BLE_SPEAKER:
            case DEV_BLE_BROADCAST:
                return OutputKind.BLUETOOTH_LE;
            case DEV_WIRED_HEADSET:
            case DEV_WIRED_HEADPHONES:
            case DEV_LINE_ANALOG:
            case DEV_AUX_LINE:
            case DEV_DOCK_ANALOG:
                return OutputKind.WIRED_35MM;
            case DEV_HDMI:
                return OutputKind.HDMI;
            case DEV_HDMI_ARC:
                return OutputKind.HDMI_ARC;
            case DEV_HDMI_EARC:
                return OutputKind.HDMI_EARC;
            case DEV_LINE_DIGITAL:
                return OutputKind.OPTICAL;
            case DEV_BUILTIN_SPEAKER:
            case DEV_BUILTIN_SPEAKER_SAFE:
            case DEV_BUILTIN_EARPIECE:
                return OutputKind.SPEAKER;
            case DEV_REMOTE_SUBMIX:
            case DEV_IP:
                return OutputKind.CAST;
            default:
                return OutputKind.OTHER;
        }
    }

    public static boolean isUsbDeviceType(int deviceType) {
        return deviceType == DEV_USB_DEVICE
                || deviceType == DEV_USB_HEADSET
                || deviceType == DEV_USB_ACCESSORY;
    }

    public static String normalizeCodecLabel(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        if (s.startsWith("audio/")) {
            s = s.substring(6);
        }
        if (s.contains("alac")) return "ALAC";
        if (s.contains("eac3-joc") || s.contains("ec+3")) return "E-AC-3 JOC";
        if (s.contains("eac3") || s.contains("ec-3")) return "E-AC-3";
        if (s.contains("ac4") || s.contains("ac-4")) return "AC-4";
        if (s.contains("ac3") || s.contains("ac-3")) return "AC-3";
        if (s.contains("mp4a") || s.contains("aac")) return "AAC";
        if (s.contains("flac")) return "FLAC";
        if (s.contains("opus")) return "Opus";
        if (s.contains("raw") || s.contains("pcm")) return "PCM";
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    public static int bitDepthFromEncoding(int encoding) {
        switch (encoding) {
            case ENCODING_PCM_8BIT:
                return 8;
            case ENCODING_PCM_16BIT:
                return 16;
            case ENCODING_PCM_24BIT_PACKED:
            case EXO_ENCODING_PCM_24BIT:
                return 24;
            case ENCODING_PCM_32BIT:
            case EXO_ENCODING_PCM_32BIT:
                return 32;
            case ENCODING_PCM_FLOAT:
                return 24;
            default:
                return 0;
        }
    }

    public static String pcmEncodingShortLabel(int encoding) {
        switch (encoding) {
            case ENCODING_PCM_16BIT:
                return "PCM 16-bit";
            case ENCODING_PCM_24BIT_PACKED:
            case EXO_ENCODING_PCM_24BIT:
                return "PCM 24-bit";
            case ENCODING_PCM_32BIT:
            case EXO_ENCODING_PCM_32BIT:
                return "PCM 32-bit";
            case ENCODING_PCM_FLOAT:
                return "PCM Float";
            default:
                return "PCM";
        }
    }

    public static boolean isFloatEncoding(int encoding) {
        return encoding == ENCODING_PCM_FLOAT;
    }

    public static boolean isBitstreamEncoding(int encoding) {
        return encoding == ENCODING_AC3
                || encoding == ENCODING_E_AC3
                || encoding == ENCODING_E_AC3_JOC
                || encoding == ENCODING_AC4
                || encoding == ENCODING_DOLBY_MAT;
    }

    public static int maxPcmBitDepth(int[] encodings) {
        if (encodings == null || encodings.length == 0) return 0;
        int max = 0;
        for (int enc : encodings) {
            int bits = bitDepthFromEncoding(enc);
            if (bits > max) max = bits;
        }
        return max;
    }

    public static boolean supportsAtmosEncoding(int[] encodings) {
        if (encodings == null) return false;
        for (int enc : encodings) {
            if (enc == ENCODING_E_AC3_JOC || enc == ENCODING_DOLBY_MAT || enc == ENCODING_AC4) {
                return true;
            }
        }
        return false;
    }

    public static int maxSampleRate(int[] rates) {
        if (rates == null || rates.length == 0) return 0;
        int max = 0;
        for (int r : rates) {
            if (r > max) max = r;
        }
        return max;
    }

    public static boolean supportsSampleRate(int[] rates, int targetHz) {
        if (targetHz <= 0 || rates == null || rates.length == 0) return false;
        for (int r : rates) {
            if (r == targetHz) return true;
        }
        return false;
    }

    public static int chooseNearestSupportedRate(int[] rates, int requestedHz, int fallbackHz) {
        if (rates == null || rates.length == 0) {
            return requestedHz > 0 ? requestedHz : fallbackHz;
        }
        if (requestedHz > 0 && supportsSampleRate(rates, requestedHz)) {
            return requestedHz;
        }
        if (fallbackHz > 0 && supportsSampleRate(rates, fallbackHz)) {
            return fallbackHz;
        }
        int bestAbove = Integer.MAX_VALUE;
        int bestBelow = 0;
        for (int r : rates) {
            if (r <= 0) continue;
            if (requestedHz > 0 && r >= requestedHz && r < bestAbove) {
                bestAbove = r;
            }
            if (requestedHz > 0 && r < requestedHz && r > bestBelow) {
                bestBelow = r;
            }
        }
        if (bestAbove != Integer.MAX_VALUE) return bestAbove;
        if (bestBelow > 0) return bestBelow;
        int max = maxSampleRate(rates);
        return max > 0 ? max : fallbackHz;
    }

    /**
     * Selects the best matching Android 14+ USB AudioMixerAttributes option for the given
     * source/track format. Strongly prefers MIXER_BEHAVIOR_BIT_PERFECT with native or wider
     * lossless integer PCM (16/24/32-bit); optionally falls back to MIXER_BEHAVIOR_DEFAULT
     * at the exact target sample rate when the HAL exposes no bit-perfect profiles.
     */
    public static UsbMixerOption selectBestUsbMixerOption(
            List<UsbMixerOption> supported,
            int targetSampleRateHz,
            int targetChannelMask,
            int sourceBitDepth,
            boolean allowPreferredMixerFallback) {
        if (supported == null || supported.isEmpty() || targetSampleRateHz <= 0) return null;

        int desiredBits = sourceBitDepth > 0 ? sourceBitDepth : 24;
        UsbMixerOption bestBitPerfect = pickBestMatchingOption(
                supported, true, targetSampleRateHz, targetChannelMask, desiredBits);
        if (bestBitPerfect != null) {
            return bestBitPerfect;
        }
        if (!allowPreferredMixerFallback) {
            return null;
        }
        return pickBestMatchingOption(
                supported, false, targetSampleRateHz, targetChannelMask, desiredBits);
    }

    private static UsbMixerOption pickBestMatchingOption(
            List<UsbMixerOption> supported,
            boolean requireBitPerfect,
            int targetSampleRateHz,
            int targetChannelMask,
            int desiredBits) {
        UsbMixerOption best = null;
        int bestScore = -1;
        for (UsbMixerOption opt : supported) {
            if (opt == null || opt.bitPerfect != requireBitPerfect) continue;
            if (opt.sampleRateHz != targetSampleRateHz) continue;
            if (targetChannelMask != 0 && opt.channelMask != 0 && opt.channelMask != targetChannelMask) continue;
            int score = scorePcmEncodingForSource(opt.encoding, desiredBits);
            if (score > bestScore) {
                bestScore = score;
                best = opt;
            }
        }
        return best;
    }

    private static int scorePcmEncodingForSource(int androidEncoding, int sourceBits) {
        // Prefer exact native integer PCM, then lossless zero-padded wider integer PCM, then float.
        if (sourceBits <= 16) {
            switch (androidEncoding) {
                case ENCODING_PCM_16BIT:
                    return 100;
                case ENCODING_PCM_24BIT_PACKED:
                    return 90;
                case ENCODING_PCM_32BIT:
                    return 85;
                case ENCODING_PCM_FLOAT:
                    return 60;
                default:
                    return -1;
            }
        }
        if (sourceBits <= 24) {
            switch (androidEncoding) {
                case ENCODING_PCM_24BIT_PACKED:
                    return 100;
                case ENCODING_PCM_32BIT:
                    return 95;
                case ENCODING_PCM_FLOAT:
                    return 70;
                case ENCODING_PCM_16BIT:
                    return -1; // Never truncate 24-bit down to 16-bit in bit-perfect mode
                default:
                    return -1;
            }
        }
        switch (androidEncoding) {
            case ENCODING_PCM_32BIT:
                return 100;
            case ENCODING_PCM_24BIT_PACKED:
                return 80;
            case ENCODING_PCM_FLOAT:
                return 70;
            default:
                return -1;
        }
    }

    public static int bytesPerSample(int encoding) {
        switch (encoding) {
            case ENCODING_PCM_8BIT:
                return 1;
            case ENCODING_PCM_16BIT:
                return 2;
            case ENCODING_PCM_24BIT_PACKED:
            case EXO_ENCODING_PCM_24BIT:
                return 3;
            case ENCODING_PCM_32BIT:
            case EXO_ENCODING_PCM_32BIT:
            case ENCODING_PCM_FLOAT:
                return 4;
            default:
                return 0;
        }
    }

    public static boolean canConvertPcmLosslessly(int srcEncoding, int dstEncoding) {
        if (srcEncoding == dstEncoding) return true;
        if (srcEncoding == ENCODING_PCM_FLOAT) {
            return dstEncoding == ENCODING_PCM_24BIT_PACKED || dstEncoding == ENCODING_PCM_32BIT;
        }
        if (srcEncoding == ENCODING_PCM_16BIT) {
            return dstEncoding == ENCODING_PCM_24BIT_PACKED || dstEncoding == ENCODING_PCM_32BIT;
        }
        if (srcEncoding == ENCODING_PCM_24BIT_PACKED || srcEncoding == EXO_ENCODING_PCM_24BIT) {
            return dstEncoding == ENCODING_PCM_32BIT;
        }
        return false;
    }

    /**
     * Converts `sampleCount` interleaved PCM samples from `src` (little-endian) into `dst`
     * (little-endian) without modifying `src`'s position or limit.
     *
     * Note on FloatResamplingAudioProcessor round-trip:
     * Apple Music's ExoPlayer converts 24-bit signed int s24 in [-2^23, 2^23-1] via
     * (float)((s24 << 8) * (1.0 / 0x7FFFFFFF)). Multiplying back by 8388608.0f (2^23)
     * and rounding recovers every single one of the 16,777,216 24-bit integer values
     * bit-for-bit identically.
     */
    public static int convertPcmSamples(
            ByteBuffer src,
            int srcOffset,
            int srcEncoding,
            ByteBuffer dst,
            int dstEncoding,
            int sampleCount) {
        int dstPos = 0;
        if (srcEncoding == ENCODING_PCM_FLOAT && dstEncoding == ENCODING_PCM_24BIT_PACKED) {
            int p = srcOffset;
            for (int i = 0; i < sampleCount; i++, p += 4) {
                int bits = (src.get(p) & 0xFF)
                        | ((src.get(p + 1) & 0xFF) << 8)
                        | ((src.get(p + 2) & 0xFF) << 16)
                        | ((src.get(p + 3) & 0xFF) << 24);
                float f = Float.intBitsToFloat(bits);
                int s24 = floatToSigned24(f);
                dst.put(dstPos++, (byte) (s24 & 0xFF));
                dst.put(dstPos++, (byte) ((s24 >>> 8) & 0xFF));
                dst.put(dstPos++, (byte) ((s24 >>> 16) & 0xFF));
            }
            return dstPos;
        }
        if (srcEncoding == ENCODING_PCM_FLOAT && dstEncoding == ENCODING_PCM_32BIT) {
            int p = srcOffset;
            for (int i = 0; i < sampleCount; i++, p += 4) {
                int bits = (src.get(p) & 0xFF)
                        | ((src.get(p + 1) & 0xFF) << 8)
                        | ((src.get(p + 2) & 0xFF) << 16)
                        | ((src.get(p + 3) & 0xFF) << 24);
                float f = Float.intBitsToFloat(bits);
                int s24 = floatToSigned24(f);
                // Exact 24-bit sample left-aligned in 32-bit integer container (zero low 8 bits).
                dst.put(dstPos++, (byte) 0);
                dst.put(dstPos++, (byte) (s24 & 0xFF));
                dst.put(dstPos++, (byte) ((s24 >>> 8) & 0xFF));
                dst.put(dstPos++, (byte) ((s24 >>> 16) & 0xFF));
            }
            return dstPos;
        }
        if (srcEncoding == ENCODING_PCM_16BIT && dstEncoding == ENCODING_PCM_24BIT_PACKED) {
            int p = srcOffset;
            for (int i = 0; i < sampleCount; i++, p += 2) {
                dst.put(dstPos++, (byte) 0);
                dst.put(dstPos++, src.get(p));
                dst.put(dstPos++, src.get(p + 1));
            }
            return dstPos;
        }
        if (srcEncoding == ENCODING_PCM_16BIT && dstEncoding == ENCODING_PCM_32BIT) {
            int p = srcOffset;
            for (int i = 0; i < sampleCount; i++, p += 2) {
                dst.put(dstPos++, (byte) 0);
                dst.put(dstPos++, (byte) 0);
                dst.put(dstPos++, src.get(p));
                dst.put(dstPos++, src.get(p + 1));
            }
            return dstPos;
        }
        if ((srcEncoding == ENCODING_PCM_24BIT_PACKED || srcEncoding == EXO_ENCODING_PCM_24BIT)
                && dstEncoding == ENCODING_PCM_32BIT) {
            int p = srcOffset;
            for (int i = 0; i < sampleCount; i++, p += 3) {
                dst.put(dstPos++, (byte) 0);
                dst.put(dstPos++, src.get(p));
                dst.put(dstPos++, src.get(p + 1));
                dst.put(dstPos++, src.get(p + 2));
            }
            return dstPos;
        }
        return 0;
    }

    public static int floatToSigned24(float f) {
        if (Float.isNaN(f)) return 0;
        int v = Math.round(f * 8388608.0f);
        if (v > 8388607) return 8388607;
        if (v < -8388608) return -8388608;
        return v;
    }

    /**
     * Resolves the full 3-stage audio signal path AND the complete inventory of available
     * vs active output paths on this device.
     */
    public static ResolvedSignalPath resolve(
            SourceInput source,
            PipelineInput pipeline,
            HardwareOutputInput hardware) {
        String codecLabel = normalizeCodecLabel(source.codec);
        AudioTier tier = resolveTier(source.audioVariant, codecLabel, source.sampleRateHz);
        boolean isLosslessTier = tier == AudioTier.HI_RES_LOSSLESS || tier == AudioTier.LOSSLESS;

        int srcRate = source.sampleRateHz > 0
                ? source.sampleRateHz
                : (pipeline.sinkInputSampleRateHz > 0 ? pipeline.sinkInputSampleRateHz : 48000);
        int srcBits = source.bitDepth > 0 ? source.bitDepth : bitDepthFromEncoding(pipeline.sinkInputEncoding);
        if (srcBits <= 0 && isLosslessTier) {
            srcBits = tier == AudioTier.HI_RES_LOSSLESS ? 24 : 16;
        }
        int srcChannels = source.channelCount > 0
                ? source.channelCount
                : (pipeline.trackChannelCount > 0 ? pipeline.trackChannelCount : 2);
        if (codecLabel.isEmpty()) {
            if (isLosslessTier) codecLabel = "ALAC";
            else if (tier == AudioTier.DOLBY_ATMOS) codecLabel = "E-AC-3 JOC";
            else if (tier == AudioTier.DOLBY_AUDIO) codecLabel = "E-AC-3";
            else if (tier == AudioTier.HIGH_QUALITY || tier == AudioTier.HIGH_EFFICIENCY) codecLabel = "AAC";
        }
        boolean hiResAvailable = tier == AudioTier.LOSSLESS && (source.audioCapabilities & CAP_HI_RES_LOSSLESS) != 0;

        UsbDirectState usbDirect = pipeline.usbDirect != null ? pipeline.usbDirect : UsbDirectState.NONE;
        boolean usbDirectRunning = usbDirect.streamActive && usbDirect.sampleRateHz > 0;

        OutputKind outputKind = usbDirectRunning
                ? OutputKind.USB
                : resolveOutputKind(hardware.deviceType, hardware.isCastActive);
        int rawDevType = usbDirectRunning ? DEV_USB_DEVICE : hardware.deviceType;
        String resolvedProductName = usbDirectRunning && !usbDirect.deviceName.isEmpty()
                ? usbDirect.deviceName
                : safeTrim(hardware.productName);
        int hwMaxRate = usbDirectRunning && usbDirect.maxSampleRateHz > 0
                ? usbDirect.maxSampleRateHz
                : maxSampleRate(hardware.supportedSampleRates);
        int hwMaxBits = usbDirectRunning && usbDirect.maxBitDepth > 0
                ? usbDirect.maxBitDepth
                : maxPcmBitDepth(hardware.supportedEncodings);
        boolean hwAtmos = !usbDirectRunning && supportsAtmosEncoding(hardware.supportedEncodings);

        // Inspect supported Android 14+ USB mixer attributes
        boolean usbBpSupported = false;
        int usbBpMaxRate = 0;
        int usbBpMaxBits = 0;
        boolean usbPrefSupported = false;
        int usbPrefMaxRate = 0;
        int usbPrefMaxBits = 0;
        for (UsbMixerOption opt : pipeline.supportedUsbMixers) {
            if (opt == null) continue;
            int bits = bitDepthFromEncoding(opt.encoding);
            if (opt.bitPerfect) {
                usbBpSupported = true;
                if (opt.sampleRateHz > usbBpMaxRate) usbBpMaxRate = opt.sampleRateHz;
                if (bits > usbBpMaxBits) usbBpMaxBits = bits;
            } else {
                usbPrefSupported = true;
                if (opt.sampleRateHz > usbPrefMaxRate) usbPrefMaxRate = opt.sampleRateHz;
                if (bits > usbPrefMaxBits) usbPrefMaxBits = bits;
            }
        }

        // Live AudioTrack / USB Direct rate & encoding
        int trackRate = usbDirectRunning
                ? usbDirect.sampleRateHz
                : (pipeline.trackSampleRateHz > 0 ? pipeline.trackSampleRateHz : srcRate);
        int effectiveTrackEnc = usbDirectRunning
                ? encodingForUsbSubslot(usbDirect.subslotBytes, usbDirect.bitDepth)
                : pipeline.trackEncoding;
        boolean bitstreamPassthrough = !usbDirectRunning && pipeline.hasLiveTrack
                && (!pipeline.isInputPcm || isBitstreamEncoding(pipeline.trackEncoding));
        boolean usesFloatPcm = !usbDirectRunning && !bitstreamPassthrough && isFloatEncoding(pipeline.trackEncoding);

        int fallbackMixerRate = pipeline.nativeHalSampleRateHz > 0
                ? pipeline.nativeHalSampleRateHz
                : (pipeline.systemMixerSampleRateHz > 0 ? pipeline.systemMixerSampleRateHz : 48000);

        int deviceRate;
        int deviceBits;
        PipelineMode mode;

        if (usbDirectRunning) {
            deviceRate = usbDirect.sampleRateHz;
            deviceBits = usbDirect.bitDepth > 0 ? usbDirect.bitDepth : (srcBits > 0 ? srcBits : 24);
            mode = PipelineMode.USB_DIRECT_DRIVER;
        } else if (bitstreamPassthrough) {
            deviceRate = trackRate;
            deviceBits = 0;
            mode = PipelineMode.PASSTHROUGH;
        } else if (outputKind == OutputKind.USB
                && (pipeline.usbBitPerfectTrackActive
                || (pipeline.preferredMixerBitPerfect && pipeline.preferredMixerSampleRateHz > 0))) {
            deviceRate = pipeline.preferredMixerSampleRateHz > 0 ? pipeline.preferredMixerSampleRateHz : trackRate;
            int enc = pipeline.trackEncoding > 0 ? pipeline.trackEncoding : pipeline.preferredMixerEncoding;
            int prefBits = bitDepthFromEncoding(enc);
            deviceBits = prefBits > 0 ? prefBits : (srcBits > 0 ? srcBits : 24);
            mode = PipelineMode.BIT_PERFECT;
        } else if (outputKind == OutputKind.USB
                && (pipeline.usbPreferredMixerTrackActive
                || (!pipeline.preferredMixerBitPerfect && pipeline.preferredMixerSampleRateHz > 0))) {
            deviceRate = pipeline.preferredMixerSampleRateHz > 0 ? pipeline.preferredMixerSampleRateHz : trackRate;
            int enc = pipeline.trackEncoding > 0 ? pipeline.trackEncoding : pipeline.preferredMixerEncoding;
            int prefBits = bitDepthFromEncoding(enc);
            deviceBits = prefBits > 0 ? prefBits : (srcBits > 0 ? srcBits : 24);
            mode = PipelineMode.USB_PREFERRED_MIXER;
        } else if (pipeline.directPlaybackSupported && trackRate > 0
                && (hardware.supportedSampleRates.length == 0 || supportsSampleRate(hardware.supportedSampleRates, trackRate))) {
            deviceRate = trackRate;
            deviceBits = resolvePcmBitDepth(pipeline.trackEncoding, pipeline.sinkInputEncoding, srcBits, hwMaxBits);
            mode = PipelineMode.DIRECT;
        } else {
            boolean inAppResampled = pipeline.sinkInputSampleRateHz > 0
                    && pipeline.trackSampleRateHz > 0
                    && pipeline.sinkInputSampleRateHz != pipeline.trackSampleRateHz;

            if (inAppResampled) {
                deviceRate = pipeline.trackSampleRateHz;
                if (fallbackMixerRate > 0 && fallbackMixerRate != deviceRate && !pipeline.directPlaybackSupported) {
                    deviceRate = fallbackMixerRate;
                }
                mode = PipelineMode.DSP_RESAMPLED;
            } else if (outputKind == OutputKind.BLUETOOTH && hardware.bluetoothSampleRateHz > 0) {
                deviceRate = hardware.bluetoothSampleRateHz;
                mode = PipelineMode.MIXER;
            } else if (pipeline.nativeHalSampleRateHz > 0) {
                if (hardware.supportedSampleRates.length > 0 && hwMaxRate > 0 && pipeline.nativeHalSampleRateHz > hwMaxRate) {
                    deviceRate = chooseNearestSupportedRate(hardware.supportedSampleRates, trackRate, hwMaxRate);
                } else {
                    deviceRate = pipeline.nativeHalSampleRateHz;
                }
                mode = (deviceRate == srcRate && srcRate > 48000) ? PipelineMode.DIRECT : PipelineMode.MIXER;
            } else {
                deviceRate = fallbackMixerRate;
                mode = PipelineMode.MIXER;
            }
            deviceBits = resolvePcmBitDepth(pipeline.trackEncoding, pipeline.sinkInputEncoding, srcBits, hwMaxBits);
        }

        boolean resampledInDevice = !bitstreamPassthrough && srcRate > 0 && deviceRate > 0 && deviceRate != srcRate;

        // Stage 3: Connected output device effective sample rate & bit depth
        int outputRate;
        int outputBits;
        if (usbDirectRunning) {
            outputRate = usbDirect.sampleRateHz;
            outputBits = deviceBits;
        } else if (outputKind == OutputKind.CAST) {
            outputRate = srcRate;
            outputBits = srcBits;
        } else if (bitstreamPassthrough) {
            outputRate = deviceRate;
            outputBits = 0;
        } else if (outputKind == OutputKind.BLUETOOTH && hardware.bluetoothSampleRateHz > 0) {
            outputRate = hardware.bluetoothSampleRateHz;
            outputBits = hardware.bluetoothBitsPerSample > 0 ? hardware.bluetoothBitsPerSample : deviceBits;
        } else if (outputKind == OutputKind.BLUETOOTH_LE) {
            outputRate = hardware.supportedSampleRates.length > 0
                    ? chooseNearestSupportedRate(hardware.supportedSampleRates, deviceRate, 48000)
                    : Math.min(deviceRate, 48000);
            outputBits = hwMaxBits > 0 ? Math.min(deviceBits > 0 ? deviceBits : 24, hwMaxBits) : (deviceBits > 0 ? deviceBits : 24);
        } else {
            if (hardware.supportedSampleRates.length > 0) {
                outputRate = chooseNearestSupportedRate(hardware.supportedSampleRates, deviceRate, fallbackMixerRate);
            } else {
                outputRate = deviceRate;
            }
            if (hwMaxBits > 0 && deviceBits > 0) {
                outputBits = Math.min(deviceBits, hwMaxBits);
            } else if (hwMaxBits > 0) {
                outputBits = hwMaxBits;
            } else {
                outputBits = deviceBits;
            }
        }

        SignalIntegrity integrity = computeIntegrity(
                tier,
                outputKind,
                mode,
                srcRate,
                srcBits,
                deviceRate,
                outputRate,
                outputBits,
                bitstreamPassthrough,
                hardware.bluetoothCodecName);

        // Build full transparent inventory of output paths (which exist & which is active)
        List<OutputPathEntry> paths = buildOutputPathInventory(
                outputKind,
                mode,
                usbDirect,
                pipeline.usbDacOutputEnabled,
                bitstreamPassthrough,
                pipeline.directPlaybackSupported,
                usbBpSupported,
                usbBpMaxBits,
                usbBpMaxRate,
                usbPrefSupported,
                usbPrefMaxBits,
                usbPrefMaxRate,
                deviceBits,
                deviceRate,
                effectiveTrackEnc,
                fallbackMixerRate,
                outputBits,
                outputRate,
                hwAtmos
        );

        return new ResolvedSignalPath(
                tier,
                integrity,
                srcBits,
                srcRate,
                srcChannels,
                source.bitrateBps,
                codecLabel,
                hiResAvailable,
                safeTrim(source.trackTitle),
                safeTrim(source.trackSubtitle),
                safeTrim(pipeline.deviceName),
                deviceBits,
                deviceRate,
                trackRate,
                effectiveTrackEnc,
                usesFloatPcm,
                bitstreamPassthrough,
                resampledInDevice,
                mode,
                pipeline.soundCheckEnabled,
                pipeline.eqEnabled,
                pipeline.isPlaying || usbDirectRunning,
                outputKind,
                rawDevType,
                resolvedProductName,
                outputBits,
                outputRate,
                hwMaxRate,
                hwMaxBits,
                hwAtmos,
                safeTrim(hardware.bluetoothCodecName),
                usbBpSupported,
                usbPrefSupported,
                pipeline.usbExclusiveSettingEnabled,
                usbDirect,
                paths);
    }

    private static int encodingForUsbSubslot(int subslotBytes, int bitResolution) {
        if (subslotBytes == 2) return ENCODING_PCM_16BIT;
        if (subslotBytes == 3) return ENCODING_PCM_24BIT_PACKED;
        if (subslotBytes == 4) return bitResolution == 24 ? ENCODING_PCM_24BIT_PACKED : ENCODING_PCM_32BIT;
        return ENCODING_PCM_24BIT_PACKED;
    }

    private static List<OutputPathEntry> buildOutputPathInventory(
            OutputKind outputKind,
            PipelineMode activeMode,
            UsbDirectState usbDirect,
            boolean usbDacOutputEnabled,
            boolean bitstreamPassthrough,
            boolean directPlaybackSupported,
            boolean usbBpSupported,
            int usbBpMaxBits,
            int usbBpMaxRate,
            boolean usbPrefSupported,
            int usbPrefMaxBits,
            int usbPrefMaxRate,
            int deviceBits,
            int deviceRate,
            int trackEncoding,
            int systemMixerRate,
            int outputBits,
            int outputRate,
            boolean hwAtmos) {
        List<OutputPathEntry> list = new ArrayList<>();

        // 1. Userspace USB Direct Driver (UAC1/UAC2, only exposed when USB DAC bit-perfect output patch is enabled)
        if (usbDacOutputEnabled) {
            PathAvailability directUsbAvail;
            if (activeMode == PipelineMode.USB_DIRECT_DRIVER) {
                directUsbAvail = PathAvailability.ACTIVE;
            } else if (!usbDirect.nativeLoaded) {
                directUsbAvail = PathAvailability.UNAVAILABLE;
            } else if (!usbDirect.deviceAttached) {
                directUsbAvail = PathAvailability.UNAVAILABLE;
            } else if (!usbDirect.permissionGranted) {
                directUsbAvail = PathAvailability.PERMISSION_REQUIRED;
            } else {
                directUsbAvail = PathAvailability.AVAILABLE;
            }
            list.add(new OutputPathEntry(
                    OutputPathId.USB_DIRECT_DRIVER,
                    directUsbAvail,
                    usbDirect.maxBitDepth,
                    usbDirect.maxSampleRateHz,
                    usbDirect.bitDepth > 0 ? usbDirect.bitDepth : deviceBits,
                    usbDirect.sampleRateHz > 0 ? usbDirect.sampleRateHz : deviceRate,
                    trackEncoding));
        }

        // 2. Android 14+ USB Exclusive Bit-Perfect (OS HAL)
        if (usbDacOutputEnabled) {
            if (outputKind == OutputKind.USB) {
                PathAvailability bpAvail = activeMode == PipelineMode.BIT_PERFECT
                        ? PathAvailability.ACTIVE
                        : (usbBpSupported ? PathAvailability.AVAILABLE : PathAvailability.UNAVAILABLE);
                list.add(new OutputPathEntry(
                        OutputPathId.USB_BIT_PERFECT,
                        bpAvail,
                        usbBpMaxBits,
                        usbBpMaxRate,
                        deviceBits,
                        deviceRate,
                        trackEncoding));
            } else {
                list.add(new OutputPathEntry(
                        OutputPathId.USB_BIT_PERFECT,
                        PathAvailability.UNAVAILABLE,
                        0,
                        0,
                        0,
                        0,
                        0));
            }
        } else if (outputKind == OutputKind.USB && activeMode == PipelineMode.BIT_PERFECT) {
            list.add(new OutputPathEntry(
                    OutputPathId.USB_BIT_PERFECT,
                    PathAvailability.ACTIVE,
                    usbBpMaxBits,
                    usbBpMaxRate,
                    deviceBits,
                    deviceRate,
                    trackEncoding));
        }

        // 3. Android 14+ USB Preferred Mixer (shown when USB DAC patch is enabled & HAL advertises it, or if active)
        if (outputKind == OutputKind.USB
                && ((usbDacOutputEnabled && usbPrefSupported) || activeMode == PipelineMode.USB_PREFERRED_MIXER)) {
            PathAvailability prefAvail = activeMode == PipelineMode.USB_PREFERRED_MIXER
                    ? PathAvailability.ACTIVE
                    : PathAvailability.AVAILABLE;
            list.add(new OutputPathEntry(
                    OutputPathId.USB_PREFERRED_MIXER,
                    prefAvail,
                    usbPrefMaxBits,
                    usbPrefMaxRate,
                    deviceBits,
                    deviceRate,
                    trackEncoding));
        }

        // 4. Direct Playback HAL (or Dolby Bitstream Passthrough)
        if (bitstreamPassthrough || hwAtmos) {
            PathAvailability passAvail = activeMode == PipelineMode.PASSTHROUGH
                    ? PathAvailability.ACTIVE
                    : PathAvailability.AVAILABLE;
            list.add(new OutputPathEntry(
                    OutputPathId.DOLBY_PASSTHROUGH,
                    passAvail,
                    0,
                    outputRate,
                    0,
                    deviceRate,
                    trackEncoding));
        }
        if (!bitstreamPassthrough) {
            PathAvailability directAvail = activeMode == PipelineMode.DIRECT
                    ? PathAvailability.ACTIVE
                    : (directPlaybackSupported ? PathAvailability.AVAILABLE : PathAvailability.UNAVAILABLE);
            list.add(new OutputPathEntry(
                    OutputPathId.DIRECT_PLAYBACK,
                    directAvail,
                    deviceBits,
                    deviceRate,
                    deviceBits,
                    deviceRate,
                    trackEncoding));
        }

        // 5. Android Standard System Mixer (AudioFlinger)
        boolean mixerActive = activeMode == PipelineMode.MIXER || activeMode == PipelineMode.DSP_RESAMPLED;
        list.add(new OutputPathEntry(
                OutputPathId.ANDROID_MIXER,
                mixerActive ? PathAvailability.ACTIVE : PathAvailability.STANDBY,
                0,
                systemMixerRate,
                deviceBits,
                systemMixerRate > 0 ? systemMixerRate : deviceRate,
                trackEncoding));

        // 6. Wireless transport layer (Bluetooth / Cast) when active
        if (outputKind == OutputKind.BLUETOOTH) {
            list.add(new OutputPathEntry(
                    OutputPathId.BLUETOOTH_A2DP,
                    PathAvailability.ACTIVE,
                    outputBits,
                    outputRate,
                    outputBits,
                    outputRate,
                    0));
        } else if (outputKind == OutputKind.BLUETOOTH_LE) {
            list.add(new OutputPathEntry(
                    OutputPathId.BLUETOOTH_LE,
                    PathAvailability.ACTIVE,
                    outputBits,
                    outputRate,
                    outputBits,
                    outputRate,
                    0));
        } else if (outputKind == OutputKind.CAST) {
            list.add(new OutputPathEntry(
                    OutputPathId.CAST_REMOTE,
                    PathAvailability.ACTIVE,
                    outputBits,
                    outputRate,
                    outputBits,
                    outputRate,
                    0));
        }

        return list;
    }

    private static int resolvePcmBitDepth(int trackEncoding, int sinkInputEncoding, int srcBits, int hwMaxBits) {
        int trackBits = bitDepthFromEncoding(trackEncoding);
        int inputBits = bitDepthFromEncoding(sinkInputEncoding);
        int bits;
        if (isFloatEncoding(trackEncoding)) {
            bits = inputBits > 0 ? inputBits : (srcBits > 0 ? Math.max(srcBits, 24) : 24);
        } else if (trackBits > 0) {
            bits = trackBits;
        } else if (inputBits > 0) {
            bits = inputBits;
        } else if (srcBits > 0) {
            bits = srcBits;
        } else {
            bits = 24;
        }
        if (hwMaxBits > 0 && bits > hwMaxBits) {
            return hwMaxBits;
        }
        return bits;
    }

    private static SignalIntegrity computeIntegrity(
            AudioTier tier,
            OutputKind outputKind,
            PipelineMode mode,
            int srcRate,
            int srcBits,
            int deviceRate,
            int outputRate,
            int outputBits,
            boolean bitstreamPassthrough,
            String btCodecName) {
        if (outputKind == OutputKind.CAST) {
            return SignalIntegrity.CAST_REMOTE;
        }
        if (tier == AudioTier.DOLBY_ATMOS || tier == AudioTier.DOLBY_AUDIO) {
            return SignalIntegrity.SPATIAL;
        }
        if (outputKind == OutputKind.BLUETOOTH || outputKind == OutputKind.BLUETOOTH_LE) {
            if (btCodecName != null && btCodecName.toLowerCase(Locale.ROOT).contains("lossless")
                    && deviceRate == srcRate && outputRate == srcRate) {
                return SignalIntegrity.LOSSLESS;
            }
            return SignalIntegrity.BLUETOOTH_COMPRESSED;
        }
        boolean isLossless = tier == AudioTier.HI_RES_LOSSLESS || tier == AudioTier.LOSSLESS;
        if (!isLossless) {
            return SignalIntegrity.STANDARD;
        }
        boolean ratePreserved = srcRate > 0 && deviceRate == srcRate && outputRate == srcRate;
        boolean bitsPreserved = srcBits <= 0 || outputBits <= 0 || outputBits >= srcBits;
        if (!ratePreserved || !bitsPreserved) {
            return SignalIntegrity.RESAMPLED;
        }
        if (mode == PipelineMode.BIT_PERFECT || mode == PipelineMode.USB_DIRECT_DRIVER) {
            return SignalIntegrity.BIT_PERFECT;
        }
        if (tier == AudioTier.HI_RES_LOSSLESS && outputRate > 48000) {
            return SignalIntegrity.HI_RES_LOSSLESS;
        }
        return SignalIntegrity.LOSSLESS;
    }

    public static String formatSampleRateKHz(int sampleRateHz) {
        if (sampleRateHz <= 0) return "";
        if (sampleRateHz % 1000 == 0) {
            return (sampleRateHz / 1000) + " kHz";
        }
        double khz = sampleRateHz / 1000.0;
        if (Math.abs(khz * 10.0 - Math.rint(khz * 10.0)) < 1e-6) {
            return String.format(Locale.US, "%.1f kHz", khz);
        }
        return String.format(Locale.US, "%.2f kHz", khz);
    }

    public static String formatBitrateKbps(int bitrateBps) {
        if (bitrateBps <= 0) return "";
        int kbps = Math.round(bitrateBps / 1000f);
        if (kbps <= 0) return "";
        return kbps + " kbps";
    }

    public static String formatPlaybackDeviceName(String friendlySettingName, String manufacturer, String model) {
        String friendly = safeTrim(friendlySettingName);
        String mfg = safeTrim(manufacturer);
        String mod = safeTrim(model);
        if (!friendly.isEmpty() && !friendly.equalsIgnoreCase("android") && !friendly.equalsIgnoreCase("unknown")) {
            return friendly;
        }
        if (mod.isEmpty()) {
            return mfg.isEmpty() ? "Android" : capitalizeWord(mfg);
        }
        if (mfg.isEmpty()) {
            return mod;
        }
        if (mod.toLowerCase(Locale.ROOT).startsWith(mfg.toLowerCase(Locale.ROOT))) {
            return mod;
        }
        return capitalizeWord(mfg) + " " + mod;
    }

    public static boolean isGenericBuiltInProductName(String productName, String deviceModel) {
        String p = safeTrim(productName);
        if (p.isEmpty()) return true;
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.equals("android") || lower.equals("default") || lower.equals("speaker") || lower.equals("phone")) {
            return true;
        }
        String m = safeTrim(deviceModel);
        return !m.isEmpty() && lower.equals(m.toLowerCase(Locale.ROOT));
    }

    private static String capitalizeWord(String s) {
        if (s.isEmpty()) return s;
        boolean hasUpper = false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                hasUpper = true;
                break;
            }
        }
        if (hasUpper) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public static int outputDevicePriority(int deviceType) {
        switch (deviceType) {
            case DEV_USB_HEADSET:
            case DEV_USB_DEVICE:
            case DEV_USB_ACCESSORY:
                return 100;
            case DEV_WIRED_HEADSET:
            case DEV_WIRED_HEADPHONES:
            case DEV_LINE_ANALOG:
            case DEV_AUX_LINE:
                return 90;
            case DEV_BLE_HEADSET:
            case DEV_BLE_SPEAKER:
            case DEV_BLUETOOTH_A2DP:
            case DEV_HEARING_AID:
                return 80;
            case DEV_HDMI_EARC:
            case DEV_HDMI_ARC:
            case DEV_HDMI:
                return 70;
            case DEV_LINE_DIGITAL:
                return 65;
            case DEV_BLUETOOTH_SCO:
                return 50;
            case DEV_BUILTIN_SPEAKER:
            case DEV_BUILTIN_SPEAKER_SAFE:
                return 20;
            case DEV_BUILTIN_EARPIECE:
                return 10;
            default:
                return 5;
        }
    }

    public static int[] sanitizeSampleRates(int[] raw) {
        if (raw == null || raw.length == 0) return new int[0];
        int[] copy = raw.clone();
        Arrays.sort(copy);
        int count = 0;
        for (int r : copy) {
            if (r > 0 && (count == 0 || copy[count - 1] != r)) {
                copy[count++] = r;
            }
        }
        return Arrays.copyOf(copy, count);
    }

    public static int decodeBluetoothSampleRateHz(int bitmask) {
        if ((bitmask & 32) != 0) return 192000;
        if ((bitmask & 16) != 0) return 176400;
        if ((bitmask & 8) != 0) return 96000;
        if ((bitmask & 4) != 0) return 88200;
        if ((bitmask & 2) != 0) return 48000;
        if ((bitmask & 1) != 0) return 44100;
        return 0;
    }

    public static int decodeBluetoothBitsPerSample(int bitmask) {
        if ((bitmask & 4) != 0) return 32;
        if ((bitmask & 2) != 0) return 24;
        if ((bitmask & 1) != 0) return 16;
        return 0;
    }

    public static String resolveBluetoothCodecName(int codecType, String extendedName, String rawConfigString) {
        String ext = normalizeBluetoothCodecName(extendedName);
        if (!ext.isEmpty()) return ext;

        switch (codecType) {
            case 0:
                return "SBC";
            case 1:
                return "AAC";
            case 2:
                return "aptX";
            case 3:
                return "aptX HD";
            case 4:
                return "LDAC";
            case 5:
                return "LC3";
            case 6:
                return "Opus";
            default:
                break;
        }

        if (rawConfigString != null) {
            int idx = rawConfigString.indexOf("codecName:");
            if (idx >= 0) {
                int start = idx + "codecName:".length();
                int end = rawConfigString.indexOf(',', start);
                if (end < 0) end = rawConfigString.indexOf('}', start);
                if (end > start) {
                    return normalizeBluetoothCodecName(rawConfigString.substring(start, end));
                }
            }
        }
        return "";
    }

    private static String normalizeBluetoothCodecName(String raw) {
        String s = safeTrim(raw);
        if (s.isEmpty()) return "";
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.equals("UNKNOWN") || upper.equals("INVALID") || upper.equals("NONE")
                || upper.startsWith("UNKNOWN CODEC")) {
            return "";
        }
        if (upper.equals("APTX")) return "aptX";
        if (upper.equals("APTX HD") || upper.equals("APTX_HD")) return "aptX HD";
        if (upper.equals("APTX ADAPTIVE") || upper.equals("APTX_ADAPTIVE")) return "aptX Adaptive";
        if (upper.equals("APTX LOSSLESS") || upper.equals("APTX_LOSSLESS")) return "aptX Lossless";
        if (upper.equals("OPUS")) return "Opus";
        return s;
    }

    public static String formatBluetoothCodecLabel(String baseCodecName, int sampleRateHz, long codecSpecific1) {
        String base = safeTrim(baseCodecName);
        if (base.isEmpty()) return "";
        if ("LDAC".equalsIgnoreCase(base)) {
            boolean is44Family = (sampleRateHz == 44100 || sampleRateHz == 88200 || sampleRateHz == 176400);
            if (codecSpecific1 == 1000L) {
                return is44Family ? "LDAC · 909 kbps" : "LDAC · 990 kbps";
            }
            if (codecSpecific1 == 1001L) {
                return is44Family ? "LDAC · 606 kbps" : "LDAC · 660 kbps";
            }
            if (codecSpecific1 == 1002L) {
                return is44Family ? "LDAC · 303 kbps" : "LDAC · 330 kbps";
            }
            if (codecSpecific1 == 1003L) {
                return "LDAC · ABR";
            }
            return "LDAC";
        }
        return base;
    }
}
