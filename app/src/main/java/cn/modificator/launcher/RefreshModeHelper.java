package cn.modificator.launcher;

/**
 * 墨水屏刷新工具方法（反射调用 Onyx SDK）。
 */
public class RefreshModeHelper {

  private RefreshModeHelper() {}

  public static void refreshGU() {
    refreshByName("GU");
  }

  public static void refreshGC() {
    refreshByName("GC");
  }

  private static void refreshByName(String name) {
    try {
      Class<?> ec = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController");
      Class<?> mc = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode");
      Object mode = Enum.valueOf((Class<Enum>) mc, name);
      ec.getMethod("repaintEveryThing", mc).invoke(null, mode);
    } catch (Exception ignored) {
    }
  }

  /** Root 启用/禁用 AccessibilityService */
  public static void setAccessibilityEnabled(boolean enable) {
    String svc = "cn.modificator.launcher/.RefreshAccessibilityService";
    String cmd;
    if (enable) {
      cmd = "settings put secure enabled_accessibility_services " + svc
          + " && settings put secure accessibility_enabled 1";
    } else {
      cmd = "settings put secure enabled_accessibility_services ''"
          + " && settings put secure accessibility_enabled 0";
    }
    try {
      Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
    } catch (Exception ignored) {
    }
  }
}
