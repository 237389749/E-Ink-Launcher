package cn.modificator.launcher;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Root 权限工具类，用于强制刷新包管理器缓存。
 * 仅在已 root 的设备上生效，无 root 时静默失败。
 */
public class RootRefreshHelper {

  private static final String TAG = "RootRefresh";

  private RootRefreshHelper() {
  }

  /**
   * 检查设备是否有 root 权限。
   */
  public static boolean isRootAvailable() {
    return runCommand("id").contains("uid=0");
  }

  /**
   * 通过 root 强制包管理器刷新缓存。
   * 执行无害的 pm 命令以唤醒 PackageManagerService，
   * 后续 queryIntentActivities 将返回最新结果。
   *
   * @return 命令输出
   */
  public static String forcePackageRefresh() {
    StringBuilder output = new StringBuilder();

    // 用 root 执行 pm 命令，迫使 PackageManagerService 同步状态
    String pmResult = runCommand("pm list packages android");
    output.append("pm: ").append(pmResult);

    // 如果有新安装的 APK，强制扫描
    String scanResult = runCommand("cmd package bg-dexopt-job 2>/dev/null");
    output.append("\ndexopt: ").append(scanResult);

    return output.toString();
  }

  private static String runCommand(String command) {
    StringBuilder sb = new StringBuilder();
    Process process = null;
    try {
      process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
      BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
      }
      while ((line = errReader.readLine()) != null) {
        sb.append(line).append("\n");
      }
      process.waitFor();
    } catch (Exception e) {
      Log.w(TAG, "Root command failed: " + command, e);
      return sb.toString();
    } finally {
      if (process != null) {
        process.destroy();
      }
    }
    return sb.toString().trim();
  }
}
