package app.voidhack.extension.applemusic.quality;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

/**
 * Entry points wired by AudioQualitySheetPatch into Apple Music's Now Playing
 * audio quality badge views and native settings navigation.
 */
public final class AudioQualityController {
    private static final String TAG = "VoidhackAudioQuality";

    private AudioQualityController() {}

    /**
     * Makes Apple Music's secondary audio badge (Dolby Atmos icon, audio_badge_no_text)
     * clickable with the same tap handler and comfortable hit padding as the Lossless badge.
     */
    public static void bindSecondaryBadge(View badgeView, View.OnClickListener listener) {
        if (badgeView == null || listener == null) return;
        try {
            badgeView.setClickable(true);
            badgeView.setFocusable(true);
            badgeView.setOnClickListener(listener);
            float d = badgeView.getResources().getDisplayMetrics().density;
            int padH = Math.round(10 * d);
            int padV = Math.round(5 * d);
            badgeView.setPadding(padH, padV, padH, padV);

            StateListDrawable bg = new StateListDrawable();
            GradientDrawable pressed = new GradientDrawable();
            pressed.setCornerRadius(8 * d);
            pressed.setColor(0x1EFFFFFF);
            bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
            bg.addState(new int[]{}, new GradientDrawable());
            badgeView.setBackground(bg);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to bind secondary audio badge", t);
        }
    }

    /**
     * Intercepts clicks on Now Playing audio badges (Lossless, Hi-Res Lossless, Dolby Atmos).
     * Returns true if handled (suppressing Apple Music's default plain AlertDialog).
     */
    public static boolean onBadgeClicked(
            View badgeView,
            Context context,
            int audioVariant,
            int bitDepth,
            int sampleRate,
            String codec,
            int audioCapabilities) {
        try {
            Activity activity = resolveActivity(context != null ? context
                    : (badgeView != null ? badgeView.getContext() : null));
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return false;
            }
            if (badgeView != null) {
                int withTextId = AudioQualityStrings.id(activity, "id", "audio_badge_with_text");
                int noTextId = AudioQualityStrings.id(activity, "id", "audio_badge_no_text");
                int viewId = badgeView.getId();
                if (viewId != withTextId && viewId != noTextId) {
                    return false;
                }
                badgeView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            }

            AudioQualitySheet.show(
                    activity,
                    badgeView,
                    audioVariant,
                    bitDepth,
                    sampleRate,
                    codec,
                    audioCapabilities
            );
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to present Audio Quality Signal Path sheet", t);
            return false;
        }
    }

    public static void openAudioQualitySettings(Context context) {
        if (context == null) return;
        try {
            int destId = AudioQualityStrings.id(context, "id", "settings_audio_quality");
            if (destId != 0) {
                nativeOpenSettings(context, destId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to navigate to Audio Quality Settings", t);
        }
    }

    /**
     * Patched at build time by AudioQualitySheetPatch to invoke Apple Music's resolved
     * navigation helper (M9.p.a(Context, int)) with R.id.settings_audio_quality.
     */
    public static void nativeOpenSettings(Context context, int destinationId) {
        // Replaced by bytecode patch.
    }

    /**
     * Resolves the currently playing track's [title, subtitle] strictly from the player container
     * owning the tapped badge view, never searching DecorView or background list fragments.
     */
    static String[] resolveTrackMetadata(View badgeView, Context context) {
        if (badgeView == null || context == null) return new String[]{"", ""};
        int currentItemId = AudioQualityStrings.id(context, "id", "current_player_item");
        int miniTitleId = AudioQualityStrings.id(context, "id", "mini_player_title");
        int miniSubtitleId = AudioQualityStrings.id(context, "id", "mini_player_subtitle");
        int titleId = AudioQualityStrings.id(context, "id", "title");
        int subtitleId = AudioQualityStrings.id(context, "id", "subtitle");

        ViewParent parent = badgeView.getParent();
        int depth = 0;
        while (parent instanceof ViewGroup && depth < 10) {
            ViewGroup group = (ViewGroup) parent;
            if (group.getId() == android.R.id.content) {
                break;
            }

            // 1. Lyrics / Queue sheets host track metadata inside direct child `current_player_item`.
            if (currentItemId != 0) {
                View currentItem = findDirectChildById(group, currentItemId);
                if (currentItem != null) {
                    String title = readTextById(currentItem, miniTitleId);
                    String subtitle = readTextById(currentItem, miniSubtitleId);
                    if (!title.isEmpty() || !subtitle.isEmpty()) {
                        return new String[]{title, subtitle};
                    }
                }
            }

            // 2. Full Now Playing screen (`music_player.xml`) hosts `title` and `subtitle` as direct children.
            if (titleId != 0) {
                View directTitle = findDirectChildById(group, titleId);
                if (directTitle instanceof TextView) {
                    String title = extractText((TextView) directTitle);
                    String subtitle = "";
                    if (subtitleId != 0) {
                        View directSub = findDirectChildById(group, subtitleId);
                        if (directSub instanceof TextView) {
                            subtitle = extractText((TextView) directSub);
                        }
                    }
                    if (!title.isEmpty() || !subtitle.isEmpty()) {
                        return new String[]{title, subtitle};
                    }
                }
            }

            parent = group.getParent();
            depth++;
        }
        return new String[]{"", ""};
    }

    private static View findDirectChildById(ViewGroup group, int targetId) {
        if (targetId == 0) return null;
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child != null && child.getId() == targetId) {
                return child;
            }
        }
        return null;
    }

    private static String readTextById(View container, int id) {
        if (container == null || id == 0) return "";
        View v = container.findViewById(id);
        return v instanceof TextView ? extractText((TextView) v) : "";
    }

    private static String extractText(TextView tv) {
        CharSequence text = tv.getText();
        return text != null ? text.toString().trim() : "";
    }

    private static Activity resolveActivity(Context context) {
        Context c = context;
        int depth = 0;
        while (c instanceof ContextWrapper && depth < 10) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
            depth++;
        }
        return c instanceof Activity ? (Activity) c : null;
    }
}
