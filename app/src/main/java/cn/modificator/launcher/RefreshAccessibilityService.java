package cn.modificator.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 全系统"每次点击即刷新屏幕"辅助服务。
 * 监听所有 TYPE_VIEW_CLICKED 事件，触发全屏刷新(GU 无闪烁)。
 * 解决硬件屏幕响应慢、需要持续刷新才能正常加载的问题。
 */
public class RefreshAccessibilityService extends AccessibilityService {

  private static boolean serviceRunning = false;

  public static boolean isRunning() {
    return serviceRunning;
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
      // GU 无闪烁全屏刷新
      RefreshModeHelper.refreshGU();
    }
  }

  @Override
  public void onInterrupt() {
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    serviceRunning = true;
    FileLog.log("RefreshAS", "Service connected");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    serviceRunning = false;
    FileLog.log("RefreshAS", "Service destroyed");
  }
}
