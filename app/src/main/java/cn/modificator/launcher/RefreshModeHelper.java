package cn.modificator.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 墨水屏刷新模式切换 —— 双管齐下（scope 主通道 + EAC per-app 配置兜底）。
 *
 * 模式集（7 档，采用 Onyx **逻辑 mode 域**，与官方磁贴/EAC 决策同语义）：
 *   None + 逻辑 0/1/2/3/4/5 = NORMAL/DU/A2/REGAL/X/REGAL_PLUS
 *   （Constant.UPDATE_MODE_* = 0 DEFAULT/NORMAL、1 DU、2 A2、3 REGAL、4 X、5 REGAL_PLUS）
 *
 * 值域说明（ref.md §12.9）：
 * - EAC refreshConfig.updateMode 字段在官方路径（setAppScopeRefreshMode 的 setUpdateMode）
 *   存的就是**逻辑 mode 0-5**；NONE 直通时该值交由 ViewUpdateHelper 解释 → 故本档位值
 *   直接作为 EAC 写入值（与官方一致）。
 * - scope 通道 applyAppScopeUpdate 接受 UI/EPD 值，需经 EACUtils.toEpdMode 转换：
 *   逻辑 0→5(AUTO) / 1→2305(DU|0x900) / 2→2308(A2|0x900) / 3→6(REGAL) /
 *   4→16777220(X_A2) / 5→9(REGAL_PLUS)。2305/2308 的基础值是 DU(1)/A2(4)，
 *   0x900 为质量标志位；DU4 是独立值（UI 2312 / EPD 8），逻辑域不可达。
 *
 * 通道（ref.md §12 两台真机确证）：
 * 1. scope（ViewUpdateHelper.applyAppScopeUpdate null 包名）是改变第三方 app 真实合成
 *    翻页波形的【唯一】有效主通道（实测 UI 1/2/4 → waveform1/22帧 DU、GC16 38帧、A2 5帧）；
 *    启动由 Launcher.onCreate 自动恢复 config 档。
 * 2. EAC per-app 配置（GlobalEacRefreshHelper 分批写 eac theme：refreshModeIndex=NONE +
 *    updateMode=<逻辑 mode>，save-only 持久化）作为兜底，消除批量热应用的窗口风暴；
 *    以 OECService 下次启动重载为生效点。
 *
 * 执行：scope 段同步（byPass 暂停 → 设 scope → 恢复 → 整屏全刷）；EAC 段在 scope 成功后
 * 后台分批执行。手动切档（设置面板）调 {@link #applyWithEac(int)}（双管）；
 * 启动恢复调 {@link #apply(int)}（仅 scope，EAC 配置已持久化于系统 MMKV）。
 */
public class RefreshModeHelper {

  /** Onyx 逻辑 mode 域（Constant.UPDATE_MODE_*；EAC 决策与官方磁贴同域） */
  private static final int LM_NONE = -1;
  private static final int LM_NORMAL = 0;
  private static final int LM_DU = 1;
  private static final int LM_A2 = 2;
  private static final int LM_REGAL = 3;
  private static final int LM_X = 4;
  private static final int LM_REGAL_PLUS = 5;

  /**
   * 逻辑 mode → scope 通道 UI/EPD 值（等价 EACUtils.toEpdMode，索引 0..5）。
   * 0→5(AUTO,交系统) / 1→2305(DU|0x900) / 2→2308(A2|0x900) / 3→6 / 4→16777220 / 5→9。
   * ⚠ 2305/2308/16777220 在 scope 通道的实测校准（与已知有效的裸值 1/4 对比）待设备重连验证。
   */
  private static final int[] LOGIC_TO_SCOPE = {5, 2305, 2308, 6, 16777220, 9};

  /** 可选模式集（Onyx 逻辑档：None + NORMAL/DU/A2/REGAL/X/REGAL_PLUS） */
  public static final String[] MODE_NAMES = {
      "None",
      "NORMAL",
      "DU",
      "A2",
      "REGAL",
      "X",
      "REGAL_PLUS",
  };

  public static final String[] LABELS = {
      "None — 还原各 app 原配置（备份恢复）",
      "NORMAL(0) — 系统默认（清 scope，GC16 族）",
      "DU(1) — 2级黑白·22帧（实测 waveform1/22）",
      "A2(2) — 无灰阶·5帧极速（实测 waveform6/5）",
      "REGAL(3) — 低残影（Poke6 实测 = GC16 38帧）",
      "X(4) — X 模式（AUTO+A2 组合）",
      "REGAL_PLUS(5) — 16级灰·高质量",
  };

  /** 档位值 = Onyx 逻辑 mode（直接用于 EAC refreshConfig.updateMode 写入） */
  private static final int[] MODE_VALUES = {
      LM_NONE, LM_NORMAL, LM_DU, LM_A2, LM_REGAL, LM_X, LM_REGAL_PLUS,
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

  /** 返回指定 index 的档位值（Onyx 逻辑 mode，供 per-app 配置 JSON 使用）；越界返回 -1 */
  public static int getModeValue(int index) {
    if (index < 0 || index >= MODE_VALUES.length) return -1;
    return MODE_VALUES[index];
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
   * 双管齐下：scope 主通道（同步，同上）+ EAC per-app 配置兜底（后台分批）。
   * 供设置面板手动切档调用。立即返回 scope 段结果；EAC 段在后台执行并 Toast 汇总。
   */
  public static boolean applyWithEac(int index) {
    if (index < 0 || index >= MODE_NAMES.length) return false;
    if (!init()) return false;
    boolean ok = apply(index);
    if (ok && initEac()) {
      final int idx = index;
      log("applyWithEac: EAC fallback async start (mode=" + MODE_NAMES[idx] + ")");
      Thread worker = new Thread(new Runnable() {
        @Override
        public void run() {
          doApplyEacFallback(idx);
        }
      }, "eac-fallback");
      worker.start();
    }
    return ok;
  }

  /** EAC 兜底每批处理的 pkg 数（防整批长任务在低内存设备被 OOM/SIGKILL，分批降低峰值与时长） */
  private static final int EAC_BATCH_SIZE = 4;

  /** 后台：root + app_process 调 GlobalEacRefreshHelper 分批遍历第三方 pkg 写 EAC 配置 */
  private static void doApplyEacFallback(int index) {
    try {
      List<String> pkgs = collectThirdPartyPkgs();
      if (pkgs.isEmpty()) {
        log("eac-fallback: no third-party pkg");
        return;
      }
      int value = MODE_VALUES[index];
      // ── 双防御第二道：官方写法（root）──────────────────────────────
      // EInkHelper.setAppScopeRefreshMode(逻辑档) → 改当前 top app + fallback 内存配置
      // + 立即 per-pkg scope + saveDeviceConfig 持久化（与官方磁贴同路径）。
      // 其内部会 applyAppScopeUpdate(当前pkg)/clear，可能影响我们的全局 null scope，
      // 故随后重设一次我们的全局 scope，确保主通道最终生效。
      if (value != LM_NONE) {
        try {
          String clazz0 = GlobalEacRefreshHelper.class.getName();
          String apk0 = appContext.getApplicationInfo().sourceDir;
          String officialCmd = "CLASSPATH=" + apk0 + " app_process /system/bin " + clazz0
              + " official " + value;
          log("eac-fallback: official -> su -c " + officialCmd);
          String officialOut = runRoot(officialCmd);
          log("eac-fallback: official output:\n" + officialOut);
        } catch (Throwable t) {
          log("eac-fallback: official EXCEPTION " + t);
        }
        sleep(300);
        if (doSetScope(index)) {
          log("eac-fallback: global scope re-applied after official (logic=" + value + ")");
        }
      }
      // ── 第三道：各 app 自定义 theme save-only 持久化（分批）────────────
      String cmd;
      if (value == LM_NONE) {
        cmd = "restore";
        log("eac-fallback: restore per-app backups for " + pkgs.size() + " pkgs");
      } else {
        cmd = "set";
        log("eac-fallback: unify " + pkgs.size() + " pkgs to NONE+updateMode=" + value
            + " (logic mode)");
      }
      int totalOk = 0;
      int totalSkip = 0;
      int killedBatches = 0;
      for (int from = 0; from < pkgs.size(); from += EAC_BATCH_SIZE) {
        int to = Math.min(from + EAC_BATCH_SIZE, pkgs.size());
        List<String> batch = pkgs.subList(from, to);
        String csv = TextUtils.join(",", batch);
        String clazz = GlobalEacRefreshHelper.class.getName();
        String apk = appContext.getApplicationInfo().sourceDir;
        String shellCmd = "CLASSPATH=" + apk + " app_process /system/bin " + clazz
            + " " + cmd + " " + csv + (value == LM_NONE ? "" : " " + value);
        log("eac-fallback: batch[" + from + "-" + (to - 1) + "] su -c " + shellCmd);
        String output = runRoot(shellCmd);
        log("eac-fallback: batch output:\n" + output);
        if (output.contains("RESULT cmd=")) {
          totalOk += parseCount(output, "ok=");
          totalSkip += parseCount(output, "skip=");
        } else if (output.contains("exit=137") || output.contains("Killed")) {
          killedBatches++; // 该批被系统 OOM 杀，跳过继续下一批
          log("eac-fallback: batch killed by system (OOM), continue");
        } else {
          totalSkip += batch.size();
        }
      }
      toast("EAC 兜底完成：写入 " + totalOk + " 个应用"
          + (killedBatches > 0 ? "（" + killedBatches + " 批被系统内存回收，可重试）" : ""));
    } catch (Throwable t) {
      log("eac-fallback: EXCEPTION " + t + "\n" + stackTrace(t));
      toast("EAC 兜底失败：" + t);
    }
  }

  /** 解析 GlobalEacRefreshHelper RESULT 行中的 key=N（形如 "RESULT cmd=set mode=4 ok=3 skip=1"） */
  private static int parseCount(String output, String key) {
    try {
      int idx = output.indexOf(key);
      if (idx < 0) return 0;
      int start = idx + key.length();
      int end = start;
      while (end < output.length() && Character.isDigit(output.charAt(end))) end++;
      return end > start ? Integer.parseInt(output.substring(start, end)) : 0;
    } catch (Throwable t) {
      return 0;
    }
  }

  /** 枚举第三方 pkg（排除自身、com.onyx / com.android 系统族，防破坏系统优化面板） */
  private static List<String> collectThirdPartyPkgs() {
    List<String> pkgs = new ArrayList<>();
    PackageManager pm = appContext.getPackageManager();
    List<ApplicationInfo> apps = pm.getInstalledApplications(0);
    String self = appContext.getPackageName();
    for (ApplicationInfo ai : apps) {
      String pkg = ai.packageName;
      if (pkg == null || pkg.equals(self)) continue;
      if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue; // 仅第三方
      if (pkg.startsWith("com.onyx")) continue; // Onyx 系统应用（自有主题通道）
      if (pkg.startsWith("com.android")) continue;
      pkgs.add(pkg);
    }
    return pkgs;
  }

  /** su + 执行，收集 stdout/stderr 合并输出 */
  private static String runRoot(String cmd) throws Exception {
    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
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
   * 设 scope（在 byPass 暂停段内调用）：逻辑档经 toEpdMode 转 UI 值后 applyAppScopeUpdate(null)。
   * None / NORMAL(0) → clearAppScopeUpdate（交系统默认，官方 mode 0 同款行为）。
   */
  private static boolean doSetScope(int index) {
    try {
      int logic = MODE_VALUES[index];
      if (logic <= LM_NORMAL) { // None(-1) / NORMAL(0)：清除 scope，交系统管理
        log("apply: clearing app scope (logic=" + logic + ")");
        viewUpdateHelperClass.getMethod("clearAppScopeUpdate", boolean.class).invoke(null, true);
      } else {
        int value = LOGIC_TO_SCOPE[logic];
        log("apply: globalScope logic=" + logic + " -> ui=" + value);
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
