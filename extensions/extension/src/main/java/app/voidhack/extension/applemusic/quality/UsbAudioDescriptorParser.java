package app.voidhack.extension.applemusic.quality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java USB Audio Class 1.0 and 2.0 raw descriptor parser, alt-setting
 * selector, and Feature Unit hardware volume helpers. Android-independent so it
 * can be unit-tested exhaustively in :patches:test against real UAC1/UAC2 dumps.
 */
public final class UsbAudioDescriptorParser {
    private static final int DESC_CONFIGURATION = 0x02;
    private static final int DESC_INTERFACE = 0x04;
    private static final int DESC_ENDPOINT = 0x05;
    private static final int DESC_CS_INTERFACE = 0x24;
    private static final int DESC_CS_ENDPOINT = 0x25;

    private static final int USB_CLASS_AUDIO = 0x01;
    private static final int SUBCLASS_AUDIOCONTROL = 0x01;
    private static final int SUBCLASS_AUDIOSTREAMING = 0x02;
    private static final int PROTOCOL_UAC2 = 0x20;

    // AudioControl CS_INTERFACE subtypes
    private static final int AC_HEADER = 0x01;
    private static final int AC_INPUT_TERMINAL = 0x02;
    private static final int AC_OUTPUT_TERMINAL = 0x03;
    private static final int AC_MIXER_UNIT = 0x04;
    private static final int AC_SELECTOR_UNIT = 0x05;
    private static final int AC_FEATURE_UNIT = 0x06;
    private static final int AC_CLOCK_SOURCE = 0x0A;
    private static final int AC_CLOCK_SELECTOR = 0x0B;

    // AudioStreaming CS_INTERFACE subtypes
    private static final int AS_GENERAL = 0x01;
    private static final int AS_FORMAT_TYPE = 0x02;

    // UAC Q8.8 silence sentinel (-32768 = 0x8000 = -inf dB)
    public static final int VOLUME_SILENCE_DB256 = -32768;

    // Standard audio rates used to expand continuous UAC2 RANGE subranges
    private static final int[] STANDARD_SAMPLE_RATES = {
            44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000
    };

    private UsbAudioDescriptorParser() {}

    public static final class AltSettingInfo {
        public final int interfaceNumber;
        public final int altSetting;
        public final int uacVersion;          // 1 or 2
        public final int terminalLink;
        public final boolean isPcm;
        public final int channelCount;
        public final int subslotBytes;        // Wire container bytes per sample: 2, 3, or 4
        public final int bitResolution;       // Significant bits: 16, 24, or 32
        public final int endpointOut;         // ISO OUT endpoint address (0x01..0x0F)
        public final int maxPacketSize;       // Effective bytes per microframe/frame
        public final int bInterval;           // Endpoint bInterval
        public final int syncType;            // 1=Async, 2=Adaptive, 3=Sync
        public final boolean epSupportsFreqControl; // UAC1 CS_ENDPOINT bit 0
        public final int endpointFeedback;    // ISO IN feedback endpoint address (0x81..0x8F) or 0
        public final int feedbackMaxPacketSize;
        public final int[] discreteSampleRates;
        public final int continuousMinRateHz;
        public final int continuousMaxRateHz;

        public AltSettingInfo(
                int interfaceNumber,
                int altSetting,
                int uacVersion,
                int terminalLink,
                boolean isPcm,
                int channelCount,
                int subslotBytes,
                int bitResolution,
                int endpointOut,
                int maxPacketSize,
                int bInterval,
                int syncType,
                boolean epSupportsFreqControl,
                int endpointFeedback,
                int feedbackMaxPacketSize,
                int[] discreteSampleRates,
                int continuousMinRateHz,
                int continuousMaxRateHz) {
            this.interfaceNumber = interfaceNumber;
            this.altSetting = altSetting;
            this.uacVersion = uacVersion;
            this.terminalLink = terminalLink;
            this.isPcm = isPcm;
            this.channelCount = channelCount;
            this.subslotBytes = subslotBytes;
            this.bitResolution = bitResolution;
            this.endpointOut = endpointOut;
            this.maxPacketSize = maxPacketSize;
            this.bInterval = bInterval;
            this.syncType = syncType;
            this.epSupportsFreqControl = epSupportsFreqControl;
            this.endpointFeedback = endpointFeedback;
            this.feedbackMaxPacketSize = feedbackMaxPacketSize;
            this.discreteSampleRates = discreteSampleRates == null
                    ? new int[0] : AudioQualityModels.sanitizeSampleRates(discreteSampleRates);
            this.continuousMinRateHz = continuousMinRateHz;
            this.continuousMaxRateHz = continuousMaxRateHz;
        }

        public boolean supportsRateInDescriptor(int sampleRateHz) {
            if (sampleRateHz <= 0) return false;
            if (uacVersion == 2) return true; // UAC2 rates come from CLOCK_SOURCE RANGE control transfer
            if (continuousMinRateHz > 0 && continuousMaxRateHz >= continuousMinRateHz) {
                return sampleRateHz >= continuousMinRateHz && sampleRateHz <= continuousMaxRateHz;
            }
            if (discreteSampleRates.length == 0) return true;
            for (int r : discreteSampleRates) {
                if (r == sampleRateHz) return true;
            }
            return false;
        }

        public boolean isHighSpeedTransport() {
            if (uacVersion != 2) return false;
            // UAC 2.0 runs at High-Speed (8000 uframes/s) unless explicit 3-byte FS feedback says Full-Speed.
            return feedbackMaxPacketSize != 3;
        }

        public int maxDescriptorRateHz() {
            if (continuousMaxRateHz > 0) return continuousMaxRateHz;
            return AudioQualityModels.maxSampleRate(discreteSampleRates);
        }
    }

    public static final class FeatureUnitInfo {
        public final int unitId;
        public final int sourceId;
        public final boolean masterMute;
        public final boolean perChannelMute;
        public final boolean masterVolume;
        public final boolean perChannelVolume;

        public FeatureUnitInfo(
                int unitId,
                int sourceId,
                boolean masterMute,
                boolean perChannelMute,
                boolean masterVolume,
                boolean perChannelVolume) {
            this.unitId = unitId;
            this.sourceId = sourceId;
            this.masterMute = masterMute;
            this.perChannelMute = perChannelMute;
            this.masterVolume = masterVolume;
            this.perChannelVolume = perChannelVolume;
        }

        public boolean supportsMute() {
            return masterMute || perChannelMute;
        }

        public boolean supportsVolume() {
            return masterVolume || perChannelVolume;
        }
    }

    public static final class VolumeRange {
        public final int minDb256;
        public final int maxDb256;
        public final int resDb256;

        public VolumeRange(int minDb256, int maxDb256, int resDb256) {
            this.minDb256 = minDb256;
            this.maxDb256 = maxDb256;
            this.resDb256 = Math.max(1, resDb256);
        }

        public boolean isAdjustable() {
            return maxDb256 > minDb256;
        }
    }

    public static final class ParsedUsbAudioDevice {
        public final int uacVersion;           // 1 = UAC 1.0, 2 = UAC 2.0, 0 = none
        public final int audioControlInterfaceId;
        private final Map<Integer, Uac2Clock> clocksByTerminal;
        public final int featureUnitId;        // Playback FeatureUnit bUnitID or -1
        public final boolean featureUnitSupportsMute;
        public final boolean featureUnitSupportsVolume;
        public final boolean featureUnitMasterMute;
        public final boolean featureUnitPerChannelMute;
        public final boolean featureUnitMasterVolume;
        public final boolean featureUnitPerChannelVolume;
        public final List<AltSettingInfo> playbackAltSettings;

        private ParsedUsbAudioDevice(
                int uacVersion,
                int audioControlInterfaceId,
                Map<Integer, Uac2Clock> clocksByTerminal,
                int featureUnitId,
                boolean featureUnitMasterMute,
                boolean featureUnitPerChannelMute,
                boolean featureUnitMasterVolume,
                boolean featureUnitPerChannelVolume,
                List<AltSettingInfo> playbackAltSettings) {
            this.uacVersion = uacVersion;
            this.audioControlInterfaceId = audioControlInterfaceId;
            this.clocksByTerminal = clocksByTerminal;
            this.featureUnitId = featureUnitId;
            this.featureUnitMasterMute = featureUnitMasterMute;
            this.featureUnitPerChannelMute = featureUnitPerChannelMute;
            this.featureUnitMasterVolume = featureUnitMasterVolume;
            this.featureUnitPerChannelVolume = featureUnitPerChannelVolume;
            this.featureUnitSupportsMute = featureUnitMasterMute || featureUnitPerChannelMute;
            this.featureUnitSupportsVolume = featureUnitMasterVolume || featureUnitPerChannelVolume;
            this.playbackAltSettings = playbackAltSettings == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(playbackAltSettings));
        }

        public boolean isValidPlaybackDevice() {
            return uacVersion > 0 && !playbackAltSettings.isEmpty();
        }

        Uac2Clock clockFor(AltSettingInfo alt) {
            return alt.uacVersion == 2 ? clocksByTerminal.get(alt.terminalLink) : null;
        }

        Uac2Clock commonPlaybackClock() {
            Uac2Clock common = null;
            for (AltSettingInfo alt : playbackAltSettings) {
                Uac2Clock clock = clockFor(alt);
                if (clock == null || (common != null && common.sourceId != clock.sourceId)) return null;
                common = clock;
            }
            return common;
        }

        public int getMaxBitDepth() {
            int max = 0;
            for (AltSettingInfo alt : playbackAltSettings) {
                if (alt.isPcm && alt.bitResolution > max) max = alt.bitResolution;
            }
            return max;
        }

        public int getMaxDescriptorSampleRateHz() {
            int max = 0;
            for (AltSettingInfo alt : playbackAltSettings) {
                if (!alt.isPcm) continue;
                int r = alt.maxDescriptorRateHz();
                if (r > max) max = r;
            }
            return max;
        }
    }

    public static ParsedUsbAudioDevice parse(byte[] raw) {
        if (raw == null || raw.length < 9) {
            return new ParsedUsbAudioDevice(0, -1, Collections.emptyMap(), -1,
                    false, false, false, false, Collections.emptyList());
        }

        int acInterfaceId = -1;
        int acUacVersion = 0;
        int configurationCount = 0;
        boolean multipleAudioControlInterfaces = false;

        // AudioControl topology tables
        Map<Integer, Integer> inputTerminalClockSource = new HashMap<>(); // terminalId -> bCSourceID (UAC2)
        Map<Integer, Integer> clockSourceControls = new HashMap<>();      // clockId -> bmControls
        Map<Integer, int[]> clockSelectorPins = new HashMap<>();          // selectorId -> sourceIds[]
        Map<Integer, FeatureUnitInfo> featureUnitsById = new HashMap<>();
        List<FeatureUnitInfo> featureUnits = new ArrayList<>();
        Map<Integer, int[]> unitSources = new HashMap<>();                // unitId -> upstream source IDs
        Map<Integer, Integer> outputTerminalSource = new HashMap<>();     // outTermId -> sourceId
        Map<Integer, Integer> outputTerminalType = new HashMap<>();       // outTermId -> wTerminalType

        List<AltSettingBuilder> altBuilders = new ArrayList<>();
        AltSettingBuilder currentAs = null;
        boolean inAudioControl = false;
        int currentAcVersion = 1;
        boolean lastEndpointWasIsoOut = false;

        int i = 0;
        while (i + 1 < raw.length) {
            int bLength = raw[i] & 0xFF;
            if (bLength < 2 || i + bLength > raw.length) break;
            int bType = raw[i + 1] & 0xFF;

            if (bType == DESC_CONFIGURATION) {
                configurationCount++;
            } else if (bType == DESC_INTERFACE && bLength >= 9) {
                int ifaceNum = raw[i + 2] & 0xFF;
                int altNum = raw[i + 3] & 0xFF;
                int ifaceClass = raw[i + 5] & 0xFF;
                int ifaceSub = raw[i + 6] & 0xFF;
                int ifaceProto = raw[i + 7] & 0xFF;

                inAudioControl = (ifaceClass == USB_CLASS_AUDIO && ifaceSub == SUBCLASS_AUDIOCONTROL);
                currentAs = null;
                lastEndpointWasIsoOut = false;

                if (inAudioControl) {
                    if (acInterfaceId >= 0 && acInterfaceId != ifaceNum) multipleAudioControlInterfaces = true;
                    int protoVer = (ifaceProto == PROTOCOL_UAC2) ? 2 : 1;
                    if (acInterfaceId < 0 || protoVer > acUacVersion) {
                        acInterfaceId = ifaceNum;
                        acUacVersion = protoVer;
                    }
                    currentAcVersion = protoVer;
                } else if (ifaceClass == USB_CLASS_AUDIO && ifaceSub == SUBCLASS_AUDIOSTREAMING) {
                    int asVer = (ifaceProto == PROTOCOL_UAC2) ? 2 : (acUacVersion > 0 ? acUacVersion : 1);
                    if (altNum > 0) {
                        currentAs = new AltSettingBuilder(ifaceNum, altNum, asVer);
                        altBuilders.add(currentAs);
                    }
                }
            } else if (inAudioControl && bType == DESC_CS_INTERFACE && bLength >= 3) {
                int subtype = raw[i + 2] & 0xFF;
                if (subtype == AC_HEADER && bLength >= 5) {
                    int bcdAdc = le16(raw, i + 3);
                    if ((bcdAdc & 0xFF00) >= 0x0200) {
                        currentAcVersion = 2;
                        acUacVersion = 2;
                    } else if (acUacVersion == 0) {
                        currentAcVersion = 1;
                        acUacVersion = 1;
                    }
                } else if (subtype == AC_INPUT_TERMINAL && bLength >= 6) {
                    int termId = raw[i + 3] & 0xFF;
                    int termType = le16(raw, i + 4);
                    if (currentAcVersion == 2 && bLength >= 17 && termType == 0x0101) {
                        int cSourceId = raw[i + 7] & 0xFF;
                        inputTerminalClockSource.put(termId, cSourceId);
                    }
                } else if (subtype == AC_OUTPUT_TERMINAL && bLength >= 8) {
                    int termId = raw[i + 3] & 0xFF;
                    int termType = le16(raw, i + 4);
                    int sourceId = raw[i + 7] & 0xFF;
                    outputTerminalType.put(termId, termType);
                    outputTerminalSource.put(termId, sourceId);
                    unitSources.put(termId, new int[]{sourceId});
                } else if ((subtype == AC_MIXER_UNIT || subtype == AC_SELECTOR_UNIT) && bLength >= 6) {
                    int unitId = raw[i + 3] & 0xFF;
                    int nrPins = raw[i + 4] & 0xFF;
                    if (nrPins > 0 && bLength >= 5 + nrPins) {
                        int[] pins = new int[nrPins];
                        for (int p = 0; p < nrPins; p++) {
                            pins[p] = raw[i + 5 + p] & 0xFF;
                        }
                        unitSources.put(unitId, pins);
                    }
                } else if (subtype == AC_FEATURE_UNIT && bLength >= 7) {
                    int unitId = raw[i + 3] & 0xFF;
                    int sourceId = raw[i + 4] & 0xFF;
                    unitSources.put(unitId, new int[]{sourceId});
                    boolean masterMute = false;
                    boolean perChMute = false;
                    boolean masterVol = false;
                    boolean perChVol = false;
                    if (currentAcVersion == 2 && bLength >= 10) {
                        int numEntries = (bLength - 6) / 4;
                        for (int ch = 0; ch < numEntries; ch++) {
                            int off = i + 5 + ch * 4;
                            if (off + 4 > i + bLength) break;
                            int ctrl = le32(raw, off);
                            boolean m = (ctrl & 0x03) == 0x03;        // RW mute
                            boolean v = ((ctrl >>> 2) & 0x03) == 0x03; // RW volume
                            if (ch == 0) {
                                masterMute = m;
                                masterVol = v;
                            } else {
                                if (m) perChMute = true;
                                if (v) perChVol = true;
                            }
                        }
                    } else if (currentAcVersion == 1 && bLength >= 7) {
                        int ctrlSize = raw[i + 5] & 0xFF;
                        if (ctrlSize >= 1) {
                            int numEntries = (bLength - 7) / ctrlSize;
                            for (int ch = 0; ch < numEntries; ch++) {
                                int off = i + 6 + ch * ctrlSize;
                                if (off >= i + bLength) break;
                                int ctrl = raw[off] & 0xFF;
                                boolean m = (ctrl & 0x01) != 0;
                                boolean v = (ctrl & 0x02) != 0;
                                if (ch == 0) {
                                    masterMute = m;
                                    masterVol = v;
                                } else {
                                    if (m) perChMute = true;
                                    if (v) perChVol = true;
                                }
                            }
                        }
                    }
                    FeatureUnitInfo fu = new FeatureUnitInfo(
                            unitId, sourceId, masterMute, perChMute, masterVol, perChVol);
                    featureUnits.add(fu);
                    featureUnitsById.put(unitId, fu);
                } else if (subtype == AC_CLOCK_SOURCE && bLength >= 8) {
                    int clockId = raw[i + 3] & 0xFF;
                    int bmControls = raw[i + 5] & 0xFF;
                    clockSourceControls.put(clockId, bmControls);
                } else if (subtype == AC_CLOCK_SELECTOR && bLength >= 6) {
                    int selId = raw[i + 3] & 0xFF;
                    int nrPins = raw[i + 4] & 0xFF;
                    if (bLength >= 5 + nrPins) {
                        int[] pins = new int[nrPins];
                        for (int p = 0; p < nrPins; p++) {
                            pins[p] = raw[i + 5 + p] & 0xFF;
                        }
                        clockSelectorPins.put(selId, pins);
                    }
                }
            } else if (currentAs != null && bType == DESC_CS_INTERFACE && bLength >= 3) {
                int subtype = raw[i + 2] & 0xFF;
                if (subtype == AS_GENERAL) {
                    if (currentAs.uacVersion == 2 && bLength >= 16) {
                        currentAs.terminalLink = raw[i + 3] & 0xFF;
                        int formatType = raw[i + 5] & 0xFF;
                        int bmFormats = le32(raw, i + 6);
                        int nrCh = raw[i + 10] & 0xFF;
                        currentAs.isPcm = (formatType == 1) && ((bmFormats & 0x01) != 0);
                        if (nrCh > 0) currentAs.channelCount = nrCh;
                    } else if (currentAs.uacVersion == 1 && bLength >= 7) {
                        currentAs.terminalLink = raw[i + 3] & 0xFF;
                        int wFormatTag = le16(raw, i + 5);
                        currentAs.isPcm = (wFormatTag == 0x0001);
                    }
                } else if (subtype == AS_FORMAT_TYPE && bLength >= 6) {
                    int formatType = raw[i + 3] & 0xFF;
                    if (formatType == 1) {
                        if (currentAs.uacVersion == 2 && bLength >= 6) {
                            currentAs.subslotBytes = raw[i + 4] & 0xFF;
                            currentAs.bitResolution = raw[i + 5] & 0xFF;
                        } else if (currentAs.uacVersion == 1 && bLength >= 11) {
                            currentAs.channelCount = raw[i + 4] & 0xFF;
                            currentAs.subslotBytes = raw[i + 5] & 0xFF;
                            currentAs.bitResolution = raw[i + 6] & 0xFF;
                            int samFreqType = raw[i + 7] & 0xFF;
                            if (samFreqType == 0 && bLength >= 14) {
                                currentAs.continuousMinRateHz = le24(raw, i + 8);
                                currentAs.continuousMaxRateHz = le24(raw, i + 11);
                            } else if (samFreqType > 0) {
                                int maxEntries = Math.min(samFreqType, (bLength - 8) / 3);
                                int[] rates = new int[maxEntries];
                                for (int r = 0; r < maxEntries; r++) {
                                    rates[r] = le24(raw, i + 8 + r * 3);
                                }
                                currentAs.discreteSampleRates = rates;
                            }
                        }
                    } else {
                        currentAs.isPcm = false;
                    }
                }
            } else if (currentAs != null && bType == DESC_ENDPOINT && bLength >= 7) {
                int epAddr = raw[i + 2] & 0xFF;
                int bmAttr = raw[i + 3] & 0xFF;
                int rawMaxPkt = le16(raw, i + 4);
                int maxPkt = (rawMaxPkt & 0x07FF) * (1 + ((rawMaxPkt >>> 11) & 0x03));
                int bInterval = raw[i + 6] & 0xFF;

                int xferType = bmAttr & 0x03;
                boolean isIn = (epAddr & 0x80) != 0;
                int syncType = (bmAttr >>> 2) & 0x03;
                int usageType = (bmAttr >>> 4) & 0x03;

                if (xferType == 1 && !isIn) {
                    // Isochronous OUT audio data endpoint
                    currentAs.endpointOut = epAddr;
                    currentAs.maxPacketSize = maxPkt;
                    currentAs.bInterval = bInterval > 0 ? bInterval : 1;
                    currentAs.syncType = syncType;
                    lastEndpointWasIsoOut = true;
                } else if (xferType == 1 && isIn && (usageType == 1 || maxPkt <= 8)) {
                    // Isochronous IN explicit feedback endpoint
                    currentAs.endpointFeedback = epAddr;
                    currentAs.feedbackMaxPacketSize = maxPkt;
                    lastEndpointWasIsoOut = false;
                } else {
                    lastEndpointWasIsoOut = false;
                }
            } else if (currentAs != null && lastEndpointWasIsoOut && bType == DESC_CS_ENDPOINT && bLength >= 4) {
                int subtype = raw[i + 2] & 0xFF;
                if (subtype == 0x01) { // EP_GENERAL
                    int epAttr = raw[i + 3] & 0xFF;
                    currentAs.epSupportsFreqControl = (epAttr & 0x01) != 0;
                }
            }

            i += bLength;
        }

        // Collect valid playback (ISO OUT, PCM) alt settings
        List<AltSettingInfo> playbackAlts = new ArrayList<>();
        int playbackTerminalId = -1;
        for (AltSettingBuilder b : altBuilders) {
            if (b.endpointOut <= 0 || !b.isPcm) continue;
            if (b.subslotBytes < 2 || b.subslotBytes > 4) continue;
            if (b.bitResolution <= 0) b.bitResolution = b.subslotBytes * 8;
            if (b.channelCount <= 0) b.channelCount = 2;
            playbackAlts.add(b.build());
            if (playbackTerminalId < 0 && b.terminalLink > 0) {
                playbackTerminalId = b.terminalLink;
            }
        }

        // An entity ID is only unambiguous within its AudioControl function and configuration.
        Map<Integer, Uac2Clock> clocksByTerminal = new HashMap<>();
        if (acUacVersion == 2 && acInterfaceId >= 0
                && configurationCount <= 1 && !multipleAudioControlInterfaces) {
            for (Map.Entry<Integer, Integer> terminal : inputTerminalClockSource.entrySet()) {
                int clockId = resolveClockSource(terminal.getValue(), clockSourceControls, clockSelectorPins);
                if (clockId > 0) {
                    clocksByTerminal.put(terminal.getKey(),
                            new Uac2Clock(acInterfaceId, clockId, clockSourceControls.get(clockId)));
                }
            }
        }

        // Resolve playback FeatureUnit by walking the AudioControl topology graph:
        // 1. Prefer a FeatureUnit along a path between playbackTerminalId (USB Streaming IN)
        //    and a non-USB OutputTerminal (Headphones / Speaker / Line Out, wTerminalType != 0x0101).
        FeatureUnitInfo playbackFu = findPlaybackFeatureUnit(
                playbackTerminalId, outputTerminalType, outputTerminalSource, unitSources, featureUnitsById, featureUnits);

        int finalUacVersion = acUacVersion;
        if (finalUacVersion == 0 && !playbackAlts.isEmpty()) {
            finalUacVersion = playbackAlts.get(0).uacVersion;
        }
        if (acInterfaceId < 0 && !playbackAlts.isEmpty()) {
            acInterfaceId = 0;
        }

        return new ParsedUsbAudioDevice(
                finalUacVersion,
                acInterfaceId,
                clocksByTerminal,
                playbackFu != null ? playbackFu.unitId : -1,
                playbackFu != null && playbackFu.masterMute,
                playbackFu != null && playbackFu.perChannelMute,
                playbackFu != null && playbackFu.masterVolume,
                playbackFu != null && playbackFu.perChannelVolume,
                playbackAlts
        );
    }

    private static FeatureUnitInfo findPlaybackFeatureUnit(
            int playbackTerminalId,
            Map<Integer, Integer> outputTerminalType,
            Map<Integer, Integer> outputTerminalSource,
            Map<Integer, int[]> unitSources,
            Map<Integer, FeatureUnitInfo> featureUnitsById,
            List<FeatureUnitInfo> featureUnits) {
        if (featureUnits.isEmpty()) return null;

        // 1. Walk backward from playback OutputTerminals (wTerminalType != 0x0101 USB Streaming capture)
        FeatureUnitInfo bestOnPath = null;
        for (Map.Entry<Integer, Integer> outEntry : outputTerminalSource.entrySet()) {
            int outId = outEntry.getKey();
            Integer outType = outputTerminalType.get(outId);
            if (outType != null && outType == 0x0101) continue; // Skip USB capture output terminal

            List<FeatureUnitInfo> fusOnPath = new ArrayList<>();
            boolean reachesPlaybackInput = collectFeatureUnitsReachingInput(
                    outEntry.getValue(), playbackTerminalId, unitSources, featureUnitsById,
                    new HashSet<>(), fusOnPath);
            if (reachesPlaybackInput && !fusOnPath.isEmpty()) {
                for (FeatureUnitInfo fu : fusOnPath) {
                    if (fu.supportsVolume()) return fu;
                    if (bestOnPath == null) bestOnPath = fu;
                }
            }
        }
        if (bestOnPath != null) return bestOnPath;

        // 2. Direct child of playbackTerminalId
        for (FeatureUnitInfo fu : featureUnits) {
            if (playbackTerminalId > 0 && fu.sourceId == playbackTerminalId && fu.supportsVolume()) {
                return fu;
            }
        }
        for (FeatureUnitInfo fu : featureUnits) {
            if (playbackTerminalId > 0 && fu.sourceId == playbackTerminalId) {
                return fu;
            }
        }

        // 3. Any FeatureUnit feeding a non-USB OutputTerminal (even if terminalLink was omitted)
        for (Map.Entry<Integer, Integer> outEntry : outputTerminalSource.entrySet()) {
            Integer outType = outputTerminalType.get(outEntry.getKey());
            if (outType != null && outType == 0x0101) continue;
            FeatureUnitInfo fu = featureUnitsById.get(outEntry.getValue());
            if (fu != null && fu.supportsVolume()) return fu;
        }
        return null;
    }

    private static boolean collectFeatureUnitsReachingInput(
            int nodeId,
            int targetInputId,
            Map<Integer, int[]> unitSources,
            Map<Integer, FeatureUnitInfo> featureUnitsById,
            Set<Integer> visited,
            List<FeatureUnitInfo> found) {
        if (nodeId <= 0 || !visited.add(nodeId)) return false;
        if (targetInputId > 0 && nodeId == targetInputId) return true;

        int[] sources = unitSources.get(nodeId);
        if (sources == null) return false;
        boolean reached = false;
        for (int src : sources) {
            if (collectFeatureUnitsReachingInput(src, targetInputId, unitSources, featureUnitsById, visited, found)) {
                reached = true;
            }
        }
        if (reached) {
            FeatureUnitInfo fu = featureUnitsById.get(nodeId);
            if (fu != null && !found.contains(fu)) {
                found.add(fu);
            }
        }
        return reached;
    }

    /**
     * Selects the best bit-perfect AudioStreaming alt setting for the requested
     * source bit depth, sample rate, and channel count. Never selects DSD/raw
     * non-PCM alt settings and strongly avoids truncating bit depth.
     */
    public static AltSettingInfo selectBestAltSetting(
            ParsedUsbAudioDevice device,
            int sourceBitDepth,
            int targetSampleRateHz,
            int targetChannels) {
        if (device == null || !device.isValidPlaybackDevice()) return null;
        int desiredBits = sourceBitDepth > 0 ? sourceBitDepth : 24;
        int desiredCh = targetChannels > 0 ? targetChannels : 2;

        AltSettingInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        for (AltSettingInfo alt : device.playbackAltSettings) {
            if (!alt.isPcm || alt.endpointOut <= 0) continue;
            if (alt.channelCount != desiredCh) continue;
            if (targetSampleRateHz > 0 && !alt.supportsRateInDescriptor(targetSampleRateHz)) continue;

            int score = 0;
            if (alt.bitResolution == desiredBits) {
                score += 1000;
            } else if (alt.bitResolution > desiredBits) {
                score += 850 - (alt.bitResolution - desiredBits);
            } else {
                score += 100 + alt.bitResolution;
            }

            if (alt.subslotBytes * 8 == alt.bitResolution) {
                score += 20;
            }
            if (alt.syncType == 1 && alt.endpointFeedback > 0) {
                score += 30;
            } else if (alt.syncType == 1) {
                score += 10;
            }

            if (score > bestScore) {
                bestScore = score;
                best = alt;
            }
        }
        return best;
    }

    /**
     * Parses a UAC 2.0 CS_SAM_FREQ_CONTROL RANGE response buffer into discrete
     * supported sample rates (Hz), sorted and deduplicated.
     */
    public static int[] parseUac2SampleRateRange(byte[] response, int length) {
        if (response == null || length < 2 || length > response.length) {
            return new int[0];
        }
        int numSubRanges = le16(response, 0);
        if (numSubRanges <= 0) return new int[0];

        List<Integer> rates = new ArrayList<>();
        int off = 2;
        for (int s = 0; s < numSubRanges && off + 12 <= length; s++, off += 12) {
            int minHz = le32(response, off);
            int maxHz = le32(response, off + 4);
            int resHz = le32(response, off + 8);
            if (minHz <= 0 || maxHz < minHz) continue;
            if (minHz == maxHz || resHz <= 0) {
                rates.add(minHz);
                if (maxHz != minHz) rates.add(maxHz);
            } else {
                for (int std : STANDARD_SAMPLE_RATES) {
                    if (std >= minHz && std <= maxHz && ((std - minHz) % resHz == 0)) {
                        rates.add(std);
                    }
                }
                if (rates.isEmpty()) {
                    rates.add(minHz);
                    rates.add(maxHz);
                }
            }
        }
        int[] out = new int[rates.size()];
        for (int i = 0; i < rates.size(); i++) out[i] = rates.get(i);
        return AudioQualityModels.sanitizeSampleRates(out);
    }

    /**
     * Parses a UAC 2.0 FU_VOLUME_CONTROL RANGE response (wNumSubRanges + 6-byte
     * signed Q8.8 min/max/res tuples in 1/256 dB units).
     */
    public static VolumeRange parseUac2VolumeRange(byte[] response, int length) {
        if (response == null || length < 8 || length > response.length) return null;
        int numSubRanges = le16(response, 0);
        if (numSubRanges <= 0) return null;

        int minFinite = Integer.MAX_VALUE;
        int minAny = Integer.MAX_VALUE;
        int maxAny = Integer.MIN_VALUE;
        int minRes = Integer.MAX_VALUE;

        int off = 2;
        for (int s = 0; s < numSubRanges && off + 6 <= length; s++, off += 6) {
            int minVal = le16Signed(response, off);
            int maxVal = le16Signed(response, off + 2);
            int resVal = le16(response, off + 4);
            if (maxVal < minVal) continue;
            if (minVal < minAny) minAny = minVal;
            if (minVal != VOLUME_SILENCE_DB256 && minVal < minFinite) minFinite = minVal;
            if (maxVal > maxAny) maxAny = maxVal;
            if (resVal > 0 && resVal < minRes) minRes = resVal;
        }
        if (maxAny == Integer.MIN_VALUE || minAny == Integer.MAX_VALUE) return null;
        int chosenMin = (minFinite != Integer.MAX_VALUE && minFinite < maxAny) ? minFinite : minAny;
        int chosenRes = (minRes != Integer.MAX_VALUE) ? minRes : 256;
        if (maxAny <= chosenMin) return null;
        return new VolumeRange(chosenMin, maxAny, chosenRes);
    }

    /**
     * Quantizes a target Q8.8 volume value (1/256 dB) to a valid hardware step
     * supported by the DAC within [minDb256, maxDb256].
     */
    public static int quantizeVolumeDb256(int targetDb256, int minDb256, int maxDb256, int resDb256) {
        if (maxDb256 <= minDb256) return maxDb256;
        if (targetDb256 <= minDb256) return minDb256;
        if (targetDb256 >= maxDb256) return maxDb256;
        int step = Math.max(1, resDb256);
        int steps = Math.round((targetDb256 - minDb256) / (float) step);
        int q = minDb256 + steps * step;
        if (q < minDb256) return minDb256;
        if (q > maxDb256) return maxDb256;
        return q;
    }

    /**
     * Maps a 0..100 user volume percentage onto a perceptual audiophile dB taper
     * (covering up to 72 dB dynamic window below maxDb) quantized to DAC hardware steps.
     */
    public static int percentToVolumeDb256(float percent, int minDb256, int maxDb256, int resDb256) {
        if (maxDb256 <= minDb256) return maxDb256;
        if (percent <= 0f) return minDb256;
        if (percent >= 100f) return maxDb256;

        float frac = Math.max(0f, Math.min(1f, percent / 100f));
        int effectiveMin = Math.max(minDb256, maxDb256 - (72 * 256));
        double span = maxDb256 - effectiveMin;
        double target = maxDb256 - span * Math.pow(1.0 - frac, 1.35);
        return quantizeVolumeDb256((int) Math.round(target), minDb256, maxDb256, resDb256);
    }

    /**
     * Inverse of percentToVolumeDb256() for syncing slider UI with DAC hardware dB.
     */
    public static float volumeDb256ToPercent(int db256, int minDb256, int maxDb256, boolean muted) {
        if (muted || maxDb256 <= minDb256 || db256 <= minDb256 || db256 == VOLUME_SILENCE_DB256) {
            return 0f;
        }
        if (db256 >= maxDb256) return 100f;
        int effectiveMin = Math.max(minDb256, maxDb256 - (72 * 256));
        if (db256 <= effectiveMin) return 1f;
        double ratio = (double) (maxDb256 - db256) / (double) (maxDb256 - effectiveMin);
        double frac = 1.0 - Math.pow(Math.max(0.0, Math.min(1.0, ratio)), 1.0 / 1.35);
        return (float) Math.max(1.0, Math.min(100.0, frac * 100.0));
    }

    public static String formatVolumeDb(int db256) {
        if (db256 == VOLUME_SILENCE_DB256) return "-∞ dB";
        double db = db256 / 256.0;
        if (Math.abs(db) < 0.05) return "0.0 dB";
        if (db > 0.0) return String.format(Locale.US, "+%.1f dB", db);
        return String.format(Locale.US, "%.1f dB", db);
    }

    private static int resolveClockSource(
            int entityId,
            Map<Integer, Integer> clockSources,
            Map<Integer, int[]> selectors) {
        for (int depth = 0; depth < 8 && entityId > 0; depth++) {
            if (clockSources.containsKey(entityId)) return entityId;
            int[] pins = selectors.get(entityId);
            // A multi-input selector or multiplier requires live topology controls to resolve.
            if (pins == null || pins.length != 1) return -1;
            entityId = pins[0];
        }
        return -1;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le16Signed(byte[] b, int off) {
        return (short) le16(b, off);
    }

    private static int le24(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static final class AltSettingBuilder {
        final int interfaceNumber;
        final int altSetting;
        final int uacVersion;
        int terminalLink = -1;
        boolean isPcm = true;
        int channelCount = 2;
        int subslotBytes = 0;
        int bitResolution = 0;
        int endpointOut = -1;
        int maxPacketSize = 0;
        int bInterval = 1;
        int syncType = 0;
        boolean epSupportsFreqControl = false;
        int endpointFeedback = 0;
        int feedbackMaxPacketSize = 0;
        int[] discreteSampleRates = new int[0];
        int continuousMinRateHz = 0;
        int continuousMaxRateHz = 0;

        AltSettingBuilder(int interfaceNumber, int altSetting, int uacVersion) {
            this.interfaceNumber = interfaceNumber;
            this.altSetting = altSetting;
            this.uacVersion = uacVersion;
        }

        AltSettingInfo build() {
            return new AltSettingInfo(
                    interfaceNumber,
                    altSetting,
                    uacVersion,
                    terminalLink,
                    isPcm,
                    channelCount,
                    subslotBytes,
                    bitResolution,
                    endpointOut,
                    maxPacketSize,
                    bInterval,
                    syncType,
                    epSupportsFreqControl,
                    endpointFeedback,
                    feedbackMaxPacketSize,
                    discreteSampleRates,
                    continuousMinRateHz,
                    continuousMaxRateHz
            );
        }
    }
}
