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
 * 模式集（**5 档** = 3 个基础波形 + None/NORMAL，采用 **scope UI/EPD 值域**，见 {@link #SCOPE_VALUES}）：
 *   None / NORMAL（清 scope）+ DU(1) / GC16(2) / A2(4)
 *
 * **档位依据**（ref.md §9.3.15/§9.3.16 实测确证）：Poke6 波形库硬件上共 **4 种独立波形**
 * （DU / GC16 / A2 / DU4），全屏与局部通道都不会更多。
 * ⚠️ **DU4(2312) 已从选项移除**（用户决定）：2bpp 4 级灰 / 24 帧，视觉实测"极淡、只剩特征"
 * （ref.md §9.8），灰阶不如 GC16、速度不如 DU ⇒ 无实用价值。内核仍支持，需要时把 2312
 * 加回 {@link #SCOPE_VALUES}/MODE_NAMES/LABELS 的**末尾**即可（顺序即持久化 index）。
 *
 * ⚠️ **A2(4) 只在【未刷 v7 内核】的故障机上会 reset**（实测 10 次/5 轮）。
 *    该现象已在故障机内核上定位并修复，分两层（详见 HANDOFF §13 / §13.10）：
 *      · 第 1 层：tps6518x powerup 判据恒假 `reg 0x0F == 0xFA`（实读 0xBA）→ 反复拉低电源轨；
 *      · 第 2 层：`wait all_lut_free` 超时被 v3/v5 压到 500 ms，小于全屏 GC16 的 ~426 ms。
 *    ⇒ 刷 **v7H / v7G** 后 **A2(4) 实测 0 reset**（含原 DU4/108 在内六档全零）；
 *      未刷则沿用旧建议：故障机选 DU / GC16，A2 只用于正常机。
 *    现场判据：dmesg 里有无 `reset cause` / `wait all_lut_free timeout`。
 *
 * 通道设计（ref.md §9.3.6 源码级定论）：
 * 1. scope（ViewUpdateHelper.applyAppScopeUpdate，null 包名 = 全局）是改变第三方 app 真实
 *    合成翻页波形的【唯一】有效主通道，且**接受任意 UI/EPD 值**（不经 EACUtils.toEpdMode）。
 * 2. EAC 通道的 updateMode 字段被 toEpdMode 硬归一化（非 0-5 → 5），承载不了
 *    2312 这类实测值；且切档批量写 12 个 app 会引发窗口重建风暴 → system_server WTF
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
   * 档位 = Poke6 波形库里的基础波形（ref.md §9.3.15/§9.3.16 实测确证硬件共 4 种独立波形）：
   *   1    = DU   ：1bpp 纯黑白，22 帧，**多次脉冲**，不闪
   *   2    = GC16 ：4bpp 16 级灰，38 帧，**全摆动**（闪烁），覆盖 243/256
   *   4    = A2   ：5 帧，**单次脉冲**（最快），覆盖仅 18/256，残影最大
   * 另加 None（还原各 app 原配置）/ NORMAL（清 scope，交系统默认）两个非波形档。
   *
   * ⚠️ **DU4(2312) 已从选项移除**（用户决定）：它是 2bpp 4 级灰、24 帧，视觉实测
   * "极淡、只剩特征"（ref.md §9.8），灰阶不如 GC16、速度不如 DU ⇒ 无实用价值。
   * 内核仍支持该波形，需要时可把 2312 加回本数组末尾（**只能加在末尾**，见下）。
   * ⚠️ **本数组/`MODE_NAMES`/`LABELS` 的顺序即 `Config.KEY_REFRESH_MODE` 的持久化 index**，
   * 增删只能动**末尾**，否则会打乱已保存的档位。本次删的正是末尾项，0~4 语义不变。
   *
   * ⚠️ **波动类型与 reset 的关系**（ref.md §9.3.5 / HANDOFF §13.10）：
   *   DU / GC16 走 `update[0]` **局部刷新**，任何内核下都稳定；
   *   A2(4) 会走 `update[1]` **全屏刷新**路径 —— 在**未修内核的故障机**上必然
   *   `wait all_lut_free` 超时 → reset → 卡顿（实测 A2 10 次/5 轮）。
   *   ⇒ **已刷 v7H/v7G 的故障机：A2 也可用（实测 0 reset）；未刷：故障机请选 DU / GC16。**
   */
  private static final int[] SCOPE_VALUES = {-1, -1, 1, 2, 4};

  /** 可选模式集（3 个基础波形 + None/NORMAL 两个非波形档；DU4 已移除） */
  public static final String[] MODE_NAMES = {
      "None",
      "NORMAL",
      "DU",
      "GC16",
      "A2",
  };

  public static final String[] LABELS = {
      "None — 还原各 app 原配置（备份恢复）",
      "NORMAL — 系统默认（清 scope）",
      "DU — 纯黑白·22帧·1bpp",
      "GC16 — 16级灰·38帧·4bpp",
      "A2 — 最快·5帧·无灰阶",
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
   * A2(4) / DU4(2312) 等档位值 —— 故切档与 EAC 解耦。
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

  /** 切档后的收尾重画：**无参** `repaintEverything()` —— 按当前已生效的 scope 波形重画全部窗口。
   *  ⚠️ 它**带不上 FULL(32) 位**，只清「变化区域」的残影；整屏清残影请用 {@link #clearGhosting()}
   *  （ref.md §9.3.26④ 更正：磁贴走的是**带参**的 98 全屏 GC16，与此不等价）。
   *  失败只记日志，不把已生效的 scope 判为失败。 */
  private static void fullRefreshScreen() {
    try {
      viewUpdateHelperClass.getMethod("repaintEverything").invoke(null);
      log("apply: refresh screen (post-apply) -> OK");
    } catch (Throwable t) {
      log("apply: refresh screen (post-apply) EXCEPTION " + t);
    }
  }

  // =========================================================================
  // 整屏清残影
  // =========================================================================

  /**
   * 「整屏清残影」用的 UI/EPD 值：`GC16(2) | FULL(32) | WAIT(64)` = 98。
   *
   * 依据（ref.md §9.3.28⑨ / HANDOFF §13.9③，故障机实测）：
   *  · FULL(32) 只被**全摆动类**波形接受（GC16 / DEEP_GC16）；DU/A2/DU4 差分模式被拒；
   *  · 98 与通知栏「刷新屏幕」磁贴 / `applyGCOnce()` / gcInterval 全刷是**同一条路**；
   *  · 内核修好（v7F/v7G/v7H）后故障机实测 **0 reset / 8 次**（修复前"5 次中 3 次 reset"）。
   */
  public static final int UI_GC16_FULL = 98;

  /** 设置页「刷新模式」弹窗里追加的"清残影"项文字（不进入 {@link #LABELS}，故不影响 per-app 菜单） */
  public static final String CLEAR_GHOSTING_LABEL = "清残影 — 整屏全刷（GC16·38帧）";

  /**
   * 整屏清残影：反射调 `ViewUpdateHelper.repaintEverything(98)`。
   *
   * 与通知栏磁贴「刷新屏幕」同通道（ref.md §9.3.26）。**故障机上也是安全的** ——
   * 前提是内核已修（v7H/v7G/v7F）：实测 0 reset；未修内核的故障机上会触发 reset。
   * ⚠️ 整屏全刷会**明显闪一下**（全摆动波形），属正常现象。
   */
  public static boolean clearGhosting() {
    if (!init()) return false;
    try {
      viewUpdateHelperClass.getMethod("repaintEverything", int.class).invoke(null, UI_GC16_FULL);
      log("clearGhosting: repaintEverything(" + UI_GC16_FULL + ") -> OK");
      toast("已整屏清残影");
      return true;
    } catch (Throwable t) {
      log("clearGhosting: EXCEPTION " + t + "\n" + stackTrace(t));
      toast("清残影失败（见 refresh_mode.log）");
      return false;
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
