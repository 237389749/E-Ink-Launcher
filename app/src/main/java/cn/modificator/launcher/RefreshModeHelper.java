package cn.modificator.launcher;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 墨水屏全局刷新模式切换。
 *
 * 模式集与系统引擎 4 模式（Normal/DU/A2/X）互补：
 *   1. None            — 恢复默认（清除 launcher 的 app scope，回归系统 per-app 模式管理）
 *   2. GU              — 手写模式，16 级灰度，无闪烁（日常最舒适）
 *   3. DEEP_GC         — 深度全刷，最彻底清除残影（最清晰）
 *   4. ANIMATION_X     — X 模式，极速响应（最快）
 *   5. ANIMATION_MONO  — 单色 A2，纯黑白滑动
 *
 * 实现：反射调用 framework 私有类 {@code android.onyx.ViewUpdateHelper}（Onyx 定制 ROM
 * 已将其编入 boot classpath，第三方应用可加载）。对 launcher 自身设置 app scope 波形，
 * None 则清除 scope 交还系统管理。
 *
 * 两步处理 hidden API 限制（Android 9+）：
 *   1. HiddenApiBypass 豁免 {@code android.onyx} 包
 *   2. 若仍失败，root 下执行 {@code settings put global hidden_api_policy 1} 后重试
 *
 * 所有关键步骤写入文件日志 {@code files/refresh_mode.log}（本机 logcat 缓冲可能失效，
 * 文件日志用于排查；root 可读：adb shell su -c cat /data/data/.../files/refresh_mode.log）。
 */
public class RefreshModeHelper {

  /** ViewUpdateHelper UI 模式值（ref.md：UI 组合标志位） */
  private static final int UI_GU_MODE = 2;
  private static final int UI_DEEP_GC_MODE = 108;
  private static final int UI_X_A2_MODE = 16777220;
  private static final int UI_MONO_A2_MODE = 33554436;
  /** None 用 -1 表示清除 scope */
  private static final int UI_NONE = -1;

  public static final String[] MODE_NAMES = {
      "None",
      "GU",
      "DEEP_GC",
      "ANIMATION_X",
      "ANIMATION_MONO",
  };

  public static final String[] LABELS = {
      "恢复默认（系统 per-app 模式）",
      "GU — 无闪烁，16 级灰度（日常）",
      "DEEP GC — 深度全刷（最清晰）",
      "ANIM X — 极速响应（最快）",
      "ANIM MONO — 纯黑白滑动",
  };

  private static final int[] MODE_VALUES = {
      UI_NONE, UI_GU_MODE, UI_DEEP_GC_MODE, UI_X_A2_MODE, UI_MONO_A2_MODE
  };

  private static final String VIEW_UPDATE_HELPER = "android.onyx.ViewUpdateHelper";
  private static final String APP_PACKAGE = "cn.modificator.launcher";
  private static final String LOG_FILE = "refresh_mode.log";

  private static Context appContext;
  private static Class<?> viewUpdateHelperClass;
  private static boolean inited;

  private RefreshModeHelper() {}

  /** 需在 Application/Launcher 启动时调用，用于文件日志 */
  public static void init(Context context) {
    appContext = context.getApplicationContext();
  }

  /** Onyx 定制 ROM 可加载 ViewUpdateHelper 时返回 true（非 Onyx 设备 UI 应隐藏入口） */
  public static boolean isAvailable() {
    init();
    return viewUpdateHelperClass != null;
  }

  private static boolean init() {
    if (inited) return viewUpdateHelperClass != null;
    inited = true;
    log("== init: SDK=" + Build.VERSION.SDK_INT + " ==");
    try {
      // Android 9+ 隐藏 API 限制：豁免 android.onyx 私有框架包
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
          org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/onyx;");
          log("init: HiddenApiBypass exemption added");
        } catch (Throwable t) {
          log("init: HiddenApiBypass failed: " + t);
        }
      }
      viewUpdateHelperClass = Class.forName(VIEW_UPDATE_HELPER);
      log("init: ViewUpdateHelper loaded, loader=" + viewUpdateHelperClass.getClassLoader());
      return true;
    } catch (Throwable t) {
      log("init: ViewUpdateHelper FAILED: " + t);
      return false;
    }
  }

  /** 应用全局刷新模式（index 对应 MODE_NAMES），成功返回 true */
  public static boolean apply(int index) {
    if (index < 0 || index >= MODE_NAMES.length) return false;
    if (!init()) return false;
    boolean ok = doApply(index);
    if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      // root 兜底：放开 hidden API 限制后重试一次
      log("apply: retry with hidden_api_policy=1");
      if (enableHiddenApiPolicyViaRoot()) {
        ok = doApply(index);
      }
    }
    log("apply: " + MODE_NAMES[index] + " -> " + (ok ? "OK" : "FAILED"));
    return ok;
  }

  private static boolean doApply(int index) {
    try {
      int value = MODE_VALUES[index];
      if (value == UI_NONE) {
        log("apply: clearing app scope");
        viewUpdateHelperClass.getMethod("clearAppScopeUpdate", boolean.class).invoke(null, true);
        viewUpdateHelperClass.getMethod("repaintEverything").invoke(null);
      } else {
        log("apply: appScope value=" + value);
        viewUpdateHelperClass
            .getMethod("applyAppScopeUpdate", String.class, boolean.class, int.class, int.class, int.class)
            .invoke(null, APP_PACKAGE, true, 0, value, Integer.MAX_VALUE);
        viewUpdateHelperClass.getMethod("repaintEverything", int.class).invoke(null, value);
      }
      return true;
    } catch (Throwable t) {
      log("apply: EXCEPTION " + t + "\n" + stackTrace(t));
      return false;
    }
  }

  /** root 执行 settings put global hidden_api_policy 1（Magisk su；非 root 设备静默失败） */
  private static boolean enableHiddenApiPolicyViaRoot() {
    try {
      Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "settings put global hidden_api_policy 1"});
      int code = p.waitFor();
      log("apply: su hidden_api_policy exit=" + code);
      return code == 0;
    } catch (Throwable t) {
      log("apply: su failed: " + t);
      return false;
    }
  }

  // =========================================================================
  // 文件日志（本机 logcat 缓冲可能失效，落盘便于排查）
  // =========================================================================

  private static void log(String msg) {
    try {
      if (appContext == null) return;
      File f = new File(appContext.getFilesDir(), LOG_FILE);
      String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
          + " " + msg + "\n";
      try (FileOutputStream fos = new FileOutputStream(f, true)) {
        fos.write(line.getBytes());
      }
    } catch (Throwable ignored) {
    }
    Log.i("RefreshMode", msg);
  }

  private static String stackTrace(Throwable t) {
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }
}
