package com.xrctrl;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.Presentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Locale;

/**
 * Phone-side driver for RayNeo Air 3s (TCL) over Android USB Host.
 *
 * The glasses expose a USB HID interface (VID 0x1bbb / PID 0xaf50) that carries a
 * 64-byte command/response protocol. Packet layout is taken from XRLinuxDriver:
 *   modules/rayneoSDKHeaders/device/usb/XRPacket.h
 *   modules/rayneoSDKHeaders/device/usb/XRDeviceState.h
 *
 * This app replicates EditUsbConnection / StartXR / SwitchTo2D / SwitchTo3D /
 * OpenIMU / AcquireDeviceInfo and reimplements the head tracker with a Madgwick
 * filter, then renders a status view on the glasses' external display.
 */
public class MainActivity extends Activity {
    private static final String TAG = "XRCtrl";
    private static final String ACTION_USB_PERMISSION = "com.xrctrl.USB_PERMISSION";

    // Measured on hardware: gyro reports deg/s (values in the tens..hundreds),
    // so convert to rad/s for the fusion filter.
    private static final float GYRO_SCALE = (float) (Math.PI / 180.0);
    private static final float MADGWICK_BETA = 0.08f;

    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface hidInterface;
    private UsbEndpoint epIn, epOut;
    private Thread reader;
    private volatile boolean running;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private TextView statusView;

    private Presentation presentation;
    private TextView glassesView;

    // ---- latest device state -------------------------------------------------
    private volatile XrProtocol.DeviceInfo deviceInfo;
    private volatile int packetCount;
    private volatile int sensorCount;

    private final float[] acc = new float[3];
    private final float[] gyro = new float[3];
    private final float[] mag = new float[3];
    private volatile float temperature, psensor, lsensor;

    private final MadgwickAHRS ahrs = new MadgwickAHRS(MADGWICK_BETA);
    private volatile float[] quat = new float[]{0f, 0f, 0f, 1f};
    private long lastImuNs;

    // Lets us drive the tool from adb without tapping the screen:
    //   adb shell am broadcast -a com.xrctrl.CMD --es cmd 3d
    //   cmd in {connect, info, imu_on, imu_off, 2d, 3d, sbs, glasses}
    private static final String ACTION_CMD = "com.xrctrl.CMD";

    private final BroadcastReceiver cmdReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String cmd = intent.getStringExtra("cmd");
            if (cmd == null) return;
            log("CMD " + cmd);
            if ("connect".equals(cmd)) connect();
            else if ("info".equals(cmd)) sendCommand(XrProtocol.CMD_DEVICE_INFO, 0);
            else if ("imu_on".equals(cmd)) sendCommand(XrProtocol.CMD_IMU_ON, 1);
            else if ("imu_off".equals(cmd)) sendCommand(XrProtocol.CMD_IMU_OFF, 0);
            else if ("2d".equals(cmd)) sendCommand(XrProtocol.CMD_DISPLAY_2D, 0);
            else if ("3d".equals(cmd)) sendCommand(XrProtocol.CMD_DISPLAY_3D, 0);
            else if ("sbs".equals(cmd)) sendCommand(XrProtocol.CMD_SBS_CHANGE, 0);
            else if ("glasses".equals(cmd)) showOnGlasses();
        }
    };

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            log("permission result granted=" + granted);
            if (granted && d != null) {
                device = d;
                openDevice(d);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(permReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permReceiver, f);
        }

        IntentFilter cf = new IntentFilter(ACTION_CMD);
        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(cmdReceiver, cf, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(cmdReceiver, cf);
        }

        log("Protocol: VID=0x" + Integer.toHexString(XrProtocol.VID)
                + " PID=0x" + Integer.toHexString(XrProtocol.PID));
        if (findDevice() != null) {
            log("RayNeo attached; auto-connecting...");
            ui.postDelayed(new Runnable() {
                public void run() { connect(); }
            }, 800);
        } else {
            log("Plug the glasses into the phone, then tap Connect.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        if (presentation != null) presentation.dismiss();
        try {
            if (connection != null) {
                if (hidInterface != null) connection.releaseInterface(hidInterface);
                connection.close();
            }
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(permReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(cmdReceiver);
        } catch (Exception ignored) {
        }
    }

    // ---- UI ------------------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(Color.WHITE);

        statusView = new TextView(this);
        statusView.setTextSize(14f);
        statusView.setTextColor(Color.BLACK);
        statusView.setText("status: idle");
        root.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(actButton("Connect", new Runnable() {
            public void run() { connect(); }
        }));
        row1.addView(cmdButton("Device Info", XrProtocol.CMD_DEVICE_INFO, 0));
        row1.addView(cmdButton("IMU On", XrProtocol.CMD_IMU_ON, 1));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(cmdButton("IMU Off", XrProtocol.CMD_IMU_OFF, 0));
        row2.addView(cmdButton("2D mirror", XrProtocol.CMD_DISPLAY_2D, 0));
        row2.addView(cmdButton("3D bino", XrProtocol.CMD_DISPLAY_3D, 0));
        row2.addView(cmdButton("SBS toggle", XrProtocol.CMD_SBS_CHANGE, 0));
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(actButton("Show on glasses", new Runnable() {
            public void run() { showOnGlasses(); }
        }));
        row3.addView(actButton("Recenter", new Runnable() {
            public void run() {
                ahrs.reset();
                log("AHRS recentered");
            }
        }));
        row3.addView(actButton("Clear log", new Runnable() {
            public void run() { logView.setText(""); }
        }));
        root.addView(row3);

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextColor(Color.DKGRAY);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // keep content clear of the status bar / cutout / nav bar
        final int base = 24;
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    v.setPadding(base + bars.left, base + bars.top,
                            base + bars.right, base + bars.bottom);
                    return insets;
                }
            });
        } else {
            root.setFitsSystemWindows(true);
        }

        setContentView(root);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Button cmdButton(String text, final int type, final int value) {
        return button(text, new View.OnClickListener() {
            public void onClick(View v) { sendCommand(type, value); }
        });
    }

    private Button actButton(String text, final Runnable action) {
        return button(text, new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
    }

    private void log(final String msg) {
        Log.i(TAG, msg);
        ui.post(new Runnable() {
            public void run() {
                if (logView != null) {
                    logView.append(msg + "\n");
                    int scroll = logView.getBottom();
                    logView.scrollTo(0, scroll);
                }
            }
        });
    }

    // ---- USB -----------------------------------------------------------------

    private UsbDevice findDevice() {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getVendorId() == XrProtocol.VID && d.getProductId() == XrProtocol.PID) {
                return d;
            }
        }
        return null;
    }

    private void connect() {
        device = findDevice();
        if (device == null) {
            log("RayNeo device not found on the USB bus.");
            return;
        }
        dumpDescriptors(device);
        if (!usbManager.hasPermission(device)) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            Intent permIntent = new Intent(ACTION_USB_PERMISSION);
            permIntent.setPackage(getPackageName());   // explicit: required with FLAG_MUTABLE
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, permIntent, flags);
            usbManager.requestPermission(device, pi);
            log("requesting USB permission...");
            return;
        }
        openDevice(device);
    }

    private void dumpDescriptors(UsbDevice d) {
        log("device " + d.getDeviceName() + " ifaces=" + d.getInterfaceCount());
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            log(String.format(Locale.US, "  iface[%d] id=%d class=%d sub=%d proto=%d eps=%d",
                    i, it.getId(), it.getInterfaceClass(), it.getInterfaceSubclass(),
                    it.getInterfaceProtocol(), it.getEndpointCount()));
            for (int e = 0; e < it.getEndpointCount(); e++) {
                UsbEndpoint ep = it.getEndpoint(e);
                log(String.format(Locale.US, "    ep addr=0x%02x dir=%s type=%d maxPacket=%d",
                        ep.getAddress(),
                        (ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT"),
                        ep.getType(), ep.getMaxPacketSize()));
            }
        }
    }

    private void openDevice(UsbDevice d) {
        connection = usbManager.openDevice(d);
        if (connection == null) {
            log("openDevice() returned null");
            return;
        }

        hidInterface = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            if (it.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                hidInterface = it;
                break;
            }
        }
        if (hidInterface == null) hidInterface = d.getInterface(0);

        if (!connection.claimInterface(hidInterface, true)) {
            log("claimInterface(force) failed on id=" + hidInterface.getId());
            return;
        }

        epIn = null;
        epOut = null;
        for (int e = 0; e < hidInterface.getEndpointCount(); e++) {
            UsbEndpoint ep = hidInterface.getEndpoint(e);
            int t = ep.getType();
            if (t != UsbConstants.USB_ENDPOINT_XFER_BULK
                    && t != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                if (epIn == null) epIn = ep;
            } else {
                if (epOut == null) epOut = ep;
            }
        }
        log(String.format(Locale.US, "claimed iface id=%d, epIn=%s epOut=%s",
                hidInterface.getId(),
                epIn == null ? "none" : String.format("0x%02x", epIn.getAddress()),
                epOut == null ? "none" : String.format("0x%02x", epOut.getAddress())));

        if (epIn == null) {
            log("no IN endpoint; cannot read sensor data");
            return;
        }

        running = true;
        reader = new Thread(new Runnable() {
            public void run() { readLoop(); }
        }, "xr-reader");
        reader.start();
        log("connected. Sending DeviceInfo + IMU On.");
        sendCommand(XrProtocol.CMD_DEVICE_INFO, 0);
        sendCommand(XrProtocol.CMD_IMU_ON, 1);
    }

    private synchronized void sendCommand(int type, int value) {
        if (connection == null) {
            log("not connected");
            return;
        }
        byte[] cmd = XrProtocol.buildCommand(type, value);
        int n = -1;
        if (epOut != null) {
            n = connection.bulkTransfer(epOut, cmd, cmd.length, 500);
        }
        if (n < 0) {
            // fall back to a HID SET_REPORT control transfer
            n = connection.controlTransfer(0x21, 0x09, 0x0200, hidInterface.getId(),
                    cmd, cmd.length, 500);
        }
        log(String.format(Locale.US, "TX type=0x%02x value=%d -> %d", type, value, n));

        // after a display-mode change, ask the glasses to report the new state
        if (type == XrProtocol.CMD_DISPLAY_2D || type == XrProtocol.CMD_DISPLAY_3D
                || type == XrProtocol.CMD_SBS_CHANGE) {
            ui.postDelayed(new Runnable() {
                public void run() { sendCommand(XrProtocol.CMD_DEVICE_INFO, 0); }
            }, 600);
        }
    }

    private void readLoop() {
        byte[] buf = new byte[XrProtocol.REPORT_SIZE];
        while (running) {
            int n;
            try {
                n = connection.bulkTransfer(epIn, buf, buf.length, 500);
            } catch (Exception ex) {
                log("read error: " + ex);
                break;
            }
            if (n > 0) {
                byte[] copy = Arrays.copyOf(buf, n);
                try {
                    handlePacket(copy, n);
                } catch (Exception ex) {
                    log("parse error: " + ex);
                }
            }
        }
        log("reader stopped");
    }

    // ---- protocol handling ---------------------------------------------------

    private void handlePacket(byte[] b, int n) {
        packetCount++;
        int magic = XrProtocol.u8(b, 0);
        if (magic != XrProtocol.MAGIC_ACK) {
            log("RX non-ack magic=0x" + Integer.toHexString(magic)
                    + " : " + XrProtocol.hex(b, Math.min(n, 16)));
            return;
        }
        int type = XrProtocol.u8(b, 1);

        if (type == XrProtocol.ACK_IMU_DATA) {
            handleSensor(XrProtocol.parseSensor(b));
        } else if (type == XrProtocol.CMD_DEVICE_INFO || type == XrProtocol.ACK_COMMAND) {
            XrProtocol.DeviceInfo di = XrProtocol.parseDeviceInfo(b);
            if (di != null) {
                deviceInfo = di;
                log(String.format(Locale.US,
                        "DEVICE_INFO board=0x%02x sbs=%d sensor_on=%d fps=%d lum=%d/%d vol=%d/%d dp=%d "
                                + "psValid=%d lsValid=%d gyroValid=%d magValid=%d",
                        di.boardId, di.sideBySide, di.sensorOn, di.glassesFps,
                        di.luminance, di.maxLuminance, di.volume, di.maxVolume, di.dpStatus,
                        di.psensorValid, di.lsensorValid, di.gyroValid, di.magnetValid));
            } else {
                log("RX len<64 type=0x" + Integer.toHexString(type));
            }
        } else {
            log("RX type=0x" + Integer.toHexString(type) + " : " + XrProtocol.hex(b, Math.min(n, 32)));
        }
    }

    private void handleSensor(XrProtocol.Sensor s) {
        if (s == null) return;
        sensorCount++;
        System.arraycopy(s.acc, 0, acc, 0, 3);
        System.arraycopy(s.gyro, 0, gyro, 0, 3);
        System.arraycopy(s.mag, 0, mag, 0, 3);
        temperature = s.temperature;
        psensor = s.psensor;
        lsensor = s.lsensor;

        long now = System.nanoTime();
        if (lastImuNs != 0) {
            float dt = (now - lastImuNs) / 1e9f;
            if (dt > 0f && dt < 0.1f) {
                ahrs.update(
                        gyro[0] * GYRO_SCALE, gyro[1] * GYRO_SCALE, gyro[2] * GYRO_SCALE,
                        acc[0], acc[1], acc[2],
                        mag[0], mag[1], mag[2],
                        dt);
                quat = ahrs.getQuaternion();
            }
        }
        lastImuNs = now;

        if (sensorCount % 50 == 0) {
            float[] q = quat;
            log(String.format(Locale.US,
                    "IMU#%d acc=[%+.2f %+.2f %+.2f] gyro=[%+.1f %+.1f %+.1f] "
                            + "mag=[%+.1f %+.1f %+.1f] t=%.1f ps=%.1f ls=%.1f "
                            + "quat=[%+.3f %+.3f %+.3f %+.3f]",
                    sensorCount, acc[0], acc[1], acc[2], gyro[0], gyro[1], gyro[2],
                    mag[0], mag[1], mag[2], temperature, psensor, lsensor,
                    q[0], q[1], q[2], q[3]));
        }

        ui.post(new Runnable() {
            public void run() { refreshStatus(); }
        });
    }

    private void refreshStatus() {
        XrProtocol.DeviceInfo di = deviceInfo;
        String sbs = (di == null) ? "?" : String.valueOf(di.sideBySide);
        statusView.setText(String.format(Locale.US,
                "packets=%d sensors=%d\nmode(SBS)=%s  q=[%+.3f %+.3f %+.3f %+.3f]",
                packetCount, sensorCount, sbs, quat[0], quat[1], quat[2], quat[3]));
        if (glassesView != null) {
            glassesView.setText(String.format(Locale.US,
                    "XR Ctrl\n\npackets: %d\nsensors: %d\nSBS: %s\n\n"
                            + "acc  %+7.2f %+7.2f %+7.2f\n"
                            + "gyro %+7.1f %+7.1f %+7.1f\n"
                            + "mag  %+7.1f %+7.1f %+7.1f\n"
                            + "quat %+.3f %+.3f %+.3f %+.3f",
                    packetCount, sensorCount, sbs,
                    acc[0], acc[1], acc[2], gyro[0], gyro[1], gyro[2],
                    mag[0], mag[1], mag[2], quat[0], quat[1], quat[2], quat[3]));
        }
    }

    // ---- external display (glasses) -----------------------------------------

    private void showOnGlasses() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display target = null;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            boolean presentation = (d.getFlags() & Display.FLAG_PRESENTATION) != 0;
            String name = d.getName();
            if (presentation || (name != null && name.contains("SmartGlasses"))
                    || (name != null && name.contains("HDMI"))) {
                target = d;
                break;
            }
        }
        if (target == null) {
            log("no external display found (is the glasses' DP output active?)");
            return;
        }
        log("showing on display " + target.getDisplayId() + " (" + target.getName() + ")");
        presentation = new XrPresentation(this, target);
        presentation.show();
    }

    private class XrPresentation extends Presentation {
        XrPresentation(Context outer, Display display) {
            super(outer, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            glassesView = new TextView(getContext());
            glassesView.setTextSize(28f);
            glassesView.setGravity(Gravity.CENTER);
            glassesView.setTextColor(Color.WHITE);
            glassesView.setBackgroundColor(Color.BLACK);
            glassesView.setText("XR Ctrl\nwaiting for data...");
            setContentView(glassesView);
            refreshStatus();
        }

        @Override
        protected void onStop() {
            super.onStop();
            glassesView = null;
        }
    }
}
