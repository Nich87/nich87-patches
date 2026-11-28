package app.voidhack.extension.applemusic.quality;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Uac2ClockTest {
    @Test
    public void writableClockRequiresSuccessfulSetAndMatchingCurrentRate() {
        Uac2Clock clock = new Uac2Clock(2, 5, 0x03);
        int[] deviceRate = {48_000};
        Uac2Clock.ControlTransfer device = (type, request, value, index, data, length, timeout) -> {
            assertEquals(0x01, request);
            assertEquals(0x0100, value);
            assertEquals(0x0502, index);
            assertEquals(4, length);
            ByteBuffer rate = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (type == 0x21) {
                deviceRate[0] = rate.getInt();
            } else {
                assertEquals(0xA1, type);
                rate.putInt(deviceRate[0]);
            }
            return 4;
        };

        assertTrue(clock.setRateIfWritable(device, 96_000));
        assertTrue(clock.confirmsRate(device, 96_000));
        assertFalse(clock.setRateIfWritable((type, request, value, index, data, length, timeout) -> 3, 96_000));
        assertFalse(clock.confirmsRate(device, 48_000));
    }

    @Test
    public void fixedClockCanBeVerifiedWithoutSetOrOptionalValidityControl() {
        Uac2Clock clock = new Uac2Clock(0, 5, 0x01);
        Uac2Clock.ControlTransfer fixedDevice = (type, request, value, index, data, length, timeout) -> {
            assertEquals(0xA1, type);
            assertEquals(0x0100, value);
            return replyRate(data, 96_000);
        };

        assertTrue(clock.setRateIfWritable(fixedDevice, 96_000));
        assertTrue(clock.confirmsRate(fixedDevice, 96_000));
        assertFalse(clock.confirmsRate(fixedDevice, 48_000));
    }

    @Test
    public void clockNeverConfirmsAnUnreadableFrequencyOrInvalidAdvertisedValidity() {
        Uac2Clock clock = new Uac2Clock(0, 5, 0x07);
        assertFalse(clock.confirmsRate((type, request, value, index, data, length, timeout) -> {
            assertEquals(0x0100, value);
            replyRate(data, 96_000);
            return 3;
        }, 96_000));

        assertFalse(clock.confirmsRate(clockReply(0, 1), 96_000));
        assertFalse(clock.confirmsRate(clockReply(1, -1), 96_000));
        assertTrue(clock.confirmsRate(clockReply(1, 1), 96_000));

        Uac2Clock missingFrequency = new Uac2Clock(0, 5, 0x04);
        assertFalse(missingFrequency.confirmsRate((type, request, value, index, data, length, timeout) -> {
            fail("Clock descriptor has no Sample Rate control");
            return -1;
        }, 96_000));
    }

    @Test
    public void unknownPlaybackClockDoesNotFallBackToAnUnrelatedSource() {
        UsbAudioDescriptorParser.ParsedUsbAudioDevice parsed = UsbAudioDescriptorParser.parse(bytes(
                9, 0x04, 0, 0, 0, 0x01, 0x01, 0x20, 0,
                8, 0x24, 0x0A, 0x05, 0x01, 0x07, 0x00, 0x00,
                // Clock 0x09 is not present, and clock 0x05 is not on this playback path.
                17, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x09, 0x02, 0x03, 0, 0, 0, 0, 0, 0, 0,
                9, 0x04, 1, 1, 1, 0x01, 0x02, 0x20, 0,
                16, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0, 0, 0, 2, 3, 0, 0, 0, 0,
                6, 0x24, 0x02, 0x01, 3, 24,
                7, 0x05, 0x01, 0x05, 0x00, 0x03, 1
        ));
        UsbAudioDescriptorParser.AltSettingInfo alt =
                UsbAudioDescriptorParser.selectBestAltSetting(parsed, 24, 96_000, 2);
        assertNotNull(alt);
        assertNull(parsed.clockFor(alt));
        assertNull(parsed.commonPlaybackClock());
    }

    private static Uac2Clock.ControlTransfer clockReply(int isValid, int validityLength) {
        return (type, request, value, index, data, length, timeout) -> {
            if (value == 0x0100) return replyRate(data, 96_000);
            assertEquals(0x0200, value);
            data[0] = (byte) isValid;
            return validityLength;
        };
    }

    private static int replyRate(byte[] data, int sampleRateHz) {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRateHz);
        return 4;
    }

    private static byte[] bytes(int... values) {
        byte[] data = new byte[values.length];
        for (int i = 0; i < values.length; i++) data[i] = (byte) values[i];
        return data;
    }
}
