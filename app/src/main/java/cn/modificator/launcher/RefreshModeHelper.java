package cn.modificator.launcher;

/**
 * 墨水屏全局刷新模式切换。
 * 通过反射调用 Onyx SDK EpdController（避免编译时依赖）。
 *
 * apply() 做三件事确保全局持久化：
 *   1. clearAppScopeUpdate() — 清掉所有 App 的独立配置
 *   2. setSystemDefaultUpdateMode(mode) — 设为系统默认
 *   3. repaintEveryThing(mode) — 立即生效
 */
public class RefreshModeHelper {

  public static final String[] MODE_NAMES = {
      "GU_FAST",
      "GU",
      "ANIMATION_MONO",
      "ANIMATION_X",
      "GC",
      "GC4",
      "DEEP_GC",
  };

  public static final String[] LABELS = {
      "GU FAST — 极速，轻微残影",
      "GU — 灰度更新，无闪烁",
      "ANIM MONO — 纯黑滑动",
      "ANIM X — 极速动画",
      "GC — 标准全刷",
      "GC4 — 四帧全刷",
      "DEEP GC — 深度全刷",
  };

  private static Class<?> epdClass;
  private static Class<?> modeClass;
  private static boolean inited;

  private RefreshModeHelper() {}

  private static boolean init() {
    if (inited) return epdClass != null;
    inited = true;
    try {
      epdClass = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController");
      modeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode");
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  /** 单次 GU 无闪烁全屏刷新（供 AccessibilityService 等频繁调用） */
  public static void refreshGU() {
    try {
      Class<?> ec = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController");
      Class<?> mc = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode");
      Object gu = Enum.valueOf((Class<Enum>) mc, "GU");
      ec.getMethod("repaintEveryThing", mc).invoke(null, gu);
    } catch (Exception ignored) {
    }
  }

  /** 持久化全局刷新模式 */
  public static void apply(int index) {
    if (index < 0 || index >= MODE_NAMES.length) return;
    if (!init()) return;
    try {
      Object mode = Enum.valueOf((Class<Enum>) modeClass, MODE_NAMES[index]);

      // 1. 清除所有 App 独立配置，让全局默认生效
      epdClass.getMethod("clearAppScopeUpdate", boolean.class).invoke(null, true);

      // 2. 设为系统默认模式
      epdClass.getMethod("setSystemDefaultUpdateMode", modeClass).invoke(null, mode);

      // 3. 立即全屏刷新
      epdClass.getMethod("repaintEveryThing", modeClass).invoke(null, mode);

      FileLog.log("RefreshMode", "Global: " + MODE_NAMES[index]);
    } catch (Exception e) {
      FileLog.log("RefreshMode", "Failed: " + e.getMessage());
    }
  }
}
