package com.xrdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedList;

/**
 * Phone side = touchpad + control panel.
 *
 * Glasses side = GlassesPresentation rendering an in-memory 1080x1920 VirtualDisplay
 * that hosts TestDesktopActivity, plus our software cursor. Clicks are injected into
 * the virtual display through XrInputService (AccessibilityService + setDisplayId).
 *
 * Touchpad gestures (phone):
 *   1 finger drag  -> move cursor
 *   1 finger tap   -> click
 *   1 finger hold  -> long press
 *   2 finger tap   -> Back (in the app on the glasses)
 *   2 finger up    -> Home (XR desktop / app grid)
 *   2 finger down  -> switch to the previous app (MRU)
 *   3 finger swipe -> cycle through launched apps
 *
 * Can be driven from adb for verification:
 *   adb shell am broadcast -a com.xrdesk.CMD --es cmd move --ei dx 40 --ei dy -30
 *   adb shell am broadcast -a com.xrdesk.CMD --es cmd click
 *   adb shell am broadcast -a com.xrdesk.CMD --es cmd start
 *   adb shell am broadcast -a com.xrdesk.CMD --es cmd back
 */
public class MainActivity extends Activity {
    private static final String TAG = XrInputService.TAG;
    private static final String ACTION_CMD = "com.xrdesk.CMD";
    private static final float SENSITIVITY = 1.8f;

    private VirtualScreen screen;
    private GlassesPresentation presentation;
    private HandlerThread frameThread;
    private Handler frameHandler;
    private int targetDisplayId = -1;
    private int dispW = 1920;
    private int dispH = 1080;

    private float cursorX = VirtualScreen.W / 2f;
    private float cursorY = VirtualScreen.H / 2f;

    private float lastX, lastY, downX, downY;
    private long downTime;
    private boolean moved;

    // multi-finger gesture bookkeeping
    private int maxPointers;
    private float gestureStartX, gestureStartY;
    private boolean gestureSwiped;
    private final float[] lastCentroid = new float[2];

    // most-recently-used app packages, shared with DesktopActivity (same process)
    static final LinkedList<String> MRU = new LinkedList<>();

    private TextView statusView;
    private View touchpad;

    private final Handler ui = new Handler();

    private final ImageReader.OnImageAvailableListener frameListener =
            new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader r) {
                    Image img;
                    try {
                        img = r.acquireLatestImage();
                    } catch (Exception e) {
                        return;
                    }
                    if (img == null) return;
                    final Bitmap bmp = VirtualScreen.toBitmap(img);
                    img.close();
                    if (presentation == null) {
                        bmp.recycle();
                        return;
                    }
                    final GlassesPresentation.ScreenView v = presentation.screenView();
                    if (v == null) {
                        bmp.recycle();
                        return;
                    }
                    v.post(new Runnable() {
                        public void run() {
                            v.setFrame(bmp);
                        }
                    });
                }
            };

    private final BroadcastReceiver cmdReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String cmd = intent.getStringExtra("cmd");
            if (cmd == null) return;
            Log.i(TAG, "CMD " + cmd);
            if ("start".equals(cmd)) startDesk();
            else if ("stop".equals(cmd)) stopDesk();
            else if ("desk".equals(cmd)) bringDesktop();
            else if ("click".equals(cmd)) doClick();
            else if ("long".equals(cmd)) doLongPress();
            else if ("home".equals(cmd)) bringDesktop();
            else if ("back".equals(cmd)) handleBack();
            else if ("recents".equals(cmd)) global(2);
            else if ("prev".equals(cmd)) cycleApp(-1);
            else if ("next".equals(cmd)) cycleApp(1);
            else if ("move".equals(cmd)) moveBy(
                    intent.getIntExtra("dx", 0), intent.getIntExtra("dy", 0));
            else if ("cursor".equals(cmd)) setCursor(
                    intent.getIntExtra("x", (int) cursorX),
                    intent.getIntExtra("y", (int) cursorY));
            else if ("launch".equals(cmd)) launchApp(intent.getStringExtra("pkg"));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        IntentFilter f = new IntentFilter(ACTION_CMD);
        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(cmdReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(cmdReceiver, f);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(cmdReceiver);
        } catch (Exception ignored) {
        }
        stopDesk();
    }

    // ---- UI ------------------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(actButton("启动", new Runnable() {
            public void run() { startDesk(); }
        }));
        row1.addView(actButton("停止", new Runnable() {
            public void run() { stopDesk(); }
        }));
        row1.addView(actButton("无障碍", new Runnable() {
            public void run() {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(actButton("桌面", new Runnable() {
            public void run() { bringDesktop(); }
        }));
        row2.addView(actButton("Back", new Runnable() {
            public void run() { handleBack(); }
        }));
        row2.addView(actButton("点击", new Runnable() {
            public void run() { doClick(); }
        }));
        row2.addView(actButton("长按", new Runnable() {
            public void run() { doLongPress(); }
        }));
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(actButton("列表上翻", new Runnable() {
            public void run() { scroll(true); }
        }));
        row3.addView(actButton("列表下翻", new Runnable() {
            public void run() { scroll(false); }
        }));
        row3.addView(actButton("切换应用", new Runnable() {
            public void run() { cycleApp(-1); }
        }));
        root.addView(row3);

        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setTextColor(Color.DKGRAY);
        statusView.setPadding(16, 16, 16, 16);
        statusView.setText("未启动。先开无障碍，再点“启动”。\n触摸板：单指点击/拖动，双指轻点=返回，双指上滑=桌面，双指下滑/三指滑=切应用。");
        root.addView(statusView);

        touchpad = new View(this);
        touchpad.setBackgroundColor(Color.parseColor("#1A1F26"));
        touchpad.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                return onPadTouch(e);
            }
        });
        root.addView(touchpad, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private Button actButton(String text, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    private boolean onPadTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(0);
                lastY = e.getY(0);
                downX = lastX;
                downY = lastY;
                downTime = SystemClock.uptimeMillis();
                moved = false;
                gestureSwiped = false;
                maxPointers = 1;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (maxPointers < 2) {
                    centroid(e, lastCentroid);
                    gestureStartX = lastCentroid[0];
                    gestureStartY = lastCentroid[1];
                }
                maxPointers = Math.max(maxPointers, e.getPointerCount());
                moved = true;                    // never treat multi-touch as a click
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (e.getPointerCount() >= 2) {
                    centroid(e, lastCentroid);
                    float gx = lastCentroid[0] - gestureStartX;
                    float gy = lastCentroid[1] - gestureStartY;
                    if (Math.abs(gx) + Math.abs(gy) > 40f) gestureSwiped = true;
                    moved = true;
                    return true;
                }
                float dx = e.getX(0) - lastX;
                float dy = e.getY(0) - lastY;
                lastX = e.getX(0);
                lastY = e.getY(0);
                if (maxPointers == 1
                        && Math.abs(e.getX(0) - downX) + Math.abs(e.getY(0) - downY) > 24) {
                    moved = true;
                }
                if (maxPointers == 1) {
                    moveBy(dx * SENSITIVITY, dy * SENSITIVITY);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                // Snap the end point to the centroid of the remaining pointers.
                centroidExcept(e, e.getActionIndex(), lastCentroid);
                return true;
            case MotionEvent.ACTION_UP:
                if (maxPointers >= 2) {
                    if (gestureSwiped) handleMultiGesture(maxPointers);
                    else handleMultiTap(maxPointers);
                } else if (!moved && SystemClock.uptimeMillis() - downTime < 400) {
                    doClick();
                }
                return true;
            default:
                return true;
        }
    }

    private static void centroid(MotionEvent e, float[] out) {
        float sx = 0f, sy = 0f;
        int n = e.getPointerCount();
        for (int i = 0; i < n; i++) {
            sx += e.getX(i);
            sy += e.getY(i);
        }
        out[0] = sx / n;
        out[1] = sy / n;
    }

    private static void centroidExcept(MotionEvent e, int skip, float[] out) {
        float sx = 0f, sy = 0f;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            sx += e.getX(i);
            sy += e.getY(i);
            n++;
        }
        if (n == 0) return;
        out[0] = sx / n;
        out[1] = sy / n;
    }

    /** A multi-finger gesture that moved. lastCentroid holds the last movement point. */
    private void handleMultiGesture(int fingers) {
        float dx = lastCentroid[0] - gestureStartX;
        float dy = lastCentroid[1] - gestureStartY;
        float ax = Math.abs(dx), ay = Math.abs(dy);

        if (fingers >= 3) {
            cycleApp(dx < 0 ? -1 : 1);       // 3-finger swipe left/right = cycle apps
            return;
        }
        // two fingers
        if (ay >= ax) {
            if (dy < 0) bringDesktop();      // up = Home / XR desktop
            else cycleApp(-1);               // down = previous app
        } else {
            if (dx < 0) handleBack();        // left = Back
            else bringDesktop();             // right = Home
        }
    }

    /** A multi-finger tap (no movement). */
    private void handleMultiTap(int fingers) {
        if (fingers >= 3) {
            status("三指轻点");
        } else {
            handleBack();                    // 2-finger tap = Back
        }
    }

    // ---- actions -------------------------------------------------------------

    private void startDesk() {
        if (screen != null) {
            status("已在运行 (virtual display=" + screen.getDisplayId() + ")");
            return;
        }
        Display glasses = findGlasses();
        if (glasses == null) {
            status("未找到眼镜外接屏（DP 没连上？）");
            return;
        }
        targetDisplayId = glasses.getDisplayId();

        android.graphics.Point sz = new android.graphics.Point();
        glasses.getRealSize(sz);
        dispW = sz.x;
        dispH = sz.y;
        cursorX = dispW / 2f;
        cursorY = dispH / 2f;

        bringDesktop();
        updateCursor();
        status("已启动到眼镜屏 display=" + targetDisplayId + " " + dispW + "x" + dispH
                + "  accessibility=" + (XrInputService.get() != null));
    }

    private void stopDesk() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        if (screen != null) {
            screen.release();
            screen = null;
        }
        if (frameThread != null) {
            frameThread.quitSafely();
            frameThread = null;
            frameHandler = null;
        }
    }

    private void doClick() {
        XrInputService svc = XrInputService.get();
        if (svc == null) {
            status("无障碍服务未开启");
            return;
        }
        if (targetDisplayId < 0) {
            status("请先点“启动”");
            return;
        }
        boolean ok = svc.tap(targetDisplayId, cursorX, cursorY);
        status("tap display=" + targetDisplayId
                + " (" + (int) cursorX + "," + (int) cursorY + ") -> " + ok);
    }

    private void doLongPress() {
        XrInputService svc = XrInputService.get();
        if (svc == null || targetDisplayId < 0) {
            status("无障碍未开启或未启动");
            return;
        }
        boolean ok = svc.longPress(targetDisplayId, cursorX, cursorY);
        status("longPress -> " + ok);
    }

    private void bringDesktop() {
        if (targetDisplayId < 0) {
            status("请先点“启动”");
            return;
        }
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(targetDisplayId);
            Intent i = new Intent(this, DesktopActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i, opts.toBundle());
        } catch (Exception e) {
            Log.e(TAG, "bringDesktop failed", e);
            status("显示桌面失败: " + e.getMessage());
        }
    }

    private void launchApp(String pkg) {
        if (pkg == null) return;
        if (targetDisplayId < 0) {
            status("请先点“启动”");
            return;
        }
        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) {
            status("找不到 " + pkg + " 的启动入口");
            return;
        }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(targetDisplayId);
        try {
            startActivity(li, opts.toBundle());
            noteAppLaunched(pkg);
            status("已把 " + pkg + " 启动到 display=" + targetDisplayId);
        } catch (Exception e) {
            Log.e(TAG, "launchApp failed", e);
            status("启动 " + pkg + " 到眼镜屏失败: " + e.getMessage());
        }
    }

    /** Records an app launch so the touchpad can cycle back to it. Same process as DesktopActivity. */
    static void noteAppLaunched(String pkg) {
        if (pkg == null) return;
        synchronized (MRU) {
            MRU.remove(pkg);
            MRU.addFirst(pkg);
            while (MRU.size() > 10) MRU.removeLast();
        }
    }

    /** Cycle to the previous (dir<0) or next (dir>0) launched app on the glasses. */
    private void cycleApp(int dir) {
        if (targetDisplayId < 0) {
            status("请先点“启动”");
            return;
        }
        String pkg;
        synchronized (MRU) {
            if (MRU.size() < 2) {
                status("没有其他已启动应用，回到桌面");
                bringDesktop();
                return;
            }
            pkg = dir < 0 ? MRU.get(1) : MRU.get(MRU.size() - 1);
        }
        launchApp(pkg);
    }

    /** In-app Back on the glasses: display-targeted injection, not the phone's global back. */
    private void handleBack() {
        XrInputService svc = XrInputService.get();
        if (svc == null) {
            status("无障碍服务未开启");
            return;
        }
        if (targetDisplayId < 0) {
            status("请先点“启动”");
            return;
        }
        if (targetDisplayId == Display.DEFAULT_DISPLAY) {
            status("back(default) -> " + svc.globalBack());
            return;
        }
        status("back(display=" + targetDisplayId + ") -> "
                + svc.back(targetDisplayId, dispW, dispH));
    }

    private void scroll(boolean up) {
        XrInputService svc = XrInputService.get();
        if (svc == null || targetDisplayId < 0) {
            status("无障碍未开启或未启动");
            return;
        }
        float cx = dispW / 2f;
        float y1 = up ? dispH * 0.3f : dispH * 0.7f;
        float y2 = up ? dispH * 0.7f : dispH * 0.3f;
        boolean ok = svc.swipe(targetDisplayId, cx, y1, cx, y2, 300);
        status("scroll " + (up ? "up" : "down") + " -> " + ok);
    }

    private void global(int which) {
        XrInputService svc = XrInputService.get();
        if (svc == null) {
            status("无障碍服务未开启");
            return;
        }
        boolean ok = which == 0 ? svc.globalHome() : which == 1 ? svc.globalBack()
                : svc.globalRecents();
        status("global(" + which + ") -> " + ok);
    }

    private void moveBy(float dx, float dy) {
        cursorX = clamp(cursorX + dx, 0, dispW);
        cursorY = clamp(cursorY + dy, 0, dispH);
        updateCursor();
    }

    private void setCursor(float x, float y) {
        cursorX = clamp(x, 0, dispW);
        cursorY = clamp(y, 0, dispH);
        updateCursor();
    }

    private void updateCursor() {
        XrInputService svc = XrInputService.get();
        if (svc == null || targetDisplayId < 0) return;
        if (svc.cursorDisplayId() != targetDisplayId) {
            svc.showCursor(targetDisplayId);
        }
        svc.moveCursor(cursorX, cursorY);
    }

    private void status(final String msg) {
        Log.i(TAG, msg);
        ui.post(new Runnable() {
            public void run() {
                statusView.setText(msg
                        + "\n光标 (" + (int) cursorX + "," + (int) cursorY + ")");
            }
        });
    }

    private Display findGlasses() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            if ((d.getFlags() & Display.FLAG_PRESENTATION) != 0) return d;
        }
        return null;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
