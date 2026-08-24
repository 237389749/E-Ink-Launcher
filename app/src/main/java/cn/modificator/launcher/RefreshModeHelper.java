package cn.modificator.launcher;

import android.os.Build;
import android.util.Log;

/**
 * 墨水屏全局刷新模式切换。
 *
 * 模式集与系统引擎 4 模式（Normal/DU/A2/X）互补：
 *   1. None            — 恢复默认（清除 launcher 的 app scope，回归系统 per-app 模式管理）
 *   2. GU              — 手写模式，16 级灰度，无闪烁（日常最舒适）
 *   3. DEEP_GC         — 深度全刷，最彻底清除残影（最清晰）
 *   4. ANIMATION_X     — X 模式，极速响应（最快）
 *   5. ANIMATION_MONO  — 单色 A2，纯黑白滑动
 *
 * 实现：反射调用 framework 私有类 {@code android.onyx.ViewUpdateHelper}（Onyx 定制 ROM
 * 已将其编入 boot classpath，第三方应用可加载）。对 launcher 自身设置 app scope 波形，
 * None 则清除 scope 交还系统管理。Android 9+ 需先经 HiddenApiBypass 豁免隐藏 API。
 *
 * 注意：Onyx SDK 的 EpdController（com.onyx.android.sdk.*）位于 eink-home 应用内，
 * 不在第三方应用 classpath（实测 ClassNotFoundException），不可用。
 */
public class RefreshModeHelper {

  /** ViewUpdateHelper UI 模式值（ref.md：UI 组合标志位） */
  private static final int UI_GU_MODE = 2;
  private static final int UI_DEEP_GC_MODE = 108;
  private static final int UI_X_A2_MODE = 16777220;
  private static final int UI_MONO_A2_MODE = 33554436;
  /** None 用 -1 表示清除 scope */
  private static final int UI_NONE = -1;

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

  private static final int[] MODE_VALUES = {
      UI_NONE, UI_GU_MODE, UI_DEEP_GC_MODE, UI_X_A2_MODE, UI_MONO_A2_MODE
  };

  private static final String VIEW_UPDATE_HELPER = "android.onyx.ViewUpdateHelper";
  private static final String APP_PACKAGE = "cn.modificator.launcher";

  private static Class<?> viewUpdateHelperClass;
  private static boolean inited;

  private RefreshModeHelper() {}

  /** Onyx 定制 ROM 可加载 ViewUpdateHelper 时返回 true（非 Onyx 设备 UI 应隐藏入口） */
  public static boolean isAvailable() {
    init();
    return viewUpdateHelperClass != null;
  }

  private static boolean init() {
    if (inited) return viewUpdateHelperClass != null;
    inited = true;
    try {
      // Android 9+ 隐藏 API 限制：豁免 android.onyx 私有框架包
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/onyx;");
      }
      viewUpdateHelperClass = Class.forName(VIEW_UPDATE_HELPER);
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** 应用全局刷新模式（index 对应 MODE_NAMES），成功返回 true */
  public static boolean apply(int index) {
    if (index < 0 || index >= MODE_NAMES.length) return false;
    if (!init()) return false;
    try {
      int value = MODE_VALUES[index];
      if (value == UI_NONE) {
        // 清除 launcher 的 app scope，交还系统引擎管理 per-app 模式
        viewUpdateHelperClass.getMethod("clearAppScopeUpdate", boolean.class).invoke(null, true);
        viewUpdateHelperClass.getMethod("repaintEverything").invoke(null);
      } else {
        // 对 launcher 自身应用指定波形（不影响其他应用），再立即全屏刷新
        viewUpdateHelperClass
            .getMethod("applyAppScopeUpdate", String.class, boolean.class, int.class, int.class, int.class)
            .invoke(null, APP_PACKAGE, true, 0, value, Integer.MAX_VALUE);
        viewUpdateHelperClass.getMethod("repaintEverything", int.class).invoke(null, value);
      }
      Log.i("RefreshMode", "Global: " + MODE_NAMES[index]);
      return true;
    } catch (Throwable e) {
      Log.w("RefreshMode", "Failed: " + e);
      return false;
    }
  }
}
