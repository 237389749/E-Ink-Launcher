package cn.modificator.launcher;

import android.util.Base64;

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
 * 覆盖 Onyx 手势设置的全部 17 个手势位置（底部/左侧/右侧/顶部/三指/侧滑）；
 * 动作集只保留不依赖 Onyx 特有环境的通用动作（无前光/对比度/优化引擎/翻页/笔记等）。
 *
 * root 调用统一经 {@link SuHelper}（探测 /debug_ramdisk/su 等绝对路径）——此前直接用
 * {@code Runtime.exec("su")} 因 app 进程 PATH 不含 /debug_ramdisk 而必然失败（读全空、写报错）。
 */
public class GestureConfigHelper {

  private static final String GESTURES_CONFIG = "/data/data/com.android.systemui/gestures_config";

  /** 全部手势位置（gestures_config guestConfigMap 的 key，17 个） */
  public static final String[] POSITIONS = {
      "gestures_bottom_middle", "gestures_bottom_left", "gestures_bottom_right",
      "gestures_left_top", "gestures_left_middle", "gestures_left_bottom",
      "gestures_right_top", "gestures_right_middle", "gestures_right_bottom",
      "gestures_top_left", "gestures_top_middle", "gestures_top_right",
      "gestures_tree_point_down", "gestures_tree_point_up",
      "slide_gestures_left", "slide_gestures_right",
  };

  /** 手势位置显示名 */
  public static final String[] POSITION_LABELS = {
      "底部上滑", "底部左缘上滑", "底部右缘上滑",
      "左侧上部", "左侧中部", "左侧下部",
      "右侧上部", "右侧中部", "右侧下部",
      "顶部左滑", "顶部上滑", "顶部右滑",
      "三指下滑", "三指上滑",
      "左侧滑条", "右侧滑条",
  };

  /** 动作集 = 系统级 + Onyx ROM 层（EventHandler dispatchEvent case 1-32 + VOLUME），
   *  排除依赖 com.onyx app 的（AI 助手/手写笔记/笔类型/自由标注）与亮度风格广播。 */
  public static final String[] ACTIONS = {
      "NONE", "HOME", "BACK", "TASK_SWITCH", "SCREENSHOTS", "PARTIAL_SCREENSHOTS",
      "CLEAN_CACHE", "REFRESH_SCREEN", "STANDBY", "PREV_PAGE", "NEXT_PAGE",
      "PREV_CHAPTER", "NEXT_CHAPTER", "EINK_CENTER", "TOGGLE_CTM_BRIGHTNESS",
      "TOGGLE_CTM_TEMPERATURE", "TOGGLE_COLD_BRIGHTNESS", "TOGGLE_WARM_BRIGHTNESS",
      "SWITCH_REFRESH_MODE", "MEDIA_PLAY_PAUSE", "MEDIA_PLAY", "MEDIA_FAST_FORWARD",
      "MEDIA_REWIND", "VOLUME",
  };
  public static final String[] ACTION_LABELS = {
      "无", "回到桌面", "返回", "任务切换", "截屏", "区域截屏",
      "清理任务", "刷新屏幕", "待机", "上一页", "下一页",
      "上一章", "下一章", "优化引擎", "CTM 亮度", "CTM 色温",
      "冷光", "暖光", "切换刷新模式", "播放/暂停", "媒体播放",
      "快进", "快退", "音量",
  };

  /** 默认映射（Poke6 验证：底部上滑=任务切换、两侧=返回、三指下=截屏；侧滑条默认无） */
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
    m.put("gestures_top_left", "NONE");
    m.put("gestures_top_middle", "NONE");
    m.put("gestures_top_right", "NONE");
    m.put("gestures_tree_point_down", "SCREENSHOTS");
    m.put("gestures_tree_point_up", "NONE");
    m.put("slide_gestures_left", "NONE");
    m.put("slide_gestures_right", "NONE");
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
      if (map.containsKey(p)) full.put(p, map.get(p));
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

  /** 是否有 root（su 可用；经 SuHelper 探测 /debug_ramdisk/su 等绝对路径） */
  public static boolean isRootAvailable() {
    return SuHelper.isAvailable();
  }

  private static boolean exec(String cmd) {
    return SuHelper.execOk(cmd);
  }

  private static String execRead(String cmd) {
    return SuHelper.execRead(cmd);
  }
}
