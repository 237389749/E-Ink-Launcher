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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;

/**
 * 全局刷新模式 EAC per-app 配置写入入口（root app_process 运行，**save-only**）。
 *
 * ★★★ 2026-11-06 定位调整（重要）：本类**默认不启用**，是【独立的手动工具 / 诊断修复通道】。
 *   · launcher 的日常「切换刷新模式」**默认走纯 scope 通道**（{@link RefreshModeHelper}：
 *     byPass + applyAppScopeUpdate + repaint），**不写 /onyxconfig、不落盘、重启即回默认**。
 *   · 本类只在需要「持久改写第三方 app 的 EAC 刷新配置」（如修复历史残留、强制某 app
 *     固定档位）时，由用户**手动** `su -c ... app_process ...` 调用，**平时不自动触发**。
 *
 * 为什么降级为手动工具：写 `/onyxconfig/mmkv/onyx_config` 是 append-only，运行期只涨不缩，
 * 长期写入会逼近 2MB 容量边界，历史上已因此造成「卡开机」（§14.4/§14.10）。
 * 而纯 scope 通道不落盘、零遗留，代价是**不改第三方 app 的合成翻页/滚动波形**（恒 GC16）。
 * 两者物理不可兼得，故日常便捷性让位于「绝不再给 onyxconfig 遗留问题」的安全底线。
 *
 * 由 launcher 经 {@code su -c "CLASSPATH=<apk> app_process /system/bin cn.modificator.launcher.GlobalEacRefreshHelper <cmd> ..."}
 * 启动。将每个 pkg 的【active theme（themeType=3）】的 refreshConfig 统一改写为
 * {@code refreshModeIndex="NONE" + updateMode=<UI 模式值>} 并 **只 saveEACAppThemes 持久化**，
 * 使 OECService 下次启动/重载后 per-app 决策（caculateRefreshConfig：NONE → updateMode 直通）
 * 对该 app 采用目标档。作为 scope 主通道之外的持久兜底（仅在确需持久改配置时手动用）。
 *
 * ⚠️ 刻意不做 applyEACAppThemes（内存热应用）：2026-09-10 实测批量 apply 12 个第三方
 * app 的 theme → OECService 对每 app 发 configChanged/重建 activity → system_server 窗口
 * token 风暴（dropbox WTF: WakeLock forbidden @ WindowToken.setExiting）+ 系统重启
 * （bootreason=reboot）。save-only 写入 MMKV 无热应用副作用。
 *
 * cmd:
 *   set     <pkgCsv> <updateMode>   对每 pkg 的 active theme(3) 写 NONE+updateMode（save-only）；首写前自动备份
 *   defcfg  <pkgCsv> <updateMode>   ★2026-10-09 新增：改写"默认 app 配置"两条键的 refreshConfig
 *                                   为 NONE+updateMode：`eac_app_<pkg>`（EACAppConfig.save()）与
 *                                   `eac_default_app_config<pkg>`（saveDefaultConfig()），仅改已存在的键。
 *                                   **为什么必须补这一步**：`set` 只写 theme，而
 *                                   `EACThemeFactory.loadThemeOrCreate` 的回退链第 2 步是
 *                                   `loadLowVersionAppConfigThemeOrNull`（= 从 `eac_app_<pkg>` 迁移）
 *                                   ⇒ 只写 theme 会在 OECService 重载时**被覆盖回去**
 *                                   （2026-10-09 实测：重启后 themeType 3 回到旧值，而它才是
 *                                   `DEFAULT_ACTIVE_THEME` = 生效的那个）。
 *   restore <pkgCsv>                从备份恢复原 theme（save-only，用于"None/恢复默认"档）
 *   reset   [pkgCsv]                ★2026-10-11：还原为原始状态（修复旧版本写坏的值）。
 *                                   省略 pkgCsv 时自动枚举 MMKV 里所有 `eac_app_<pkg>`。
 *                                   写入 idx=NONE + updateMode=5(REGAL_PLUS) + alias=""：
 *                                   NONE 让刷新引擎的写入能被采纳；5 不在固件迁移表里 ⇒ 不会被改写。
 *   cleanup                        ★2026-11-06：卸载/停用前自愈——删除【已卸载 app】的 EAC 残留键。
 *                                   依据：`/onyxconfig` 是独立 ext4 分区（mmcblk0p80），
 *                                   **恢复出厂不擦**；卸载 app 也不会清它的 EAC 配置 ⇒ 残留永久累积。
 *                                   按完整 `pm list packages`（不是 -3）判定未安装 pkg，
 *                                   删 `eac_app_<pkg>` / `eac_default_app_config<pkg>` /
 *                                   `eac_theme_<pkg>@theme_type_*`，并清 `eac_theme_pkg_set@*`
 *                                   这类早期脚本误造的垃圾键；删除前逐键备份到
 *                                   `/data/local/tmp/eac_cleanup_bak/`。
 *                                   ⚠️ 不删仍安装 app 的键（保留用户在优化引擎里自设的颜色/DPI 等），
 *                                   ⚠️ 不删 `eac_app_pkg_set`（系统内部"包名集合"，非 JSON 配置），
 *                                   ⚠️ ★2026-11-06 新增：凡仍留在 `eac_app_pkg_set` 成员里的 pkg 一律跳过。
 * 备份文件：/data/local/tmp/eac_bak/<pkg>.json（每行一个 theme JSON）
 *           /data/local/tmp/eac_bak/<pkg>.{app,defcfg}.json（defcfg 命令的原始 JSON）
 * ⚠️ `defcfg` **不新建**缺失的键（只改已存在的），故不会给本来没有默认配置的 app 造出新键。
 *
 * ⚠️⚠️ **值域纪律（2026-08-30~09-05 事故的根因）**：`updateMode` 只接受 **EAC 逻辑域 −1..5**。
 *   当时的 launcher 版本用 `MODE_VALUES = {UI_NONE, UI_GU_MODE, …, UI_REGAL_PLUS_MODE(9), …}`
 *   去写 EAC，把**波形/UI 域的 9** 写进了逻辑域字段。后果是**永久性**的：
 *   9 不在固件 `refreshMigrateMap {0→3,1→2,2→2,3→5,4→2}` 里 ⇒ `validateRefreshConfig`
 *   永远不会修正它 ⇒ `caculateRefreshConfig` 恒返回 9 ⇒ `setAppRefreshModeImpl` 走
 *   `dumpError("Incorrect target system refresh mode") + return`、`applyUpdateMode` 的 switch
 *   也没有 case 9 ⇒ **刷新引擎（通知栏面板/全局档）点了完全没反应**（连对话框都勾不上单选钮）。
 *   ⇒ `set`/`defcfg` 已加逻辑域校验；历史上被写坏的用 `reset` 修。
 */
public class GlobalEacRefreshHelper {

  private static final String TAG = "GlobalEacRefresh";
  private static final String BAK_DIR = "/data/local/tmp/eac_bak";
  private static final String CLEANUP_BAK_DIR = "/data/local/tmp/eac_cleanup_bak";
  /** 系统内部键：值为裸包名列表，不是 JSON 配置，不能当 pkg 处理 */
  private static final String PKG_SET_KEY = "eac_app_pkg_set";
  private static final String EAC_APP_CONFIG = "android.onyx.optimization.data.p008v2.EACAppConfig";
  // ⚠️ 真实类名是 `data.v2`；`data.p008v2` 只是反编译源码里的改名（jadx 为避免与 `data.v1` 重名）。
  // 证据：EACAppConfig.java 内部匿名类注释为 "from class: ...data.v2.EACAppConfig.1"。
  // 2026-10-09 实测：只用 `p008v2` 会 ClassNotFoundException。两个都试，v2 优先。
  private static final String[] EAC_APP_CONFIG_NAMES = {
      "android.onyx.optimization.data.v2.EACAppConfig",
      EAC_APP_CONFIG,
  };
  private static final String EAC_MMKV_BASE = "android.onyx.optimization.BaseMMKV";
  private static final String DEFAULT_APP_KEY = "eac_default_app_config";
  /** reset 写入的 updateMode：EAC 逻辑域的 REGAL_PLUS。选它是因为它**不在**固件迁移表
   *  {0→3, 1→2, 2→2, 3→5, 4→2} 里 ⇒ validateRefreshConfig 不会改写，配置能稳定留住。 */
  private static final int RESTORE_UPDATE_MODE = 5;
  /** ★ 待办 3（容量守护）：MMKV 文件固定 2MB（append-only，运行期只涨不缩），
   *  实际有效数据 actual_size 逼近 2MB 时写入会把 MMKV 推向扩容/写坏边缘。
   *  写前若剩余空间不足 MMKV_MIN_FREE 则拒绝写入，提示重启设备让系统 full write-back 回收。 */
  private static final long MMKV_MAX_SIZE = 2L * 1024 * 1024;
  private static final long MMKV_MIN_FREE = 256L * 1024;
  private static final String MMKV_CONFIG_PATH = "/onyxconfig/mmkv/onyx_config";

  private static Class<?> eInkHelperClass;

  public static void main(String[] args) {
    // reset / cleanup 允许只给命令（自动枚举 MMKV 里所有 eac_app_<pkg>）；其余命令必须有 pkgCsv
    boolean noPkgArg = "reset".equals(args.length > 0 ? args[0] : "")
        || "cleanup".equals(args.length > 0 ? args[0] : "");
    if (args.length < 1 || (args.length < 2 && !noPkgArg)) {
      System.err.println("usage: GlobalEacRefreshHelper <set|defcfg|restore> <pkgCsv> [updateMode] | reset [pkgCsv] | cleanup | official <logicMode>");
      System.exit(2);
      return;
    }
    String cmd = args[0];
    if ("cleanup".equals(cmd)) {
      // 卸载/停用前自愈不需要 EInkHelper；直接操作 MMKV（见 runCleanup）
      // 待办 3（容量守护）：cleanup 逐个 remove 制造墓碑，是逼近容量边界的元凶之一，
      // 写前同样先查剩余空间，不足则拒绝（提示重启让系统 full write-back 回收）。
      if (!hasMmkvCapacity()) {
        System.exit(4);
        return;
      }
      int removed = runCleanup();
      System.out.println("RESULT cmd=cleanup removedKeys=" + removed);
      System.exit(0);
      return;
    }
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
    String[] pkgs;
    int mode = 0;
    if ("reset".equals(cmd)) {
      // reset 可省略 pkgCsv：直接从 MMKV 枚举所有 eac_app_<pkg>（也覆盖已卸载 app 的残留）
      pkgs = (args.length >= 2 && args[1].length() > 0) ? args[1].split(",") : enumeratePkgs();
      mode = RESTORE_UPDATE_MODE;
    } else {
      pkgs = args[1].split(",");
      if ("set".equals(cmd) || "defcfg".equals(cmd)) {
        if (args.length < 3) {
          System.err.println(cmd + " requires updateMode arg");
          System.exit(2);
          return;
        }
        mode = Integer.parseInt(args[2]);
        // ★ 值域纪律：只允许 EAC 逻辑域 −1..5。9/98/33554436 等是波形域/UI 域值，
        //   写进 updateMode 会让刷新引擎永久空转（9 不在固件迁移表里，永不被修正）
        if (mode < -1 || mode > 5) {
          System.err.println("FAIL mode=" + mode
              + " 不在 EAC 逻辑域 -1..5。波形/UI 域值（9=REGAL_PLUS, 98=GC16|FULL, 33554436=MONO_A2 等）"
              + "写进 updateMode 会使刷新引擎永久失效；如需修复请用 reset");
          System.exit(3);
          return;
        }
      }
    }
    if (!init()) {
      System.err.println("FAIL EInkHelper unavailable");
      System.exit(1);
      return;
    }
    // 待办 3（容量守护）：任何写命令执行前先检查 MMKV 剩余空间，不足则整体拒绝，
    // 提示重启让系统 full write-back 回收（2026-11-06 实测开机后系统会重写并清墓碑）。
    if (!hasMmkvCapacity()) {
      System.exit(4);
      return;
    }
    int okCount = 0;
    int skipCount = 0;
    if ("reset".equals(cmd)) {
      String g = resetGlobalDefault();
      if (g != null) System.out.println("OK <global default> -> " + g);
    }
    for (String pkg : pkgs) {
      pkg = pkg.trim();
      if (pkg.length() == 0) continue;
      try {
        String out = "set".equals(cmd) ? applySet(pkg, mode)
            : "defcfg".equals(cmd) ? applyDefaultCfg(pkg, mode)
            : "reset".equals(cmd) ? applyReset(pkg)
            : applyRestore(pkg);
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

  /** 统一写 NONE+updateMode；首写前把原 theme 备份到 BAK_DIR/<pkg>.json。
   *
   * ★2026-11-06 待办 1+2 落实：只写 active theme（themeType=3 = DEFAULT_ACTIVE_THEME），
   *   不动 type 1/2；且写前 diff——目标值（NONE+mode）已等于现值就不写。
   *   依据（framework.jar 反编译确证）：
   *     · EACAppThemeManager.DEFAULT_ACTIVE_THEME=3，launcher 从不改 active type ⇒ 生效的只有 type 3；
   *     · EACAppTheme.save() 用 themeKey(pkg, themeType) 映射 key，与 list 顺序/长度无关 ⇒
   *       只传 1 个 type_3 的 JSON 就只写 type_3 一个 key。旧版把 loadThemes 返回的 [1,2,3]
   *       全改全存 = 把出厂「仅 type3」形态（donor 71/74 包）膨胀成「三套齐全」≈3 倍写入量。 */
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
      if (root.optInt("themeType", -1) != 3) continue;  // 待办 1：只写 active theme
      JSONObject rc = root.getJSONObject("appConfig")
          .getJSONObject("globalActivityConfig")
          .getJSONObject("refreshConfig");
      // 待办 2：写前 diff，值没变就不写 ⇒ 幂等操作变零写入
      if ("NONE".equals(rc.optString("refreshModeIndex")) && rc.optInt("updateMode") == mode) {
        continue;
      }
      rc.put("refreshModeIndex", "NONE");
      rc.put("updateMode", mode);
      edited.add(root.toString());
      n++;
    }
    if (n == 0) return "no-change (already NONE+mode=" + mode + ", save skipped)";
    // save-only：持久化到 MMKV，供 OECService 下次启动/重载读取；不 apply（避免热应用
    // 窗口重建风暴与系统重启，见类头注释）。
    eInkHelperClass.getMethod("saveEACAppThemes", List.class).invoke(null, edited);
    return "themes=" + n + " NONE+mode=" + mode + " (save-only)";
  }

  /** ★2026-10-09：把"默认 app 配置"（eac_app_<pkg> 与 eac_default_app_config<pkg>）的
   *  refreshConfig 改成 NONE+mode，**只改已存在的键**。理由见类头 cmd 说明（只写 theme 会被重载覆盖）。 */
  @SuppressWarnings("unchecked")
  private static String applyDefaultCfg(String pkg, int mode) throws Exception {
    Class<?> cfgCls = resolveAppConfigClass();
    int n = 0;
    n += writeDefaultCfgKey(cfgCls, "loadSavedConfig", "save",
        "eac_app_" + pkg, pkg, "app", mode);
    n += writeDefaultCfgKey(cfgCls, "loadDefaultConfig", "saveDefaultConfig",
        "eac_default_app_config" + pkg, pkg, "defcfg", mode);
    return n == 0 ? null : "defcfg keys=" + n + " NONE+mode=" + mode + " (save-only)";
  }

  /** 解析 EACAppConfig 的真名（`data.v2` 优先，`data.p008v2` 保底） */
  private static Class<?> resolveAppConfigClass() throws Exception {
    Throwable last = null;
    for (String n : EAC_APP_CONFIG_NAMES) {
      try {
        return Class.forName(n);
      } catch (Throwable t) {
        last = t;
      }
    }
    throw new Exception("EACAppConfig not found; tried both names", last);
  }

  private static int writeDefaultCfgKey(Class<?> cfgCls, String loader, String saver,
                                        String mmkvKey, String pkg, String tag, int mode)
      throws Exception {
    Object cfg = cfgCls.getMethod(loader, String.class).invoke(null, pkg);
    if (cfg == null) return 0;
    Object activity = cfgCls.getMethod("getGlobalActivityConfig").invoke(cfg);
    if (activity == null) return 0;
    Object rc = activity.getClass().getMethod("getRefreshConfig").invoke(activity);
    if (rc == null) return 0;
    // 写前 diff：已是 NONE+mode 就不写
    String curIdx = (String) rc.getClass().getMethod("getRefreshModeIndex").invoke(rc);
    int curUm = (Integer) rc.getClass().getMethod("getUpdateMode").invoke(rc);
    if ("NONE".equals(curIdx) && curUm == mode) return 0;
    backupRawKey(mmkvKey, pkg, tag);
    rc.getClass().getMethod("setRefreshModeIndex", String.class).invoke(rc, "NONE");
    rc.getClass().getMethod("setUpdateMode", int.class).invoke(rc, mode);
    cfgCls.getMethod(saver).invoke(cfg);
    return 1;
  }

  /** 把某 MMKV 键改写前的原始 JSON 备份到 BAK_DIR/<pkg>.<tag>.json（只备份首次） */
  private static void backupRawKey(String mmkvKey, String pkg, String tag) throws Exception {
    File bak = new File(BAK_DIR, pkg + "." + tag + ".json");
    if (bak.exists()) return;
    Object raw = Class.forName(EAC_MMKV_BASE).getMethod("getString", String.class).invoke(null, mmkvKey);
    if (raw == null) return;
    File dir = new File(BAK_DIR);
    if (!dir.exists() && !dir.mkdirs()) return;
    try (OutputStreamWriter w = new OutputStreamWriter(
        new FileOutputStream(bak), StandardCharsets.UTF_8)) {
      w.write(String.valueOf(raw));
    }
  }

  /** ★2026-10-11 「还原为原始状态」：把该 pkg 的 EAC refreshConfig 写回
   *  **idx=NONE + updateMode=5 + alias=""**（理由见 RESTORE_UPDATE_MODE 与类头值域纪律）。
   *  同时覆盖三条键：theme（决策源）、eac_app_<pkg>、eac_default_app_config<pkg>（重建来源）。
   *  ★2026-11-06 收敛：theme 只写 type_3（active）+ 三条键都做写前 diff（值已相等则跳过）。 */
  private static String applyReset(String pkg) throws Exception {
    Class<?> cfgCls = resolveAppConfigClass();
    int n = 0;
    n += writeResetKey(cfgCls, "loadSavedConfig", "save", "eac_app_" + pkg, pkg, "app");
    n += writeResetKey(cfgCls, "loadDefaultConfig", "saveDefaultConfig",
        DEFAULT_APP_KEY + pkg, pkg, "defcfg");

    int t = 0;
    List<String> themes = (List<String>) eInkHelperClass
        .getMethod("loadThemes", String.class).invoke(null, pkg);
    if (themes != null && !themes.isEmpty()) {
      backupIfAbsent(pkg, themes);
      List<String> edited = new ArrayList<>();
      for (String s : themes) {
        if (s == null) continue;
        JSONObject root = new JSONObject(s);
        if (root.optInt("themeType", -1) != 3) continue;   // 只写 active theme
        String out = resetThemeJson(root);
        if (out != null) {                                   // null = 已是最新，跳过
          edited.add(out);
          t++;
        }
      }
      if (!edited.isEmpty()) {
        eInkHelperClass.getMethod("saveEACAppThemes", List.class).invoke(null, edited);
      }
    }
    if (n == 0 && t == 0) return "no-change (already NONE+um=" + RESTORE_UPDATE_MODE + ", save skipped)";
    return "app/defcfg=" + n + " themes=" + t
        + " NONE+um=" + RESTORE_UPDATE_MODE + "+alias=\"\" (save-only)";
  }

  private static int writeResetKey(Class<?> cfgCls, String loader, String saver,
                                   String mmkvKey, String pkg, String tag) throws Exception {
    Object c = cfgCls.getMethod(loader, String.class).invoke(null, pkg);
    if (c == null) return 0;
    Object act = cfgCls.getMethod("getGlobalActivityConfig").invoke(c);
    Object rc = act == null ? null : act.getClass().getMethod("getRefreshConfig").invoke(act);
    if (rc != null) {
      // 写前 diff：已是 NONE+um=5+alias="" 就不写
      String curIdx = (String) rc.getClass().getMethod("getRefreshModeIndex").invoke(rc);
      int curUm = (Integer) rc.getClass().getMethod("getUpdateMode").invoke(rc);
      String curAlias = (String) rc.getClass().getMethod("getRefreshModeAlias").invoke(rc);
      if ("NONE".equals(curIdx) && curUm == RESTORE_UPDATE_MODE
          && (curAlias == null || curAlias.isEmpty() || "null".equals(curAlias))) {
        return 0;
      }
    }
    backupRawKey(mmkvKey, pkg, tag);
    resetRefresh(cfgCls, c);
    cfgCls.getMethod(saver).invoke(c);
    return 1;
  }

  /** theme 是 JSON 字符串；改写三个字段为 NONE+um=5+alias=""。
   *  若已是目标值返回 null（写前 diff，调用方据此跳过）。 */
  private static String resetThemeJson(JSONObject root) throws Exception {
    JSONObject rc = root.getJSONObject("appConfig")
        .getJSONObject("globalActivityConfig")
        .getJSONObject("refreshConfig");
    String curIdx = rc.optString("refreshModeIndex");
    int curUm = rc.optInt("updateMode");
    String curAlias = rc.optString("refreshModeAlias");
    if ("NONE".equals(curIdx) && curUm == RESTORE_UPDATE_MODE
        && (curAlias == null || curAlias.isEmpty() || "null".equals(curAlias))) {
      return null;
    }
    rc.put("refreshModeIndex", "NONE");
    rc.put("updateMode", RESTORE_UPDATE_MODE);
    rc.put("refreshModeAlias", "");
    return root.toString();
  }

  /** 无 pkg 后缀的全局默认键——影响所有"没有自定义配置"的 app */
  private static String resetGlobalDefault() {
    try {
      Class<?> cfgCls = resolveAppConfigClass();
      Object g = cfgCls.getMethod("loadGlobalDefaultAppConfig").invoke(null);
      if (g == null) return null;
      backupRawKey(DEFAULT_APP_KEY, "global", "defcfg");
      resetRefresh(cfgCls, g);
      cfgCls.getMethod("saveGlobalDefaultConfig").invoke(g);
      return "NONE+um=" + RESTORE_UPDATE_MODE + "+alias=\"\" (save-only)";
    } catch (Throwable t) {
      System.err.println("resetGlobalDefault failed: " + t);
      return null;
    }
  }

  private static void resetRefresh(Class<?> cfgCls, Object appConfig) throws Exception {
    Object act = cfgCls.getMethod("getGlobalActivityConfig").invoke(appConfig);
    Object rc = act.getClass().getMethod("getRefreshConfig").invoke(act);
    rc.getClass().getMethod("setRefreshModeIndex", String.class).invoke(rc, "NONE");
    rc.getClass().getMethod("setUpdateMode", int.class).invoke(rc, RESTORE_UPDATE_MODE);
    rc.getClass().getMethod("setRefreshModeAlias", String.class).invoke(rc, "");
  }

  /** reset 省略 pkgCsv 时：枚举 MMKV 里所有 eac_app_<pkg>（也覆盖已卸载 app 的残留配置）。
   *  ⚠️ 必须排除 eac_app_pkg_set：它是系统内部"包名集合"键（值为裸包名列表，非 JSON），
   *     误当 pkg 传入会抛 fastjson JSONException 并打断循环（2026-11-06 实测）。 */
  private static String[] enumeratePkgs() {
    return enumerateEacPkgs().toArray(new String[0]);
  }

  private static List<String> enumerateEacPkgs() {
    List<String> out = new ArrayList<>();
    try {
      Object keys = Class.forName(EAC_MMKV_BASE)
          .getMethod("getKeysByPrefix", String.class).invoke(null, "eac_app_");
      if (keys instanceof java.util.Set) {
        for (Object k : (java.util.Set<?>) keys) {
          String s = String.valueOf(k);
          if (s.startsWith("eac_app_") && !PKG_SET_KEY.equals(s)) {
            out.add(s.substring("eac_app_".length()));
          }
        }
      }
    } catch (Throwable t) {
      System.err.println("enumerateEacPkgs failed: " + t);
    }
    return out;
  }

  // =========================================================================
  // 卸载/停用前自愈：清掉已卸载 app 的 EAC 残留
  // =========================================================================

  /**
   * 删除【已卸载 app】的 EAC 键。返回删除的键数。
   *
   * 为什么必须做：EAC 配置存在 `/onyxconfig/mmkv/onyx_config`，而 `/onyxconfig` 是独立
   * ext4 分区（mmcblk0p80）——**恢复出厂设置不擦它**，卸载 app 也不会清它 ⇒ 残留永久累积。
   * 那些残留的 `refreshModeIndex=refresh_mode_*` 会让 `caculateRefreshConfig` 忽略
   * `updateMode`，表现为「刷新引擎切档点了没反应」。
   *
   * 只删未安装 pkg 的键；仍安装 app 的键保留（用户可能在优化引擎里自设了颜色/DPI 等）。
   */
  private static int runCleanup() {
    File dir = new File(CLEANUP_BAK_DIR);
    if (!dir.exists() && !dir.mkdirs()) {
      System.err.println("cleanup bak dir create failed: " + dir);
      return 0;
    }
    List<String> eacPkgs = enumerateEacPkgs();
    Set<String> installed = readInstalledPackages();
    Set<String> pkgSetMembers = readPkgSetMembers();   // ★ 待办 5 改进：pkg_set 成员跳过
    int removed = 0;
    for (String pkg : eacPkgs) {
      if (pkg.length() == 0 || PKG_SET_KEY.equals("eac_app_" + pkg)) continue;
      if (installed.contains(pkg)) continue;                 // 仍安装：保留
      if (pkgSetMembers.contains(pkg)) {                     // ★ 仍在 pkg_set：跳过并告警
        System.out.println("cleanup SKIP " + pkg + " (still in eac_app_pkg_set)");
        continue;
      }
      for (String key : keysOf(pkg)) {
        backupRawKeyTo(key, dir);
        if (mmkvRemove(key)) removed++;
      }
      System.out.println("cleanup " + pkg + " (uninstalled)");
    }
    // 早期脚本把 eac_app_pkg_set 误当 pkg，造出 3 个垃圾 theme 键；一并清掉
    for (int t = 1; t <= 3; t++) {
      String key = "eac_theme_pkg_set@theme_type_" + t;
      backupRawKeyTo(key, dir);
      if (mmkvRemove(key)) removed++;
    }
    // eac_app_pkg_set 本身是系统键，保留（OECService 依赖它管理包名集合）
    System.out.println("cleanup done: eacPkgs=" + eacPkgs.size()
        + " installed=" + installed.size() + " pkgSet=" + pkgSetMembers.size()
        + " removedKeys=" + removed);
    return removed;
  }

  /** 读系统内部键 eac_app_pkg_set 的成员列表（StringSet 语义，见 EACDeviceConfig.load()）。
   *  ⚠️ 它决定 OECService 启动时遍历哪些 app，仍在其成员里的 pkg 绝不能删配置（否则重蹈卡开机覆辙）。 */
  private static Set<String> readPkgSetMembers() {
    Set<String> out = new HashSet<>();
    try {
      Object v = Class.forName(EAC_MMKV_BASE)
          .getMethod("getStringSet", String.class).invoke(null, PKG_SET_KEY);
      if (v instanceof java.util.Set) {
        for (Object o : (java.util.Set<?>) v) {
          if (o != null) out.add(String.valueOf(o));
        }
      }
    } catch (Throwable t) {
      System.err.println("readPkgSetMembers failed: " + t);
    }
    return out;
  }

  private static List<String> keysOf(String pkg) {
    List<String> keys = new ArrayList<>();
    keys.add("eac_app_" + pkg);
    keys.add(DEFAULT_APP_KEY + pkg);
    for (int t = 1; t <= 3; t++) {
      keys.add("eac_theme_" + pkg + "@theme_type_" + t);
    }
    return keys;
  }

  /** 完整包列表（含系统 app）——绝不能用 `pm list packages -3`，会把系统 app 误判成"未安装" */
  private static Set<String> readInstalledPackages() {
    Set<String> out = new HashSet<>();
    try {
      Process p = Runtime.getRuntime().exec(new String[]{"pm", "list", "packages"});
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      String line;
      while ((line = r.readLine()) != null) {
        if (line.startsWith("package:")) out.add(line.substring("package:".length()).trim());
      }
      r.close();
      p.waitFor();
      p.destroy();
    } catch (Throwable t) {
      System.err.println("readInstalledPackages failed: " + t);
    }
    return out;
  }

  private static boolean mmkvRemove(String key) {
    try {
      Class.forName(EAC_MMKV_BASE).getMethod("remove", String.class).invoke(null, key);
      return true;
    } catch (Throwable t) {
      System.err.println("remove " + key + " failed: " + t);
      return false;
    }
  }

  /** 删除前把键的原始值备份到 eac_cleanup_bak/<key>.raw（只备份首次） */
  private static void backupRawKeyTo(String key, File dir) {
    File bak = new File(dir, key.replace('/', '_') + ".raw");
    if (bak.exists()) return;
    try {
      Object raw = Class.forName(EAC_MMKV_BASE)
          .getMethod("getString", String.class).invoke(null, key);
      if (raw == null) return;
      try (OutputStreamWriter w = new OutputStreamWriter(
          new FileOutputStream(bak), StandardCharsets.UTF_8)) {
        w.write(String.valueOf(raw));
      }
    } catch (Throwable t) {
      System.err.println("backup " + key + " failed: " + t);
    }
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

  /** 待办 3（容量守护）：读 /onyxconfig/mmkv/onyx_config 的 actual_size（前 4 字节 LE），
   *  剩余空间 < MMKV_MIN_FREE 时拒绝写入。返回 false = 应中止本次 save。
   *  用 shell 可读的文件直接读，避免依赖 MMKV 内部实例（root app_process 下 MMKV 未必初始化到同一 root）。 */
  private static boolean hasMmkvCapacity() {
    try (FileInputStream in = new FileInputStream(MMKV_CONFIG_PATH)) {
      byte[] head = new byte[4];
      int read = in.read(head);
      if (read != 4) return true; // 读不到头就放行，交给 MMKV 自身判定
      long actual = (head[0] & 0xFFL) | ((head[1] & 0xFFL) << 8)
          | ((head[2] & 0xFFL) << 16) | ((head[3] & 0xFFL) << 24);
      long free = MMKV_MAX_SIZE - actual;
      if (free < MMKV_MIN_FREE) {
        System.err.println("FAIL MMKV 容量不足: actual=" + actual
            + " free=" + free + " < " + MMKV_MIN_FREE
            + "。拒绝写入。请重启设备让系统 full write-back 回收后再试。");
        return false;
      }
      return true;
    } catch (Throwable t) {
      System.err.println("hasMmkvCapacity read failed (放行): " + t);
      return true;
    }
  }
}
