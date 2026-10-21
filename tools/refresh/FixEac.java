package io.onyx;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正常机专用：把"刷新引擎切换"修回可用，并全程可验证。
 *
 * 背景（2026-10-11 实测于 6C1BF7D9）：
 *   面板 OnyxRefreshModeController → OnyxRefreshModeHelper.setCurrentRefreshMode(i)
 *     → EInkHelper.setAppScopeRefreshMode(i)   ← i ∈ {0=Normal,1=DU,2=A2,4=X}
 *   → OECService → TabletEACRefreshImpl:
 *        写 当前前台 app 的 updateMode=i + fallback.updateMode=i
 *        应用/读回 走 caculateRefreshConfig(cfg).getMode()
 *          ★ idx ≠ NONE 时【忽略 updateMode】直接返回系统档的 mode
 *          ★ 值不在 -1..5 内 ⇒ dumpError("Incorrect target system refresh mode") + return（空转）
 *   本机现场：foreground=Legado，activeThemeType=1，该 theme 为 idx=NONE um=9
 *     ⇒ getAppScopeRefreshMode()=9（9 是波形域 UI_REGAL_PLUS_MODE，属错域），getFastModeIndex()=0
 *
 * 用法（root app_process）：
 *   app_process /system/bin io.onyx.FixEac read  <pkgCsv>
 *   app_process /system/bin io.onyx.FixEac setmode <logicMode>       // 等价于点面板上的档位
 *   app_process /system/bin io.onyx.FixEac fix   <pkgCsv> <logicMode> // 把 idx 归 NONE + um=logicMode
 *                                                                     // 写 themes + eac_app_ + default 键 + 全局默认
 * 备份：/data/local/tmp/eac_bak/<key>.raw.json（原始 JSON，只备份首次）
 */
public class FixEac {

    private static final String MMKV = "android.onyx.optimization.BaseMMKV";
    private static final String EINK = "android.onyx.optimization.EInkHelper";
    private static final String[] CFG_NAMES = {
            "android.onyx.optimization.data.v2.EACAppConfig",
            "android.onyx.optimization.data.p008v2.EACAppConfig",
    };
    private static final String BAK = "/data/local/tmp/eac_bak";
    private static final Pattern P_IDX = Pattern.compile("\"refreshModeIndex\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern P_UM = Pattern.compile("\"updateMode\"\\s*:\\s*(-?\\d+)");
    private static final Pattern P_TT = Pattern.compile("\"themeType\"\\s*:\\s*(\\d+)");

    private static void say(String s) {
        System.out.println(s);
        System.out.flush();
    }

    private static String rcOf(String json) {
        if (json == null) return "(null)";
        int i = json.indexOf("\"refreshConfig\":");
        String sub = i >= 0 ? json.substring(i, Math.min(json.length(), i + 600)) : json;
        Matcher mi = P_IDX.matcher(sub);
        Matcher mu = P_UM.matcher(sub);
        return "idx=" + (mi.find() ? mi.group(1) : "?") + " um=" + (mu.find() ? mu.group(1) : "?");
    }

    private static Class<?> cfgClass() throws Exception {
        Throwable last = null;
        for (String n : CFG_NAMES) {
            try {
                return Class.forName(n);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new Exception("EACAppConfig not found", last);
    }

    private static void backupRaw(String key) {
        try {
            File f = new File(BAK, key.replace('/', '_') + ".raw.json");
            if (f.exists()) return;
            Object raw = Class.forName(MMKV).getMethod("getString", String.class).invoke(null, key);
            if (raw == null) return;
            new File(BAK).mkdirs();
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                w.write(String.valueOf(raw));
            } finally {
                w.close();
            }
            say("    (backup " + f.getName() + ")");
        } catch (Throwable t) {
            say("    (backup failed " + key + ": " + t + ")");
        }
    }

    private static void readOne(String pkg) {
        say("=== " + pkg + " ===");
        try {
            Object att = Class.forName("android.onyx.optimization.EACAppThemeManager")
                    .getMethod("getActiveThemeType", String.class).invoke(null, pkg);
            say("  getActiveThemeType = " + att);
        } catch (Throwable t) {
            say("  getActiveThemeType ERR " + t);
        }
        for (String k : new String[]{"eac_app_" + pkg, "eac_default_app_config" + pkg}) {
            try {
                Object raw = Class.forName(MMKV).getMethod("getString", String.class).invoke(null, k);
                say("  " + k + " -> " + rcOf((String) raw));
            } catch (Throwable t) {
                say("  " + k + " ERR " + t);
            }
        }
        try {
            Object themes = Class.forName(EINK).getMethod("loadThemes", String.class).invoke(null, pkg);
            if (themes instanceof List) {
                List<?> l = (List<?>) themes;
                for (int i = 0; i < l.size(); i++) {
                    String t = (String) l.get(i);
                    Matcher mt = P_TT.matcher(t == null ? "" : t);
                    say("  theme[" + i + "] themeType=" + (mt.find() ? mt.group(1) : "?") + " " + rcOf(t));
                }
            }
        } catch (Throwable t) {
            say("  loadThemes ERR " + t);
        }
    }

    private static void readGlobal() {
        say("=== 设备级 / 全局默认 ===");
        try {
            say("  getAppScopeRefreshMode() = " + Class.forName(EINK).getMethod("getAppScopeRefreshMode").invoke(null));
        } catch (Throwable t) {
            say("  getAppScopeRefreshMode ERR " + t);
        }
        try {
            say("  getFastModeIndex() = " + Class.forName("android.onyx.ViewUpdateHelper")
                    .getMethod("getFastModeIndex").invoke(null) + "  (0=normal 1=system-fast 2=app-fast)");
        } catch (Throwable t) {
            say("  getFastModeIndex ERR " + t);
        }
        try {
            Object raw = Class.forName(MMKV).getMethod("getString", String.class).invoke(null, "eac_default_app_config");
            say("  eac_default_app_config(全局) -> " + rcOf((String) raw));
        } catch (Throwable t) {
            say("  eac_default_app_config ERR " + t);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            say("usage: FixEac read <pkgCsv> | setmode <n> | fix <pkgCsv> <mode>");
            return;
        }
        String cmd = args[0];

        if (args.length >= 1 && "sysmap".equals(args[0])) {
            // 只读：列出固件定义的全部刷新档（索引名 + mode + turbo + title）
            try {
                Object cfg = Class.forName("android.onyx.config.SysUIConfig")
                        .getMethod("singleton").invoke(null);
                Object map = cfg.getClass().getMethod("getRefreshConfigMap").invoke(cfg);
                say("refreshConfigMap = " + map);
                if (map instanceof java.util.Map) {
                    java.util.List<String> ks = new java.util.ArrayList<>();
                    for (Object k : ((java.util.Map<?, ?>) map).keySet()) ks.add(String.valueOf(k));
                    java.util.Collections.sort(ks);
                    for (String k : ks) {
                        Object d = ((java.util.Map<?, ?>) map).get(k);
                        String dt = "";
                        for (String g : new String[]{"getMode", "getTurbo", "getTitle", "getRefreshModeAlias"}) {
                            try {
                                dt += " " + g + "=" + d.getClass().getMethod(g).invoke(d);
                            } catch (Throwable ignored) {
                            }
                        }
                        say("   " + k + " ->" + dt);
                    }
                    say("TOTAL " + ks.size());
                }
            } catch (Throwable t) {
                say("sysmap ERR " + t);
            }
            return;
        }

        if (args.length >= 1 && "keys".equals(args[0])) {
            String prefix = args.length >= 2 ? args[1] : "eac_theme_";
            try {
                Object ks = Class.forName(MMKV).getMethod("getKeysByPrefix", String.class).invoke(null, prefix);
                if (ks instanceof java.util.Set) {
                    java.util.List<String> l = new java.util.ArrayList<>();
                    for (Object k : (java.util.Set<?>) ks) l.add(String.valueOf(k));
                    java.util.Collections.sort(l);
                    for (String k : l) say(k);
                    say("TOTAL " + l.size());
                } else {
                    say("keys type " + ks);
                }
            } catch (Throwable t) {
                say("keys ERR " + t);
            }
            return;
        }

        if (args.length >= 1 && "rm".equals(args[0])) {
            // 删除某 pkg 的 EAC 键（app / default / 3 个 themeType）——用于清理误写入的垃圾键
            try {
                Method rm = Class.forName(MMKV).getMethod("remove", String.class);
                for (String pkg : args[1].split(",")) {
                    pkg = pkg.trim();
                    if (pkg.isEmpty()) continue;
                    String[] keys = {
                            "eac_app_" + pkg,
                            "eac_default_app_config" + pkg,
                            "eac_theme_" + pkg + "@theme_type_1",
                            "eac_theme_" + pkg + "@theme_type_2",
                            "eac_theme_" + pkg + "@theme_type_3",
                    };
                    for (String k : keys) rm.invoke(null, k);
                    say("removed " + pkg);
                }
            } catch (Throwable t) {
                say("rm ERR " + t);
            }
            return;
        }

        if (args.length >= 1 && "migrate".equals(args[0])) {
            // 只读：打印旧值迁移表。含义：updateMode 若命中此表 ⇒ 加载校验时会被改写成目标值
            // （这正是我们写的 idx=NONE+um=0 被改成 refresh_mode_2/3 的原因）
            for (boolean sys : new boolean[]{false, true}) {
                try {
                    Object cfg = Class.forName("android.onyx.config.SysUIConfig")
                            .getMethod("singleton").invoke(null);
                    Object map = cfg.getClass().getMethod("getRefreshMigrateMap", boolean.class)
                            .invoke(cfg, sys);
                    say("getRefreshMigrateMap(systemApp=" + sys + ") = " + map);
                } catch (Throwable t) {
                    say("migrate(systemApp=" + sys + ") ERR " + t);
                }
            }
            return;
        }

        if (args.length >= 1 && "aliasmap".equals(args[0])) {
            // 只读：把 RefreshModeUI 的每个 alias 经固件自己的映射表翻译一次
            // getRealRefreshMode(pkg, alias, indexMode) = 选项名 -> 真实刷新模式
            for (String pkg : args[1].split(",")) {
                pkg = pkg.trim();
                if (pkg.isEmpty()) continue;
                say("=== aliasmap " + pkg + " ===");
                for (String a : new String[]{"hd", "du", "regal_plus", "smooth", "new_speed"}) {
                    try {
                        Object v = Class.forName("android.onyx.RefreshMappingConfig")
                                .getMethod("getRealRefreshMode", String.class, String.class, String.class)
                                .invoke(null, pkg, a, "NONE");
                        say(String.format("    %-12s -> %s", a, v));
                    } catch (Throwable t) {
                        say("    " + a + " -> ERR " + t);
                    }
                }
            }
            return;
        }

        if ("setmode".equals(cmd)) {
            int m = Integer.parseInt(args[1]);
            readGlobal();
            say(">>> 调用 EInkHelper.setAppScopeRefreshMode(" + m + ")  —— 与面板点击完全同一入口");
            Class.forName(EINK).getMethod("setAppScopeRefreshMode", int.class).invoke(null, m);
            say(">>> 调用返回");
            Thread.sleep(800);
            readGlobal();
            return;
        }

        if ("read".equals(cmd)) {
            readGlobal();
            for (String p : args[1].split(",")) {
                p = p.trim();
                if (!p.isEmpty()) readOne(p);
            }
            return;
        }

        if (!"fix".equals(cmd)) {
            say("unknown cmd " + cmd);
            return;
        }

        // ---- fix：把 idx 归 NONE + um=<mode> 写进 themes / eac_app_ / default / 全局默认 ----
        int mode = Integer.parseInt(args[2]);
        Class<?> cfg = cfgClass();
        say("EACAppConfig = " + cfg.getName());
        readGlobal();

        // 全局默认（影响所有"无自定义配置"的 app）
        try {
            Object g = cfg.getMethod("loadGlobalDefaultAppConfig").invoke(null);
            if (g != null) {
                backupRaw("eac_default_app_config");
                Object act = cfg.getMethod("getGlobalActivityConfig").invoke(g);
                Object rc = act.getClass().getMethod("getRefreshConfig").invoke(act);
                rc.getClass().getMethod("setRefreshModeIndex", String.class).invoke(rc, "NONE");
                rc.getClass().getMethod("setUpdateMode", int.class).invoke(rc, mode);
                // ★ 必须清 alias：否则 refreshModeAlias(如 regal_plus) 会在每次加载/校验时
                //   把 updateMode 重新推导回错域值 9（UI_REGAL_PLUS_MODE），修复永远被覆盖
                try {
                    rc.getClass().getMethod("setRefreshModeAlias", String.class).invoke(rc, "");
                } catch (Throwable ignored) {
                }
                cfg.getMethod("saveGlobalDefaultConfig").invoke(g);
                say("  全局默认 -> NONE + um=" + mode + " + alias 清空 (saveGlobalDefaultConfig)");
            } else {
                say("  全局默认为 null，跳过");
            }
        } catch (Throwable t) {
            say("  全局默认失败: " + t);
        }

        for (String pkg : args[1].split(",")) {
            pkg = pkg.trim();
            if (pkg.isEmpty()) continue;
            say("--- fix " + pkg + " -> NONE + um=" + mode);

            // ① 两条 source 键（持久化的真正来源）
            for (String[] pair : new String[][]{
                    {"loadSavedConfig", "save", "eac_app_" + pkg},
                    {"loadDefaultConfig", "saveDefaultConfig", "eac_default_app_config" + pkg}}) {
                try {
                    Object c = cfg.getMethod(pair[0], String.class).invoke(null, pkg);
                    if (c == null) {
                        say("    " + pair[2] + " 不存在，跳过");
                        continue;
                    }
                    backupRaw(pair[2]);
                    Object act = cfg.getMethod("getGlobalActivityConfig").invoke(c);
                    Object rc = act.getClass().getMethod("getRefreshConfig").invoke(act);
                    rc.getClass().getMethod("setRefreshModeIndex", String.class).invoke(rc, "NONE");
                    rc.getClass().getMethod("setUpdateMode", int.class).invoke(rc, mode);
                    try {
                        rc.getClass().getMethod("setRefreshModeAlias", String.class).invoke(rc, "");
                    } catch (Throwable ignored) {
                    }
                    cfg.getMethod(pair[1]).invoke(c);
                    say("    " + pair[2] + " -> NONE + um=" + mode + " + alias 清空 (" + pair[1] + ")");
                } catch (Throwable t) {
                    say("    " + pair[2] + " FAIL: " + t);
                }
            }

            // ② 三个 theme（含生效的 active theme）
            try {
                Object themes = Class.forName(EINK).getMethod("loadThemes", String.class).invoke(null, pkg);
                if (themes instanceof List) {
                    List<String> edited = new ArrayList<>();
                    for (Object o : (List<?>) themes) {
                        String t = (String) o;
                        if (t == null) continue;
                        String nt = t.replaceFirst("\"refreshModeIndex\"\\s*:\\s*\"[^\"]*\"", "\"refreshModeIndex\":\"NONE\"");
                        nt = nt.replaceFirst("\"updateMode\"\\s*:\\s*-?\\d+", "\"updateMode\":" + mode);
                        // ★ 同一个理由：alias 留着就会把 um 推导回 9
                        nt = nt.replaceFirst("\"refreshModeAlias\"\\s*:\\s*\"[^\"]*\"", "\"refreshModeAlias\":\"\"");
                        edited.add(nt);
                    }
                    if (!edited.isEmpty()) {
                        Class.forName(EINK).getMethod("saveEACAppThemes", List.class).invoke(null, edited);
                        say("    themes 写入 " + edited.size() + " 条 -> NONE + um=" + mode + " (saveEACAppThemes)");
                    }
                }
            } catch (Throwable t) {
                say("    themes FAIL: " + t);
            }
        }
        say(">>> fix 完成");
        readGlobal();
    }
}
