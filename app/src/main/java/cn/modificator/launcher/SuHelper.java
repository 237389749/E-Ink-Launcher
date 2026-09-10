package cn.modificator.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * root / su 调用统一入口。
 *
 * 根因（2026-09-10 在故障机实测确认）：本机 Magisk 的 su **只存在于 /debug_ramdisk/su**，
 * /system/bin/su 与 /system/xbin/su 都不存在；而 app 进程的 PATH 不包含 /debug_ramdisk，
 * 因此 {@code Runtime.exec(new String[]{"su", "-c", ...})} 会抛 IOException
 * （shell 报 "su: inaccessible or not found"）——表现为所有 root 功能静默失败：
 * 手势设置读出来全是"无"、保存报错（GestureConfigHelper），以及导航模式/刷新模式/启用
 * Onyx 桌面等 su 操作全部无效。
 *
 * 本类按优先级探测 su 的绝对路径并缓存（实测 app 进程可直接执行 /debug_ramdisk/su），
 * 最后回退到 PATH 中的 "su"（magisk 老版本 /system/bin/su 或其它 root 环境）。
 */
public final class SuHelper {

  /** 候选 su 路径（按优先级） */
  private static final String[] CANDIDATES = {
      "/debug_ramdisk/su",   // Magisk（当前设备，实测可用）
      "/system/bin/su",      // Magisk 老版本 / 其它 root
      "/system/xbin/su",
      "/sbin/su",
      "su",                  // PATH 回退（adb shell 等环境）
  };

  private static boolean probed;
  private static String resolved;

  private SuHelper() {}

  /** 返回可用的 su 命令（绝对路径或 "su"）；无可用返回 null。结果缓存 */
  public static synchronized String suPath() {
    if (probed) return resolved;
    probed = true;
    for (String c : CANDIDATES) {
      try {
        Process p = Runtime.getRuntime().exec(new String[]{c, "-c", "id"});
        drain(p);
        if (p.waitFor() == 0) {
          resolved = c;
          break;
        }
      } catch (Throwable ignored) {
        // 路径不存在 / 不可执行 → 试下一个
      }
    }
    return resolved;
  }

  /** root 是否可用 */
  public static boolean isAvailable() {
    return suPath() != null;
  }

  /** 执行 root 命令，exit 0 返回 true */
  public static boolean execOk(String cmd) {
    Process p = start(cmd);
    if (p == null) return false;
    try {
      drain(p);
      return p.waitFor() == 0;
    } catch (Throwable t) {
      return false;
    }
  }

  /** 执行 root 命令并返回 stdout（strip 换行）；失败或无输出返回 null */
  public static String execRead(String cmd) {
    Process p = start(cmd);
    if (p == null) return null;
    try {
      StringBuilder sb = new StringBuilder();
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      String line;
      while ((line = r.readLine()) != null) sb.append(line);
      drain(p);
      p.waitFor();
      return sb.length() > 0 ? sb.toString() : null;
    } catch (Throwable t) {
      return null;
    }
  }

  /** 启动 root 进程（未探测到 su 返回 null），供调用方自行读流 */
  public static Process start(String cmd) {
    String su = suPath();
    if (su == null) return null;
    try {
      return Runtime.getRuntime().exec(new String[]{su, "-c", cmd});
    } catch (Throwable t) {
      return null;
    }
  }

  private static void drain(Process p) {
    try {
      BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
      while (e.readLine() != null) { /* drain */ }
    } catch (Throwable ignored) {
    }
  }
}
