package cn.modificator.launcher;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 墨水屏刷新模式切换 —— 仅 scope 通道（EAC 定死，不随档位写入）。
 *
 * 模式集（6 档，采用 **scope UI/EPD 值域**，见 {@link #SCOPE_VALUES}）：
 *   None / NORMAL（清 scope）+ DU(257) / DU_RAW(1) / REGAL(6) / REGAL_PLUS(9)
 *
 * **仅保留 update[0]（局部刷新）族**（ref.md §9.3.4 / §9.3.5 全 18 模式实测矩阵）：
 * A2(2308) / X(16777220) 等 update[1]（全屏刷新）族在故障机必然触发 wait all_lut_free 超时
 * → reset → 卡顿（实测每轮 reset 12 次），已从档位集去除。
 *
 * 通道设计（ref.md §9.3.6 源码级定论）：
 * 1. scope（ViewUpdateHelper.applyAppScopeUpdate，null 包名 = 全局）是改变第三方 app 真实
 *    合成翻页波形的【唯一】有效主通道，且**接受任意 UI/EPD 值**（不经 EACUtils.toEpdMode）。
 * 2. EAC 通道的 updateMode 字段被 toEpdMode 硬归一化（非 0-5 → 5），承载不了
 *    257 等实测最优值；且切档批量写 12 个 app 会引发窗口重建风暴 → system_server WTF
 *    刷屏 → Watchdog 60s 重启（ref.md §12.7 实测撞上）。
 *    ⇒ **EAC 不随档位变**，由 {@link #applyFixedEac()} 一次性定死为 REGAL(3)
 *    （启用周期 GC + 滚动瞬态；其子路径模式恒为 toEpdMode(0)=AUTO=GC16 局部，实测安全）。
 *
 * 执行：切档与启动恢复均只走 scope 段（byPass 暂停 → 设 scope → 恢复 → 整屏全刷）。
 * {@link #applyWithEac(int)} 保留为 {@link #apply(int)} 的别名（兼容既有调用点）。
 */
public class RefreshModeHelper {

  /**
   * 档位 → scope 通道 UI/EPD 值（-1 = 清 scope，交系统默认）。
   *
   * 实测依据（ref.md §9.3.4 / §9.3.5 / §9.3.7 / §9.3.10，2026-09-23 全 18 模式矩阵）：
   * **仅保留 update[0]（局部刷新）族**；A2(2308)/X(16777220) 等 update[1]（全屏刷新）族
   * 在故障机必然触发 wait all_lut_free 超时 → reset → 卡顿（实测 reset 12/轮），已去除。
   *   257 = DU | DITHER_MODE(0x100)：实测 GC16 38 帧**局部**（16 级灰、不卡）
   *   1   = 裸 DU：实测 DU 22 帧局部（2 级黑白）
   *   6 / 9 = REGAL / REGAL_PLUS：实测落 sg 槽 2（GC16 38 帧局部）
   *
   * ⚠️ 已弃用 `X_DU`(16777217 = DU | ONYX_AUTO(0x1000000))：实测波形与裸 `DU` **完全相同**
   *   （同落 `waveform[1]`/22帧），那位无可见收益 —— 见 §9.3.10 ⑤。
   */
  private static final int[] SCOPE_VALUES = {-1, -1, 257, 1, 6, 9};

  /** 可选模式集（仅局部刷新族；None/NORMAL = 清 scope） */
  public static final String[] MODE_NAMES = {
      "None",
      "NORMAL",
      "DU",
      "DU_RAW",
      "REGAL",
      "REGAL_PLUS",
  };

  public static final String[] LABELS = {
      "None — 还原各 app 原配置（备份恢复）",
      "NORMAL — 系统默认（清 scope）",
      "DU — 有灰阶(16级)·38帧局部（DU+抖动）",
      "DU_RAW — 纯黑白·22帧局部（裸 DU）",
      "REGAL — 低残影·38帧局部",
      "REGAL_PLUS — 高质量·38帧局部",
  };

  private static final String VIEW_UPDATE_HELPER = "android.onyx.ViewUpdateHelper";
  /** EAC 决策 API 所在类（boot classpath，root app_process 可加载；GlobalEacRefreshHelper 用） */
  private static final String EAC_HELPER = "android.onyx.optimization.EInkHelper";
  private static final String LOG_FILE = "refresh_mode.log";

  private static Context appContext;
  private static Class<?> viewUpdateHelperClass;
  private static Class<?> eacHelperClass;
  private static boolean inited;
  private static boolean eacInited;

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
      initEac();
      return true;
    } catch (Throwable t) {
      log("init: ViewUpdateHelper FAILED: " + t);
      return false;
    }
  }

  /** EInkHelper 可用性探测（GlobalEacRefreshHelper 兜底通道；不可用则仅 scope） */
  private static boolean initEac() {
    if (eacInited) return eacHelperClass != null;
    eacInited = true;
    try {
      eacHelperClass = Class.forName(EAC_HELPER);
      return true;
    } catch (Throwable t) {
      log("init: EInkHelper unavailable (EAC channel disabled): " + t);
      return false;
    }
  }

  /**
   * 仅 scope 通道应用全局刷新模式（index 对应 MODE_NAMES），成功返回 true。
   * 用于启动恢复（Launcher.onCreate）：EAC 配置已持久化于系统 MMKV，无需重写。
   * 机制：byPass(10) 暂停 EPDC → 设 scope → byPass(0) 恢复 → 补「刷新屏幕」整屏全刷。
   */
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

  /**
   * 设置面板切档入口：**仅设 scope**。
   *
   * 旧实现为"双管齐下"（scope + 遍历写 12 个第三方 app 的 EAC theme），实测会引发窗口重建
   * 风暴 → system_server WTF 刷屏 → Watchdog 60s 重启（ref.md §12.7 撞上；§9.3.6 定性）。
   * 且 EAC 的 updateMode 字段被 EACUtils.toEpdMode 归一化（非 0-5 → 5），无法承载
   * 257 等实测最优值 —— 故切档与 EAC 解耦。
   */
  public static boolean applyWithEac(int index) {
    return apply(index);
  }

  /** EAC 定死值：逻辑 REGAL(3)。
   *  依据 ref §9.3.6：EAC mode ∈ {0,3,5} 才启用防抖/周期 GC，1/2/4 会关闭它们；
   *  且子路径模式恒为 toEpdMode(0)=5(AUTO) —— 实测 GC16 38 帧**局部**、reset 0（安全）。
   *  选 3 而非 0：额外启用滚动/触摸瞬态更新（0 被 applyDebouncerTransientUpdateMode 排除）。 */
  private static final int FIXED_EAC_LOGIC = 3;

  /**
   * 一次性把 EAC 定死为 {@link #FIXED_EAC_LOGIC}（手动调用；EAC 为 save-only，需重启后
   * 由 OECService 重载才耐久生效）。
   *
   * 调官方 {@code EInkHelper.setAppScopeRefreshMode(3)}：改当前 top app + fallback 内存配置
   * + saveDeviceConfig 持久化。**注意**：它会顺手设置 per-app scope，可能覆盖全局 null scope
   * —— 调用方若要保持全局档位，请在其后重新 {@link #apply(int)}。
   */
  public static boolean applyFixedEac() {
    if (!init() || !initEac()) return false;
    try {
      String clazz = GlobalEacRefreshHelper.class.getName();
      String apk = appContext.getApplicationInfo().sourceDir;
      String cmd = "CLASSPATH=" + apk + " app_process /system/bin " + clazz
          + " official " + FIXED_EAC_LOGIC;
      log("fixedEac: official -> su -c " + cmd);
      String out = runRoot(cmd);
      log("fixedEac: output:\n" + out);
      boolean ok = out.contains("OK official");
      toast(ok ? "EAC 已定死为 REGAL(3)" : "EAC 定死失败（见 refresh_mode.log）");
      return ok;
    } catch (Throwable t) {
      log("fixedEac: EXCEPTION " + t + "\n" + stackTrace(t));
      return false;
    }
  }

  /** su + 执行，收集 stdout/stderr 合并输出（su 路径经 SuHelper 探测绝对路径） */
  private static String runRoot(String cmd) throws Exception {
    Process p = SuHelper.start(cmd);
    if (p == null) {
      return "su not available (SuHelper)\n";
    }
    StringBuilder sb = new StringBuilder();
    InputStream is = p.getInputStream();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
      String line;
      while ((line = r.readLine()) != null) {
        sb.append(line).append('\n');
      }
    }
    InputStream es = p.getErrorStream();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(es))) {
      String line;
      while ((line = r.readLine()) != null) {
        sb.append("ERR ").append(line).append('\n');
      }
    }
    p.waitFor();
    sb.append("exit=").append(p.exitValue()).append('\n');
    return sb.toString();
  }

  /** byPass 暂停 EPDC → 只设 scope → 恢复 → 模式走完后补一次「刷新屏幕」全刷
   *  （finally 保证恢复，避免 EPDC 卡在暂停态） */
  private static boolean doApplyWithBypass(int index) {
    boolean ok = false;
    try {
      byPass(10);
      sleep(300);
      ok = doSetScope(index);
      sleep(500);
    } finally {
      byPass(0);
    }
    if (ok) {
      // byPass 已恢复、scope 已生效：等 EPDC 稳定后做一次「刷新屏幕」（磁贴同款整屏全刷）
      sleep(200);
      fullRefreshScreen();
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

  /**
   * 设 scope（在 byPass 暂停段内调用）：按 {@link #SCOPE_VALUES} 取 UI/EPD 值，
   * 经 applyAppScopeUpdate(null) 设为全局 scope（scope 通道接受任意值，不经 toEpdMode）。
   * 值为 -1（None / NORMAL）→ clearAppScopeUpdate（交系统默认）。
   */
  private static boolean doSetScope(int index) {
    try {
      int value = SCOPE_VALUES[index];
      if (value < 0) { // None / NORMAL：清除 scope，交系统管理
        log("apply: clearing app scope (" + MODE_NAMES[index] + ")");
        viewUpdateHelperClass.getMethod("clearAppScopeUpdate", boolean.class).invoke(null, true);
      } else {
        log("apply: globalScope " + MODE_NAMES[index] + " -> ui=" + value);
        // 全局 scope（null 包名 = SurfaceFlinger 所有窗口，含第三方应用）
        viewUpdateHelperClass
            .getMethod("applyAppScopeUpdate", String.class, boolean.class, int.class, int.class, int.class)
            .invoke(null, null, true, 0, value, Integer.MAX_VALUE);
      }
      return true;
    } catch (Throwable t) {
      log("apply: EXCEPTION " + t + "\n" + stackTrace(t));
      return false;
    }
  }

  /**
   * per-app scope：对指定包名单独设 scope（scope 通道接受任意 UI 值，不受 EAC 逻辑域限制）。
   * 供长按图标菜单使用。None / NORMAL（清 scope）对 per-app 无对应语义，返回 false。
   */
  public static boolean applyPerApp(String pkg, int index) {
    if (pkg == null || index < 0 || index >= SCOPE_VALUES.length) return false;
    if (!init()) return false;
    int value = SCOPE_VALUES[index];
    if (value < 0) {
      log("perApp: " + pkg + " -> " + MODE_NAMES[index] + "（清 scope 档不适用于 per-app）");
      return false;
    }
    try {
      viewUpdateHelperClass
          .getMethod("applyAppScopeUpdate", String.class, boolean.class, int.class, int.class, int.class)
          .invoke(null, pkg, true, 0, value, Integer.MAX_VALUE);
      log("perApp: " + pkg + " -> " + MODE_NAMES[index] + " ui=" + value);
      return true;
    } catch (Throwable t) {
      log("perApp: EXCEPTION " + t + "\n" + stackTrace(t));
      return false;
    }
  }

  /** 「刷新屏幕」：等同通知栏刷新屏幕磁贴的一次整屏全刷（无参 repaintEverything，
   *  按当前已生效的 scope 波形重画全部窗口）。失败只记日志，不把已生效的 scope 判为失败。 */
  private static void fullRefreshScreen() {
    try {
      viewUpdateHelperClass.getMethod("repaintEverything").invoke(null);
      log("apply: refresh screen (post-apply) -> OK");
    } catch (Throwable t) {
      log("apply: refresh screen (post-apply) EXCEPTION " + t);
    }
  }

  /** root 执行 settings put global hidden_api_policy 1（Magisk su；非 root 设备静默失败） */
  private static boolean enableHiddenApiPolicyViaRoot() {
    boolean ok = SuHelper.execOk("settings put global hidden_api_policy 1");
    log("apply: su hidden_api_policy ok=" + ok);
    return ok;
  }

  private static void toast(String msg) {
    if (appContext == null) return;
    android.os.Handler main = new android.os.Handler(appContext.getMainLooper());
    main.post(new Runnable() {
      @Override
      public void run() {
        Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show();
      }
    });
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
