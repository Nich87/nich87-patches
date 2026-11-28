package app.voidhack.extension.applemusic.routines;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

public final class MediaButtonReceiver extends BroadcastReceiver {
    private static final String TAG = "VoidhackMediaButton";
    private static final String PLAYBACK_SERVICE =
            "com.apple.android.music.player.MediaPlaybackService";
    private static final long CONNECTION_TIMEOUT_MS = 5_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }

        KeyEvent event;
        try {
            event = readKeyEvent(intent);
        } catch (BadParcelableException | ClassCastException ignored) {
            return;
        }
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() != 0) {
            return;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                new PlaybackConnection(context.getApplicationContext(), event, goAsync()).connect();
                break;
            default:
                break;
        }
    }

    @SuppressWarnings("deprecation")
    private static KeyEvent readKeyEvent(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    }

    private static final class PlaybackConnection extends MediaBrowser.ConnectionCallback {
        private final Context context;
        private final KeyEvent event;
        private final PendingResult result;
        private final MediaBrowser browser;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable timeout = this::finish;
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                if (state == null) {
                    return;
                }
                boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
                if (playing == expectPlaying && state.getState() != PlaybackState.STATE_BUFFERING) {
                    finish();
                }
            }

            @Override
            public void onSessionDestroyed() {
                finish();
            }
        };

        private MediaController controller;
        private boolean expectPlaying;
        private boolean finished;

        PlaybackConnection(Context context, KeyEvent event, PendingResult result) {
            this.context = context;
            this.event = event;
            this.result = result;
            browser = new MediaBrowser(
                    context, new ComponentName(context, PLAYBACK_SERVICE), this, null);
        }

        void connect() {
            // Keep the service bound until it has handled the key, without starting a foreground
            // service that might have no queue to play.
            handler.postDelayed(timeout, CONNECTION_TIMEOUT_MS);
            browser.connect();
        }

        @Override
        public void onConnected() {
            controller = new MediaController(context, browser.getSessionToken());
            controller.registerCallback(callback, handler);
            PlaybackState state = controller.getPlaybackState();
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    if (playing) {
                        finish();
                        return;
                    }
                    expectPlaying = true;
                    controller.getTransportControls().play();
                    break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    expectPlaying = false;
                    controller.getTransportControls().stop();
                    break;
                default:
                    expectPlaying = !playing;
                    if (!controller.dispatchMediaButtonEvent(event)) {
                        finish();
                    }
                    break;
            }
        }

        @Override
        public void onConnectionFailed() {
            Log.w(TAG, "Unable to connect to the Apple Music playback service");
            finish();
        }

        @Override
        public void onConnectionSuspended() {
            finish();
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            handler.removeCallbacks(timeout);
            if (controller != null) {
                controller.unregisterCallback(callback);
            }
            browser.disconnect();
            result.finish();
        }
    }
}
