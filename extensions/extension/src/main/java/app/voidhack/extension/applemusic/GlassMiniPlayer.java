package app.voidhack.extension.applemusic;

import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.TextView;

final class GlassMiniPlayer implements View.OnAttachStateChangeListener,
        ViewTreeObserver.OnPreDrawListener {
    // This resource ID exists in the host APK and is stable for the life of the process.
    private static int playerSheetId = View.NO_ID;

    private final View root;
    private final View target;
    private final View blur;
    private final ViewGroup sheet;
    private final int contentId;
    private final GlassPlayerControlsTouch controlsTouch;
    private final int[] location = new int[2];
    private int screenX = Integer.MIN_VALUE;
    private int screenY = Integer.MIN_VALUE;
    private ViewTreeObserver observer;
    private View content;
    private TextView title;
    private GlassDrawable material;
    private GlassSurfaceMotion motion;
    private GlassMiniTouch touch;
    private View play;
    private View next;
    private GlassPlayerTransition transition;
    private float slideProgress;
    private View decor;

    GlassMiniPlayer(View root, View target) {
        this.root = root;
        this.target = target;
        blur = LiquidGlass.find(root, "mini_player_blur");
        View sheetView = LiquidGlass.find(root, "player_sheet_container");
        sheet = sheetView instanceof ViewGroup ? (ViewGroup) sheetView : null;
        controlsTouch = sheet == null ? null : new GlassPlayerControlsTouch(sheet);
        contentId = root.getResources().getIdentifier("mini_player_content", "id", root.getContext().getPackageName());
    }

    void attach() {
        if (observer != null) return;
        observer = root.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        if (sheet != null) {
            playerSheetId = sheet.getId();
            sheet.setTag(playerSheetId, this);
            decor = sheet.getRootView();
            decor.setTag(playerSheetId, this);
        }
        onPreDraw();
    }

    void onPlayerSlide(float progress) {
        slideProgress = progress;
    }

    static boolean onControlTouch(Window window, MotionEvent event) {
        View decor = window.peekDecorView();
        return decor != null && playerSheetId != View.NO_ID
                && decor.getTag(playerSheetId) instanceof GlassMiniPlayer mini
                && mini.controlsTouch.onTouch(window, event, mini.slideProgress >= 1f);
    }

    @Override public void onViewAttachedToWindow(View view) { attach(); }

    @Override public void onViewDetachedFromWindow(View view) {
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        if (sheet != null) {
            if (sheet.getTag(playerSheetId) == this) sheet.setTag(playerSheetId, null);
            if (decor != null && decor.getTag(playerSheetId) == this) decor.setTag(playerSheetId, null);
            decor = null;
            controlsTouch.reset();
        }
        slideProgress = 0f;
        release();
    }

    @Override public boolean onPreDraw() {
        View current = contentId == 0 ? null : root.findViewById(contentId);
        if (current != content) {
            release();
            content = current;
            if (content != null) {
                for (ViewParent parent = content.getParent(); parent instanceof ViewGroup && parent != root; parent = parent.getParent()) {
                    ((ViewGroup) parent).setClipChildren(false);
                    ((ViewGroup) parent).setClipToPadding(false);
                }
                material = new GlassDrawable(content, LiquidGlass.backdrop(target, content));
                content.setBackground(material);
                motion = new GlassSurfaceMotion(content, material);
                touch = new GlassMiniTouch(content, motion);
                content.setOnTouchListener(touch);
                play = LiquidGlass.find(content, "mini_player_play_btn");
                next = LiquidGlass.find(content, "mini_player_next_btn");
                if (play != null) play.setOnTouchListener(touch);
                if (next != null) next.setOnTouchListener(touch);
                View label = LiquidGlass.find(content, "mini_player_title");
                title = label instanceof TextView ? (TextView) label : null;
                View player = sheet == null ? null : LiquidGlass.find(sheet, "player_root");
                if (player instanceof ViewGroup && player.findViewById(contentId) == content) {
                    transition = new GlassPlayerTransition(sheet, (ViewGroup) player, content, target);
                }
            }
        }
        if (material != null) {
            content.getLocationOnScreen(location);
            if (screenX != location[0] || screenY != location[1]) {
                screenX = location[0];
                screenY = location[1];
                material.invalidateSelf();
            }
            if (title != null) {
                boolean dark = Color.luminance(title.getCurrentTextColor()) > 0.5f;
                if (material.dark != dark) {
                    material.dark = dark;
                    material.invalidateSelf();
                }
            }
            if (material.backdrop != null && blur != null) blur.setAlpha(0f);
            boolean wasMoving = transition != null && transition.isRunning();
            boolean moving = transition != null && transition.update(slideProgress, material);
            if (moving && !wasMoving) {
                touch.cancel();
                motion.stop();
            } else if (!moving) {
                motion.hover(content.isShown() && (content.isHovered() || (play != null && play.isHovered()) || (next != null && next.isHovered())));
                if (!content.isShown()) touch.cancel();
            }
        }
        return true;
    }

    private void release() {
        if (transition != null) transition.release(material);
        if (touch != null) touch.cancel();
        if (motion != null) motion.stop();
        if (material != null && material.backdrop != null) material.backdrop.release();
        content = null;
        title = null;
        material = null;
        motion = null;
        touch = null;
        transition = null;
        play = next = null;
        screenX = screenY = Integer.MIN_VALUE;
    }
}
