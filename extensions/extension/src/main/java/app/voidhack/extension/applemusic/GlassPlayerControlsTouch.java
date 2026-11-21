package app.voidhack.extension.applemusic;

import android.content.Context;
import android.graphics.Rect;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;

/** Lets a downward drag from the playback controls reach the native player sheet. */
final class GlassPlayerControlsTouch {
    private static final float VERTICAL_DOMINANCE = 1.2f;

    private final View sheet;
    private final int slop;
    private final Rect hit = new Rect();
    private final int[] location = new int[2];
    private MotionEvent down;
    private ViewGroup dragParent;

    GlassPlayerControlsTouch(View sheet) {
        this.sheet = sheet;
        slop = ViewConfiguration.get(sheet.getContext()).getScaledTouchSlop();
    }

    boolean onTouch(Window window, MotionEvent event, boolean expanded) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (dragParent != null) sendToParent(event, MotionEvent.ACTION_CANCEL);
            reset();
            if (expanded && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    && startsInControls(event)) {
                down = MotionEvent.obtainNoHistory(event);
            }
            return false;
        }

        if (dragParent != null) {
            sendToParent(event, event.getAction());
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) reset();
            return true;
        }
        if (down == null) return false;
        if (!expanded || action != MotionEvent.ACTION_MOVE) {
            reset();
            return false;
        }

        float dx = Math.abs(event.getRawX() - down.getRawX());
        float dy = event.getRawY() - down.getRawY();
        if (Math.max(dx, Math.abs(dy)) <= slop) return false;
        if (dy <= slop || dy < dx * VERTICAL_DOMINANCE
                || !(sheet.getParent() instanceof ViewGroup parent)) {
            reset();
            return false;
        }

        // The controls may have disallowed interception and reset the coordinator's
        // drag helper. Cancel their stream and replay DOWN directly to the coordinator
        // so it can capture the sheet before processing the first drag MOVE.
        MotionEvent start = down;
        down = null;
        MotionEvent cancel = MotionEvent.obtainNoHistory(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            window.superDispatchTouchEvent(cancel);
            if (sheet.isAttachedToWindow() && sheet.getParent() == parent) {
                dragParent = parent;
                sendToParent(start, MotionEvent.ACTION_DOWN);
                sendToParent(event, MotionEvent.ACTION_MOVE);
            }
        } finally {
            cancel.recycle();
            start.recycle();
        }
        return true;
    }

    void reset() {
        clearDown();
        dragParent = null;
    }

    private void clearDown() {
        if (down != null) down.recycle();
        down = null;
    }

    private void sendToParent(MotionEvent event, int action) {
        MotionEvent local = MotionEvent.obtain(event);
        dragParent.getLocationOnScreen(location);
        local.setLocation(event.getRawX() - location[0], event.getRawY() - location[1]);
        local.setAction(action);
        try {
            dragParent.onTouchEvent(local);
        } finally {
            local.recycle();
        }
    }

    private boolean startsInControls(MotionEvent event) {
        AccessibilityManager accessibility = (AccessibilityManager)
                sheet.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (accessibility != null && accessibility.isTouchExplorationEnabled()) return false;
        sheet.getRootView().getLocationOnScreen(location);
        int x = (int) (event.getRawX() - location[0]);
        int y = (int) (event.getRawY() - location[1]);
        View controls = LiquidGlass.find(sheet, "player_controls");
        return contains(controls, x, y)
                && !contains(LiquidGlass.find(controls, "seek_bar_controls"), x, y);
    }

    private boolean contains(View view, int x, int y) {
        return view != null && view.isShown() && view.getGlobalVisibleRect(hit)
                && hit.contains(x, y);
    }
}
