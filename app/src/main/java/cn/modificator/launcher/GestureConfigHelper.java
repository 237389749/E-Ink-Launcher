package cn.modificator.launcher;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全面屏手势配置管理（quickstep gestures_config）。
 *
 * 背景（ref.md §三）：Onyx 全面屏手势由 systemui 的
 * {@code /data/data/com.android.systemui/gestures_config}（JSON）控制——enable 总开关 +
 * guestConfigMap 各手势位置映射。com.onyx 通过 apply_config intent 推送（需
 * STATUS_BAR_SERVICE 权限，第三方不可用）；本类用 root 直接写文件 + 重启 systemui 生效，
 * 无 com.onyx 时配置持久。
 *
 * 动作集不含侧滑音量/亮度（slideEnable=false，不写 slide_gestures_*）。
 */
public class GestureConfigHelper {

  private static final String GESTURES_CONFIG = "/data/data/com.android.systemui/gestures_config";

  /** 手势位置（对应 gestures_config guestConfigMap 的 key） */
  public static final String[] POSITIONS = {
      "gestures_bottom_middle", "gestures_bottom_left", "gestures_bottom_right",
      "gestures_left_top", "gestures_left_middle", "gestures_left_bottom",
      "gestures_right_top", "gestures_right_middle", "gestures_right_bottom",
      "gestures_tree_point_down",
  };

  /** 手势位置显示名 */
  public static final String[] POSITION_LABELS = {
      "底部上滑", "底部左缘上滑", "底部右缘上滑",
      "左侧上部", "左侧中部", "左侧下部",
      "右侧上部", "右侧中部", "右侧下部",
      "三指下滑",
  };

  /** 动作集（无侧滑音量/亮度） */
  public static final String[] ACTIONS = {"NONE", "HOME", "BACK", "TASK_SWITCH", "SCREENSHOTS", "EINK_CENTER"};
  public static final String[] ACTION_LABELS = {"无", "回到桌面", "返回", "任务切换", "截屏", "优化引擎"};

  /** Poke6 验证过的默认映射 */
  private static Map<String, String> defaultMap() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("gestures_bottom_middle", "HOME");
    m.put("gestures_bottom_left", "TASK_SWITCH");
    m.put("gestures_bottom_right", "BACK");
    m.put("gestures_left_top", "BACK");
    m.put("gestures_left_middle", "BACK");
    m.put("gestures_left_bottom", "BACK");
    m.put("gestures_right_top", "BACK");
    m.put("gestures_right_middle", "BACK");
    m.put("gestures_right_bottom", "BACK");
    m.put("gestures_tree_point_down", "SCREENSHOTS");
    return m;
  }

  /** 读取当前 gestures_config（root），失败返回 null */
  public static String load() {
    return execRead("cat " + GESTURES_CONFIG);
  }

  /**
   * 保存手势配置并应用（root：备份 → 写文件 → 重启 systemui）。
   * map 只需包含要设置的位置（其余按默认 NONE 处理）。
   */
  public static boolean save(Map<String, String> map) {
    StringBuilder json = new StringBuilder();
    json.append("{\"backGestureEnable\":true,\"displayIndicator\":true,\"enable\":true,");
    json.append("\"enableBehindKeyboard\":true,\"enableThreePoint\":false,");
    json.append("\"guestConfigMap\":{");
    Map<String, String> full = defaultMap();
    for (String p : POSITIONS) {
      String v = map.containsKey(p) ? map.get(p) : "NONE";
      if (full.containsKey(p)) full.put(p, v);
    }
    boolean first = true;
    for (Map.Entry<String, String> e : full.entrySet()) {
      if (!first) json.append(',');
      json.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
      first = false;
    }
    json.append("},\"indicatesHeight\":50,\"indicatesTransparency\":50,\"indicatesWidth\":50,");
    json.append("\"preventMiscontact\":false,\"slideEnable\":false,\"slidingDistance\":100}");
    String body = json.toString();
    // base64 传输避免 shell 引号/特殊字符问题
    String b64 = Base64.encodeToString(body.getBytes(), Base64.NO_WRAP);
    return exec("cp " + GESTURES_CONFIG + " " + GESTURES_CONFIG + ".bak_launcher")
        && exec("echo '" + b64 + "' | base64 -d > " + GESTURES_CONFIG)
        && exec("kill $(pidof com.android.systemui)");
  }

  /** 恢复 launcher 备份（异常时） */
  public static boolean restore() {
    return exec("cp " + GESTURES_CONFIG + ".bak_launcher " + GESTURES_CONFIG)
        && exec("kill $(pidof com.android.systemui)");
  }

  /** 是否有 root（su 可用） */
  public static boolean isRootAvailable() {
    return exec("id") || exec("su -c id");
  }

  private static boolean exec(String cmd) {
    try {
      Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
      consume(p);
      return p.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static String execRead(String cmd) {
    try {
      Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = r.readLine()) != null) sb.append(line);
      consume(p);
      p.waitFor();
      return sb.length() > 0 ? sb.toString() : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static void consume(Process p) throws Exception {
    BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
    while (e.readLine() != null) { /* drain */ }
  }
}
