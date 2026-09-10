package cn.modificator.launcher.model;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 设备管理员（一键锁屏用）。
 *
 * 同时承担"卸载/停用前自愈"职责（app 无自身卸载回调，DeviceAdmin 是唯一可挂钩点）：
 * 卸载带 DeviceAdmin 的 app 时，系统会先走 admin 停用流程 → 触发
 * {@link #onDisableRequested}/{@link #onDisabled}（此时本 app 尚在运行）——
 * 在此把默认 HOME 恢复为 Onyx 原始桌面（com.onyx/.StartupActivity），
 * 避免卸载后设备无桌面可用（只剩 FallbackHome）。
 *
 * 动作（需 root；失败静默不阻塞）：
 *   pm enable com.onyx                                        解冻（该包常为 DISABLED_USER）
 *   cmd package set-home-activity com.onyx/.StartupActivity   设为默认桌面
 *
 * Created by Modificator
 * time: 16/12/3.下午2:49
 * des:create file and achieve model（Modified: 卸载前恢复 Onyx 原始桌面）
 */

public class AdminReceiver extends DeviceAdminReceiver {

  /** Onyx 原始桌面组件（恢复默认 HOME 的目标） */
  private static final String ONYX_HOME_COMPONENT = "com.onyx/.StartupActivity";

  @Override
  public CharSequence onDisableRequested(Context context, Intent intent) {
    restoreOnyxHome();
    return "停用后已将默认桌面恢复为 Onyx 原始桌面（com.onyx），避免卸载后无桌面可用。";
  }

  @Override
  public void onDisabled(Context context, Intent intent) {
    restoreOnyxHome();
  }

  /** 解冻并恢复默认 HOME 为 Onyx 原始桌面（root；失败静默） */
  private void restoreOnyxHome() {
    try {
      Runtime.getRuntime().exec(new String[]{"su", "-c",
          "pm enable com.onyx; cmd package set-home-activity " + ONYX_HOME_COMPONENT}).waitFor();
    } catch (Throwable ignored) {
    }
  }
}
