package com.xrdesk;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Display;
import android.view.View;

/**
 * Rendered on the glasses' external display: draws the captured virtual screen
 * scaled to fit, plus our own software cursor.
 */
public class GlassesPresentation extends Presentation {

    private ScreenView view;

    public GlassesPresentation(Context outer, Display display) {
        super(outer, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new ScreenView(getContext());
        setContentView(view);
    }

    public ScreenView screenView() {
        return view;
    }

    /** Draws the virtual-screen frame and the cursor. */
    public static class ScreenView extends View {
        private Bitmap frame;
        private float cursorX = VirtualScreen.W / 2f;
        private float cursorY = VirtualScreen.H / 2f;

        private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();

        public float scale = 1f;
        public float offX = 0f;
        public float offY = 0f;

        public ScreenView(Context c) {
            super(c);
            cursorPaint.setColor(Color.parseColor("#00E5FF"));
            cursorPaint.setStyle(Paint.Style.STROKE);
            cursorPaint.setStrokeWidth(5f);
            textPaint.setColor(Color.parseColor("#00E5FF"));
            textPaint.setTextSize(28f);
        }

        public void setFrame(Bitmap b) {
            Bitmap old = frame;
            frame = b;
            invalidate();
            if (old != null && old != b && !old.isRecycled()) old.recycle();
        }

        public void setCursor(float x, float y) {
            cursorX = x;
            cursorY = y;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;

            scale = Math.min(w / (float) VirtualScreen.W, h / (float) VirtualScreen.H);
            float dw = VirtualScreen.W * scale;
            float dh = VirtualScreen.H * scale;
            offX = (w - dw) / 2f;
            offY = (h - dh) / 2f;
            dst.set(offX, offY, offX + dw, offY + dh);

            if (frame != null && !frame.isRecycled()) {
                canvas.drawBitmap(frame, null, dst, framePaint);
            }

            float cx = offX + cursorX * scale;
            float cy = offY + cursorY * scale;
            canvas.drawCircle(cx, cy, 16f, cursorPaint);
            canvas.drawLine(cx - 26f, cy, cx + 26f, cy, cursorPaint);
            canvas.drawLine(cx, cy - 26f, cx, cy + 26f, cursorPaint);
            canvas.drawText(String.format("(%.0f, %.0f)", cursorX, cursorY),
                    cx + 24f, cy - 24f, textPaint);
        }

        /** view pixel -> logical virtual-screen coordinate. */
        public float toLogicalX(float viewX) {
            if (scale <= 0f) return 0f;
            return (viewX - offX) / scale;
        }

        public float toLogicalY(float viewY) {
            if (scale <= 0f) return 0f;
            return (viewY - offY) / scale;
        }
    }
}
