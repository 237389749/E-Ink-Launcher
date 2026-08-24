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
 * 墨水屏刷新模式切换（launcher app scope）。
 *
 * 模式集覆盖速度↔质量完整谱系（14 项，全部有精确 UI 模式值，ref.md 映射表）：
 *   速度组：GU_FAST/DU(1)、A2_QUALITY(2308)、ANIMATION_X(16777220)、ANIMATION_MONO(33554436)
 *   质量组：GC(98)、GCC(107)、DEEP_GC(108)、GC4(3)、DU4(2312)、DU_QUALITY(2305)
 *   无闪烁：GU(2)；低残影：REGAL(6)、REGAL_PLUS(9)
 *   None    — 清除 scope，回归系统 per-app 模式管理
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

  /** ViewUpdateHelper UI 模式值（ref.md：SDMDevice 映射表） */
  private static final int UI_GU_FAST_DU = 1;          // DU / GU_FAST
  private static final int UI_GU_MODE = 2;
  private static final int UI_GC4_MODE = 3;
  private static final int UI_REGAL_MODE = 6;
  private static final int UI_REGAL_PLUS_MODE = 9;
  private static final int UI_GC_MODE = 98;
  private static final int UI_GCC_MODE = 107;
  private static final int UI_DEEP_GC_MODE = 108;
  private static final int UI_DU_QUALITY_MODE = 2305;
  private static final int UI_A2_QUALITY_MODE = 2308;
  private static final int UI_DU4_MODE = 2312;
  private static final int UI_X_A2_MODE = 16777220;
  private static final int UI_MONO_A2_MODE = 33554436;
  /** None 用 -1 表示清除 scope */
  private static final int UI_NONE = -1;

  /** 模式名 → UI 模式值；None 清除 scope 回归系统。覆盖速度↔质量完整谱系。 */
  public static final String[] MODE_NAMES = {
      "None",
      "GU",
      "GC4",
      "REGAL",
      "REGAL_PLUS",
      "GC",
      "GCC",
      "DEEP_GC",
      "GU_FAST",
      "DU_QUALITY",
      "A2_QUALITY",
      "DU4",
      "ANIMATION_X",
      "ANIMATION_MONO",
  };

  public static final String[] LABELS = {
      "恢复默认（系统 per-app 模式）",
      "GU — 无闪烁，16 级灰度（日常）",
      "GC4 — 4 级全刷",
      "REGAL — 低残影",
      "REGAL PLUS — 最高质量低残影",
      "GC — 标准全刷（清残影）",
      "GCC — 压缩全刷",
      "DEEP GC — 深度全刷（最清晰）",
      "GU FAST / DU — 快速 2 级",
      "DU QUALITY — DU 质量",
      "A2 QUALITY — 动画质量",
      "DU4 — 4 级 DU",
      "ANIM X — 极速响应（最快）",
      "ANIM MONO — 纯黑白滑动",
  };

  private static final int[] MODE_VALUES = {
      UI_NONE, UI_GU_MODE, UI_GC4_MODE, UI_REGAL_MODE, UI_REGAL_PLUS_MODE,
      UI_GC_MODE, UI_GCC_MODE, UI_DEEP_GC_MODE, UI_GU_FAST_DU, UI_DU_QUALITY_MODE,
      UI_A2_QUALITY_MODE, UI_DU4_MODE, UI_X_A2_MODE, UI_MONO_A2_MODE,
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
