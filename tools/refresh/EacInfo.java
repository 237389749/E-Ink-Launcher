package io.onyx;

import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读诊断：把"刷新引擎切换为什么不生效"所需的全部现场一次打出来。
 *
 * 用法: app_process /system/bin io.onyx.EacInfo <pkg1> [pkg2 ...]
 *   会额外自动查 当前前台 pkg（由 EInkHelper 侧不可靠，故仅当显式给出 pkg 时可靠）
 *
 * 打印：
 *   - 设备级读数 EInkHelper.getAppScopeRefreshMode() / ViewUpdateHelper.getFastModeIndex()
 *   - refresh_mode_N → 有效 mode 映射（SysUIConfig）
 *   - 每个 pkg：eac_app_<pkg> / eac_default_app_config<pkg> 的 refreshConfig(idx/um)
 *     + 全局空 pkg 默认 eac_default_app_config + 该 pkg 的全部 theme 的 idx/um
 */
public class EacInfo {

    private static final String MMKV = "android.onyx.optimization.BaseMMKV";
    private static final String[] CFG_NAMES = {
            "android.onyx.optimization.data.v2.EACAppConfig",
            "android.onyx.optimization.data.p008v2.EACAppConfig",
    };
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

    /** ★ 生效的是哪个 themeType —— 由 EACAppThemeManager.getActiveThemeType 决定（默认 3） */
    private static void dumpActiveThemeType(String pkg) {
        try {
            Object v = Class.forName("android.onyx.optimization.EACAppThemeManager")
                    .getMethod("getActiveThemeType", String.class).invoke(null, pkg);
            say("    ★ getActiveThemeType = " + v + "   (默认 3；生效的就是这个 themeType 的 theme)");
        } catch (Throwable t) {
            say("    getActiveThemeType ERR " + t);
        }
    }

    private static void dumpKey(String key) {
        try {
            Object raw = Class.forName(MMKV).getMethod("getString", String.class).invoke(null, key);
            say("    " + key + "  ->  " + rcOf((String) raw));
        } catch (Throwable t) {
            say("    " + key + "  ->  ERR " + t);
        }
    }

    public static void main(String[] args) {
        say("=== 设备级读数 ===");
        try {
            Method m = Class.forName("android.onyx.optimization.EInkHelper").getMethod("getAppScopeRefreshMode");
            say("  EInkHelper.getAppScopeRefreshMode() = " + m.invoke(null));
        } catch (Throwable t) {
            say("  getAppScopeRefreshMode ERR " + t);
        }
        try {
            Method m = Class.forName("android.onyx.ViewUpdateHelper").getMethod("getFastModeIndex");
            say("  ViewUpdateHelper.getFastModeIndex() = " + m.invoke(null) + "   (0=normal 1=system-fast 2=app-fast)");
        } catch (Throwable t) {
            say("  getFastModeIndex ERR " + t);
        }

        say("=== refresh_mode_N -> 有效 mode ===");
        try {
            Class<?> sys = Class.forName("android.onyx.config.SysUIConfig");
            Method get = sys.getMethod("getRefreshConfigByIndex", String.class);
            for (int i = 1; i <= 6; i++) {
                try {
                    Object d = get.invoke(null, "refresh_mode_" + i);
                    say("  refresh_mode_" + i + " -> " + (d == null ? "(null)"
                            : "mode=" + d.getClass().getMethod("getMode").invoke(d)));
                } catch (Throwable t) {
                    say("  refresh_mode_" + i + " -> ERR " + t);
                }
            }
        } catch (Throwable t) {
            say("  SysUIConfig ERR " + t);
        }

        say("=== 全局空 pkg 默认（新装/无自定义 app 的继承源） ===");
        dumpKey("eac_default_app_config");

        Class<?> cfg = null;
        for (String n : CFG_NAMES) {
            try {
                cfg = Class.forName(n);
                break;
            } catch (Throwable ignored) {
            }
        }
        say("EACAppConfig class = " + (cfg == null ? "(未找到)" : cfg.getName()));

        for (String pkg : args) {
            say("=== " + pkg + " ===");
            dumpActiveThemeType(pkg);
            dumpKey("eac_app_" + pkg);
            dumpKey("eac_default_app_config" + pkg);
            try {
                Object themes = Class.forName("android.onyx.optimization.EInkHelper")
                        .getMethod("loadThemes", String.class).invoke(null, pkg);
                if (themes instanceof List) {
                    List<?> l = (List<?>) themes;
                    say("    themes = " + l.size());
                    for (int i = 0; i < l.size(); i++) {
                        String t = (String) l.get(i);
                        Matcher mt = P_TT.matcher(t == null ? "" : t);
                        say("      theme[" + i + "] themeType=" + (mt.find() ? mt.group(1) : "?")
                                + "  " + rcOf(t));
                    }
                } else {
                    say("    themes = " + themes);
                }
            } catch (Throwable t) {
                say("    loadThemes ERR " + t);
            }
        }
    }
}
