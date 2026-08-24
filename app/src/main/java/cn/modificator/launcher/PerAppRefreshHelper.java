package cn.modificator.launcher;

import android.os.Build;
import android.util.Base64;

/**
 * Per-app 刷新模式配置的 root 入口。
 *
 * 由 launcher 通过 {@code su -c "CLASSPATH=<apk> app_process /system/bin <此类> <pkg> <base64(json)>"}
 * 启动（root/shell uid 被 SELinux 放行，可访问系统服务 oec_service）。
 *
 * 流程：HiddenApiBypass 豁免 android.onyx 包 → 反射 EInkHelper.applyEACAppTheme(json)
 * 把 per-app 刷新配置写入系统 EACAppTheme（MMKV 持久化），系统引擎自动应用。
 * 与通知栏 EInk Center 的"应用优化引擎"写的是同一份配置。
 *
 * 用法示例（由 Launcher 内部调用，不对外）：
 *   CLASSPATH=/data/app/.../base.apk app_process /system/bin cn.modificator.launcher.PerAppRefreshHelper
 *       com.example.koreader <base64主题JSON>
 */
public class PerAppRefreshHelper {

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("usage: PerAppRefreshHelper <pkg> <base64themeJson>");
      System.exit(2);
      return;
    }
    String pkg = args[0];
    String json;
    try {
      json = new String(Base64.decode(args[1], Base64.DEFAULT), "UTF-8");
    } catch (Exception e) {
      System.err.println("bad base64: " + e);
      System.exit(2);
      return;
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/onyx;");
      }
      Class<?> helper = Class.forName("android.onyx.optimization.EInkHelper");
      helper.getMethod("applyEACAppTheme", String.class).invoke(null, json);
      System.out.println("OK " + pkg);
      System.exit(0);
    } catch (Throwable t) {
      t.printStackTrace();
      System.err.println("FAIL " + pkg + " : " + t);
      System.exit(1);
    }
  }
}
