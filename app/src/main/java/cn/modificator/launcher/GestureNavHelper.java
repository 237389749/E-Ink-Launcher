package cn.modificator.launcher;

import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Onyx/Poke6 手势导航控制。
 * 系统桌面通过 Settings.Global["system_navigation_mode_key"] 切换手势，
 * 我们的 launcher 作为普通应用没有 WRITE_SECURE_SETTINGS 权限，
 * 通过 shell command 写入。
 */
public class GestureNavHelper {

  private static final String TAG = "GestureNav";
  static final String KEY = "system_navigation_mode_key";
  static final String MODE_GESTURE = "gesture";
  static final String MODE_VIRTUAL_KEY = "virtual_key";

  private GestureNavHelper() {
  }

  /** 读取当前导航模式 */
  public static String getCurrentMode() {
    try {
      String mode = Settings.Global.getString(
          App.getInstance().getContentResolver(), KEY);
      return mode != null ? mode : MODE_GESTURE;
    } catch (Exception e) {
      return MODE_GESTURE;
    }
  }

  /** 当前是否是手势模式 */
  public static boolean isGestureMode() {
    return MODE_GESTURE.equals(getCurrentMode());
  }

  /** 切换到手势导航 */
  public static boolean switchToGesture() {
    return setMode(MODE_GESTURE);
  }

  /** 切换到虚拟按键 */
  public static boolean switchToVirtualKey() {
    return setMode(MODE_VIRTUAL_KEY);
  }

  private static boolean setMode(String mode) {
    // 方式 1: shell command（无需 root，但需要 shell 权限）
    String result = runShell("settings put global " + KEY + " " + mode);
    FileLog.log(TAG, "setMode(" + mode + ") shell result: " + result);

    // 验证是否生效
    String current = getCurrentMode();
    boolean ok = mode.equals(current);
    FileLog.log(TAG, "setMode verify: current=" + current + " ok=" + ok);

    if (!ok) {
      // 方式 2: su root fallback
      FileLog.log(TAG, "shell failed, trying su...");
      result = runSu("settings put global " + KEY + " " + mode);
      FileLog.log(TAG, "su result: " + result);
      current = getCurrentMode();
      ok = mode.equals(current);
      FileLog.log(TAG, "su verify: current=" + current + " ok=" + ok);
    }

    return ok;
  }

  private static String runShell(String cmd) {
    StringBuilder sb = new StringBuilder();
    try {
      Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
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

  private static String runSu(String cmd) {
    StringBuilder sb = new StringBuilder();
    try {
      Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
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
