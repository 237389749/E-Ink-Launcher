package cn.modificator.launcher;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件日志工具 — 输出到 Download/EInkLauncher.log。
 * 用于排查启动流程和图标加载问题。
 */
public class FileLog {

  private static final String TAG = "FileLog";
  private static final SimpleDateFormat DF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());
  private static File logFile;

  private FileLog() {
  }

  public static void init() {
    try {
      File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
      if (!dir.exists()) dir.mkdirs();
      logFile = new File(dir, "EInkLauncher.log");
      // 每次启动覆盖旧日志，避免文件膨胀
      try (PrintWriter w = new PrintWriter(new FileWriter(logFile, false))) {
        w.println("=== E-Ink Launcher Startup " + DF.format(new Date()) + " ===");
        w.println("model=" + android.os.Build.MODEL + " sdk=" + android.os.Build.VERSION.SDK_INT);
        w.println();
        w.flush();
      }
    } catch (IOException e) {
      Log.e(TAG, "Failed to init log file", e);
    }
  }

  public static void log(String tag, String msg) {
    Log.d(tag, msg);
    if (logFile == null) return;
    try (FileWriter fw = new FileWriter(logFile, true);
         PrintWriter pw = new PrintWriter(fw)) {
      pw.println(DF.format(new Date()) + " [" + tag + "] " + msg);
      pw.flush();
    } catch (IOException e) {
      Log.e(TAG, "Failed to write log", e);
    }
  }

  public static void log(String tag, String msg, Throwable t) {
    Log.e(tag, msg, t);
    if (logFile == null) return;
    try (FileWriter fw = new FileWriter(logFile, true);
         PrintWriter pw = new PrintWriter(fw)) {
      pw.println(DF.format(new Date()) + " [" + tag + "] " + msg);
      StringWriter sw = new StringWriter();
      t.printStackTrace(new PrintWriter(sw));
      pw.println(sw.toString());
      pw.flush();
    } catch (IOException e) {
      Log.e(TAG, "Failed to write log", e);
    }
  }
}
