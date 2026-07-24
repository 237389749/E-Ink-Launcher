package cn.modificator.launcher;

import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;

/**
 * 墨水屏全局刷新模式切换。
 * 通过 Onyx SDK EpdController 直接调用驱动层设置。
 */
public class RefreshModeHelper {

  /** 用户可选模式 */
  public static final UpdateMode[] MODES = {
      UpdateMode.GU_FAST,        // GU 快速 — 极速，轻微残影
      UpdateMode.GU,             // GU — 灰度更新，无闪烁
      UpdateMode.DU,             // DU — 直接更新，适合手写
      UpdateMode.ANIMATION_MONO, // 纯黑白动画 — 最快滑动
      UpdateMode.ANIMATION_X,    // 极速动画 — 视频级
      UpdateMode.GC,             // GC — 标准全刷
      UpdateMode.GC4,            // GC4 — 四帧全刷，灰度更均匀
      UpdateMode.DEEP_GC,        // 深度全刷 — 残影全清除
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

  private RefreshModeHelper() {}

  /** 应用全局刷新模式 */
  public static void apply(UpdateMode mode) {
    try {
      EpdController.repaintEveryThing(mode);
      FileLog.log("RefreshMode", "Applied: " + mode.name());
    } catch (Exception e) {
      FileLog.log("RefreshMode", "Apply failed: " + e.getMessage(), e);
    }
  }

  /** 对当前 View 应用刷新模式 */
  public static void applyToView(android.view.View view, UpdateMode mode) {
    try {
      EpdController.setViewDefaultUpdateMode(view, mode);
      view.invalidate();
      FileLog.log("RefreshMode", "View mode: " + mode.name());
    } catch (Exception e) {
      FileLog.log("RefreshMode", "View apply failed: " + e.getMessage(), e);
    }
  }

  public static int indexOf(UpdateMode mode) {
    for (int i = 0; i < MODES.length; i++) {
      if (MODES[i] == mode) return i;
    }
    return -1;
  }
}
