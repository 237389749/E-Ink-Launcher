package cn.modificator.launcher;

import android.view.View;
import java.lang.reflect.Method;

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
      "DU",
      "ANIMATION_MONO",
      "ANIMATION_X",
      "GC",
      "GC4",
      "DEEP_GC",
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

  private static Class<?> epdClass;
  private static Class<?> modeClass;
  private static boolean reflected;

  private RefreshModeHelper() {}

  private static boolean init() {
    if (reflected) return epdClass != null;
    reflected = true;
    try {
      epdClass = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController");
      modeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode");
      FileLog.log("RefreshMode", "OK");
      return true;
    } catch (Exception e) {
      FileLog.log("RefreshMode", "Not Onyx device: " + e.getMessage());
      return false;
    }
  }

  private static Object mode(String name) {
    try {
      return Enum.valueOf((Class<Enum>) modeClass, name);
    } catch (Exception e) {
      return null;
    }
  }

  private static void call(String methodName, Class<?>[] paramTypes, Object[] args) {
    try {
      Method m = epdClass.getMethod(methodName, paramTypes);
      m.invoke(null, args);
    } catch (Exception e) {
      FileLog.log("RefreshMode", methodName + " failed: " + e.getMessage());
    }
  }

  /** 持久化全局刷新模式 */
  public static void apply(int index) {
    if (index < 0 || index >= MODE_NAMES.length) return;
    if (!init()) return;
    Object m = mode(MODE_NAMES[index]);
    if (m == null) return;

    // 1. 清除所有 App 独立配置，让全局默认生效
    call("clearAppScopeUpdate", new Class<?>[]{boolean.class}, new Object[]{true});

    // 2. 设为系统默认模式
    call("setSystemDefaultUpdateMode", new Class<?>[]{modeClass}, new Object[]{m});

    // 3. 立即全屏刷新
    call("repaintEveryThing", new Class<?>[]{modeClass}, new Object[]{m});

    FileLog.log("RefreshMode", "Applied global: " + MODE_NAMES[index]);
  }

  /** View 级刷新 */
  public static void applyToView(View view, int index) {
    if (view == null || index < 0 || index >= MODE_NAMES.length) return;
    if (!init()) return;
    Object m = mode(MODE_NAMES[index]);
    if (m == null) return;
    call("setViewDefaultUpdateMode", new Class<?>[]{View.class, modeClass}, new Object[]{view, m});
    view.invalidate();
  }
}
