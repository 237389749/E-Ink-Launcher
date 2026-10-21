package io.onyx;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * 把"默认 app 配置"的 refreshConfig 改写为 NONE + updateMode（save-only）。
 *
 * 为什么需要它（2026-10-09 实测结论）：
 *   EACThemeFactory.loadThemeOrCreate() 的取用链是
 *     loadThemeFromMMKV（eac_theme_&lt;pkg&gt;@theme_type_N）
 *     → loadLowVersionAppConfigThemeOrNull（**从 eac_app_&lt;pkg&gt; 迁移**）
 *     → loadDefaultThemeFromMMKV → ... → createDummyAppConfig
 *   只写 theme 时，重载会走第 2 步把 theme 用 eac_app_&lt;pkg&gt; 重建回去
 *   ⇒ 必须同时改 eac_app_&lt;pkg&gt;（EACAppConfig.save()）
 *     与 eac_default_app_config&lt;pkg&gt;（saveDefaultConfig()）。
 *   且 DEFAULT_ACTIVE_THEME = 3，即 themeType 3 才是生效的那个。
 *
 * ⚠️ 类名坑：反编译源码里写的是 data.p008v2.EACAppConfig，但**真实类名是
 *   android.onyx.optimization.data.v2.EACAppConfig**（p008v2 是 jadx 为避免与
 *   data.v1 重名而改的；证据：其内部匿名类注释为 "from class: ...data.v2.EACAppConfig.1"）。
 *   两个名字都试，v2 优先。
 *
 * 用法: app_process /system/bin io.onyx.SetDefCfg &lt;pkgCsv&gt; &lt;mode&gt;
 * 只改【已存在】的键，不新建。改写前把原始 JSON 备份到 /data/local/tmp/eac_bak/&lt;pkg&gt;.{app,defcfg}.json
 */
public class SetDefCfg {

    private static final String[] CFG_NAMES = {
            "android.onyx.optimization.data.v2.EACAppConfig",      // 真实类名（优先）
            "android.onyx.optimization.data.p008v2.EACAppConfig",  // jadx 改名（保底）
    };
    private static final String MMKV = "android.onyx.optimization.BaseMMKV";
    private static final String BAK = "/data/local/tmp/eac_bak";

    private static void say(String s) {
        System.out.println(s);
        System.out.flush();
    }

    private static Class<?> resolveCfg() {
        Throwable last = null;
        for (String n : CFG_NAMES) {
            try {
                return Class.forName(n);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new RuntimeException("EACAppConfig not found; tried both names", last);
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "sysidx".equals(args[0])) {
            Class<?> sys = Class.forName("android.onyx.config.SysUIConfig");
            java.lang.reflect.Method get = sys.getMethod("getRefreshConfigByIndex", String.class);
            for (int i = 1; i <= 9; i++) {
                String key = "refresh_mode_" + i;
                try {
                    Object d = get.invoke(null, key);
                    if (d == null) {
                        say(key + " -> (null)");
                    } else {
                        Object m = d.getClass().getMethod("getMode").invoke(d);
                        Object t = d.getClass().getMethod("getTurbo").invoke(d);
                        say(key + " -> mode=" + m + " turbo=" + t);
                    }
                } catch (Throwable t) {
                    say(key + " -> ERR " + t);
                }
            }
            return;
        }
        if (args.length < 2) {
            say("usage: SetDefCfg <pkgCsv> <mode> | sysidx");
            return;
        }
        int mode = Integer.parseInt(args[1]);
        Class<?> cfg = resolveCfg();
        say("EACAppConfig = " + cfg.getName() + "   mode=" + mode);
        int ok = 0, skip = 0, fail = 0;
        for (String pkg : args[0].split(",")) {
            pkg = pkg.trim();
            if (pkg.isEmpty()) continue;
            try {
                int n = 0;
                n += one(cfg, pkg, mode, "loadSavedConfig", "save", "eac_app_" + pkg, "app");
                n += one(cfg, pkg, mode, "loadDefaultConfig", "saveDefaultConfig",
                        "eac_default_app_config" + pkg, "defcfg");
                if (n == 0) {
                    skip++;
                    say("SKIP " + pkg + " (neither key exists)");
                } else {
                    ok++;
                    say("OK   " + pkg + " keys=" + n);
                }
            } catch (Throwable t) {
                fail++;
                say("FAIL " + pkg + " : " + t);
            }
        }
        say("RESULT mode=" + mode + " ok=" + ok + " skip=" + skip + " fail=" + fail);
    }

    private static int one(Class<?> cfg, String pkg, int mode, String loader, String saver,
                           String mmkvKey, String tag) throws Exception {
        Object c = cfg.getMethod(loader, String.class).invoke(null, pkg);
        if (c == null) return 0;
        Object activity = cfg.getMethod("getGlobalActivityConfig").invoke(c);
        if (activity == null) return 0;
        Object rc = activity.getClass().getMethod("getRefreshConfig").invoke(activity);
        if (rc == null) return 0;
        backup(mmkvKey, pkg, tag);
        rc.getClass().getMethod("setRefreshModeIndex", String.class).invoke(rc, "NONE");
        rc.getClass().getMethod("setUpdateMode", int.class).invoke(rc, mode);
        cfg.getMethod(saver).invoke(c);
        return 1;
    }

    private static void backup(String mmkvKey, String pkg, String tag) {
        try {
            File f = new File(BAK, pkg + "." + tag + ".json");
            if (f.exists()) return;
            Object raw = Class.forName(MMKV).getMethod("getString", String.class).invoke(null, mmkvKey);
            if (raw == null) return;
            new File(BAK).mkdirs();
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                w.write(String.valueOf(raw));
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            say("     (backup failed: " + t + ")");
        }
    }
}
