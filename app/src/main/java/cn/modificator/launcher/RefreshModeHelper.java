package cn.modificator.launcher;

import android.util.Log;

/**
 * 墨水屏全局刷新模式切换。
 *
 * 模式集与系统引擎 4 模式（Normal/DU/A2/X）互补：
 *   1. None            — 恢复系统默认（回归系统引擎 per-app 模式管理）
 *   2. GU              — 手写模式，16 级灰度，无闪烁（日常最舒适）
 *   3. DEEP_GC         — 深度全刷，最彻底清除残影（最清晰）
 *   4. ANIMATION_X     — X 模式，极速响应（最快）
 *   5. ANIMATION_MONO  — 单色 A2，纯黑白滑动
 *
 * 通过反射调用 Onyx SDK EpdController（避免编译时依赖；非 Onyx 设备上自动禁用）。
 *
 * apply() 做三件事确保全局生效：
 *   1. clearAppScopeUpdate() — 清掉所有 App 的独立配置，让全局默认生效
 *   2. setSystemDefaultUpdateMode(mode) — 设为系统默认模式
 *   3. repaintEveryThing(mode) — 立即全屏刷新
 */
public class RefreshModeHelper {

  public static final String[] MODE_NAMES = {
      "None",
      "GU",
      "DEEP_GC",
      "ANIMATION_X",
      "ANIMATION_MONO",
  };

  public static final String[] LABELS = {
      "恢复默认（系统 per-app 模式）",
      "GU — 无闪烁，16 级灰度（日常）",
      "DEEP GC — 深度全刷（最清晰）",
      "ANIM X — 极速响应（最快）",
      "ANIM MONO — 纯黑白滑动",
  };

  private static Class<?> epdClass;
  private static Class<?> modeClass;
  private static boolean inited;

  private RefreshModeHelper() {}

  /** 检测 Onyx SDK 是否可用（非 Onyx 设备返回 false，UI 应隐藏入口） */
  public static boolean isAvailable() {
    init();
    return epdClass != null;
  }

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

  /** 应用全局刷新模式（index 对应 MODE_NAMES），成功返回 true */
  public static boolean apply(int index) {
    if (index < 0 || index >= MODE_NAMES.length) return false;
    if (!init()) return false;
    try {
      Object mode = Enum.valueOf((Class<Enum>) modeClass, MODE_NAMES[index]);

      // 1. 清除所有 App 独立配置，让全局默认生效
      epdClass.getMethod("clearAppScopeUpdate", boolean.class).invoke(null, true);

      // 2. 设为系统默认模式
      epdClass.getMethod("setSystemDefaultUpdateMode", modeClass).invoke(null, mode);

      // 3. 立即全屏刷新
      epdClass.getMethod("repaintEveryThing", modeClass).invoke(null, mode);

      Log.i("RefreshMode", "Global: " + MODE_NAMES[index]);
      return true;
    } catch (Exception e) {
      Log.w("RefreshMode", "Failed: " + e.getMessage());
      return false;
    }
  }
}
