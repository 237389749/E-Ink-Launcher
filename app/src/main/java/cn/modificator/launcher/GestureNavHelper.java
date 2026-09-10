package cn.modificator.launcher;

import android.provider.Settings;

/**
 * Onyx/Poke6 手势导航切换。
 * Settings.Global["system_navigation_mode_key"] = "gesture" | "virtual_key"
 */
public class GestureNavHelper {

  private static final String KEY = "system_navigation_mode_key";
  private static final String MODE_GESTURE = "gesture";
  private static final String MODE_VIRTUAL_KEY = "virtual_key";

  private GestureNavHelper() {}

  public static boolean isGestureMode() {
    String mode = Settings.Global.getString(App.getInstance().getContentResolver(), KEY);
    return MODE_GESTURE.equals(mode);
  }

  public static boolean switchToGesture() {
    return setMode(MODE_GESTURE);
  }

  public static boolean switchToVirtualKey() {
    return setMode(MODE_VIRTUAL_KEY);
  }

  private static boolean setMode(String mode) {
    try {
      Settings.Global.putString(App.getInstance().getContentResolver(), KEY, mode);
      return true;
    } catch (SecurityException e) {
      String cmd = "settings put global " + KEY + " " + mode;
      return SuHelper.execOk(cmd)
          || exec(new String[]{"sh", "-c", cmd});
    }
  }

  private static boolean exec(String[] cmd) {
    try {
      Process p = Runtime.getRuntime().exec(cmd);
      consumeStream(p.getInputStream());
      consumeStream(p.getErrorStream());
      return p.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static void consumeStream(java.io.InputStream in) {
    try { byte[] b = new byte[1024]; while (in.read(b) != -1) {} } catch (Exception ignored) {}
  }
}
