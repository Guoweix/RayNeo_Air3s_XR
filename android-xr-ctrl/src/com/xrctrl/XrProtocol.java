package com.xrctrl;

// RayNeo Air 3s USB HID protocol, derived from XRLinuxDriver headers:
//   modules/rayneoSDKHeaders/device/usb/XRPacket.h
//   modules/rayneoSDKHeaders/device/usb/XRDeviceState.h
final class XrProtocol {
    private XrProtocol() {}

    static final int VID = 0x1bbb;
    static final int PID = 0xaf50;
    static final int REPORT_SIZE = 64;

    static final int MAGIC_SEND = 0x66;
    static final int MAGIC_ACK  = 0x99;

    // enum FXRUsbCommand
    static final int CMD_DEVICE_INFO = 0x00;
    static final int CMD_IMU_ON      = 0x01;
    static final int CMD_IMU_OFF     = 0x02;
    static final int CMD_DISPLAY_3D  = 0x06;
    static final int CMD_DISPLAY_2D  = 0x07;
    static final int CMD_SBS_CHANGE  = 0x30;
    static final int ACK_IMU_DATA    = 0x65;
    static final int ACK_COMMAND     = 0xC8;

    static byte[] buildCommand(int type, int value) {
        byte[] b = new byte[REPORT_SIZE];
        b[0] = (byte) MAGIC_SEND;
        b[1] = (byte) type;
        b[2] = (byte) value;
        return b;
    }

    static int u8(byte[] b, int i) { return b[i] & 0xff; }

    static long u32le(byte[] b, int i) {
        return (b[i] & 0xffL) | ((b[i + 1] & 0xffL) << 8)
                | ((b[i + 2] & 0xffL) << 16) | ((b[i + 3] & 0xffL) << 24);
    }

    static float f32(byte[] b, int i) {
        return Float.intBitsToFloat((int) u32le(b, i));
    }

    static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < b.length; i++) {
            sb.append(String.format("%02x ", b[i] & 0xff));
            if ((i + 1) % 16 == 0 && i + 1 < n) sb.append('\n');
        }
        return sb.toString();
    }

    // ---- XrHidSensorEvent (XRPacket.h) --------------------------------
    // head(4) acc[3](12) gyro[3](12) temperature(4) magnet[2](8) tick(4)
    // psensor(4) lsensor(4) magnet_2(4) end(8)
    static final class Sensor {
        final float[] acc = new float[3];
        final float[] gyro = new float[3];
        float temperature;
        final float[] mag = new float[3];
        long tick;
        float psensor;
        float lsensor;
    }

    static Sensor parseSensor(byte[] b) {
        if (b.length < 56) return null;
        Sensor s = new Sensor();
        for (int i = 0; i < 3; i++) s.acc[i] = f32(b, 4 + i * 4);
        for (int i = 0; i < 3; i++) s.gyro[i] = f32(b, 16 + i * 4);
        s.temperature = f32(b, 28);
        s.mag[0] = f32(b, 32);
        s.mag[1] = f32(b, 36);
        s.tick = u32le(b, 40);
        s.psensor = f32(b, 44);
        s.lsensor = f32(b, 48);
        s.mag[2] = f32(b, 52);
        return s;
    }

    // ---- XrHidDeviceInfo (XRPacket.h) ---------------------------------
    static final class DeviceInfo {
        long tick;
        int boardId;
        int sensorOn;
        int glassesFps;
        int luminance;
        int volume;
        int sideBySide;   // 1 = side-by-side (3D), 0 = mirror (2D)
        int psensorEnable;
        int audioMode;
        int dpStatus;
        int psensorValid;
        int lsensorValid;
        int gyroValid;
        int magnetValid;
        int maxLuminance;
        int maxVolume;
    }

    static DeviceInfo parseDeviceInfo(byte[] b) {
        if (b.length < 64) return null;
        DeviceInfo d = new DeviceInfo();
        d.tick = u32le(b, 4);
        d.boardId = u8(b, 21);
        d.sensorOn = u8(b, 22);
        d.glassesFps = u8(b, 40);
        d.luminance = u8(b, 41);
        d.volume = u8(b, 42);
        d.sideBySide = u8(b, 43);
        d.psensorEnable = u8(b, 44);
        d.audioMode = u8(b, 45);
        d.dpStatus = u8(b, 46);
        d.psensorValid = u8(b, 48);
        d.lsensorValid = u8(b, 49);
        d.gyroValid = u8(b, 50);
        d.magnetValid = u8(b, 51);
        d.maxLuminance = u8(b, 60);
        d.maxVolume = u8(b, 61);
        return d;
    }
}
