package cn.modificator.launcher.model;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

import cn.modificator.launcher.SuHelper;

/**
 * 设备管理员（一键锁屏用）。
 *
 * 同时承担"卸载/停用前自愈"职责（app 无自身卸载回调，DeviceAdmin 是唯一可挂钩点）：
 * 卸载带 DeviceAdmin 的 app 时，系统会先走 admin 停用流程 → 触发
 * {@link #onDisableRequested}/{@link #onDisabled}（此时本 app 尚在运行）——
 *  ① 把默认 HOME 恢复为 Onyx 原始桌面（com.onyx/.StartupActivity），避免卸载后无桌面可用；
 *  ② 清掉本 launcher 写进 {@code /onyxconfig/mmkv/onyx_config} 的 EAC 残留。
 *
 * ★ 为什么必须清 EAC（2026-11-06 实测）：`/onyxconfig` 是独立 ext4 分区
 * （mmcblk0p80），**恢复出厂设置不擦**；卸载 app 也不会清它的 EAC 配置。历史版本
 * 批量写过 `eac_app_<pkg>` / theme / default 三组键，卸载后仍留在分区里，其中
 * `refreshModeIndex=refresh_mode_*` 会让 `caculateRefreshConfig` 忽略 `updateMode`
 * ⇒ 系统自带刷新引擎点了完全没反应（连恢复出厂都修不好）。
 * `GlobalEacRefreshHelper cleanup` 按完整 `pm list packages` 判定"已卸载"，
 * 只删这些残留键，保留仍安装 app 的配置与系统内部键 `eac_app_pkg_set`。
 *
 * 动作均走 {@link SuHelper}（root；失败静默不阻塞），避免裸 "su" 的 PATH 老坑。
 *
 * Created by Modificator
 * time: 16/12/3.下午2:49
 * des:create file and achieve model（Modified: 卸载前恢复 Onyx 原始桌面 + 清理 EAC 残留）
 */

public class AdminReceiver extends DeviceAdminReceiver {

  /** Onyx 原始桌面组件（恢复默认 HOME 的目标） */
  private static final String ONYX_HOME_COMPONENT = "com.onyx/.StartupActivity";

  @Override
  public CharSequence onDisableRequested(Context context, Intent intent) {
    runCleanup(context);
    restoreOnyxHome(context);
    return "停用后已恢复 Onyx 原始桌面并清理刷新配置残留。";
  }

  @Override
  public void onDisabled(Context context, Intent intent) {
    runCleanup(context);
    restoreOnyxHome(context);
  }

  /** 卸载/停用前清 EAC 残留。fire-and-forget：{@code cleanup} 可能遍历上百个键，
   *  超过 {@link SuHelper} 的 8s 超时，故用 nohup 后台跑（与 resetRefreshConfig 同法）。 */
  private void runCleanup(Context context) {
    String apk = context.getPackageCodePath();
    String inner = "env CLASSPATH=" + apk
        + " app_process /system/bin cn.modificator.launcher.GlobalEacRefreshHelper cleanup";
    SuHelper.execOk("nohup " + inner + " > /data/local/tmp/eac_cleanup.log 2>&1 &");
  }

  /**
   * 解冻（判定：未启用才启用）并将默认 HOME 恢复为 Onyx 原始桌面（root；失败静默）
   */
  private void restoreOnyxHome(Context context) {
    try {
      boolean enabled;
      try {
        enabled = context.getPackageManager().getApplicationInfo("com.onyx", 0).enabled;
      } catch (Throwable t) {
        enabled = false;
      }
      String shellCmd = (enabled ? "" : "pm enable com.onyx; ")
          + "cmd package set-home-activity " + ONYX_HOME_COMPONENT;
      SuHelper.execOk(shellCmd);
    } catch (Throwable ignored) {
    }
  }
}
