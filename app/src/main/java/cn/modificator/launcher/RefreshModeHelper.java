package cn.modificator.launcher;

import android.view.View;
import java.lang.reflect.Method;

/**
 * 墨水屏全局刷新模式切换。
 * 通过反射调用 Onyx SDK EpdController（避免编译时依赖）。
 */
public class RefreshModeHelper {

  // UpdateMode 枚举值名称，对应 com.onyx.android.sdk.api.device.epd.UpdateMode
  public static final String[] MODE_NAMES = {
      "GU_FAST",        // GU 快速 — 极速，轻微残影
      "GU",             // GU — 灰度更新，无闪烁
      "DU",             // DU — 直接更新，适合手写
      "ANIMATION_MONO", // 纯黑白动画 — 最快滑动
      "ANIMATION_X",    // 极速动画 — 视频级
      "GC",             // GC — 标准全刷
      "GC4",            // GC4 — 四帧全刷，灰度更均匀
      "DEEP_GC",        // 深度全刷 — 残影全清除
  };

  public static final String[] LABELS = {
      "GU FAST — 极速，轻微残影",
      "GU — 灰度更新，无闪烁",
      "DU — 直接更新，手写用",
      "ANIM MONO — 纯黑滑动",
      "ANIM X — 极速动画",
      "GC — 标准全刷（默认）",
      "GC4 — 四帧全刷",
      "DEEP GC — 深度全刷",
  };

  private static Object epdController;
  private static Method repaintEverythingMethod;

  private RefreshModeHelper() {}

  /** 通过反射获取 EpdController 和 UpdateMode */
  private static boolean ensureReflected() {
    if (repaintEverythingMethod != null) return true;
    try {
      Class<?> epdClass = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController");
      // repaintEveryThing(UpdateMode mode)
      repaintEverythingMethod = epdClass.getMethod("repaintEveryThing",
          Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode"));
      FileLog.log("RefreshMode", "Reflection OK");
      return true;
    } catch (Exception e) {
      FileLog.log("RefreshMode", "Reflection failed (not Onyx device?): " + e.getMessage());
      return false;
    }
  }

  /** 获取 UpdateMode 枚举值 */
  private static Object getUpdateMode(String name) {
    try {
      Class<?> modeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode");
      return Enum.valueOf((Class<Enum>) modeClass, name);
    } catch (Exception e) {
      FileLog.log("RefreshMode", "getUpdateMode failed: " + name);
      return null;
    }
  }

  /** 应用全局刷新模式 */
  public static void apply(int index) {
    if (index < 0 || index >= MODE_NAMES.length) return;
    if (!ensureReflected()) return;

    Object mode = getUpdateMode(MODE_NAMES[index]);
    if (mode == null) {
      FileLog.log("RefreshMode", "Unknown mode: " + MODE_NAMES[index]);
      return;
    }

    try {
      repaintEverythingMethod.invoke(null, mode);
      FileLog.log("RefreshMode", "Applied: " + MODE_NAMES[index]);
    } catch (Exception e) {
      FileLog.log("RefreshMode", "Apply failed: " + e.getMessage(), e);
    }
  }

  /** 对指定 View 应用刷新模式 */
  public static void applyToView(View view, int index) {
    if (view == null || index < 0 || index >= MODE_NAMES.length) return;
    if (!ensureReflected()) return;

    Object mode = getUpdateMode(MODE_NAMES[index]);
    if (mode == null) return;

    try {
      Class<?> epdClass = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController");
      Class<?> modeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode");
      Method setModeMethod = epdClass.getMethod("setViewDefaultUpdateMode", View.class, modeClass);
      setModeMethod.invoke(null, view, mode);
      view.invalidate();
      FileLog.log("RefreshMode", "View mode: " + MODE_NAMES[index]);
    } catch (Exception e) {
      FileLog.log("RefreshMode", "View apply failed: " + e.getMessage(), e);
    }
  }
}
