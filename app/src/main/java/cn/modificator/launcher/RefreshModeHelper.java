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
 * 模式集（7 档，按灰阶档组织；2026-09-05 帧数实测定性，ref.md §9）：
 *   None/GU/GC/DEEP_GC/REGAL_PLUS/A2/DU
 *   （16级灰组 GU=GC=DEEP_GC 帧数同族 38帧 GC16；A2=5帧无灰动画；DU=22帧黑白）
 *
 * 通道（ref.md §12 两台真机确证，2026-09-08）：
 * 1. scope（ViewUpdateHelper.applyAppScopeUpdate null 包名）是改变第三方 app 真实合成
 *    翻页波形的【唯一】有效主通道：scope=GU/DU/A2 时翻页分别落 GC16/DU22/A2 5帧，
 *    与 EAC per-app 配置无关。启动由 Launcher.onCreate 自动恢复 config 档。
 * 2. EAC per-app 配置（GlobalEacRefreshHelper 遍历写 eac theme refreshConfig：
 *    refreshModeIndex=NONE + updateMode=<同 scope 的 UI 值>）作为**兜底**：覆盖部分
 *    普通 app（如起点读书）中不走 scope 的更新路径；None 档从备份 restore。
 *    实测 EAC 直通不影响 scope 已覆盖的合成翻页，但写入无害且为不走 scope 的路径
 *    提供第二通道。
 *
 * 执行：scope 段同步（byPass 暂停 → 设 scope → 恢复 → 整屏全刷）；EAC 段在 scope
 * 成功后后台执行（root su + app_process 调 GlobalEacRefreshHelper，遍历第三方 pkg）。
 * 手动切档（设置面板）调 {@link #applyWithEac(int)}（双管）；启动恢复调
 * {@link #apply(int)}（仅 scope，EAC 配置已持久化于系统 MMKV，重启无需重写）。
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

  /** 返回指定 index 的 UI 模式值（供 per-app 配置 JSON 使用）；越界返回 -1 */
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
   * 双管齐下：scope 主通道（同步，同上）+ EAC per-app 配置兜底（后台）。
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
      String cmd;
      if (value == UI_NONE) {
        cmd = "restore";
        log("eac-fallback: restore per-app backups for " + pkgs.size() + " pkgs");
      } else {
        cmd = "set";
        log("eac-fallback: unify " + pkgs.size() + " pkgs to NONE+updateMode=" + value);
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
            + " " + cmd + " " + csv + (value == UI_NONE ? "" : " " + value);
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

  /** 只设 scope（在 byPass 暂停段内调用；此处不做全刷——暂停态发出的刷新不保证执行） */
  private static boolean doSetScope(int index) {
    try {
      int value = MODE_VALUES[index];
      if (value == UI_NONE) {
        log("apply: clearing app scope");
        viewUpdateHelperClass.getMethod("clearAppScopeUpdate", boolean.class).invoke(null, true);
      } else {
        log("apply: globalScope value=" + value);
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
