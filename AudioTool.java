import java.lang.reflect.Method;

public class AudioTool {
    public static void main(String[] args) throws Exception {
        String action = args.length > 0 ? args[0] : "status";

        // Setup Android environment
        Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null);
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Object at = atClass.getMethod("systemMain").invoke(null);
        Object ctx = atClass.getMethod("getSystemContext").invoke(at);
        Object am = ctx.getClass().getMethod("getSystemService", String.class).invoke(ctx, "audio");

        switch (action) {
            case "break":
                am.getClass().getMethod("setSpeakerphoneOn", boolean.class).invoke(am, true);
                System.out.println("DONE: setSpeakerphoneOn(true) - simulated bug");
                System.out.println("Keeping process alive (Ctrl+C to release)...");
                Thread.sleep(Long.MAX_VALUE);
                break;

            case "fix":
                am.getClass().getMethod("setSpeakerphoneOn", boolean.class).invoke(am, false);
                System.out.println("setSpeakerphoneOn(false) called");
                trySetCommunicationDevice(am);
                System.out.println("Fix applied. Keeping process alive (Ctrl+C to release)...");
                Thread.sleep(Long.MAX_VALUE);
                break;

            case "clear":
                try {
                    am.getClass().getMethod("clearCommunicationDevice").invoke(am);
                    System.out.println("clearCommunicationDevice() called");
                } catch (Exception e) {
                    System.out.println("clearCommunicationDevice failed: " + e.getMessage());
                }
                break;

            case "status":
            default:
                boolean speakerOn = (boolean) am.getClass().getMethod("isSpeakerphoneOn").invoke(am);
                System.out.println("isSpeakerphoneOn: " + speakerOn);
                listDevices(am);
                showCommunicationDevice(am);
                break;
        }
    }

    static void trySetCommunicationDevice(Object am) throws Exception {
        Class<?> deviceClass = Class.forName("android.media.AudioDeviceInfo");
        Object[] devices = (Object[]) am.getClass().getMethod("getDevices", int.class).invoke(am, 2);

        Method getType = deviceClass.getMethod("getType");
        Method getName = deviceClass.getMethod("getProductName");

        try {
            Method setComm = am.getClass().getMethod("setCommunicationDevice", deviceClass);
            for (Object device : devices) {
                int type = (int) getType.invoke(device);
                // TYPE_USB_HEADSET=22, TYPE_WIRED_HEADSET=3, TYPE_BLUETOOTH_A2DP=8, TYPE_BLE_HEADSET=26
                if (type == 22 || type == 3 || type == 8 || type == 26) {
                    Object result = setComm.invoke(am, device);
                    System.out.println("setCommunicationDevice type=" + type
                            + " name=" + getName.invoke(device)
                            + " result=" + result);
                    return;
                }
            }
            System.out.println("No headset device found in output devices");
        } catch (NoSuchMethodException e) {
            System.out.println("setCommunicationDevice API not available");
        }
    }

    static void showCommunicationDevice(Object am) {
        try {
            Class<?> deviceClass = Class.forName("android.media.AudioDeviceInfo");
            Method getComm = am.getClass().getMethod("getCommunicationDevice");
            Object device = getComm.invoke(am);
            if (device != null) {
                int type = (int) deviceClass.getMethod("getType").invoke(device);
                Object name = deviceClass.getMethod("getProductName").invoke(device);
                System.out.println("CommunicationDevice: type=" + type + " name=" + name);
            } else {
                System.out.println("CommunicationDevice: null");
            }
        } catch (Exception e) {
            System.out.println("getCommunicationDevice failed: " + e.getMessage());
        }
    }

    static void listDevices(Object am) throws Exception {
        Class<?> deviceClass = Class.forName("android.media.AudioDeviceInfo");
        Object[] devices = (Object[]) am.getClass().getMethod("getDevices", int.class).invoke(am, 2);
        Method getType = deviceClass.getMethod("getType");
        Method getName = deviceClass.getMethod("getProductName");
        Method getId = deviceClass.getMethod("getId");

        System.out.println("Output devices:");
        for (Object device : devices) {
            System.out.println("  type=" + getType.invoke(device)
                    + " id=" + getId.invoke(device)
                    + " name=" + getName.invoke(device));
        }
    }
}
