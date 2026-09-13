package com.xrdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * An in-memory 1080x1920 screen (VirtualDisplay -> ImageReader). A desktop activity
 * is launched onto it; we then grab each frame and render it onto the glasses.
 */
public class VirtualScreen {
    public static final int W = 1080;
    public static final int H = 1920;

    private VirtualDisplay display;
    private ImageReader reader;

    public void create(Context ctx, Handler handler) {
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        display = dm.createVirtualDisplay(
                "xrdesk", W, H, 320,
                reader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                null, handler);
        Log.i(XrInputService.TAG, "virtual display id=" + getDisplayId());
    }

    public int getDisplayId() {
        if (display == null || display.getDisplay() == null) return -1;
        return display.getDisplay().getDisplayId();
    }

    public ImageReader getReader() {
        return reader;
    }

    public Surface getSurface() {
        return reader == null ? null : reader.getSurface();
    }

    public void release() {
        if (display != null) {
            display.release();
            display = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    public static Bitmap toBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap padded = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);

        if (rowPadding == 0) {
            return padded;
        }
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return cropped;
    }
}
