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
 * 墨水屏全局刷新模式 —— "全局统一各 app 配置档位"（EAC per-app 通道）。
 *
 * 模式集（7 档，按灰阶档组织；2026-09-05 帧数实测定性，ref.md §9）：
 *   None / GU / GC / DEEP_GC / REGAL_PLUS / A2 / DU
 *   （16级灰组 GU=GC=DEEP_GC 帧数同族 38帧 GC16；A2=5帧无灰动画；DU=22帧黑白）
 *
 * 通道（res/eink-framework 源码 + ref.md §10-§13.1 实测定论）：
 * - scope 通道（ViewUpdateHelper null-package applyAppScopeUpdate + byPass + repaint）只走
 *   SurfaceFlinger scope，对第三方 app 的真实合成翻页**结构性无效**（翻页恒 GC16，§10/§11）。
 * - 有效通道 = OECService per-app EAC 决策：把每个第三方 app 的 theme 统一改写为
 *   {@code refreshModeIndex="NONE" + updateMode=<UI 值>}（NONE 时 caculateRefreshConfig 直通
 *   updateMode 无转换），经 saveEACAppThemes+applyEACAppThemes 全链持久化并热应用
 *   （active themeType=3；前台 app 会被系统重建载入新配置，无需整机重启）。
 *   §13 实测：Legado 翻页从恒 GC16(38帧) 变 DU(22帧)。
 *
 * 执行：root（su）+ app_process 运行 {@link GlobalEacRefreshHelper}，一次调用批量处理全部第三方
 * pkg；首写前自动备份原 theme 到 /data/local/tmp/eac_bak，None 档从备份恢复。异步执行
 * （数秒~数十秒），结果经 refresh_mode.log + Toast 反馈；成功后补一次整屏全刷
 * （repaintEverything）让屏幕立即呈现新波形。
 */
public class RefreshModeHelper {

  /** ViewUpdateHelper/UI 模式值（与 EAC refreshConfig.updateMode 直通同域，ref.md §13.1 源码确认） */
  private static final int UI_GU_MODE = 2;
  private static final int UI_GC_MODE = 98;
  private static final int UI_DEEP_GC_MODE = 108;
  private static final int UI_REGAL_PLUS_MODE = 9;
  private static final int UI_A2_PERFORMANCE_MODE = 4;
  private static final int UI_DU_MODE = 1;
  /** None 用 -1 表示恢复默认（restore 备份） */
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
      "恢复默认（还原各 app 原配置）",
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

  /** 决策 API 类（boot classpath，root app_process 可加载） */
  private static final String EAC_HELPER = "android.onyx.optimization.EInkHelper";
  /** 整屏全刷用（可选；不可用不影响 EAC 统一） */
  private static final String VIEW_UPDATE_HELPER = "android.onyx.ViewUpdateHelper";
  private static final String LOG_FILE = "refresh_mode.log";

  private static Context appContext;
  private static Class<?> eacHelperClass;
  private static Class<?> viewUpdateHelperClass;
  private static boolean inited;

  private RefreshModeHelper() {}

  /** 需在 Application/Launcher 启动时调用，用于文件日志 */
  public static void init(Context context) {
    appContext = context.getApplicationContext();
  }

  /** EInkHelper 可加载（Onyx 定制 ROM）时返回 true（非 Onyx 设备 UI 应隐藏入口） */
  public static boolean isAvailable() {
    init();
    return eacHelperClass != null;
  }

  private static boolean init() {
    if (inited) return eacHelperClass != null;
    inited = true;
    log("== init: SDK=" + Build.VERSION.SDK_INT + " ==");
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
          org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/onyx;");
          log("init: HiddenApiBypass exemption added");
        } catch (Throwable t) {
          log("init: HiddenApiBypass failed: " + t);
        }
      }
      eacHelperClass = Class.forName(EAC_HELPER);
      log("init: EInkHelper loaded");
      try {
        viewUpdateHelperClass = Class.forName(VIEW_UPDATE_HELPER);
      } catch (Throwable ignored) {
        log("init: ViewUpdateHelper unavailable (full-refresh post-step disabled)");
      }
      return true;
    } catch (Throwable t) {
      log("init: EInkHelper FAILED: " + t);
      return false;
    }
  }

  /** 返回指定 index 的 UI 模式值（供 per-app 配置 JSON 使用）；越界返回 -1 */
  public static int getModeValue(int index) {
    if (index < 0 || index >= MODE_VALUES.length) return -1;
    return MODE_VALUES[index];
  }

  /**
   * 应用全局刷新模式（index 对应 MODE_NAMES）。立即返回；实际统一写入在后台线程执行，
   * 结果经 refresh_mode.log 与 Toast 反馈；成功后补一次整屏全刷。返回 false 仅表示参数
   * 非法/通道不可用。
   */
  public static boolean apply(int index) {
    if (index < 0 || index >= MODE_NAMES.length) return false;
    if (!init()) {
      log("apply: rejected, EInkHelper unavailable");
      return false;
    }
    final int idx = index;
    log("apply: " + MODE_NAMES[idx] + " (EAC unified, async start)");
    Thread worker = new Thread(new Runnable() {
      @Override
      public void run() {
        doApplyEacUnified(idx);
      }
    }, "eac-unified-apply");
    worker.start();
    return true;
  }

  /** 后台执行：枚举第三方 pkg → su + app_process 调 GlobalEacRefreshHelper 统一写入 → 成功后整屏全刷 */
  private static void doApplyEacUnified(int index) {
    try {
      List<String> pkgs = collectThirdPartyPkgs();
      if (pkgs.isEmpty()) {
        log("apply: no third-party pkg found");
        return;
      }
      int value = MODE_VALUES[index];
      String cmd;
      String modeArg;
      if (value == UI_NONE) {
        cmd = "restore";
        modeArg = "";
        log("apply: restoring per-app backups for " + pkgs.size() + " pkgs");
      } else {
        cmd = "set";
        modeArg = " " + value;
        log("apply: unifying " + pkgs.size() + " pkgs to NONE+updateMode=" + value);
      }
      String csv = TextUtils.join(",", pkgs);
      String clazz = GlobalEacRefreshHelper.class.getName();
      String apk = appContext.getApplicationInfo().sourceDir;
      String shellCmd = "CLASSPATH=" + apk + " app_process /system/bin " + clazz
          + " " + cmd + " " + csv + modeArg;
      log("apply: su -c " + shellCmd);
      String output = runRoot(shellCmd);
      log("apply: exit output:\n" + output);
      // 成功 = RESULT 行 ok>=1；None 档全 no-backup（无备份可恢复=已是默认）也算成功
      boolean ok = output.matches("(?s).*RESULT cmd=(set|restore) .*ok=[1-9][0-9]*.*");
      if (!ok && value == UI_NONE && output.contains("no-backup")) {
        ok = true;
      }
      if (ok) {
        // 统一成功后补一次整屏全刷，让新波形立即呈现
        sleep(300);
        fullRefreshScreen();
      }
      toast("全局刷新模式已统一 " + pkgs.size() + " 个应用"
          + (value == UI_NONE ? "（已还原默认）" : "：" + MODE_NAMES[index]));
    } catch (Throwable t) {
      log("apply: EXCEPTION " + t + "\n" + stackTrace(t));
      toast("刷新模式统一失败：" + t);
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

  /** 「刷新屏幕」：整屏全刷（repaintEverything 无参，按已生效配置重画全部窗口）。失败仅记日志。 */
  private static void fullRefreshScreen() {
    try {
      if (viewUpdateHelperClass == null) return;
      viewUpdateHelperClass.getMethod("repaintEverything").invoke(null);
      log("apply: refresh screen (post-apply) -> OK");
    } catch (Throwable t) {
      log("apply: refresh screen (post-apply) EXCEPTION " + t);
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ignored) {
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
