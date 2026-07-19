package cn.modificator.launcher.model;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 静态注册的包变更广播接收器。
 * 在 Manifest 中声明，确保应用不在前台时也能收到安装/卸载通知，
 * 写入标记位供 Launcher.onResume() 检查并刷新。
 */
public class PackageChangeReceiver extends BroadcastReceiver {

  private static final String PREFS = "launcherPropertyFile";
  static final String KEY_NEEDS_REFRESH = "needsAppRefresh";

  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (Intent.ACTION_PACKAGE_ADDED.equals(action)
        || Intent.ACTION_PACKAGE_REMOVED.equals(action)
        || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit()
          .putBoolean(KEY_NEEDS_REFRESH, true)
          .apply();
    }
  }
}
