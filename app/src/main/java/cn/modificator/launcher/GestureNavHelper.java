package cn.modificator.launcher;

import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Onyx/Poke6 手势导航控制。
 * 系统桌面通过 Settings.Global["system_navigation_mode_key"] 切换手势，
 * 我们的 launcher 作为普通应用需要提权才能写入，尝试以下方式：
 *
 *   1. 直接写入 Settings.Global（需 ADB 授权 WRITE_SECURE_SETTINGS）
 *   2. Shizuku rish shell（ADB 级权限，无需 root）
 *   3. Root su
 */
public class GestureNavHelper {

  private static final String TAG = "GestureNav";
  static final String KEY = "system_navigation_mode_key";
  static final String MODE_GESTURE = "gesture";
  static final String MODE_VIRTUAL_KEY = "virtual_key";

  private GestureNavHelper() {
  }

  // =========================================================================
  // 读取
  // =========================================================================

  public static String getCurrentMode() {
    try {
      String mode = Settings.Global.getString(
          App.getInstance().getContentResolver(), KEY);
      return mode != null ? mode : MODE_GESTURE;
    } catch (Exception e) {
      return MODE_GESTURE;
    }
  }

  public static boolean isGestureMode() {
    return MODE_GESTURE.equals(getCurrentMode());
  }

  // =========================================================================
  // 写入
  // =========================================================================

  public static boolean switchToGesture() {
    return setMode(MODE_GESTURE);
  }

  public static boolean switchToVirtualKey() {
    return setMode(MODE_VIRTUAL_KEY);
  }

  /** 最后一次写入使用的提权方式，null=失败 */
  private static String lastSuccessMethod = null;

  public static String getLastSuccessMethod() {
    return lastSuccessMethod;
  }

  private static boolean setMode(String mode) {
    lastSuccessMethod = null;
    String cmd = "settings put global " + KEY + " " + mode;

    // 1. 直接用 Settings.Global API（如果已通过 ADB 授权）
    if (tryDirectWrite(mode)) {
      lastSuccessMethod = "direct";
      FileLog.log(TAG, "setMode OK via direct Settings.Global");
      return true;
    }

    // 2. Shizuku rish（ADB 权限，无需 root）
    if (tryRish(cmd)) {
      lastSuccessMethod = "shizuku";
      FileLog.log(TAG, "setMode OK via Shizuku rish");
      return true;
    }

    // 3. Root su
    if (trySu(cmd)) {
      lastSuccessMethod = "root";
      FileLog.log(TAG, "setMode OK via su");
      return true;
    }

    FileLog.log(TAG, "setMode FAILED — all methods exhausted");
    return false;
  }

  /** 直接写入 Settings.Global（需 WRITE_SECURE_SETTINGS 权限） */
  private static boolean tryDirectWrite(String mode) {
    try {
      return Settings.Global.putString(
          App.getInstance().getContentResolver(), KEY, mode);
    } catch (SecurityException e) {
      return false;
    }
  }

  /** 通过 Shizuku rish 执行命令 */
  private static boolean tryRish(String cmd) {
    // Shizuku rish 的可能路径
    String[] rishPaths = {
        "/data/user_de/0/moe.shizuku.privileged.api/rish",
        "/data/user/0/moe.shizuku.privileged.api/rish",
        "/sdcard/Android/data/moe.shizuku.privileged.api/files/rish",
    };
    for (String rish : rishPaths) {
      if (exec(new String[]{rish, "-c", cmd}) == 0) {
        return verify(modeFrom(cmd));
      }
    }

    // Shizuku start.sh 方式
    String shizukuSh = "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh";
    if (exec(new String[]{"sh", shizukuSh, "-c", cmd}) == 0) {
      return verify(modeFrom(cmd));
    }

    return false;
  }

  /** 通过 root su 执行命令 */
  private static boolean trySu(String cmd) {
    if (exec(new String[]{"su", "-c", cmd}) == 0) {
      return verify(modeFrom(cmd));
    }
    return false;
  }

  /** 验证写入结果 */
  private static boolean verify(String expected) {
    String current = getCurrentMode();
    return expected != null && expected.equals(current);
  }

  /** 从 "settings put global key value" 中提取 value */
  private static String modeFrom(String cmd) {
    String[] parts = cmd.split(" ");
    return parts.length > 0 ? parts[parts.length - 1] : null;
  }

  // =========================================================================
  // Shell 执行
  // =========================================================================

  private static int exec(String[] cmd) {
    try {
      Process p = Runtime.getRuntime().exec(cmd);
      // 消费输出避免阻塞
      consumeStream(p.getInputStream());
      consumeStream(p.getErrorStream());
      return p.waitFor();
    } catch (Exception e) {
      FileLog.log(TAG, "exec " + cmd[0] + " failed: " + e.getMessage());
      return -1;
    }
  }

  private static void consumeStream(java.io.InputStream in) {
    try {
      byte[] buf = new byte[4096];
      //noinspection StatementWithEmptyBody
      while (in.read(buf) != -1) {}
    } catch (Exception ignored) {
    }
  }

  /** 调试用：执行命令并返回输出 */
  static String execForOutput(String[] cmd) {
    StringBuilder sb = new StringBuilder();
    try {
      Process p = Runtime.getRuntime().exec(cmd);
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
      String line;
      while ((line = r.readLine()) != null) sb.append(line).append("\n");
      while ((line = e.readLine()) != null) sb.append(line).append("\n");
      p.waitFor();
    } catch (Exception ex) {
      sb.append("ERROR: ").append(ex.getMessage());
    }
    return sb.toString().trim();
  }
}
