package app.voidhack.extension.applemusic.quality;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AudioQualityModelsTest {

    @Test
    public void hiResLosslessResampledTo48kOnBluetooth() {
        AudioQualityModels.SourceInput source = new AudioQualityModels.SourceInput(
                AudioQualityModels.VARIANT_HI_RES_LOSSLESS,
                24,
                96000,
                2,
                -1,
                "alac",
                AudioQualityModels.CAP_LOSSLESS | AudioQualityModels.CAP_HI_RES_LOSSLESS,
                "Idol",
                "YOASOBI"
        );
        AudioQualityModels.PipelineInput pipeline = new AudioQualityModels.PipelineInput(
                "Xiaomi 2512BPNDAG",
                true,
                true,
                96000,
                AudioQualityModels.EXO_ENCODING_PCM_24BIT,
                96000,
                AudioQualityModels.ENCODING_PCM_FLOAT,
                2,
                48000,
                48000,
                false,
                0,
                0,
                false,
                false,
                false,
                true
        );
        AudioQualityModels.HardwareOutputInput hw = new AudioQualityModels.HardwareOutputInput(
                AudioQualityModels.DEV_BLUETOOTH_A2DP,
                "WH-1000XM5",
                new int[]{44100, 48000},
                new int[]{AudioQualityModels.ENCODING_PCM_16BIT, AudioQualityModels.ENCODING_PCM_24BIT_PACKED},
                "",
                0,
                0,
                false,
                ""
        );

        AudioQualityModels.ResolvedSignalPath resolved = AudioQualityModels.resolve(source, pipeline, hw);
        assertEquals(AudioQualityModels.AudioTier.HI_RES_LOSSLESS, resolved.tier);
        assertEquals(AudioQualityModels.SignalIntegrity.BLUETOOTH_COMPRESSED, resolved.integrity);
        assertEquals(24, resolved.sourceBitDepth);
        assertEquals(96000, resolved.sourceSampleRateHz);
        assertEquals("ALAC", resolved.sourceCodecLabel);
        assertEquals(24, resolved.deviceBitDepth);
        assertEquals(48000, resolved.deviceSampleRateHz);
        assertTrue(resolved.resampledInDevice);
        assertTrue(resolved.deviceUsesFloatPcm);
        assertEquals(AudioQualityModels.OutputKind.BLUETOOTH, resolved.outputKind);
        assertEquals("WH-1000XM5", resolved.outputProductName);
        assertEquals(24, resolved.outputBitDepth);
        assertEquals(48000, resolved.outputSampleRateHz);
    }

    @Test
    public void hiResLosslessBitPerfectOverUsbDac() {
        AudioQualityModels.SourceInput source = new AudioQualityModels.SourceInput(
                AudioQualityModels.VARIANT_HI_RES_LOSSLESS,
                24,
                192000,
                2,
                -1,
                "audio/alac",
                AudioQualityModels.CAP_HI_RES_LOSSLESS,
                "Track",
                "Artist"
        );
        AudioQualityModels.PipelineInput pipeline = new AudioQualityModels.PipelineInput(
                "Pixel 9 Pro",
                true,
                true,
                192000,
                AudioQualityModels.EXO_ENCODING_PCM_24BIT,
                192000,
                AudioQualityModels.ENCODING_PCM_24BIT_PACKED,
                2,
                192000,
                48000,
                true,
                192000,
                AudioQualityModels.ENCODING_PCM_24BIT_PACKED,
                true,
                false,
                false,
                true
        );
        AudioQualityModels.HardwareOutputInput hw = new AudioQualityModels.HardwareOutputInput(
                AudioQualityModels.DEV_USB_DEVICE,
                "FiiO KA17",
                new int[]{44100, 48000, 96000, 192000, 384000},
                new int[]{AudioQualityModels.ENCODING_PCM_16BIT, AudioQualityModels.ENCODING_PCM_24BIT_PACKED, AudioQualityModels.ENCODING_PCM_32BIT},
                "",
                0,
                0,
                false,
                ""
        );

        AudioQualityModels.ResolvedSignalPath resolved = AudioQualityModels.resolve(source, pipeline, hw);
        assertEquals(AudioQualityModels.AudioTier.HI_RES_LOSSLESS, resolved.tier);
        assertEquals(AudioQualityModels.SignalIntegrity.BIT_PERFECT, resolved.integrity);
        assertEquals(AudioQualityModels.PipelineMode.BIT_PERFECT, resolved.pipelineMode);
        assertEquals(AudioQualityModels.OutputKind.USB, resolved.outputKind);
        assertFalse(resolved.resampledInDevice);
        assertEquals(24, resolved.deviceBitDepth);
        assertEquals(192000, resolved.deviceSampleRateHz);
        assertEquals(24, resolved.outputBitDepth);
        assertEquals(192000, resolved.outputSampleRateHz);
        assertEquals(384000, resolved.outputMaxSampleRateHz);
        assertEquals(32, resolved.outputMaxBitDepth);
    }

    @Test
    public void losslessOn35mmPreserves24Bit48kAndFlagsHiResAvailable() {
        AudioQualityModels.SourceInput source = new AudioQualityModels.SourceInput(
                AudioQualityModels.VARIANT_LOSSLESS,
                24,
                48000,
                2,
                -1,
                "alac",
                AudioQualityModels.CAP_LOSSLESS | AudioQualityModels.CAP_HI_RES_LOSSLESS,
                "",
                ""
        );
        AudioQualityModels.PipelineInput pipeline = new AudioQualityModels.PipelineInput(
                "Xperia 1 VI",
                true,
                true,
                48000,
                AudioQualityModels.EXO_ENCODING_PCM_24BIT,
                48000,
                AudioQualityModels.ENCODING_PCM_FLOAT,
                2,
                48000,
                48000,
                false,
                0,
                0,
                false,
                true,
                false,
                true
        );
        AudioQualityModels.HardwareOutputInput hw = new AudioQualityModels.HardwareOutputInput(
                AudioQualityModels.DEV_WIRED_HEADPHONES,
                "XQ-EC44",
                new int[]{44100, 48000, 96000, 192000},
                new int[]{AudioQualityModels.ENCODING_PCM_16BIT, AudioQualityModels.ENCODING_PCM_24BIT_PACKED},
                "",
                0,
                0,
                false,
                ""
        );

        AudioQualityModels.ResolvedSignalPath resolved = AudioQualityModels.resolve(source, pipeline, hw);
        assertEquals(AudioQualityModels.AudioTier.LOSSLESS, resolved.tier);
        assertEquals(AudioQualityModels.SignalIntegrity.LOSSLESS, resolved.integrity);
        assertTrue(resolved.hiResAvailableInCatalog);
        assertFalse(resolved.resampledInDevice);
        assertEquals(AudioQualityModels.OutputKind.WIRED_35MM, resolved.outputKind);
        assertEquals(24, resolved.outputBitDepth);
        assertEquals(48000, resolved.outputSampleRateHz);
        assertTrue(AudioQualityModels.isGenericBuiltInProductName("XQ-EC44", "XQ-EC44"));
    }

    @Test
    public void dolbyAtmosBitstreamPassthroughOverHdmiEarc() {
        AudioQualityModels.SourceInput source = new AudioQualityModels.SourceInput(
                AudioQualityModels.VARIANT_DOLBY_ATMOS,
                -1,
                48000,
                16,
                768000,
                "audio/eac3-joc",
                AudioQualityModels.CAP_DOLBY_ATMOS | AudioQualityModels.CAP_SPATIAL,
                "Atmos Song",
                "Artist"
        );
        AudioQualityModels.PipelineInput pipeline = new AudioQualityModels.PipelineInput(
                "Android TV",
                true,
                false,
                48000,
                AudioQualityModels.ENCODING_E_AC3_JOC,
                48000,
                AudioQualityModels.ENCODING_E_AC3_JOC,
                6,
                48000,
                48000,
                false,
                0,
                0,
                true,
                false,
                false,
                true
        );
        AudioQualityModels.HardwareOutputInput hw = new AudioQualityModels.HardwareOutputInput(
                AudioQualityModels.DEV_HDMI_EARC,
                "Denon AVR-X3800H",
                new int[]{48000, 96000, 192000},
                new int[]{AudioQualityModels.ENCODING_PCM_16BIT, AudioQualityModels.ENCODING_E_AC3_JOC},
                "",
                0,
                0,
                false,
                ""
        );

        AudioQualityModels.ResolvedSignalPath resolved = AudioQualityModels.resolve(source, pipeline, hw);
        assertEquals(AudioQualityModels.AudioTier.DOLBY_ATMOS, resolved.tier);
        assertEquals(AudioQualityModels.SignalIntegrity.SPATIAL, resolved.integrity);
        assertEquals(AudioQualityModels.PipelineMode.PASSTHROUGH, resolved.pipelineMode);
        assertTrue(resolved.deviceBitstreamPassthrough);
        assertEquals(AudioQualityModels.OutputKind.HDMI_EARC, resolved.outputKind);
        assertTrue(resolved.outputSupportsAtmos);
        assertEquals("E-AC-3 JOC", resolved.sourceCodecLabel);
        assertEquals("768 kbps", AudioQualityModels.formatBitrateKbps(resolved.sourceBitrateBps));
    }

    @Test
    public void hiResLosslessResampledOnSpeakerMarksResampled() {
        AudioQualityModels.SourceInput source = new AudioQualityModels.SourceInput(
                AudioQualityModels.VARIANT_HI_RES_LOSSLESS,
                24,
                96000,
                2,
                -1,
                "alac",
                AudioQualityModels.CAP_HI_RES_LOSSLESS,
                "",
                ""
        );
        AudioQualityModels.PipelineInput pipeline = new AudioQualityModels.PipelineInput(
                "Pixel 9",
                true,
                true,
                96000,
                AudioQualityModels.EXO_ENCODING_PCM_24BIT,
                96000,
                AudioQualityModels.ENCODING_PCM_FLOAT,
                2,
                48000,
                48000,
                false,
                0,
                0,
                false,
                false,
                false,
                true
        );
        AudioQualityModels.HardwareOutputInput hw = new AudioQualityModels.HardwareOutputInput(
                AudioQualityModels.DEV_BUILTIN_SPEAKER,
                "Pixel 9",
                new int[]{48000},
                new int[]{AudioQualityModels.ENCODING_PCM_16BIT, AudioQualityModels.ENCODING_PCM_24BIT_PACKED},
                "",
                0,
                0,
                false,
                ""
        );

        AudioQualityModels.ResolvedSignalPath resolved = AudioQualityModels.resolve(source, pipeline, hw);
        assertEquals(AudioQualityModels.SignalIntegrity.RESAMPLED, resolved.integrity);
        assertTrue(resolved.resampledInDevice);
        assertEquals(48000, resolved.deviceSampleRateHz);
        assertEquals(48000, resolved.outputSampleRateHz);
        assertEquals(AudioQualityModels.OutputKind.SPEAKER, resolved.outputKind);
    }

    @Test
    public void formattingHelpersAndDeviceNameNormalization() {
        assertEquals("44.1 kHz", AudioQualityModels.formatSampleRateKHz(44100));
        assertEquals("48 kHz", AudioQualityModels.formatSampleRateKHz(48000));
        assertEquals("88.2 kHz", AudioQualityModels.formatSampleRateKHz(88200));
        assertEquals("96 kHz", AudioQualityModels.formatSampleRateKHz(96000));
        assertEquals("192 kHz", AudioQualityModels.formatSampleRateKHz(192000));
        assertEquals("256 kbps", AudioQualityModels.formatBitrateKbps(256000));

        assertEquals("My Pixel 9", AudioQualityModels.formatPlaybackDeviceName("My Pixel 9", "Google", "Pixel 9"));
        assertEquals("Xiaomi 2512BPNDAG", AudioQualityModels.formatPlaybackDeviceName("", "xiaomi", "2512BPNDAG"));
        assertEquals("Samsung SM-S928B", AudioQualityModels.formatPlaybackDeviceName(null, "samsung", "Samsung SM-S928B"));
        assertEquals("OnePlus CPH2581", AudioQualityModels.formatPlaybackDeviceName("android", "OnePlus", "CPH2581"));
    }

    @Test
    public void bluetoothCodecDecodingAndLdacFormatting() {
        assertEquals(44100, AudioQualityModels.decodeBluetoothSampleRateHz(1));
        assertEquals(48000, AudioQualityModels.decodeBluetoothSampleRateHz(2));
        assertEquals(88200, AudioQualityModels.decodeBluetoothSampleRateHz(4));
        assertEquals(96000, AudioQualityModels.decodeBluetoothSampleRateHz(8));
        assertEquals(176400, AudioQualityModels.decodeBluetoothSampleRateHz(16));
        assertEquals(192000, AudioQualityModels.decodeBluetoothSampleRateHz(32));
        assertEquals(0, AudioQualityModels.decodeBluetoothSampleRateHz(0));

        assertEquals(16, AudioQualityModels.decodeBluetoothBitsPerSample(1));
        assertEquals(24, AudioQualityModels.decodeBluetoothBitsPerSample(2));
        assertEquals(32, AudioQualityModels.decodeBluetoothBitsPerSample(4));
        assertEquals(0, AudioQualityModels.decodeBluetoothBitsPerSample(0));

        assertEquals("LDAC", AudioQualityModels.resolveBluetoothCodecName(4, "", ""));
        assertEquals("AAC", AudioQualityModels.resolveBluetoothCodecName(1, "", ""));
        assertEquals("aptX HD", AudioQualityModels.resolveBluetoothCodecName(3, "APTX_HD", ""));
        assertEquals("LHDC", AudioQualityModels.resolveBluetoothCodecName(99, "", "{codecName:LHDC,mCodecType:99}"));
        assertEquals("", AudioQualityModels.resolveBluetoothCodecName(1000000, "UNKNOWN", "{codecName:UNKNOWN}"));

        assertEquals("LDAC · 990 kbps", AudioQualityModels.formatBluetoothCodecLabel("LDAC", 96000, 1000L));
        assertEquals("LDAC · 909 kbps", AudioQualityModels.formatBluetoothCodecLabel("LDAC", 44100, 1000L));
        assertEquals("LDAC · 660 kbps", AudioQualityModels.formatBluetoothCodecLabel("LDAC", 48000, 1001L));
        assertEquals("LDAC · 330 kbps", AudioQualityModels.formatBluetoothCodecLabel("LDAC", 96000, 1002L));
        assertEquals("LDAC · ABR", AudioQualityModels.formatBluetoothCodecLabel("LDAC", 96000, 1003L));
        assertEquals("LDAC", AudioQualityModels.formatBluetoothCodecLabel("LDAC", 96000, 0L));
        assertEquals("AAC", AudioQualityModels.formatBluetoothCodecLabel("AAC", 44100, 0L));

        // End-to-end resolution with active LDAC 24-bit / 96 kHz probe
        AudioQualityModels.SourceInput src = new AudioQualityModels.SourceInput(
                AudioQualityModels.VARIANT_HI_RES_LOSSLESS, 24, 96000, 2, 3443000, "alac",
                AudioQualityModels.CAP_LOSSLESS | AudioQualityModels.CAP_HI_RES_LOSSLESS, "Song", "Artist");
        AudioQualityModels.PipelineInput pipe = new AudioQualityModels.PipelineInput(
                "Xiaomi 17 Ultra", true, true, 96000, AudioQualityModels.EXO_ENCODING_PCM_24BIT,
                96000, AudioQualityModels.ENCODING_PCM_FLOAT, 2, 96000, 48000,
                false, 0, 0, true, true, false, true);
        AudioQualityModels.HardwareOutputInput hw = new AudioQualityModels.HardwareOutputInput(
                AudioQualityModels.DEV_BLUETOOTH_A2DP, "WH-1000XM4",
                new int[]{44100, 48000, 88200, 96000},
                new int[]{AudioQualityModels.ENCODING_PCM_16BIT, AudioQualityModels.ENCODING_PCM_24BIT_PACKED, AudioQualityModels.ENCODING_PCM_32BIT},
                "LDAC · 990 kbps", 24, 96000, false, "");

        AudioQualityModels.ResolvedSignalPath res = AudioQualityModels.resolve(src, pipe, hw);
        assertEquals(AudioQualityModels.PipelineMode.DIRECT, res.pipelineMode);
        assertEquals("LDAC · 990 kbps", res.bluetoothCodecName);
        assertEquals(24, res.outputBitDepth);
        assertEquals(96000, res.outputSampleRateHz);
        assertEquals(32, res.outputMaxBitDepth);
        assertEquals(96000, res.outputMaxSampleRateHz);
    }

    @Test
    public void usbBitPerfectSelectionAndOutputPathInventory() {
        List<AudioQualityModels.UsbMixerOption> supported = Arrays.asList(
                new AudioQualityModels.UsbMixerOption(false, AudioQualityModels.ENCODING_PCM_16BIT, 48000, 12),
                new AudioQualityModels.UsbMixerOption(false, AudioQualityModels.ENCODING_PCM_24BIT_PACKED, 96000, 12),
                new AudioQualityModels.UsbMixerOption(true, AudioQualityModels.ENCODING_PCM_16BIT, 44100, 12),
                new AudioQualityModels.UsbMixerOption(true, AudioQualityModels.ENCODING_PCM_16BIT, 96000, 12),
                new AudioQualityModels.UsbMixerOption(true, AudioQualityModels.ENCODING_PCM_24BIT_PACKED, 96000, 12),
                new AudioQualityModels.UsbMixerOption(true, AudioQualityModels.ENCODING_PCM_32BIT, 192000, 12)
        );

        // 24-bit / 96 kHz picks exact 24-bit packed Bit-Perfect (not 16-bit, not preferred mixer)
        AudioQualityModels.UsbMixerOption opt96 = AudioQualityModels.selectBestUsbMixerOption(
                supported, 96000, 12, 24, true);
        assertNotNull(opt96);
        assertTrue(opt96.bitPerfect);
        assertEquals(AudioQualityModels.ENCODING_PCM_24BIT_PACKED, opt96.encoding);

        // 24-bit / 192 kHz picks lossless 32-bit integer container Bit-Perfect
        AudioQualityModels.UsbMixerOption opt192 = AudioQualityModels.selectBestUsbMixerOption(
                supported, 192000, 12, 24, true);
        assertNotNull(opt192);
        assertTrue(opt192.bitPerfect);
        assertEquals(AudioQualityModels.ENCODING_PCM_32BIT, opt192.encoding);

        // 16-bit / 44.1 kHz picks exact 16-bit Bit-Perfect
        AudioQualityModels.UsbMixerOption opt44 = AudioQualityModels.selectBestUsbMixerOption(
                supported, 44100, 12, 16, true);
        assertNotNull(opt44);
        assertTrue(opt44.bitPerfect);
        assertEquals(AudioQualityModels.ENCODING_PCM_16BIT, opt44.encoding);

        // Never truncates 24-bit source down to 16-bit bit-perfect
        List<AudioQualityModels.UsbMixerOption> only16 = Collections.singletonList(
                new AudioQualityModels.UsbMixerOption(true, AudioQualityModels.ENCODING_PCM_16BIT, 96000, 12));
        assertNull(AudioQualityModels.selectBestUsbMixerOption(only16, 96000, 12, 24, false));

        // Verify output path inventory marks USB_BIT_PERFECT as ACTIVE when engaged
        AudioQualityModels.SourceInput src = new AudioQualityModels.SourceInput(
                AudioQualityModels.VARIANT_HI_RES_LOSSLESS, 24, 96000, 2, 3326000, "alac",
                AudioQualityModels.CAP_HI_RES_LOSSLESS, "Song", "Artist");
        AudioQualityModels.PipelineInput pipe = new AudioQualityModels.PipelineInput(
                "Xiaomi 17 Ultra", true, true, 96000, AudioQualityModels.EXO_ENCODING_PCM_24BIT,
                96000, AudioQualityModels.ENCODING_PCM_24BIT_PACKED, 2, 96000, 48000,
                true, 96000, AudioQualityModels.ENCODING_PCM_24BIT_PACKED,
                true, false, false, true, true, true, false, supported);
        AudioQualityModels.HardwareOutputInput hw = new AudioQualityModels.HardwareOutputInput(
                AudioQualityModels.DEV_USB_DEVICE, "USB-Audio - Hi-Res Audio",
                new int[]{44100, 48000, 96000},
                new int[]{AudioQualityModels.ENCODING_PCM_16BIT, AudioQualityModels.ENCODING_PCM_24BIT_PACKED},
                "", 0, 0, false, "");

        AudioQualityModels.ResolvedSignalPath res = AudioQualityModels.resolve(src, pipe, hw);
        assertEquals(AudioQualityModels.PipelineMode.BIT_PERFECT, res.pipelineMode);
        assertTrue(res.usbBitPerfectSupportedByHal);
        assertTrue(res.usbPreferredMixerSupportedByHal);
        assertEquals(AudioQualityModels.OutputPathId.USB_DIRECT_DRIVER, res.outputPaths.get(0).id);
        assertEquals(AudioQualityModels.OutputPathId.USB_BIT_PERFECT, res.outputPaths.get(1).id);
        assertEquals(AudioQualityModels.PathAvailability.ACTIVE, res.outputPaths.get(1).availability);
        assertEquals(AudioQualityModels.OutputPathId.USB_PREFERRED_MIXER, res.outputPaths.get(2).id);
        assertEquals(AudioQualityModels.PathAvailability.AVAILABLE, res.outputPaths.get(2).availability);
    }

    @Test
    public void usbDirectDriverOverridesUnexposedHalAndReportsBitPerfect() {
        AudioQualityModels.SourceInput src = new AudioQualityModels.SourceInput(
                AudioQualityModels.VARIANT_HI_RES_LOSSLESS, 24, 96000, 2, 3326000, "alac",
                AudioQualityModels.CAP_HI_RES_LOSSLESS, "Song", "Artist");
        AudioQualityModels.UsbDirectState directActive = new AudioQualityModels.UsbDirectState(
                true, true, true, true, true,
                "USB-Audio - Hi-Res Audio", 2, 96000, 24, 3, 96001, 24, 96000, "", 0);
        // Simulate AudioManager reporting speaker after kernel snd-usb-audio was detached
        AudioQualityModels.PipelineInput pipe = new AudioQualityModels.PipelineInput(
                "Xiaomi 17 Ultra", false, true, 96000, AudioQualityModels.EXO_ENCODING_PCM_24BIT,
                0, 0, 2, 48000, 48000,
                false, 0, 0, false, true, false, false,
                false, false, false, Collections.emptyList(), directActive);
        AudioQualityModels.HardwareOutputInput hwSpeaker = new AudioQualityModels.HardwareOutputInput(
                AudioQualityModels.DEV_BUILTIN_SPEAKER, "Xiaomi 17 Ultra",
                new int[]{48000}, new int[]{AudioQualityModels.ENCODING_PCM_16BIT},
                "", 0, 0, false, "");

        AudioQualityModels.ResolvedSignalPath res = AudioQualityModels.resolve(src, pipe, hwSpeaker);
        assertEquals(AudioQualityModels.PipelineMode.USB_DIRECT_DRIVER, res.pipelineMode);
        assertEquals(AudioQualityModels.SignalIntegrity.BIT_PERFECT, res.integrity);
        assertEquals(AudioQualityModels.OutputKind.USB, res.outputKind);
        assertEquals("USB-Audio - Hi-Res Audio", res.outputProductName);
        assertEquals(24, res.deviceBitDepth);
        assertEquals(96000, res.deviceSampleRateHz);
        assertEquals(24, res.outputBitDepth);
        assertEquals(96000, res.outputSampleRateHz);
        assertEquals(96000, res.outputMaxSampleRateHz);
        assertFalse(res.resampledInDevice);
        assertEquals(AudioQualityModels.OutputPathId.USB_DIRECT_DRIVER, res.outputPaths.get(0).id);
        assertEquals(AudioQualityModels.PathAvailability.ACTIVE, res.outputPaths.get(0).availability);
        assertEquals(AudioQualityModels.OutputPathId.USB_BIT_PERFECT, res.outputPaths.get(1).id);
        assertEquals(AudioQualityModels.PathAvailability.UNAVAILABLE, res.outputPaths.get(1).availability);
    }

    @Test
    public void signalPathOnlyHidesUnpatchedUsbDacOutputPaths() {
        AudioQualityModels.SourceInput src = new AudioQualityModels.SourceInput(
                AudioQualityModels.VARIANT_HI_RES_LOSSLESS, 24, 96000, 2, 3326000, "alac",
                AudioQualityModels.CAP_HI_RES_LOSSLESS, "Song", "Artist");
        List<AudioQualityModels.UsbMixerOption> supported = Collections.singletonList(
                new AudioQualityModels.UsbMixerOption(true, AudioQualityModels.ENCODING_PCM_24BIT_PACKED, 96000, 12));
        AudioQualityModels.PipelineInput pipe = new AudioQualityModels.PipelineInput(
                "Pixel 9 Pro", true, true, 96000, AudioQualityModels.EXO_ENCODING_PCM_24BIT,
                96000, AudioQualityModels.ENCODING_PCM_FLOAT, 2, 48000, 48000,
                false, 0, 0, false, false, false, true,
                false, false, false, supported, AudioQualityModels.UsbDirectState.NONE,
                false);
        AudioQualityModels.HardwareOutputInput hwUsb = new AudioQualityModels.HardwareOutputInput(
                AudioQualityModels.DEV_USB_DEVICE, "FiiO KA17",
                new int[]{44100, 48000, 96000},
                new int[]{AudioQualityModels.ENCODING_PCM_16BIT, AudioQualityModels.ENCODING_PCM_24BIT_PACKED},
                "", 0, 0, false, "");

        AudioQualityModels.ResolvedSignalPath res = AudioQualityModels.resolve(src, pipe, hwUsb);
        for (AudioQualityModels.OutputPathEntry entry : res.outputPaths) {
            assertFalse("Unexpected USB_DIRECT_DRIVER when USB DAC patch is disabled",
                    entry.id == AudioQualityModels.OutputPathId.USB_DIRECT_DRIVER);
            assertFalse("Unexpected inactive USB_BIT_PERFECT when USB DAC patch is disabled",
                    entry.id == AudioQualityModels.OutputPathId.USB_BIT_PERFECT);
            assertFalse("Unexpected inactive USB_PREFERRED_MIXER when USB DAC patch is disabled",
                    entry.id == AudioQualityModels.OutputPathId.USB_PREFERRED_MIXER);
        }

        // If the platform itself already has OS USB Bit-Perfect active, signal-path-only still reports it truthfully.
        AudioQualityModels.PipelineInput osActiveBp = new AudioQualityModels.PipelineInput(
                "Pixel 9 Pro", true, true, 96000, AudioQualityModels.EXO_ENCODING_PCM_24BIT,
                96000, AudioQualityModels.ENCODING_PCM_24BIT_PACKED, 2, 96000, 48000,
                true, 96000, AudioQualityModels.ENCODING_PCM_24BIT_PACKED, true, false, false, true,
                false, false, false, Collections.emptyList(), AudioQualityModels.UsbDirectState.NONE,
                false);
        AudioQualityModels.ResolvedSignalPath resActive = AudioQualityModels.resolve(src, osActiveBp, hwUsb);
        assertEquals(AudioQualityModels.PipelineMode.BIT_PERFECT, resActive.pipelineMode);
        assertEquals(AudioQualityModels.OutputPathId.USB_BIT_PERFECT, resActive.outputPaths.get(0).id);
        assertEquals(AudioQualityModels.PathAvailability.ACTIVE, resActive.outputPaths.get(0).availability);
    }

    @Test
    public void usbDescriptorParserHandlesUac2WithClockSelectorAndSkipsDsd() {
        // Synthetic UAC 2.0 descriptor:
        // AC iface 0 (proto 0x20): ClockSource 0x05 (RW), ClockSelector 0x09 -> [0x05],
        //   InputTerminal 0x01 (USB Streaming, bCSourceID=0x09), FeatureUnit 0x06 (src=0x01, mute+vol RW),
        //   OutputTerminal 0x03 (Headphones, src=0x06).
        // AS iface 1 (proto 0x20):
        //   alt 0 (0 ep),
        //   alt 1: PCM 16-bit (subslot 2), ISO OUT 0x01 async + feedback 0x81 (4B),
        //   alt 2: PCM 24-bit (subslot 3), ISO OUT 0x01 async + feedback 0x81 (4B),
        //   alt 3: PCM 32-bit (subslot 4), ISO OUT 0x01 async + feedback 0x81 (4B),
        //   alt 4: Raw DSD (bmFormats=0x80000000, 32-bit) -> MUST be skipped!
        byte[] uac2 = bytes(
                // Interface 0, alt 0, AudioControl UAC2
                9, 0x04, 0, 0, 0, 0x01, 0x01, 0x20, 0,
                // AC_HEADER (bcdADC 0x0200)
                9, 0x24, 0x01, 0x00, 0x02, 0x08, 0x40, 0x00, 0x00,
                // CLOCK_SOURCE bClockID=0x05, bmAttr=0x01, bmControls=0x07 (freq RW + valid R)
                8, 0x24, 0x0A, 0x05, 0x01, 0x07, 0x00, 0x00,
                // CLOCK_SELECTOR bClockID=0x09, nrPins=1, pin[0]=0x05
                7, 0x24, 0x0B, 0x09, 0x01, 0x05, 0x00,
                // INPUT_TERMINAL id=0x01, wTerminalType=0x0101, bAssoc=0, bCSourceID=0x09, bNrCh=2
                17, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x09, 0x02, 0x03, 0, 0, 0, 0, 0, 0, 0,
                // FEATURE_UNIT id=0x06, src=0x01, bmaControls(0)=0x0000000F (mute RW + vol RW)
                10, 0x24, 0x06, 0x06, 0x01, 0x0F, 0x00, 0x00, 0x00, 0x00,
                // OUTPUT_TERMINAL id=0x03, type=0x0302, assoc=0, src=0x06, cSrc=0x09
                12, 0x24, 0x03, 0x03, 0x02, 0x03, 0x00, 0x06, 0x09, 0x00, 0x00, 0x00,
                // AS iface 1 alt 0
                9, 0x04, 1, 0, 0, 0x01, 0x02, 0x20, 0,
                // AS iface 1 alt 1: PCM 16-bit (subslot 2)
                9, 0x04, 1, 1, 2, 0x01, 0x02, 0x20, 0,
                16, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0, 0, 0, 2, 3, 0, 0, 0, 0,
                6, 0x24, 0x02, 0x01, 2, 16,
                7, 0x05, 0x01, 0x05, 0x00, 0x02, 1,
                7, 0x05, 0x81, 0x11, 0x04, 0x00, 4,
                // AS iface 1 alt 2: PCM 24-bit (subslot 3)
                9, 0x04, 1, 2, 2, 0x01, 0x02, 0x20, 0,
                16, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0, 0, 0, 2, 3, 0, 0, 0, 0,
                6, 0x24, 0x02, 0x01, 3, 24,
                7, 0x05, 0x01, 0x05, 0x00, 0x03, 1,
                7, 0x05, 0x81, 0x11, 0x04, 0x00, 4,
                // AS iface 1 alt 3: PCM 32-bit (subslot 4)
                9, 0x04, 1, 3, 2, 0x01, 0x02, 0x20, 0,
                16, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0, 0, 0, 2, 3, 0, 0, 0, 0,
                6, 0x24, 0x02, 0x01, 4, 32,
                7, 0x05, 0x01, 0x05, 0x00, 0x04, 1,
                7, 0x05, 0x81, 0x11, 0x04, 0x00, 4,
                // AS iface 1 alt 4: Raw DSD (bmFormats = 0x80000000, non-PCM)
                9, 0x04, 1, 4, 2, 0x01, 0x02, 0x20, 0,
                16, 0x24, 0x01, 0x01, 0x00, 0x01, 0x00, 0, 0, 0x80, 2, 3, 0, 0, 0, 0,
                6, 0x24, 0x02, 0x01, 4, 32,
                7, 0x05, 0x01, 0x05, 0x00, 0x04, 1,
                7, 0x05, 0x81, 0x11, 0x04, 0x00, 4
        );

        UsbAudioDescriptorParser.ParsedUsbAudioDevice parsed = UsbAudioDescriptorParser.parse(uac2);
        assertTrue(parsed.isValidPlaybackDevice());
        assertEquals(2, parsed.uacVersion);
        assertEquals(0, parsed.audioControlInterfaceId);
        assertEquals(0x06, parsed.featureUnitId);
        assertTrue(parsed.featureUnitSupportsMute);
        assertTrue(parsed.featureUnitSupportsVolume);
        assertEquals(3, parsed.playbackAltSettings.size()); // Alt 4 (DSD) skipped!

        UsbAudioDescriptorParser.AltSettingInfo alt24 =
                UsbAudioDescriptorParser.selectBestAltSetting(parsed, 24, 96000, 2);
        assertNotNull(alt24);
        assertNotNull(parsed.clockFor(alt24));
        assertEquals(0x05, parsed.clockFor(alt24).sourceId); // ClockSelector 0x09 -> ClockSource 0x05
        assertEquals(2, alt24.altSetting);
        assertEquals(3, alt24.subslotBytes);
        assertEquals(24, alt24.bitResolution);
        assertEquals(0x01, alt24.endpointOut);
        assertEquals(0x81, alt24.endpointFeedback);
        assertTrue(alt24.isHighSpeedTransport());

        // UAC2 RANGE response: 3 discrete rates (44100, 96000, 192000)
        byte[] rangeBuf = bytes(
                3, 0,
                0x44, 0xAC, 0x00, 0x00, 0x44, 0xAC, 0x00, 0x00, 0, 0, 0, 0,
                0x00, 0x77, 0x01, 0x00, 0x00, 0x77, 0x01, 0x00, 0, 0, 0, 0,
                0x00, 0xEE, 0x02, 0x00, 0x00, 0xEE, 0x02, 0x00, 0, 0, 0, 0
        );
        int[] rates = UsbAudioDescriptorParser.parseUac2SampleRateRange(rangeBuf, rangeBuf.length);
        assertEquals(3, rates.length);
        assertEquals(44100, rates[0]);
        assertEquals(96000, rates[1]);
        assertEquals(192000, rates[2]);
    }

    @Test
    public void usbDescriptorParserHandlesUac1FullSpeed24Bit96k() {
        // Synthetic UAC 1.0 Full-Speed 24-bit/96kHz dongle (plus an ignored Mic IN interface)
        byte[] uac1 = bytes(
                // AC iface 0 (proto 0x00)
                9, 0x04, 0, 0, 0, 0x01, 0x01, 0x00, 0,
                9, 0x24, 0x01, 0x00, 0x01, 0x1E, 0x00, 0x01, 0x01,
                12, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00,
                9, 0x24, 0x06, 0x02, 0x01, 0x01, 0x03, 0x00, 0x00,
                // AS Playback iface 1 alt 1: 24-bit (subframe 3), rates 44100, 48000, 96000
                9, 0x04, 1, 1, 1, 0x01, 0x02, 0x00, 0,
                7, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00,
                17, 0x24, 0x02, 0x01, 2, 3, 24, 3,
                    0x44, 0xAC, 0x00, // 44100
                    0x80, 0xBB, 0x00, // 48000
                    0x00, 0x77, 0x01, // 96000
                9, 0x05, 0x01, 0x09, 0x46, 0x02, 1, 0, 0, // ISO OUT 0x01, Adaptive, maxPkt=582
                7, 0x25, 0x01, 0x01, 0x00, 0x00, 0x00,    // CS_ENDPOINT: sampling freq control supported
                // AS Microphone iface 2 alt 1 (ISO IN only -> MUST be ignored)
                9, 0x04, 2, 1, 1, 0x01, 0x02, 0x00, 0,
                7, 0x24, 0x01, 0x04, 0x01, 0x01, 0x00,
                11, 0x24, 0x02, 0x01, 1, 2, 16, 1, 0x80, 0xBB, 0x00,
                9, 0x05, 0x82, 0x05, 0x64, 0x00, 1, 0, 0
        );

        UsbAudioDescriptorParser.ParsedUsbAudioDevice parsed = UsbAudioDescriptorParser.parse(uac1);
        assertTrue(parsed.isValidPlaybackDevice());
        assertEquals(1, parsed.uacVersion);
        assertEquals(1, parsed.playbackAltSettings.size());
        assertEquals(96000, parsed.getMaxDescriptorSampleRateHz());
        assertEquals(24, parsed.getMaxBitDepth());

        UsbAudioDescriptorParser.AltSettingInfo alt96 =
                UsbAudioDescriptorParser.selectBestAltSetting(parsed, 24, 96000, 2);
        assertNotNull(alt96);
        assertEquals(1, alt96.interfaceNumber);
        assertEquals(1, alt96.altSetting);
        assertEquals(3, alt96.subslotBytes);
        assertEquals(24, alt96.bitResolution);
        assertTrue(alt96.epSupportsFreqControl);
        assertFalse(alt96.isHighSpeedTransport());

        // 192 kHz is NOT supported by this 96 kHz UAC1 dongle -> must return null so engine falls back safely
        assertNull(UsbAudioDescriptorParser.selectBestAltSetting(parsed, 24, 192000, 2));
    }

    @Test
    public void usbHardwareVolumeParsingTopologyAndDbCurve() {
        // UAC 2.0 descriptor with TWO Feature Units:
        // - FU 0x06 on Mic IN (0x04) -> USB Capture OUT (0x05, wTerminalType=0x0101) — MUST be ignored!
        // - FU 0x0A on Playback USB IN (0x01) -> Mixer (0x08) -> FU 0x0A (per-channel L+R vol only!) -> Headphones OUT (0x03)
        byte[] uac2Graph = bytes(
                9, 0x04, 0, 0, 0, 0x01, 0x01, 0x20, 0,
                9, 0x24, 0x01, 0x00, 0x02, 0x08, 0x60, 0x00, 0x00,
                8, 0x24, 0x0A, 0x05, 0x01, 0x07, 0x00, 0x00,
                // Playback USB InputTerminal 0x01
                17, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x05, 0x02, 0x03, 0, 0, 0, 0, 0, 0, 0,
                // Mic InputTerminal 0x04 (0x0201)
                17, 0x24, 0x02, 0x04, 0x01, 0x02, 0x00, 0x05, 0x01, 0x01, 0, 0, 0, 0, 0, 0, 0,
                // Mic FeatureUnit 0x06 (src=0x04, master vol RW)
                10, 0x24, 0x06, 0x06, 0x04, 0x0F, 0x00, 0x00, 0x00, 0x00,
                // USB Capture OutputTerminal 0x05 (type 0x0101, src=0x06)
                12, 0x24, 0x03, 0x05, 0x01, 0x01, 0x00, 0x06, 0x05, 0, 0, 0,
                // MixerUnit 0x08 (1 pin = 0x01)
                7, 0x24, 0x04, 0x08, 0x01, 0x01, 0x00,
                // Playback FeatureUnit 0x0A (src=0x08; ch0=mute RW only, ch1=vol RW, ch2=vol RW)
                18, 0x24, 0x06, 0x0A, 0x08,
                    0x03, 0x00, 0x00, 0x00, // ch0: mute RW
                    0x0C, 0x00, 0x00, 0x00, // ch1: vol RW
                    0x0C, 0x00, 0x00, 0x00, // ch2: vol RW
                    0x00,
                // Headphones OutputTerminal 0x03 (type 0x0302, src=0x0A)
                12, 0x24, 0x03, 0x03, 0x02, 0x03, 0x00, 0x0A, 0x05, 0, 0, 0,
                // AS playback alt 1
                9, 0x04, 1, 1, 1, 0x01, 0x02, 0x20, 0,
                16, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0, 0, 0, 2, 3, 0, 0, 0, 0,
                6, 0x24, 0x02, 0x01, 3, 24,
                7, 0x05, 0x01, 0x05, 0x00, 0x03, 1
        );

        UsbAudioDescriptorParser.ParsedUsbAudioDevice parsed = UsbAudioDescriptorParser.parse(uac2Graph);
        assertEquals(0x0A, parsed.featureUnitId); // Must pick playback FU 0x0A (not mic FU 0x06!)
        assertTrue(parsed.featureUnitMasterMute);
        assertFalse(parsed.featureUnitMasterVolume);
        assertTrue(parsed.featureUnitPerChannelVolume);
        assertTrue(parsed.featureUnitSupportsVolume);

        // UAC2 FU_VOLUME_CONTROL RANGE response: 1 subrange: -64.0 dB (-16384), 0.0 dB (0), step 0.5 dB (128)
        byte[] volRangeBytes = bytes(
                1, 0,
                0x00, 0xC0, // -16384 (-64.0 dB)
                0x00, 0x00, // 0 (0.0 dB)
                0x80, 0x00  // 128 (0.5 dB step)
        );
        UsbAudioDescriptorParser.VolumeRange range =
                UsbAudioDescriptorParser.parseUac2VolumeRange(volRangeBytes, volRangeBytes.length);
        assertNotNull(range);
        assertEquals(-16384, range.minDb256);
        assertEquals(0, range.maxDb256);
        assertEquals(128, range.resDb256);

        // Quantization & curve endpoints
        assertEquals(0, UsbAudioDescriptorParser.percentToVolumeDb256(100f, range.minDb256, range.maxDb256, range.resDb256));
        assertEquals(-16384, UsbAudioDescriptorParser.percentToVolumeDb256(0f, range.minDb256, range.maxDb256, range.resDb256));
        int mid = UsbAudioDescriptorParser.percentToVolumeDb256(50f, range.minDb256, range.maxDb256, range.resDb256);
        assertEquals(0, (mid - range.minDb256) % range.resDb256); // Exact hardware step multiple
        assertTrue("Expected mid dB between -35 dB and -15 dB, got " + mid, mid > -9000 && mid < -3800);

        // Round-trip percent <-> dB
        float backPct = UsbAudioDescriptorParser.volumeDb256ToPercent(mid, range.minDb256, range.maxDb256, false);
        assertTrue(Math.abs(backPct - 50f) < 2.0f);
        assertEquals(0f, UsbAudioDescriptorParser.volumeDb256ToPercent(mid, range.minDb256, range.maxDb256, true), 0.001f);

        assertEquals("0.0 dB", UsbAudioDescriptorParser.formatVolumeDb(0));
        assertEquals("-18.5 dB", UsbAudioDescriptorParser.formatVolumeDb(-18 * 256 - 128));
        assertEquals("+3.0 dB", UsbAudioDescriptorParser.formatVolumeDb(3 * 256));
        assertEquals("-∞ dB", UsbAudioDescriptorParser.formatVolumeDb(UsbAudioDescriptorParser.VOLUME_SILENCE_DB256));
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) (values[i] & 0xFF);
        return out;
    }

    @Test
    public void exhaustive24BitFloatRoundTripAndIntWideningAreBitExact() {
        // Exhaustively verify all 16,777,216 24-bit signed integers through Apple Music's
        // exact FloatResamplingAudioProcessor scaling factor (1.0 / 0x7FFFFFFF).
        final double factor = 4.656612875245797E-10d;
        for (int s24 = -8388608; s24 <= 8388607; s24++) {
            float f = (float) (((double) (s24 << 8)) * factor);
            int recovered = AudioQualityModels.floatToSigned24(f);
            assertEquals("Mismatch at s24=" + s24, s24, recovered);
        }

        // Verify ByteBuffer packing for FLOAT -> PCM_24BIT_PACKED and PCM_32BIT
        int[] samples = {-8388608, -123456, 0, 123456, 8388607};
        ByteBuffer floatBuf = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int s : samples) {
            floatBuf.putFloat((float) (((double) (s << 8)) * factor));
        }
        floatBuf.flip();

        ByteBuffer out24 = ByteBuffer.allocate(samples.length * 3).order(ByteOrder.LITTLE_ENDIAN);
        int written24 = AudioQualityModels.convertPcmSamples(
                floatBuf, 0, AudioQualityModels.ENCODING_PCM_FLOAT,
                out24, AudioQualityModels.ENCODING_PCM_24BIT_PACKED, samples.length);
        assertEquals(samples.length * 3, written24);
        for (int i = 0; i < samples.length; i++) {
            int p = i * 3;
            int got = (out24.get(p) & 0xFF)
                    | ((out24.get(p + 1) & 0xFF) << 8)
                    | (out24.get(p + 2) << 16); // sign-extended top byte
            assertEquals(samples[i], got);
        }

        ByteBuffer out32 = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        int written32 = AudioQualityModels.convertPcmSamples(
                floatBuf, 0, AudioQualityModels.ENCODING_PCM_FLOAT,
                out32, AudioQualityModels.ENCODING_PCM_32BIT, samples.length);
        assertEquals(samples.length * 4, written32);
        for (int i = 0; i < samples.length; i++) {
            assertEquals(samples[i] << 8, out32.getInt(i * 4));
        }

        // Verify 16-bit -> 24-bit and 32-bit zero-padded lossless widening
        short[] s16 = {Short.MIN_VALUE, -1, 0, 12345, Short.MAX_VALUE};
        ByteBuffer pcm16 = ByteBuffer.allocate(s16.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : s16) pcm16.putShort(v);
        pcm16.flip();

        ByteBuffer w24 = ByteBuffer.allocate(s16.length * 3).order(ByteOrder.LITTLE_ENDIAN);
        AudioQualityModels.convertPcmSamples(
                pcm16, 0, AudioQualityModels.ENCODING_PCM_16BIT,
                w24, AudioQualityModels.ENCODING_PCM_24BIT_PACKED, s16.length);
        for (int i = 0; i < s16.length; i++) {
            assertEquals(0, w24.get(i * 3));
            short hi = (short) ((w24.get(i * 3 + 1) & 0xFF) | ((w24.get(i * 3 + 2) & 0xFF) << 8));
            assertEquals(s16[i], hi);
        }
    }
}
