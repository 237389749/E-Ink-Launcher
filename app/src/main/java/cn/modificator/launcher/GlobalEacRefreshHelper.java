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
 * 全局刷新模式统一入口（root app_process 运行）。
 *
 * 由 launcher 经 {@code su -c "CLASSPATH=<apk> app_process /system/bin cn.modificator.launcher.GlobalEacRefreshHelper <cmd> ..."}
 * 启动。将每个 pkg 的全部 theme 的 refreshConfig 统一改写为
 * {@code refreshModeIndex="NONE" + updateMode=<UI 模式值>} 并 save+apply，使 OECService 的
 * per-app 决策通道（{@code EACBaseRefreshImpl.caculateRefreshConfig}：NONE → updateMode 原值直通，
 * 无任何转换）对该 app 采用目标刷新档。对第三方 app 真实翻页有效（ref.md §13 实测 DU 22 帧）。
 *
 * 机制依据（res/eink-framework 反编译源码，ref.md §13.1 实测）：
 * - {@code saveEACAppThemes(List)}：全部 theme 持久化到 MMKV（重启后仍生效，长期决策源）
 * - {@code applyEACAppThemes(List)}：仅 active theme（默认 themeType=3）经 validateRefreshMode
 *   （只展开 refreshModeIndex≠NONE 的配置，NONE 直通原样放行）后内存热替换 deviceConfig 并广播
 *   → 前台 app 会被系统重建以载入新配置（无需整机重启；整机重启同样安全，OECService 启动重载 MMKV）
 * - updateMode 值域 = ViewUpdateHelper/UI 组合值（1=DU/2=GU/98=GC/107=GCC/108=DEEP_GC/…），
 *   与 RefreshModeHelper.MODE_VALUES 同域，可直接复用
 *
 * cmd:
 *   set     <pkgCsv> <updateMode>   对每 pkg 全部 theme 写 NONE+updateMode；首写前自动备份
 *   restore <pkgCsv>                从备份恢复原 theme（用于"None/恢复默认"档）
 * 备份文件：/data/local/tmp/eac_bak/<pkg>.json（每行一个 theme JSON）
 */
public class GlobalEacRefreshHelper {

  private static final String TAG = "GlobalEacRefresh";
  private static final String BAK_DIR = "/data/local/tmp/eac_bak";

  private static Class<?> eInkHelperClass;

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("usage: GlobalEacRefreshHelper <set|restore> <pkgCsv> [updateMode]");
      System.exit(2);
      return;
    }
    String cmd = args[0];
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
    eInkHelperClass.getMethod("saveEACAppThemes", List.class).invoke(null, edited);
    eInkHelperClass.getMethod("applyEACAppThemes", List.class).invoke(null, edited);
    return "themes=" + n + " NONE+mode=" + mode;
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
    eInkHelperClass.getMethod("saveEACAppThemes", List.class).invoke(null, originals);
    eInkHelperClass.getMethod("applyEACAppThemes", List.class).invoke(null, originals);
    return "restored=" + originals.size();
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
