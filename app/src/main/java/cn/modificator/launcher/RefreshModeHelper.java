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
 * 精简模式集（6 项，覆盖高频需求 + 恢复默认）：
 *   None       — 清除 scope，回归系统 per-app 模式管理（系统引擎自身还提供多种模式）
 *   GU(2)      — 无闪烁，16 级灰度（日常）
 *   DEEP_GC(108) — 深度全刷（最清晰）
 *   REGAL(6)   — 低残影
 *   GC(98)     — 标准全刷
 *   GCC(107)   — 压缩全刷
 * （ANIMATION_X / ANIMATION_MONO 已移除：两者同为 2 级无灰阶，效果重复且不佳）
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

  /** ViewUpdateHelper UI 模式值（2026-09-05 实测定性：16级灰=GU/GC/DEEP_GC/REGAL_PLUS 全 38帧 GC16 等价组；
   *   A2=5帧无灰动画、DU=22帧黑白；详见 ref.md 第 9 节） */
  private static final int UI_GU_MODE = 2;
  private static final int UI_GC_MODE = 98;
  private static final int UI_DEEP_GC_MODE = 108;
  private static final int UI_REGAL_PLUS_MODE = 9;
  private static final int UI_A2_PERFORMANCE_MODE = 4;
  private static final int UI_DU_MODE = 1;
  /** None 用 -1 表示清除 scope */
  private static final int UI_NONE = -1;

  /** 可选模式集（按灰阶档组织：16级灰组 + 无灰阶组） */
  public static final String[] MODE_NAMES = {
      "None",
      "GU",
      "GC",
      "DEEP_GC",
      "REGAL_PLUS",
      "A2",
      "DU",
  };

  public static final String[] LABELS = {
      "恢复默认（系统 per-app 模式）",
      "GU — 16级灰·无闪（日常标准）",
      "GC — 16级灰·全刷（与 GU 等价，保留）",
      "DEEP GC — 16级灰·深度清理（多一次 DU 初始化，残影更彻底）",
      "REGAL PLUS — 16级灰·低残影名（Poke6 实际 = GC16）",
      "A2 — 无灰阶·5帧极速（动画/滚动；图标会丢灰阶）",
      "DU — 黑白·22帧完整（无灰但内容完整）",
  };

  private static final int[] MODE_VALUES = {
      UI_NONE, UI_GU_MODE, UI_GC_MODE, UI_DEEP_GC_MODE, UI_REGAL_PLUS_MODE,
      UI_A2_PERFORMANCE_MODE, UI_DU_MODE,
  };

  private static final String VIEW_UPDATE_HELPER = "android.onyx.ViewUpdateHelper";
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

  /** 返回指定 index 的 UI 模式值（供 per-app 配置 JSON 使用）；越界返回 -1 */
  public static int getModeValue(int index) {
    if (index < 0 || index >= MODE_VALUES.length) return -1;
    return MODE_VALUES[index];
  }

  /** 应用全局刷新模式（index 对应 MODE_NAMES），成功返回 true。
   *  采用 prodTest setVCom 同款机制：先 byPass(10) 暂停 EPDC 更新（等效息屏重启的干净初始化），
   *  再下发 scope + 全刷，最后 byPass(0) 恢复——故障机上模式切换更可靠生效。 */
  public static boolean apply(int index) {
    if (index < 0 || index >= MODE_NAMES.length) return false;
    if (!init()) return false;
    boolean ok = doApplyWithBypass(index);
    if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      // root 兜底：放开 hidden API 限制后重试一次
      log("apply: retry with hidden_api_policy=1");
      if (enableHiddenApiPolicyViaRoot()) {
        ok = doApplyWithBypass(index);
      }
    }
    log("apply: " + MODE_NAMES[index] + " -> " + (ok ? "OK" : "FAILED"));
    return ok;
  }

  /** byPass 暂停 EPDC → 执行切换 → 恢复（finally 保证恢复，避免 EPDC 卡在暂停态） */
  private static boolean doApplyWithBypass(int index) {
    boolean ok = false;
    try {
      byPass(10);
      sleep(300);
      ok = doApply(index);
      sleep(500);
    } finally {
      byPass(0);
    }
    return ok;
  }

  /** 反射调用 ViewUpdateHelper.byPass（SurfaceFlinger BYPASS 事务，暂停/恢复 EPDC 更新） */
  private static void byPass(int count) {
    try {
      viewUpdateHelperClass.getMethod("byPass", int.class).invoke(null, count);
      log("byPass(" + count + ") -> OK");
    } catch (Throwable t) {
      log("byPass(" + count + ") -> EXCEPTION " + t);
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ignored) {
    }
  }

  private static boolean doApply(int index) {
    try {
      int value = MODE_VALUES[index];
      if (value == UI_NONE) {
        log("apply: clearing app scope");
        viewUpdateHelperClass.getMethod("clearAppScopeUpdate", boolean.class).invoke(null, true);
        viewUpdateHelperClass.getMethod("repaintEverything").invoke(null);
      } else {
        log("apply: globalScope value=" + value);
        // 全局 scope（null 包名 = SurfaceFlinger 所有窗口，含第三方应用），再立即全屏刷新
        viewUpdateHelperClass
            .getMethod("applyAppScopeUpdate", String.class, boolean.class, int.class, int.class, int.class)
            .invoke(null, null, true, 0, value, Integer.MAX_VALUE);
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
