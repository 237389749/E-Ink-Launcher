package cn.modificator.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 全系统点击刷新辅助服务。
 * 监听 TYPE_VIEW_CLICKED，根据 Config 选择 GU（无闪烁）或 GC（全刷）刷新。
 */
public class RefreshAccessibilityService extends AccessibilityService {

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
      int mode = Config.getClickRefreshMode(this);
      if (mode == Config.CLICK_REFRESH_GU) {
        RefreshModeHelper.refreshGU();
      } else if (mode == Config.CLICK_REFRESH_GC) {
        RefreshModeHelper.refreshGC();
      }
    }
  }

  @Override
  public void onInterrupt() {
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    FileLog.log("RefreshAS", "Service connected");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    FileLog.log("RefreshAS", "Service destroyed");
  }
}
