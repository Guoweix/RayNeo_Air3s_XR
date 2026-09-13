package com.xrdesk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility service that:
 *   - draws the software cursor as an accessibility overlay on the glasses display
 *   - injects tap / long-press / swipe into a chosen display id
 *   - performs global Home/Back/Recents
 */
public class XrInputService extends AccessibilityService {
    public static final String TAG = "XRDesk";

    private static volatile XrInputService instance;

    private WindowManager wm;
    private View cursorView;
    private WindowManager.LayoutParams cursorParams;
    private int cursorDisplayId = -1;

    public static XrInputService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hideCursor();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ---- gesture injection ---------------------------------------------------

    public boolean tap(int displayId, float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        return dispatch(displayId, new GestureDescription.StrokeDescription(p, 0, 60));
    }

    public boolean longPress(int displayId, float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        return dispatch(displayId, new GestureDescription.StrokeDescription(p, 0, 700));
    }

    public boolean swipe(int displayId, float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return dispatch(displayId, new GestureDescription.StrokeDescription(p, 0, ms));
    }

    private boolean dispatch(int displayId, GestureDescription.StrokeDescription stroke) {
        try {
            GestureDescription g = new GestureDescription.Builder()
                    .setDisplayId(displayId)
                    .addStroke(stroke)
                    .build();
            boolean ok = dispatchGesture(g, null, null);
            Log.i(TAG, "inject display=" + displayId + " ok=" + ok);
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "dispatchGesture failed: " + e);
            return false;
        }
    }

    public boolean globalHome() {
        return performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public boolean globalBack() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public boolean globalRecents() {
        return performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    // ---- cursor overlay ------------------------------------------------------

    public boolean showCursor(int displayId) {
        hideCursor();
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm.getDisplay(displayId);
            if (display == null) {
                Log.e(TAG, "showCursor: no display " + displayId);
                return false;
            }
            Context dc = createDisplayContext(display);
            wm = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);

            cursorView = new CursorView(dc);
            cursorParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            cursorParams.gravity = Gravity.TOP | Gravity.LEFT;
            wm.addView(cursorView, cursorParams);
            cursorDisplayId = displayId;
            Log.i(TAG, "cursor overlay added on display " + displayId);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "showCursor failed: " + e);
            wm = null;
            cursorView = null;
            return false;
        }
    }

    public void moveCursor(float x, float y) {
        if (wm == null || cursorView == null || cursorParams == null) return;
        cursorParams.x = (int) x;   // cursor hotspot is the view's top-left
        cursorParams.y = (int) y;
        try {
            wm.updateViewLayout(cursorView, cursorParams);
        } catch (Exception ignored) {
        }
    }

    public void hideCursor() {
        if (wm != null && cursorView != null) {
            try {
                wm.removeView(cursorView);
            } catch (Exception ignored) {
            }
        }
        wm = null;
        cursorView = null;
        cursorParams = null;
        cursorDisplayId = -1;
    }

    public int cursorDisplayId() {
        return cursorDisplayId;
    }

    /** Crosshair drawn at the view origin so (x,y) is the exact hotspot. */
    static class CursorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CursorView(Context c) {
            super(c);
            paint.setColor(Color.parseColor("#00E5FF"));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = 60f;
            canvas.drawLine(0f, 0f, s, 0f, paint);
            canvas.drawLine(0f, 0f, 0f, s, paint);
            canvas.drawCircle(0f, 0f, 14f, paint);
            canvas.drawLine(s - 10f, 0f, s, 0f, paint);
            canvas.drawLine(0f, s - 10f, 0f, s, paint);
        }
    }
}
