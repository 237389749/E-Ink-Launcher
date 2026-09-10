package cn.modificator.launcher;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * root / su 调用统一入口。
 *
 * 背景（2026-09-10/11 实测）：Magisk 的 su 位置随安装方式变化——
 *  - 旧布局：/debug_ramdisk/su（symlink→magisk）；
 *  - Magisk 30.x 重装后：/product/bin/su（/product/bin 由 magisk 以 tmpfs 提供）。
 * app 进程 PATH 虽常含 /product/bin，但 {@code Runtime.exec} **不查 PATH**，
 * 因此必须用绝对路径探测，否则所有 root 功能静默失败（手势设置读全空/保存报错等）。
 *
 * 另：su 请求能否提权还取决于 Magisk 的授权表（/data/adb/magisk.db policies）；
 * “彻底卸载 Magisk”会清空授权 → 所有 su 调用被拒（Permission denied），需在
 * Magisk app 内重新授权。
 *
 * 本类按优先级探测 su 绝对路径并缓存；探测带超时保护（授权弹窗未确认时不挂死 UI）。
 * 关键步骤写文件日志 {@code files/su_debug.log} 与 logcat（tag {@code SuHelper}）。
 */
public final class SuHelper {

  private static final String TAG = "SuHelper";
  private static final String LOG_FILE = "su_debug.log";
  private static final int TIMEOUT_SEC = 8;

  /** 候选 su 路径（按优先级） */
  private static final String[] CANDIDATES = {
      "/product/bin/su",     // Magisk 30.x 重装后（/product/bin 由 magisk tmpfs 提供）
      "/debug_ramdisk/su",   // Magisk 旧布局（实测位置）
      "/system/bin/su",      // Magisk 老版本 / 其它 root
      "/system/xbin/su",
      "/vendor/bin/su",
      "/odm/bin/su",
      "/sbin/su",
      "su",                  // PATH 回退（注意：Java exec 不查 PATH，仅兜底）
  };

  private static File logDir;
  private static boolean probed;
  private static String resolved;

  private SuHelper() {}

  /** 初始化日志目录（Launcher/Application onCreate 调用，可选） */
  public static void init(File filesDir) {
    logDir = filesDir;
  }

  /** 返回可用的 su 命令（绝对路径或 "su"）；无可用返回 null。结果缓存 */
  public static synchronized String suPath() {
    if (probed) return resolved;
    probed = true;
    log("probe start");
    for (String c : CANDIDATES) {
      try {
        if (c.startsWith("/")) {
          File f = new File(c);
          if (!f.exists() || !f.canExecute()) {
            log("probe " + c + " skip exists=" + f.exists() + " exec=" + f.canExecute());
            continue;
          }
        }
        Process p = Runtime.getRuntime().exec(new String[]{c, "-c", "id"});
        drain(p);
        boolean done = p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!done) {
          p.destroy();
          log("probe " + c + " TIMEOUT (Magisk authorization not confirmed?)");
          continue;
        }
        int rc = p.exitValue();
        log("probe " + c + " rc=" + rc);
        if (rc == 0) {
          resolved = c;
          break;
        }
      } catch (Throwable t) {
        log("probe " + c + " exception: " + t);
      }
    }
    log("suPath=" + resolved);
    return resolved;
  }

  /** root 是否可用 */
  public static boolean isAvailable() {
    return suPath() != null;
  }

  /** 执行 root 命令，exit 0 返回 true */
  public static boolean execOk(String cmd) {
    Process p = start(cmd);
    if (p == null) {
      log("execOk FAIL(no su): " + shortCmd(cmd));
      return false;
    }
    try {
      StringBuilder err = new StringBuilder();
      BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
      String line;
      while ((line = e.readLine()) != null) err.append(line).append(';');
      boolean done = p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
      int rc = done ? p.exitValue() : -1;
      if (!done) p.destroy();
      log("execOk rc=" + rc + " err=" + trim(err.toString()) + " cmd=" + shortCmd(cmd));
      return done && rc == 0;
    } catch (Throwable t) {
      log("execOk exception: " + t + " cmd=" + shortCmd(cmd));
      return false;
    }
  }

  /** 执行 root 命令并返回 stdout（strip 换行）；失败或无输出返回 null */
  public static String execRead(String cmd) {
    Process p = start(cmd);
    if (p == null) {
      log("execRead FAIL(no su): " + shortCmd(cmd));
      return null;
    }
    try {
      StringBuilder sb = new StringBuilder();
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      String line;
      while ((line = r.readLine()) != null) sb.append(line);
      drain(p);
      boolean done = p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
      if (!done) p.destroy();
      log("execRead len=" + sb.length() + " done=" + done + " cmd=" + shortCmd(cmd));
      return sb.length() > 0 ? sb.toString() : null;
    } catch (Throwable t) {
      log("execRead exception: " + t + " cmd=" + shortCmd(cmd));
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
      log("start exception: " + t + " cmd=" + shortCmd(cmd));
      return null;
    }
  }

  // =========================================================================
  // 日志（logcat + files/su_debug.log）
  // =========================================================================

  private static String shortCmd(String cmd) {
    if (cmd == null) return "null";
    return cmd.length() > 80 ? cmd.substring(0, 80) + "..." : cmd;
  }

  private static String trim(String s) {
    if (s == null) return "";
    return s.length() > 160 ? s.substring(0, 160) + "..." : s;
  }

  private static void drain(Process p) {
    try {
      BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
      while (e.readLine() != null) { /* drain */ }
    } catch (Throwable ignored) {
    }
  }

  private static void log(String msg) {
    Log.i(TAG, msg);
    if (logDir == null) return;
    try {
      File f = new File(logDir, LOG_FILE);
      String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
          + " " + msg + "\n";
      try (FileOutputStream fos = new FileOutputStream(f, true)) {
        fos.write(line.getBytes());
      }
    } catch (Throwable ignored) {
    }
  }
}
