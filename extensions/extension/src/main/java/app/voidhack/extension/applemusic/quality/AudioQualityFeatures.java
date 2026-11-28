package app.voidhack.extension.applemusic.quality;

/**
 * Compile-time feature gates flipped to {@code true} by the corresponding Morphe bytecode patches:
 * {@code Audio quality signal path} and {@code USB DAC bit-perfect output}.
 */
public final class AudioQualityFeatures {
    private AudioQualityFeatures() {}

    public static boolean usbDacOutput() {
        return false;
    }
}
