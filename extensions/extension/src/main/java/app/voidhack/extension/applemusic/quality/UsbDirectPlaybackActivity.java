package app.voidhack.extension.applemusic.quality;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

final class UsbDirectPlaybackActivity {
    private static final int SAMPLE_RATE = 48_000;
    private static final int SILENT_FRAMES = 2_048;

    private AudioTrack track;
    private boolean playing;

    void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        try {
            if (playing) {
                if (track == null) prepareTrack();
                track.play();
            } else if (track != null) {
                track.pause();
            }
        } catch (RuntimeException error) {
            Log.w("VoidhackUsbDirect", "Could not update Android audio playback activity", error);
            release();
        }
    }

    private void prepareTrack() {
        // Some Android builds freeze the background process if playing via usbfs alone.
        // Loop silence so audio policy sees playback while the music PCM goes to the USB DAC.
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(SILENT_FRAMES * Short.BYTES)
                .build();

        if (track.write(new short[SILENT_FRAMES], 0, SILENT_FRAMES) != SILENT_FRAMES
                || track.setLoopPoints(0, SILENT_FRAMES, -1) != AudioTrack.SUCCESS) {
            throw new IllegalStateException("Could not loop the silent AudioTrack");
        }
    }

    void release() {
        if (track == null) return;
        track.release();
        track = null;
    }
}
