package app.voidhack.extension.applemusic.quality;

import android.util.Log;

/**
 * JNI bridge for libvoidhack_usb_audio.so (userspace Linux usbdevfs UAC1/UAC2
 * isochronous audio driver). Fails safely if the native library is absent.
 */
final class UsbDirectNative {
    private static final String TAG = "VoidhackUsbDirect";
    private static volatile boolean loaded;
    private static volatile boolean loadAttempted;

    private UsbDirectNative() {}

    static boolean isAvailable() {
        if (!loadAttempted) {
            synchronized (UsbDirectNative.class) {
                if (!loadAttempted) {
                    loadAttempted = true;
                    try {
                        System.loadLibrary("voidhack_usb_audio");
                        loaded = true;
                        Log.i(TAG, "Loaded libvoidhack_usb_audio.so");
                    } catch (Throwable t) {
                        loaded = false;
                        Log.w(TAG, "libvoidhack_usb_audio.so unavailable: " + t.getMessage());
                    }
                }
            }
        }
        return loaded;
    }

    static native long nativeCreate(
            int fd,
            int interfaceId,
            int endpointOut,
            int endpointFeedback,
            int feedbackPacketSize,
            int sampleRate,
            int channelCount,
            int subslotBytes,
            int bitResolution,
            int maxPacketSize,
            boolean isHighSpeed,
            int bInterval);

    static native boolean nativeStart(long handle);

    static native int nativeWritePcm(long handle, byte[] pcm, int offset, int length, int inputEncoding);

    static native void nativeStop(long handle);

    static native void nativeFlush(long handle);

    static native int nativeDrainUrbs(long handle);

    static native void nativeDestroy(long handle);

    static native boolean nativeIsRunning(long handle);

    static native long nativeGetFramesWritten(long handle);

    static native int nativeGetUrbsInFlight(long handle);

    static native int nativeGetFramesPerUrb(long handle);

    static native int nativeGetMeasuredRateHz(long handle);

    static native boolean nativeReconnectKernelDriver(int fd, int acInterfaceId, int asInterfaceId);
}
