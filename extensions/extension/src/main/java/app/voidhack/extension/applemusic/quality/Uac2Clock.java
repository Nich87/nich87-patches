package app.voidhack.extension.applemusic.quality;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class Uac2Clock {
    private static final int HOST_TO_INTERFACE = 0x21;
    private static final int INTERFACE_TO_HOST = 0xA1;
    private static final int CUR = 0x01;
    private static final int RANGE = 0x02;
    private static final int SAMPLE_RATE = 0x0100;
    private static final int CLOCK_VALIDITY = 0x0200;
    private static final int READ_ONLY = 1;
    private static final int READ_WRITE = 3;
    private static final int TIMEOUT_MS = 1000;

    @FunctionalInterface
    interface ControlTransfer {
        int transfer(int requestType, int request, int value, int index,
                     byte[] buffer, int length, int timeoutMs);
    }

    final int sourceId;
    private final int index;
    private final int frequencyAccess;
    private final int validityAccess;

    Uac2Clock(int interfaceId, int sourceId, int controls) {
        this.sourceId = sourceId;
        index = (sourceId << 8) | interfaceId;
        frequencyAccess = controls & 0x03;
        validityAccess = (controls >>> 2) & 0x03;
    }

    boolean setRateIfWritable(ControlTransfer transfer, int rateHz) {
        if (frequencyAccess == READ_ONLY) return true;
        if (frequencyAccess != READ_WRITE) return false;
        byte[] data = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(rateHz).array();
        return transfer.transfer(HOST_TO_INTERFACE, CUR, SAMPLE_RATE, index,
                data, data.length, TIMEOUT_MS) == data.length;
    }

    boolean confirmsRate(ControlTransfer transfer, int rateHz) {
        if (frequencyAccess != READ_ONLY && frequencyAccess != READ_WRITE) return false;
        byte[] rate = new byte[Integer.BYTES];
        if (transfer.transfer(INTERFACE_TO_HOST, CUR, SAMPLE_RATE, index,
                rate, rate.length, TIMEOUT_MS) != rate.length
                || ByteBuffer.wrap(rate).order(ByteOrder.LITTLE_ENDIAN).getInt() != rateHz) {
            return false;
        }

        if (validityAccess == 0) return true;
        if (validityAccess != READ_ONLY) return false;
        byte[] validity = new byte[1];
        return transfer.transfer(INTERFACE_TO_HOST, CUR, CLOCK_VALIDITY, index,
                validity, validity.length, TIMEOUT_MS) == validity.length && validity[0] == 1;
    }

    int[] queryRates(ControlTransfer transfer) {
        if (frequencyAccess != READ_ONLY && frequencyAccess != READ_WRITE) return new int[0];
        byte[] data = new byte[256];
        int length = transfer.transfer(INTERFACE_TO_HOST, RANGE, SAMPLE_RATE, index,
                data, data.length, TIMEOUT_MS);
        return UsbAudioDescriptorParser.parseUac2SampleRateRange(data, length);
    }
}
