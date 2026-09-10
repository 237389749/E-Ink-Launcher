package cn.modificator.launcher;

import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

/**
 * 全局刷新模式 EAC per-app 配置写入入口（root app_process 运行，**save-only**）。
 *
 * 由 launcher 经 {@code su -c "CLASSPATH=<apk> app_process /system/bin cn.modificator.launcher.GlobalEacRefreshHelper <cmd> ..."}
 * 启动。将每个 pkg 的全部 theme 的 refreshConfig 统一改写为
 * {@code refreshModeIndex="NONE" + updateMode=<UI 模式值>} 并 **只 saveEACAppThemes 持久化**，
 * 使 OECService 下次启动/重载后 per-app 决策（caculateRefreshConfig：NONE → updateMode 直通）
 * 对该 app 采用目标档。作为双管齐下方案的 EAC 持久兜底通道（scope 主通道即时生效）。
 *
 * ⚠️ 刻意不做 applyEACAppThemes（内存热应用）：2026-09-10 实测批量 apply 12 个第三方
 * app 的 theme → OECService 对每 app 发 configChanged/重建 activity → system_server 窗口
 * token 风暴（dropbox WTF: WakeLock forbidden @ WindowToken.setExiting）+ 系统重启
 * （bootreason=reboot）。save-only 写入 MMKV 无热应用副作用；scope 通道负责即时生效，
 * EAC 配置在下次 OECService 启动重载后接管不走 scope 的路径。
 *
 * cmd:
 *   set     <pkgCsv> <updateMode>   对每 pkg 全部 theme 写 NONE+updateMode（save-only）；首写前自动备份
 *   restore <pkgCsv>                从备份恢复原 theme（save-only，用于"None/恢复默认"档）
 * 备份文件：/data/local/tmp/eac_bak/<pkg>.json（每行一个 theme JSON）
 */
public class GlobalEacRefreshHelper {

  private static final String TAG = "GlobalEacRefresh";
  private static final String BAK_DIR = "/data/local/tmp/eac_bak";

  private static Class<?> eInkHelperClass;

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("usage: GlobalEacRefreshHelper <set|restore> <pkgCsv> [updateMode] | official <logicMode>");
      System.exit(2);
      return;
    }
    String cmd = args[0];
    if ("official".equals(cmd)) {
      // 官方写法（双防御第二道）：EInkHelper.setAppScopeRefreshMode(逻辑mode)
      // → OECService.setAppScopeRefreshMode → TabletEACRefreshImpl：
      //   改当前 top app + fallback 内存配置 + 立即 applyAppScopeUpdate(pkg) + saveDeviceConfig 持久化
      // root app_process 绕过 SELinux 限制（普通第三方被拦）
      int logicMode = Integer.parseInt(args[1]);
      if (!init()) {
        System.err.println("FAIL EInkHelper unavailable");
        System.exit(1);
        return;
      }
      try {
        eInkHelperClass.getMethod("setAppScopeRefreshMode", int.class).invoke(null, logicMode);
        System.out.println("OK official setAppScopeRefreshMode mode=" + logicMode);
        System.exit(0);
      } catch (Throwable t) {
        System.err.println("FAIL official: " + t);
        System.exit(1);
      }
      return;
    }
    String[] pkgs = args[1].split(",");
    int mode = 0;
    if ("set".equals(cmd)) {
      if (args.length < 3) {
        System.err.println("set requires updateMode arg");
        System.exit(2);
        return;
      }
      mode = Integer.parseInt(args[2]);
    }
    if (!init()) {
      System.err.println("FAIL EInkHelper unavailable");
      System.exit(1);
      return;
    }
    int okCount = 0;
    int skipCount = 0;
    for (String pkg : pkgs) {
      try {
        String out = "set".equals(cmd) ? applySet(pkg, mode) : applyRestore(pkg);
        if (out == null) {
          skipCount++;
          System.out.println("SKIP " + pkg + " (no themes)");
        } else {
          okCount++;
          System.out.println("OK " + pkg + " -> " + out);
        }
      } catch (Throwable t) {
        skipCount++;
        System.out.println("FAIL " + pkg + " : " + t);
      }
    }
    System.out.println("RESULT cmd=" + cmd + " mode=" + mode + " ok=" + okCount + " skip=" + skipCount);
    System.exit(0);
  }

  private static boolean init() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
          org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/onyx;");
        } catch (Throwable ignored) {
          // root app_process 下通常无需豁免；失败不影响
        }
      }
      eInkHelperClass = Class.forName("android.onyx.optimization.EInkHelper");
      return true;
    } catch (Throwable t) {
      System.err.println("init failed: " + t);
      return false;
    }
  }

  /** 统一写 NONE+updateMode；首写前把原 theme 备份到 BAK_DIR/<pkg>.json */
  @SuppressWarnings("unchecked")
  private static String applySet(String pkg, int mode) throws Exception {
    List<String> themes = (List<String>) eInkHelperClass
        .getMethod("loadThemes", String.class).invoke(null, pkg);
    if (themes == null || themes.isEmpty()) return null;
    backupIfAbsent(pkg, themes);
    List<String> edited = new ArrayList<>();
    int n = 0;
    for (String t : themes) {
      if (t == null) continue;
      JSONObject root = new JSONObject(t);
      JSONObject rc = root.getJSONObject("appConfig")
          .getJSONObject("globalActivityConfig")
          .getJSONObject("refreshConfig");
      rc.put("refreshModeIndex", "NONE");
      rc.put("updateMode", mode);
      edited.add(root.toString());
      n++;
    }
    if (n == 0) return null;
    // save-only：持久化到 MMKV，供 OECService 下次启动/重载读取；不 apply（避免热应用
    // 窗口重建风暴与系统重启，见类头注释）。
    eInkHelperClass.getMethod("saveEACAppThemes", List.class).invoke(null, edited);
    return "themes=" + n + " NONE+mode=" + mode + " (save-only)";
  }

  /** 从备份恢复原 theme（None/恢复默认档） */
  @SuppressWarnings("unchecked")
  private static String applyRestore(String pkg) throws Exception {
    File bak = new File(BAK_DIR, pkg + ".json");
    if (!bak.exists()) return "no-backup";
    List<String> originals = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(
        new FileInputStream(bak), StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.trim().length() > 0) originals.add(line);
      }
    }
    if (originals.isEmpty()) return "empty-backup";
    // save-only（同 applySet 理由）
    eInkHelperClass.getMethod("saveEACAppThemes", List.class).invoke(null, originals);
    return "restored=" + originals.size() + " (save-only)";
  }

  private static void backupIfAbsent(String pkg, List<String> themes) throws Exception {
    File dir = new File(BAK_DIR);
    if (!dir.exists() && !dir.mkdirs()) {
      System.err.println("bak dir create failed: " + dir);
    }
    File bak = new File(dir, pkg + ".json");
    if (bak.exists()) return; // 只备份首次
    StringBuilder sb = new StringBuilder();
    for (String t : themes) {
      if (t != null) sb.append(t).append('\n');
    }
    try (OutputStreamWriter w = new OutputStreamWriter(
        new FileOutputStream(bak), StandardCharsets.UTF_8)) {
      w.write(sb.toString());
    }
  }
}
