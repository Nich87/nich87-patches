package app.voidhack.extension.applemusic;

import android.animation.ValueAnimator;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

/** Shapes the native Now Playing sheet using its own drag and settle progress. */
final class GlassPlayerTransition {
    private static final float GLASS_FADE_END = 0.28f;

    private final ViewGroup sheet;
    private final ViewGroup player;
    private final View mini;
    private final View target;
    private final Rect miniBounds = new Rect();
    private final Rect clip = new Rect();
    private final Rect surfaceBounds = new Rect();
    private final ViewOutlineProvider outline = new ViewOutlineProvider() {
        @Override public void getOutline(View view, Outline result) {
            result.setRoundRect(clip, radius);
        }
    };
    private ViewOutlineProvider originalOutline;
    private Drawable originalBackground;
    private boolean originalClip;
    private boolean running;
    private float radius;
    private GlassDrawable surface;

    GlassPlayerTransition(ViewGroup sheet, ViewGroup player, View mini, View target) {
        this.sheet = sheet;
        this.player = player;
        this.mini = mini;
        this.target = target;
    }

    boolean isRunning() { return running; }

    boolean update(float progress, GlassDrawable miniGlass) {
        if (!(progress > 0f && progress < 1f) || !ValueAnimator.areAnimatorsEnabled()
                || mini.getWidth() == 0 || mini.getHeight() == 0
                || player.getWidth() == 0 || player.getHeight() == 0) {
            restore();
            miniGlass.setAlpha(progress >= 1f ? 0 : 255);
            return false;
        }

        mini.getDrawingRect(miniBounds);
        player.offsetDescendantRectToMyCoords(mini, miniBounds);
        int left = Math.round(mix(miniBounds.left, 0f, progress));
        int top = Math.round(mix(miniBounds.top, 0f, progress));
        int right = Math.round(mix(miniBounds.right, player.getWidth(), progress));
        int bottom = Math.round(mix(miniBounds.bottom, player.getHeight(), progress));
        float corner = Math.min(miniBounds.width(), miniBounds.height()) * 0.5f * (1f - progress);
        if (clip.left != left || clip.top != top || clip.right != right || clip.bottom != bottom
                || radius != corner) {
            clip.set(left, top, right, bottom);
            radius = corner;
            player.invalidateOutline();
        }
        if (!running) {
            running = true;
            originalOutline = player.getOutlineProvider();
            originalClip = player.getClipToOutline();
            originalBackground = sheet.getBackground();
            player.setOutlineProvider(outline);
            player.setClipToOutline(true);
            miniGlass.setAlpha(0);
        }

        // Apple Music supplies the artwork, controls and palette. Fade the live glass
        // underneath them early so the renderer never filters a full-screen sheet.
        if (progress < GLASS_FADE_END) {
            if (surface == null) surface = new GlassDrawable(sheet, LiquidGlass.backdrop(target, sheet));
            surfaceBounds.set(clip);
            sheet.offsetDescendantRectToMyCoords(player, surfaceBounds);
            surface.dark = miniGlass.dark;
            surface.setTransitionShape(surfaceBounds, radius);
            float t = progress / GLASS_FADE_END;
            surface.setAlpha(Math.round(255f * (1f - t * t * (3f - 2f * t))));
            if (sheet.getBackground() != surface) sheet.setBackground(surface);
        } else if (surface != null) {
            surface.setAlpha(0);
        }
        return true;
    }

    void release(GlassDrawable miniGlass) {
        restore();
        miniGlass.setAlpha(255);
        if (surface != null && surface.backdrop != null) surface.backdrop.release();
    }

    private void restore() {
        if (!running) return;
        running = false;
        if (sheet.getBackground() == surface) sheet.setBackground(originalBackground);
        player.setOutlineProvider(originalOutline);
        player.setClipToOutline(originalClip);
    }

    private static float mix(float start, float end, float progress) {
        return start + (end - start) * progress;
    }
}
