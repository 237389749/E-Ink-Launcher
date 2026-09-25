# E-Ink Launcher — 项目综合文档

> 由 `E-Ink-Launcher/README.md` + `E-Ink-Launcher/CHANGELOG.md` + `ref.md` 分类合并（2026-08-25）。
> 原 README/CHANGELOG 保留在仓库内；本文件为汇总参考。

---

# 一、项目概览（原 README.md）

## E-Ink Launcher

`E-Ink Launcher` is an Android launcher for Electronic paper book

this verison is support end,new version in branch [master](https://github.com/Modificator/E-Ink-Launcher)

join telegram group https://t.me/EInkLauncher

![E-Ink Launcher](app/release/preview.png)


---

# 二、变更记录（原 CHANGELOG.md）

## 0.1.8.6
- fix custom wifi icon failed
- one key lock crash
---------

## 0.1.8.5
- fix no custom wifi icon crash
---------

## 0.1.8.4
- fix some devices can not show full setting
- lock screen icon name:`E-ink_Launcher.Lock.png`
- wifi icon name:`E-ink_Launcher.WifiOn.png` `E-ink_Launcher.WifiOff.png`


---

# 三、系统机制参考

# Onyx/Poke6 手势导航切换 — 系统实现参考

## 核心机制

通过 `Settings.Global["system_navigation_mode_key"]` 控制导航模式：

| 值 | 含义 |
|---|---|
| `"virtual_key"` | 虚拟按键导航（底部三键） |
| `"gesture"` | 手势导航（全面屏手势） |
| 空/null | 等同于 `"virtual_key"`（默认） |

## 完整切换链与事故记录（2026-08-26 实测补充）

> 背景：手势模式设置正确（key=gesture、导航栏隐藏）但**全面屏手势无效**。经排查是 overlay 与
> AOSP 手势处理层的切换被破坏。以下为从 res 反编译确认的三层控制链。

### 三层控制链

```
【层1 导航模式设置】Settings.Global["system_navigation_mode_key"]（gesture / virtual_key）
    —— 唯一"意图"来源；launcher 用 GestureNavHelper.putString 切换（Launcher.java:312 onToggleGestureNav）

【层2 导航栏显隐】SystemUI VendorServices.updateOverlayConfigByCurrentNavigationMode()（VendorServices.java:39-58）
    gesture → StatusBar.removeNavigationBar()（隐藏三键 UI）
    virtual_key → StatusBar.addNavigationBar()
    末尾【无条件】Utils.updateOverlayConfig("com.android.internal.systemui.navbar.threebutton")  ← 切 threebutton overlay

【层3 AOSP 手势处理】NavigationModeController（NavigationModeController.java:60-100）
    updateCurrentInteractionMode():
      ├── switchToDefault3ButonNavOverlay()  ← 【无条件】切 threebutton overlay
      ├── getCurrentInteractionMode() = context.getResources()
      │     .getInteger(R.integer.config_doublePressOnPowerBehavior)  ← 读 overlay 资源值
      └── Settings.Secure.putString("navigation_mode", value)  ← 写 secure navigation_mode
    触发：启动 + onOverlayChanged（overlay 变化）→ 每次 overlay 变化都会重新评估并切回 threebutton
```

### overlay 机制

```
overlay 包（/product/overlay/NavigationBarModeGesturalOnyx/...apk）：
  com.android.internal.systemui.navbar.gestural_onyx   ← Onyx 手势 overlay（config_doublePressOnPowerBehavior=1）
  com.android.internal.systemui.navbar.threebutton     ← 三键 overlay（config=0）
  cmd overlay list 可查状态；状态持久化于 /data/system/overlays.xml

正常手势 = gestural_onyx enabled → config=1 → secure navigation_mode=1 → SystemUI 手势处理启用
secure navigation_mode 被 NavigationModeController 写入（非 Onyx 定制 key，AOSP 层）
```

### 广播切换入口（OnyxCommonActionReceiver.setNavigationBarVisibility, :238）

```java
Utils.updateOverlayConfig(intent.getBooleanExtra("args_enable", true)
    ? "com.android.internal.systemui.navbar.threebutton"
    : "com.android.internal.systemui.navbar.gestural_onyx");   // args_enable=false → 启用手势 overlay
```

### 事故记录（勿重蹈）

- **手势失效根因**：`NavigationModeController` 与 `VendorServices` **无条件切 threebutton overlay**；
  一旦触发（启动/overlay 变化/key 变化），gestural_onyx 被禁用且被持久化。
  `cmd overlay enable gestural_onyx` 后 2 秒内被 NavigationModeController 切回（死循环，无法手动保持）
- **触发源头（2026-08-26）**：为恢复手势强制刷新 `system_navigation_mode_key`（put virtual_key → gesture），
  两次值变化触发 VendorServices/NavigationModeController → threebutton overlay 持久化 → gestural 丢失
- **关联**：`com.onyx`（Onyx 内置桌面）**已被卸载**（installed=false），手势曾依赖其遗留环境
  （gestural_onyx enabled 状态等）；设备操作/重启后该遗留状态被 NavigationModeController 覆盖
- **结论**：改手势前先记录 overlay 状态（`cmd overlay list`）与 secure navigation_mode 基线；
  不要用"值往返"强制刷新 system_navigation_mode_key（会触发 overlay 切换）；恢复手势需
  阻止 NavigationModeController 的 switchToDefault3ButonNavOverlay 或找到 com.onyx 遗留的启用路径

### ★ 根因修正与真正修复（2026-08-26 晚，手势已恢复）

> 上述"overlay 是手势失效根因"为**误判**。真正根因是 **quickstep 的 `gestures_config` 文件**。

**完整根因链**（实测确认）：
1. **com.onyx.kreader 也被卸载**（installed=false）——它注册 authority `com.onyx.content.database.ContentProvider`
   → **com.onyx 启动时查该 provider 崩溃**（`SecurityException: Failed to find provider`）
   → **恢复**：`pm install-existing com.onyx.kreader`（系统应用，/system/app/kreader2-release）
2. **com.onyx 每次启动把 `gestures_config`（enable 标志）推给 systemui quickstep**（`apply_config` intent，
   `InitDefaultGestureOrNavigationAction` 读 MMKV `navigation_type_key`，默认 = `gesturesConfig.enable ? 2(手势) : 1(虚拟键)`）
   → **com.onyx 卸载后无人推送** → systemui 回退**出厂默认 gestures_config（enable=false, backGestureEnable=false）**
   → **全面屏手势动作被 quickstep 丢弃**（`SideSlideInputConsumer` 按 enable 拒绝）→ 手势失效
3. overlay（gestural_onyx）被 NavigationModeController 锁死 threebutton 是**并存现象但不是主因**
   （gesture 时导航栏隐藏由 VendorServices.removeNavigationBar 负责，与 overlay 无关）

**修复（已验证恢复手势）**：
```bash
# 1. 改 systemui 的 gestures_config（enable=true + backGestureEnable=true）
adb exec-out su -c 'cat /data/data/com.android.systemui/gestures_config' > gc.json   # 备份
# 编辑 enable=true, backGestureEnable=true（保持其他字段）
adb exec-in su -c 'cat > /data/data/com.android.systemui/gestures_config' < gc_new.json
# 2. 重启 SystemUI 加载
adb shell su -c 'kill $(pidof com.android.systemui)'
```
**关键文件**：`/data/data/com.android.systemui/gestures_config`（JSON：enable/backGestureEnable/slideEnable/
gestures_bottom_middle/gestures_bottom_left/gestures_right_top 等）

**其他发现**：
- com.onyx 崩溃修复 = 装回 kreader；com.onyx 本身用 `pm install-existing com.onyx` 恢复
- com.onyx 首启教程（data 被清）→ 点「开始使用」进入 MainActivity
- gestures_config 的 enable 同时决定 com.onyx 启动时的默认导航类型（enable=true→手势）

### Onyx 桌面手势设置完整逻辑（2026-08-26 晚，eink-home 反编译）

**入口**：桌面底部区域 → 设置 → 手势设置（`GestureSettingsContainerFragment`，含「底部手势」等 tab）
→ `BottomGestureSettingsFragment`

**切换逻辑（BottomGestureSettingsFragment.m45435b(int i, boolean z)）**：
```
i=0 关闭导航   → SetGestureEnableAction(false) + 切 gesture
i=1 虚拟键     → SetGestureEnableAction(false) + changeNavigationModeToVirtualKey() + setNavigationBarEnable(true)
i=2 手势       → SetGestureEnableAction(true) + changeNavigationModeToGesture() + setNavigationBarEnable(false)
                 + KCBMMKVHelper.setNavigationType(i)（持久化）
```

**推送链路（手势配置 → systemui）**：
```
SetGestureEnableAction.setEnable(true)
→ BaseGesturesSettingAction.applyConfigToService(GesturesConfig)
→ ApplyGesturesConfigToServiceRequest.execute()
→ GesturesServiceUtils.applyGesturesConfig(context, config)
→ Intent{action="android.intent.action.QUICKSTEP_SERVICE",
         package=com.android.systemui,
         extra: service_action="apply_config" + gestures_config JSON}
→ startService（systemui TouchInteractionService 写入 /data/data/com.android.systemui/gestures_config）
```

**配置读取（初始化）**：`LoadGesturesConfigAction` → `OverviewProxyHandler` bindService systemui
→ `GestureConfigProviderBelowA16` 经 `IOverviewProxy.getGesturesConfig()` 拉当前配置；
回退读 raw 资源 `gestures_<manufacturer>_<model>.json`

**手势动作枚举（GestureActionType）**：NONE/HOME/BACK/SCREENSHOTS/PARTIAL_SCREENSHOTS/
TASK_SWITCH/FULLSCREEN/CLEAN_CACHE/REFRESH_SCREEN/COLD_LIGHT/WARM_LIGHT/VOLUME/CONTRAST/
EINK_CENTER/CTM_BRIGHTNESS/CTM_TEMPERATURE/PREV_PAGE/NEXT_PAGE/STANDBY 等
（`GestureActionType.java`；每手势位置可选列表在 `GesturesDataMap.java`）

**gestures_config JSON 结构**（`/data/data/com.android.systemui/gestures_config`）：
```json
{"enable":true,"backGestureEnable":true,"slideEnable":false,"enableBehindKeyboard":true,
 "enableThreePoint":true,"displayIndicator":true,"preventMiscontact":false,
 "indicatesHeight":50,"indicatesTransparency":50,"indicatesWidth":50,"slidingDistance":100,
 "guestConfigMap":{"gestures_bottom_middle":"HOME","gestures_bottom_left":"TASK_SWITCH",
   "gestures_left_bottom":"BACK","gestures_left_middle":"BACK","gestures_left_top":"BACK",
   "gestures_bottom_right":"BACK","gestures_tree_point_down":"SCREENSHOTS",
   "slide_gestures_left":"VOLUME","slide_gestures_right":"CTM_BRIGHTNESS",
   "gestures_right_top":"NONE","gestures_right_middle":"NONE", ...}}
```
> ★ 底部手势映射是"上滑调出优化引擎"的原因：`gestures_bottom_left=EINK_CENTER`；
> 在桌面设置改回 `TASK_SWITCH`（任务切换）即修复。

**★ 最终闭环（2026-08-26 晚，手势已恢复并确认正常）**：
- **导航模式总开关 = `enable` 字段，由 com.onyx 的 `navigation_type_key`（MMKV）决定**——
  `m45435b(2)` 选「手势」才 `SetGestureEnableAction(true)` → 推送 `enable=true`；
  **只改手势映射（guestConfigMap）不动总开关时，推送的 enable 按 navigation_type 计算**
  （首启时 gestures_config enable=false → com.onyx 默认 navigation_type=1 虚拟键 → enable=false → 手势仍失效）
  → **必须在桌面底部手势设置里把模式选为「手势」（i=2）**，enable 才会为 true
- **修复操作序列（完整）**：① `pm install-existing com.onyx.kreader`（provider 崩溃修复）
  ② `pm install-existing com.onyx` ③ 首启教程点「开始使用」④ 桌面设置→手势设置→模式选「手势」
  + 配置底部/左右/右侧手势映射 → com.onyx 推送正确的 gestures_config
  ⑤ 若 enable=false：在底部手势设置把模式切到「手势」后 enable 自动变 true
- **最终 gestures_config**（Poke6 用户配置）：enable=true, backGestureEnable=true,
  gestures_bottom_middle=HOME, gestures_bottom_left=TASK_SWITCH,
  gestures_left_*/bottom_right=BACK, gestures_right_*=BACK, gestures_tree_point_down=SCREENSHOTS,
  slide_gestures_left=VOLUME, slide_gestures_right=CTM_BRIGHTNESS, slideEnable=false
- **手动改 gestures_config 文件（enable=true）不是持久方案**——com.onyx 下次推送（切映射/模式）
  会按 navigation_type 重新计算覆盖；**正确做法是让用户在桌面手势设置里选「手势」模式**

### E-Ink-Launcher 手势设置集成设计（2026-08-26，已实现）

**目标**：E-Ink-Launcher 独立管理 gestures_config（不依赖 com.onyx 桌面），纯 launcher 场景
（com.onyx 卸载/禁用）可自管手势。

**通道选择**：
- `apply_config` intent（QUICKSTEP_SERVICE）需 `android.permission.STATUS_BAR_SERVICE`
  （signature/privileged）→ **第三方不可用**
- **root 直接写文件**（已验证可行）：`/data/data/com.android.systemui/gestures_config` + `kill systemui`；
  文件持久（systemui 重启读文件不覆盖），无 com.onyx 时不回退

**实现（E-Ink-Launcher `GestureConfigHelper.java` + SettingFragment「手势设置」入口）**：
- 设置页新增「手势设置」→ AlertDialog 手势位置列表（底部上滑/左右缘、左侧上中下、右侧上中下、三指下）
  → 二级 Dialog 选动作 → 保存
- 动作集（**不含侧滑音量/亮度**，用户要求去除）：`NONE / HOME / BACK / TASK_SWITCH / SCREENSHOTS / EINK_CENTER`
- 保存：备份原文件 → su 写 JSON（base64 传输避免引号问题）→ `su -c 'kill $(pidof com.android.systemui)'`
- **必须写 `enable=true`**（只改映射不动 enable 是踩过的坑）
- `slideEnable=false` 且 JSON **不含 slide_gestures_left/right**（无音量/亮度）
- 默认映射（Poke6 验证）：bottom_middle=HOME、bottom_left=TASK_SWITCH、bottom_right=BACK、
  left_top/middle/bottom=BACK、right_top/middle/bottom=BACK、tree_point_down=SCREENSHOTS、
  其余 NONE；enableThreePoint=false、backGestureEnable=true
- 构建/权限：同 RefreshModeHelper（su fallback），无新增权限

### ★ 动作依赖分层发现（2026-08-26，EventHandler 反编译验证）

**动作执行器**：`res/eink-systemui/.../quickstep/util/EventHandler.java`（dispatchEvent 点按手势 / dispatchSlideEvent 侧滑），
动作名 = `android.onyx.utils.OnyxActionType`。

**三层依赖**（卸载 com.onyx 内置桌面后是否失效）：

| 层级 | 动作（dispatchEvent case） | 实现 | 卸载 com.onyx 后 |
|---|---|---|---|
| ①系统级 | TASK_SWITCH/HOME/BACK、PREV/NEXT_PAGE、PREV/NEXT_CHAPTER、MEDIA_PLAY/PAUSE/FAST_FORWARD/REWIND | `KeyEventUtils.sendKeyEvent` / `CommandQueue.toggleRecentApps` | ✅ |
| ②Onyx ROM 层 | SCREENSHOTS、CLEAN_CACHE、REFRESH_SCREEN、STANDBY、EINK_CENTER(优化引擎)、PARTIAL_SCREENSHOTS、TOGGLE_CTM_BRIGHTNESS/TEMPERATURE、TOGGLE_COLD/WARM_BRIGHTNESS(前光)、SWITCH_REFRESH_MODE | SystemUI CommandQueue（brightnessUp/Down、showEinkCenter）/ framework 广播（截图、全刷、PM 待机、清理）/ EAC | ✅ 仍可用 |
| ③依赖 com.onyx 体系 app | TOGGLE_AI_ASSISTANT（绑定 com.onyx.aiassistant 服务）、QUICK_HANDWRITING_NOTES（com.onyx.intent.action.QUICK_NOTE）、TOGGLE_PEN_TYPE、TOGGLE_FREE_MARK | 绑定/广播给 Onyx app | ❌ 失效 |

**dispatchSlideEvent（侧滑条）**：case 33-43 → VOLUME（AudioManager）、前光系列（CommandQueue）、
CONTRAST/COLOR 系列（`ChangeEACColorValue`，framework EAC）——均 ROM 层，卸载 com.onyx 仍可用。

**结论**：前光/对比度/刷新屏幕/优化引擎/待机/清理任务等"Onyx 动作"实际依赖 **Onyx ROM（framework+SystemUI）**，
**不是 com.onyx app**——纯 launcher 场景仍有效；**真正依赖 com.onyx app 的仅**：AI 助手/手写笔记/笔类型/自由标注。
→ GestureConfigHelper 动作集含 ①+②（排除 ③）。

## 系统服务端 (eink-services)

**文件**: `com.android.server.wm.DisplayPolicy`

```java
private boolean isNavigationModeVirtualKey() {
    String currentMode = Settings.Global.getString(mContext.getContentResolver(), "system_navigation_mode_key");
    return TextUtils.isEmpty(currentMode) || "virtual_key".equals(currentMode);
}
```

作用：
- 控制侧滑/底部上滑是否触发瞬态导航栏（transient bars）
- 影响导航栏帧高度计算和窗口 insets

## SystemUI 端 (eink-systemui)

### VendorServices（核心观察者）

```java
private static final Uri SYSTEM_NAVIGATION_MODE_URI = Settings.Global.getUriFor("system_navigation_mode_key");

// 注册 ContentObserver 监听变化
mContext.getContentResolver().registerContentObserver(SYSTEM_NAVIGATION_MODE_URI, false, mNavigationModeObserver);

// 变化时执行：
private void updateOverlayConfigByCurrentNavigationMode() {
    String mode = Settings.Global.getString(..., "system_navigation_mode_key");
    if (TextUtils.isEmpty(mode)) mode = "virtual_key";

    if ("gesture".equals(mode)) {
        StatusBar.removeNavigationBar();   // 移除底部虚拟按键
    } else if ("virtual_key".equals(mode)) {
        StatusBar.addNavigationBar();     // 添加底部虚拟按键
    }
    Utils.updateOverlayConfig("com.android.internal.systemui.navbar.threebutton");
}
```

### NavigationBarController

```java
public boolean isShowNavigationByUserSettings() {
    String string = Settings.Global.getString(..., "system_navigation_mode_key");
    return !TextUtils.isEmpty(string) && "virtual_key".equals(string);
}

// onDisplayReady 时仅在 virtual_key 模式下创建导航栏
```

### 其他 UI 组件

- `QSPanel` / `QSContainerImpl` / `QSCustomizer` — 根据模式调整快捷面板布局高度
- `KeyguardHostView` — 锁屏界面适配
- `OnyxRecentsActivity` — 最近任务页隐藏/显示导航栏区域

## 第三方应用如何切换

### 读取（无需特殊权限）

```java
String mode = Settings.Global.getString(getContentResolver(), "system_navigation_mode_key");
boolean isGesture = "gesture".equals(mode);
```

### 写入

**方式一**: 需要 `WRITE_SECURE_SETTINGS` 权限（系统签名应用）
```java
Settings.Global.putString(getContentResolver(), "system_navigation_mode_key", "gesture");
```

**方式二**: 通过 shell 命令（需要 adb 或 su）
```bash
settings put global system_navigation_mode_key gesture
settings put global system_navigation_mode_key virtual_key
```

### Launcher 实现策略

1. 先尝试 `Settings.Global.putString()` 直接写入
2. 若抛 `SecurityException`，降级为 `Runtime.exec("settings put global ...")`
3. 写入后 SystemUI 的 ContentObserver 自动响应，无需额外广播

## 注意事项

- 该 key 是 Onyx 自定义的，不是 AOSP 标准（AOSP 用 `config_navBarInteractionMode` 整数）
- 切换后 SystemUI 会自动 add/remove NavigationBar，无需重启
- 手势模式下侧滑返回由 `SystemGesturesPointerEventListener` 处理

---

# Onyx E-Ink 刷新模式系统 — 实现参考

## UpdateMode 全集（18 个枚举值）

来源: `com.onyx.android.sdk.api.device.epd.UpdateMode` 枚举  
文件: `res/eink-home/sources/com/onyx/android/sdk/api/device/epd/UpdateMode.java`

```java
public enum UpdateMode {
    None,                    // 无操作/默认
    DU,                      // Direct Update (2级灰度快速)
    DU4,                     // 4级灰度 DU
    GU,                      // 手写模式
    GU_FAST,                 // 快速手写
    GC,                      // GC16 全刷 (高质量)
    GCC,                     // 压缩 GC16
    DEEP_GC,                 // 深度 GC (最彻底清残影)
    ANIMATION,               // A2 动画模式
    ANIMATION_QUALITY,       // A2 质量模式
    ANIMATION_MONO,          // 单色 A2
    ANIMATION_X,             // X 模式 (ONYX_AUTO + A2)
    GC4,                     // 4级灰度 GC
    REGAL,                   // Regal 低残影
    REGAL_D,                 // Regal-D 变体
    REGAL_PLUS,              // Regal+ 最高质量
    DU_QUALITY,              // DU 质量模式
    HAND_WRITING_REPAINT_MODE // 手写重绘
}
```

应用可直接选用任意一个：
```java
EpdController.setViewDefaultUpdateMode(view, UpdateMode.GC);
EpdController.applyTransientUpdate(UpdateMode.ANIMATION_QUALITY);
EpdController.repaintEveryThing(UpdateMode.GC);
```

## UI 快捷方式（5 个）→ UpdateMode 映射

通知栏/设置面板中用户可见的 5 个选项只是快捷方式，
由 `SysUIConfig.refreshConfigMap` 控制映射关系：

| UI 名称 (RefreshModeUI) | 映射到的 UpdateMode |
|---|---|
| HD | REGAL 或 GC |
| DU | DU |
| REGAL_PLUS | REGAL_PLUS |
| SMOOTH | ANIMATION / ANIMATION_X / GU |
| NEW_SPEED | DU / ANIMATION_X |

> 具体映射因设备型号而异（由 `res/raw/<model>_systemui.json` 配置）

## OECService 内部逻辑模式（6 种）

来源: `android.onyx.optimization.Constant`  
OECService 在 Activity 切换时使用这 6 种逻辑值管理 per-app 配置：

| 值 | 常量 | 说明 |
|---|---|---|
| 0 | UPDATE_MODE_NORMAL / DEFAULT | 默认，由系统 hook epdc 管理 |
| 1 | UPDATE_MODE_DU | Direct Update |
| 2 | UPDATE_MODE_A2 | 最快刷新 (对应 ANIMATION 系列) |
| 3 | UPDATE_MODE_REGAL | Regal |
| 4 | UPDATE_MODE_X | X 模式 (对应 ANIMATION_X) |
| 5 | UPDATE_MODE_REGAL_PLUS | Regal+ |

> 快速模式（isFastMode）: mode == 1 (DU) || 2 (A2) || 4 (X)
> Hook EPDC 模式: mode == 0 (NORMAL) || 3 (REGAL) — 由系统代理刷新

## EPD 硬件波形常量

来源: `android.onyx.ViewUpdateHelper`

```
EINK_WAVEFORM_MODE_INIT      = 0   // 初始化
EINK_WAVEFORM_MODE_DU        = 1   // Direct Update
EINK_WAVEFORM_MODE_GC16      = 2   // 16 级灰度全刷
EINK_WAVEFORM_MODE_GC4       = 3   // 4 级灰度
EINK_WAVEFORM_MODE_ANIM      = 4   // 动画
EINK_WAVEFORM_MODE_AUTO      = 5   // 自动
EINK_WAVEFORM_MODE_REAGL     = 6   // Regal
EINK_WAVEFORM_MODE_DU4       = 8   // 4 级 DU
EINK_WAVEFORM_MODE_REAGL_PLUS= 9   // Regal+
EINK_WAVEFORM_MODE_GCC16     = 11  // 压缩 GC16
EINK_WAVEFORM_MODE_DEEP_GC16 = 12  // 深度 GC16
```

## UI 模式（组合标志位）

```
UI_DU_MODE           = 1     // DU 基础
UI_GU_MODE           = 2     // GU (手写)
UI_A2_PERFORMANCE_MODE = 4   // A2 性能
UI_DEFAULT_MODE      = 5     // AUTO (默认)
UI_REGAL_MODE        = 6     // Regal
UI_REGAL_PLUS_MODE   = 9     // Regal+
UI_GC_MODE           = 98    // GC16 全刷
UI_GCC_MODE          = 107   // GCC16
UI_DEEP_GC_MODE      = 108   // 深度 GC
UI_DU_QUALITY_MODE   = 2305  // DU + 质量标志
UI_A2_QUALITY_MODE   = 2308  // A2 + 质量标志
UI_DU4_MODE          = 2312  // DU4 + 质量标志
UI_X_A2_MODE         = 16777220  // X 模式 (ONYX_AUTO + A2)
UI_X_DU_MODE         = 16777217  // X DU 模式
UI_MONO_A2_MODE      = 33554436  // 单色 A2
```

## 逻辑模式 → EPD 模式映射

来源: `EACUtils.toEpdMode(int mode)`

```java
switch (mode) {
  case 0: return 5;           // NORMAL → AUTO (但实际走 hook epdc，不用 app scope)
  case 1: return 2305;        // DU → UI_DU_QUALITY_MODE
  case 2: return 2308;        // A2 → UI_A2_QUALITY_MODE
  case 3: return 6;           // REGAL → UI_REGAL_MODE
  case 4: return 16777220;    // X → UI_X_A2_MODE
  case 5: return 9;           // REGAL_PLUS → UI_REGAL_PLUS_MODE
}
```

## 完整上下游调用链

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 1: App / SDK API                                         │
│  EpdController.setViewDefaultUpdateMode(view, UpdateMode.GC)    │
│  EpdController.applyTransientUpdate(UpdateMode.ANIMATION)       │
│  EpdController.applyAppScopeUpdate(pkg, en, clr, mode, count)   │
│  EpdController.setAppScopeRefreshMode(UpdateOption.FAST)        │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Device.currentDevice().xxx()
┌───────────────────────────▼─────────────────────────────────────┐
│  Layer 2: SDK Device 实现 (SDMDevice / IMX6Device / RK32XX...)  │
│  文件: res/eink-home/sources/com/onyx/android/sdk/device/        │
│                                                                   │
│  SDMDevice.m22597h(UpdateMode) → int (ViewUpdateHelper 常量)     │
│  通过反射调用 ViewUpdateHelper 的静态方法                         │
│                                                                   │
│  UpdateOption → OECService int (BaseDevice.m22545b):             │
│    NORMAL→0, FAST_QUALITY→1, FAST→2, REGAL→3, FAST_X→4          │
└───────────────────────────┬─────────────────────────────────────┘
                            │ 反射调用 (ReflectUtil)
┌───────────────────────────▼─────────────────────────────────────┐
│  Layer 3: Framework (android.onyx.ViewUpdateHelper)               │
│  文件: res/eink-framework/.../android/onyx/ViewUpdateHelper.java │
│                                                                   │
│  applyAppScopeUpdate(pkg, enable, clearFlag, mode, count)        │
│    → Parcel.writeInt(mode) → transactData(APPLY_APP_SCOPE_UPDATE)│
│  applyTransientUpdate(mode)                                      │
│    → Parcel.writeInt(mode) → transactData(APPLY_TRANSIENT_UPDATE)│
│  repaintEverything(mode)                                         │
│    → Parcel.writeInt(mode) → transactData(REPAINT_EVERY_THING)   │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Binder IPC: sf.transact(code, data)
                            │ ServiceManager.getService("SurfaceFlinger")
┌───────────────────────────▼─────────────────────────────────────┐
│  Layer 4: SurfaceFlinger (Onyx 定制)                              │
│  - 解析 mode int → 选择 EPD 波形                                 │
│  - Debouncer: 合并高频刷新请求                                   │
│  - Transient Update: 临时覆盖当前模式                            │
│  - App Scope: 对指定包名所有帧应用模式                           │
│  - GC 定时全刷清除残影                                           │
└─────────────────────────────────────────────────────────────────┘
```

### SDMDevice 核心映射表 (UpdateMode → ViewUpdateHelper int)

来源: `SDMDevice.m22597h(UpdateMode)` (line 2105)

| UpdateMode | switch# | ViewUpdateHelper 常量 | 值 |
|---|---|---|---|
| GU_FAST | 1 | UI_DU_MODE | 1 |
| DU | 2 | UI_DU_MODE | 1 |
| DU4 | 3 | UI_DU4_MODE | 2312 |
| DU_QUALITY | 4 | UI_DU_QUALITY_MODE | 2305 |
| GU | 5 | UI_GU_MODE | 2 |
| GC | 6 | UI_GC_MODE | 98 |
| GCC | 7 | UI_GCC_MODE | 107 |
| DEEP_GC | 8 | UI_DEEP_GC_MODE | 108 |
| ANIMATION | 9 | (组合: REGIONAL\|NOWAIT\|ANIM\|PARTIAL) | ~4 |
| ANIMATION_QUALITY | 10 | UI_A2_QUALITY_MODE | 2308 |
| ANIMATION_MONO | 11 | UI_MONO_A2_MODE | 33554436 |
| ANIMATION_X | 12 | UI_X_A2_MODE | 16777220 |
| GC4 | 13 | UI_GC4_MODE | 3 |
| REGAL | 14 | UI_REGAL_MODE (fallback: UI_GU_MODE) | 6 |
| REGAL_D | 15 | (组合: REGIONAL\|NOWAIT\|REAGLD\|REAGL\|PARTIAL) | ~4102 |
| REGAL_PLUS | 16 | UI_REGAL_PLUS_MODE (fallback: UI_GU_MODE) | 9 |
| HAND_WRITING_REPAINT_MODE | 17 | HAND_WRITING_REPAINT_MODE | 524290 |
| None / default | — | UI_DEFAULT_MODE | 5 |

> 注: GU_FAST 和 DU 映射到同一个值 (UI_DU_MODE=1)

### UpdateOption → OECService 逻辑模式

来源: `BaseDevice.m22545b(UpdateOption)` / `m22546c(int)`

| UpdateOption | OECService int | 反向 |
|---|---|---|
| NORMAL | 0 (UPDATE_MODE_DEFAULT) | 0 → NORMAL |
| FAST_QUALITY | 1 (UPDATE_MODE_DU) | 1 → FAST_QUALITY |
| FAST | 2 (UPDATE_MODE_A2) | 2 → FAST |
| REGAL | 3 (UPDATE_MODE_REGAL) | 3 → REGAL |
| FAST_X | 4 (UPDATE_MODE_X) | 4 → FAST_X |
| — | 5 (REGAL_PLUS) | 5 → NORMAL* |

### EAC per-activity 配置 → UpdateMode

来源: `EACUtil.getUpdateMode(int)` (eink-home SDK)

| EAC config int | UpdateMode |
|---|---|
| 0 | None (不干预) |
| 1 | DU |
| 2 | ANIMATION_QUALITY |
| 3 | REGAL |

### 设备检测

来源: `Device.detectDevice()` 根据 `ro.board.platform` 选择实现：

| 平台 | 实现类 |
|---|---|
| msm8953/sdm660/bengal/msmnile/lito/volcano | SDMDevice |
| freescale + imx7 | IMX7Device |
| freescale (其他) | IMX6Device |
| rk3288 | RK32XXDevice |
| rk312x | RK31XXDevice |
| rk3368 | RK33XXDevice |
| rk30board | RK3026Device |

## 关键 API（第三方应用可用）

### 1. 全屏刷新（清残影）
```java
// 使用 GC 模式全屏刷新
ViewUpdateHelper.repaintEverything();
// 或指定模式
ViewUpdateHelper.repaintEverything(ViewUpdateHelper.UI_GC_MODE); // 98
```

### 2. App 级刷新模式
```java
// 设置当前应用的刷新模式（通过 OECService）
EInkHelper.setAppScopeRefreshMode(mode); // mode: 0-5

// 底层调用:
ViewUpdateHelper.applyAppScopeUpdate(pkgName, true, clearFlag, EACUtils.toEpdMode(mode), Integer.MAX_VALUE);
```

### 3. 瞬态刷新（滚动/动画时临时加速）
```java
// 进入滚动时切换到 A2
ViewUpdateHelper.applyTransientUpdate(EACUtils.toEpdMode(2)); // 2308
// 滚动结束恢复
ViewUpdateHelper.clearTransientUpdate(gcAfterScroll); // gcAfterScroll=true 则做一次 GC
```

### 4. 单 View 刷新模式（Onyx SDK）
```java
import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;

EpdController.setViewDefaultUpdateMode(myView, UpdateMode.GC);
EpdController.setViewDefaultUpdateMode(myView, UpdateMode.ANIMATION);
```

### 5. 监听刷新模式变化
```java
// 广播: com.onyx.action.REFRESH_MODE_CHANGED
// extras: "refresh_mode" (int), "turbo" (int)
IntentFilter filter = new IntentFilter("com.onyx.action.REFRESH_MODE_CHANGED");
registerReceiver(receiver, filter);
```

## 系统行为

- **Activity 切换**: `TabletEACRefreshImpl.onResume()` 读取目标 Activity 的 `EACRefreshConfig`，调用 `applyAppScopeUpdate` 或 `clearAppScopeUpdate`
- **模式 0/3/5 (NORMAL/REGAL/REGAL_PLUS)**: 清除 app scope update，由系统 hook epdc 代理
- **模式 1/2/4 (DU/A2/X)**: 设置 app scope update，SurfaceFlinger 对该应用所有帧使用指定波形
- **Debouncer**: 对 REGAL/REGAL_PLUS/NORMAL 模式启用防抖，合并高频刷新
- **Turbo**: `ViewUpdateHelper.setEpdTurbo(value)` 控制 EPD 驱动电压/速度
- **滚动**: `ScrollHelper` 在滚动期间 applyTransientUpdate(A2)，结束后 clearTransientUpdate + 可选 GC

## 通知栏刷新模式面板 — 完整上下游

### 两个 UI 入口

#### 1. QS Tile 快捷磁贴（全局模式）

```
RefreshModeTile.handleClick()
  → sendEvent(1042) → OnyxStatusBarImpl.handleShowRefreshModeDialog()
  → OnyxRefreshModeDialog → OnyxRefreshModeController
```

文件: `res/eink-systemui/.../settings/OnyxRefreshModeController.java`

UI 选项（4 个）:
| 按钮 | mode 值 | 说明 |
|---|---|---|
| Normal | 0 | 默认高质量 |
| DU | 1 | 快速 2 级灰度 |
| A2 | 2 | 最快刷新 |
| X | 4 | X 模式 (可选，由 SystemUIConfig.isEnableXMode() 控制) |

额外设置:
- 滚动刷新模式: A2(2) 或 X(4)
- 滚动后自动全刷: `setAutoFullRefreshAfterScrolling(bool)`
- 滚动延迟: `Settings.Global["scroll_refresh_delay"]` (ms)

调用链:
```
OnyxRefreshModeController.handeClickEvent(mode)
  → OnyxRefreshModeHelper.setCurrentRefreshMode(mode)
  → EInkHelper.setAppScopeRefreshMode(mode)
  → OECService.setAppScopeRefreshMode(mode, apply=true)
  → TabletEACRefreshImpl.setAppScopeRefreshMode(prev, cur, deviceConfig, mode)
      → curAppConfig.setUpdateMode(curComponent, mode)  // 当前 app 的 per-activity + global
      → deviceConfig.getFallbackRefreshConfig().setUpdateMode(mode)  // ★ 全局默认
      → setAppRefreshModeImpl() → applyAppScopeUpdate() 或 clearAppScopeUpdate()
      → BroadcastHelper.sendRefreshModeChangeBroadcast(mode, turbo)
      → saveDeviceConfig()
```

> ★ 关键: QS Tile 修改的是 `fallbackRefreshConfig`，影响所有未自定义的应用

#### 2. EInk Center 下拉面板（局部/per-app 模式）

```
EInkCenterUI → EinkCenterMainView → 刷新模式按钮组
  → onRefreshButtonClick(refreshModeUI, refreshModeIndex, button)
  → new SelectRefreshModeAction(refreshModeIndex).execute()
```

文件: `res/eink-systemui/.../eink/p004ui/EinkCenterMainView.java`

UI 选项（5 个，由 SysUIConfig.refreshConfig 配置）:
- 按钮列表从 `SysUIConfig.getRefreshConfigByIndex(index)` 加载
- 每个按钮对应一个 `RefreshModeData`（包含 title/mode/turbo）
- 当前选中状态从 `getTheme().refreshConfig(topComponent()).getRefreshModeAlias()` 读取

调用链:
```
SelectRefreshModeAction.create()
  1. isRefreshModeIndexChanged()  // 检查是否真的变了
  2. loadRealRefreshModeIndex()   // RefreshMappingConfig 映射
  3. getTheme().refreshConfig(topComponent())  // ★ per-activity 配置
       .setRefreshModeIndex(index)
       .setRefreshModeAlias(alias)
       .setUpdateMode(mode)
       .setTurbo(turbo)
  4. getTheme().globalRefreshConfig()  // ★ 该 app 的全局默认
       .setRefreshModeIndex(index)
       .setRefreshModeAlias(alias)
       .setUpdateMode(mode)
       .setTurbo(turbo)
  5. onRefreshModeChanged()  // flags = 3
  6. saveAndApply()
       → getTheme().save()  // 保存到 MMKV
       → EInkHelper.applyEACAppTheme(json, flags=3)
       → OECService.applyEACTheme(json, flags)
       → TabletEACRefreshImpl.onApplyConfig()
            → applyAppRefreshModeImpl() → applyUpdateMode()
            → BroadcastHelper.sendRefreshModeChangeBroadcast()
  7. sendEventBus(UpdateThemeUIEvent)  // 更新 UI
```

> ★ 关键: EInk Center 修改的是当前 app 的 `EACAppTheme`，不影响其他应用

### 数据模型层次（局部 vs 全局）

```
EACDeviceConfig (设备级，包含所有 app 配置)
├── appConfigMap: Map<pkgName, EACAppConfig>
│   ├── EACAppConfig ["com.example.app"]
│   │   ├── activityConfigMap: Map<className, EACActivityConfig>
│   │   │   └── EACActivityConfig [".MainActivity"]
│   │   │       └── refreshConfig: EACRefreshConfig  ← “局部” (per-activity)
│   │   └── globalActivityConfig: EACActivityConfig
│   │       └── refreshConfig: EACRefreshConfig  ← “app 默认”
│   └── EACAppConfig ["com.other.app"] ...
└── extraConfig.appDefaultConfig.globalActivityConfig
    └── refreshConfig: EACRefreshConfig  ← “全局默认” (fallback)
```

查找逻辑 (`EACAppConfig.getRefreshConfig(ComponentName)`):
```java
// 1. 先查 per-activity 配置
EACActivityConfig activityConfig = activityConfigMap.get(componentName.getClassName());
if (activityConfig != null) return activityConfig.getRefreshConfig();
// 2. 回退到 app 级全局配置
return globalActivityConfig.getRefreshConfig();
```

系统回退 (`EACDeviceConfig.getFallbackRefreshConfig()`):
```java
// 当 app 未自定义时，使用设备级全局默认
return getExtraConfig().getAppDefaultConfig().getGlobalActivityConfig().getRefreshConfig();
```

### “局部”与“全局”的区别

| | QS Tile 对话框 | EInk Center 面板 |
|---|---|---|
| 作用范围 | 全局 (所有未自定义的 app) | 当前前台 app |
| 修改目标 | `deviceConfig.fallbackRefreshConfig` | `EACAppTheme.appConfig` |
| 可选项 | 4个 (Normal/DU/A2/X) | 5个 (HD/DU/REGAL_PLUS/SMOOTH/NEW_SPEED) |
| 调用 API | `EInkHelper.setAppScopeRefreshMode(int)` | `EInkHelper.applyEACAppTheme(json, flags)` |
| 持久化 | `saveDeviceConfig()` | `EACAppTheme.save()` (MMKV) |
| 重置按钮 | 无 | “重置当前应用刷新模式” → `sendResetRefreshConfigBroadcast` |

### Activity 切换时的模式应用

`TabletEACRefreshImpl.onResume(prevComponent, curComponent, deviceConfig)`:
```
1. appConfig = deviceConfig.getAppConfigByComponentName(curComponent)
2. curRefreshConfig = appConfig.getRefreshConfig(curComponent)  // per-activity 或 app 默认
3. fallbackConfig = deviceConfig.getFallbackRefreshConfig()     // 全局默认
4. applyUpdateMode(curRefreshConfig, fallbackConfig):
     mode 0/3/5 (NORMAL/REGAL/REGAL_PLUS) → clearAppScopeUpdate()  // 系统 hook 代理
     mode 1/2/4 (DU/A2/X)                → applyAppScopeUpdate()  // SF 直接控制
5. applyEpdParameter()  // turbo, gcInterval, debouncer
6. sendRefreshModeChangeBroadcast(mode, turbo)
```

### 关键文件索引

| 文件 | 作用 |
|---|---|
| `eink-systemui/.../qs/tiles/RefreshModeTile.java` | QS 磁贴，点击弹出全局模式对话框 |
| `eink-systemui/.../settings/OnyxRefreshModeController.java` | 全局模式对话框 UI 控制器 |
| `eink-systemui/.../util/OnyxRefreshModeHelper.java` | 封装 EInkHelper 调用 |
| `eink-systemui/.../eink/EInkCenterUI.java` | EInk Center 窗口管理 |
| `eink-systemui/.../eink/p004ui/EinkCenterMainView.java` | per-app 面板主视图 |
| `eink-systemui/.../eink/action/SelectRefreshModeAction.java` | 选择刷新模式的 RxAction |
| `eink-systemui/.../eink/action/BaseThemeAction.java` | saveAndApply 基类 |
| `eink-framework/.../optimization/OECService.java` | 系统服务端 |
| `eink-framework/.../optimization/impl/TabletEACRefreshImpl.java` | 刷新模式实际应用逻辑 |
| `eink-framework/.../optimization/data/v2/EACAppTheme.java` | per-app 主题数据模型 |
| `eink-framework/.../optimization/data/v2/EACAppConfig.java` | per-app 配置 (activityConfigMap + global) |
| `eink-framework/.../optimization/data/v2/EACDeviceConfig.java` | 设备级配置 (fallback) |
| `eink-framework/.../optimization/data/v2/EACRefreshConfig.java` | 刷新配置数据类 |

## QS 磁贴 "EinkWise" → 优化引擎（EInk Center）— 完整上下游

> 下滑通知栏（QS 面板）中的 **eink 磁贴**（label=`EinkWise`）即"优化引擎"入口，
> 点击后弹出的 **EInk Center 面板** 就是上文手势章节的 `EINK_CENTER`（优化引擎）。
> 本节覆盖该磁贴 → 面板 → 服务端应用的全链路（反编译自 `res/eink-systemui` + `res/eink-framework`）。

### 全部入口（4 个）

| 入口 | 调用点 | 参数 | 到达视图 |
|---|---|---|---|
| **QS 磁贴 EinkTile**（下滑通知栏） | `EinkTile.handleClick()` → `showEinkDialog()` | `showEinkCenter(0)` | 主面板 (view 1) |
| 手势 `EINK_CENTER` | `EventHandler.dispatchEvent()` (:564) | `showEinkCenter(0)` | 主面板 (view 1) |
| 导航栏 eink 按钮 | `NavigationBarFragment.java:1060` | `showEinkCenter(0)` | 主面板 (view 1) |
| 广播 `com.onyx.SHOW_EAC_SETTINGS_VIEW_REQUEST` | `EACActionReceiver` → `showEinkCenterEditMainView(args_component)` | `ComponentName` 字符串 | 编辑主题视图 (view 2/7) |

### 磁贴本体 EinkTile

文件: `res/eink-systemui/.../qs/tiles/EinkTile.java`

```java
public class EinkTile extends QSTileImpl<QSTile.BooleanState> {
    public void handleClick() { showEinkDialog(); }      // 点击
    // lambda$showEinkDialog$0:
    //   this.mCommandQueue.showEinkCenter(0);
    //   this.mHost.collapsePanels();                     // 收起通知栏
    public void handleUpdateState(BooleanState state, Object arg) {
        state.label = getTileLabel();                    // R.string.eink = "EinkWise"
        state.icon  = ResourceIcon.get(R.drawable.ic_qs_eink);
        state.state = 1;
        ...
    }
}
```

- tile spec = `"eink"`；`QSTileHost.createTile()` 遍历 `mQsFactories` 创建
- 默认位于 `quick_settings_tiles_default` **第一位**（`strings.xml` 的 default/retail/stock 三套配置均以 `eink,` 开头）
- 点击 → `mUiHandler.post` → `mCommandQueue.showEinkCenter(0)` + `mHost.collapsePanels()`（收起面板）

### CommandQueue 分发

```
showEinkCenter(int i)                         CommandQueue.java:758
  → mHandler.obtainMessage(4390912, i)        （synchronized mLock）
  → handleMessage case 4390912                CommandQueue.java:1372
      → 遍历 mCallbacks 逐个调 showEinkCenter(i) → EInkCenterUI.showEinkCenter(i)
hideEinkCenter() → msg 4456448 → callbacks.hideEinkCenter()
```

### EInkCenterUI（SystemUI 窗口管理器）

文件: `res/eink-systemui/.../eink/EInkCenterUI.java`（`extends SystemUI implements CommandQueue.Callbacks`）

**生命周期** `start()`: `CommandQueue.addCallback(this)` + `EventBus.register(this)` + 取 WindowManager/PowerManager +
注册 `user_rotation` ContentObserver（旋转变化 → `handleHideDialog()` → hideAllViews）。

**showEinkCenter(int i) 参数分发**（先判 `mPowerManager.isInteractive()`，灭屏时忽略全部）：

| 参数 | handler msg | 动作 | 视图 |
|---|---|---|---|
| 0（磁贴/手势/导航栏） | msg 0 | `showView(getViewSafe(1))` | 主面板 |
| 1 | msg 10 | `handleShowRefreshMode()` | view 3（刷新模式对话框，见上文"通知栏刷新模式面板"） |
| 2 | msg 13 | 编辑视图 | view 2/7 |
| 4 | msg 12 | `handleShowColorCustomMode()` | view 4（颜色自定义编辑） |
| 6 | msg 15 | `handleShowScrollButtonSettingsDialog()` | view 6 |

**视图工厂 getViewByType(i)**（结果缓存在 `viewStack: SparseArray<EInkCenterWindow>`）：

| i | 视图类 | 用途 |
|---|---|---|
| 1 | `EinkCenterMainView`（phone 设备用 `EinkCenterMainPhoneView`） | 优化引擎主面板 |
| 2 | `EinkCenterEditMainView` | 主题编辑（支持优化） |
| 3 | —（空） | — |
| 4 | `EinkColorModeEditView` | 颜色模式编辑 |
| 5 | `EinkDpiModeEditView` | DPI 编辑 |
| 6 | `ScrollButtonSettingsDialog` | 滚动按钮设置 |
| 7 | `EinkCenterEditSystemAppView` | 系统 app 主题编辑 |
| 8 | `CommonMessageDialog` | 通用消息框 |
| 9 | `SliderEditEacDialog` | 滑块编辑 |
| 10 | `MagicCodeResultDialog` | Magic Code 结果 |
| 11 | `ApplyAppConfigSuccessDialog` | 应用成功提示 |
| 12 | `UpdateTipsDialog` | 更新提示 |
| 13 | `RefreshModeTipsDialog` | 刷新模式提示 |
| 14 | `EinkListSelectDialog` | 列表选择（冻结超时） |

**显示流程 showView(view, z)**:
```
1. ((StatusBar) Dependency.get(StatusBar.class)).sendEmptyMessage(8007)   // 内部消息（反编译未保留处理细节）
2. z=true 时 hideAllViews()                            // 独占显示：先移除其他视图
3. eInkCenterWindow.preLoadAndCallbackEnd(runnable)
      → 主面板: LoadThemeMainDataAction 异步加载完成后才执行 runnable（见下）
4. showViewImpl(view):
      view.getParent() != null → mWindowManager.removeView(view)
      BroadcastHelper.sendEInkCenterDialogOpenBroadcast(context)
      mWindowManager.addView(view, getLayoutParams())
```

**窗口参数 getLayoutParams()**: 全屏 (-1×-1)、`format=-3`（透明）、`type=2046`、`token=mWindowToken`（静态 Binder）、
title="EinkCenterUI"、`flags=394528`、`privateFlags |= 16`。

**隐藏**: `hideEinkCenter()` / 物理 Back（`BaseEinkView.dispatchKeyEvent` KEYCODE_BACK → `hide()` → msg 1）/
旋转（`user_rotation` observer）/ 配置变化（`onConfigurationChanged` 先 hideAllViews 再按需重挂）
→ `hideAllViews()`（逐个 removeView + `BroadcastHelper.sendEInkCenterDialogCloseBroadcast()`）。

**EventBus 事件**: `ModifyEACConfigEvent`（重命名主题 / 取消重命名，执行 `RenameThemeAction`/`CancelRenameAction`）、
`RequestServiceResultEvent`（`MAGIC_CODE_RESULT` → `ParseMagicCodeResultAction`；`SERVICE_EAC_CONFIG_CHANGED_RESULT` → `CheckAppConfigUpdateAction`；`UPDATE_APP_CONFIG_RESULT` → `ParseUpdateResultAndApplyAction`）、
`ShowRefreshModeTipsDialogEvent`、`RefreshModeTipsResultEvent`（不再提醒 → `ShowRefreshModeTipsNoMoreAction`）、
`ShowFreezeTimeoutSettingsDialogEvent`、`EinkCenterSelectListDialogPositive/NegativeEvent`（冻结超时选择 → `SetAppFreezeTimeoutAction`）、
`RequestUploadEacThemeEvent`（延迟 300ms → `UpdateThemeToServiceAction` 上传主题 → 完成后 hideAllViews）。

### 主面板 EinkCenterMainView

文件: `res/eink-systemui/.../eink/ui/EinkCenterMainView.java`（layout: `layout_eink_center_main`；继承 `BaseEinkView`）

**打开流程**:
```
EInkCenterUI msg0 → showView(getViewSafe(1))
  → EinkCenterMainView.preLoadAndCallbackEnd(runnable)
      → new LoadThemeMainDataAction().build().subscribe(...)
      → 完成后: inflateView() + initViews() + runnable.run()
                + showReaderEinkCenter(EinkCenterBundle.singleton().isSupportRefreshConfig())
```

**数据加载 LoadThemeMainDataAction → BaseLoadThemeDataAction**（`requestConfigData()` 关键字段）:
```java
topComponent = ActivityManagerHelper.getCurrentTopComponent(appContext);  // 当前前台 Activity
pkg          = topComponent.getPackageName();
activeType   = validateThemeType(pkg);                       // themeRange = 1..3
theme        = EACAppThemeManager.getTheme(pkg, activeType);
isSupportOptimization = supportEAC(pkg);
    // 非 Onyx/系统 app 或不在 supportEacSystemApps
    // [org.chromium.chrome, com.android.vending, com.android.browser] 白名单 → false
isSupportRegal / supportMagicCode / supportRefreshConfig / hasTcon / showFullPmAccessSettings ...
validateRefreshMode():  refreshModePkgBlackList = [com.onyx.kreader]（黑名单 → 禁用刷新配置）
    turbo > 0 → 重置 refreshModeIndex（按 SysUIConfig 反查 index）
    否则      → 按 refreshMigrateMap 把旧 updateMode 迁移成新 index
validateColorMode() / validateDPI(): 校验并补齐当前颜色/DPI 配置（custom 项回填到 *_type_3）
```

**initViews() 构建 UI**（数据均来自 `EACViewConfigs` / `SysUIConfig` / `EACAppThemeManager`）：

| 区域 | 数据来源 | 点击动作 |
|---|---|---|
| 主题按钮区 | `EACAppThemeManager.getAppThemes(topPkg)` → `bindEinkThemeButton` | 未选中 → `SelectThemeAction`；已选中 → `showEditThemeView()`（msg13 编辑视图） |
| 刷新模式单选 | `EACViewConfigs.getRefreshRadioViewDatas()`（普通 app 用 `SysUIConfig.refreshConfig`，系统 app 用 `refreshConfigForSystemApp`） | `onRefreshButtonClick` → `SelectRefreshModeAction(str)` |
| 颜色模式单选 | `getTopAppColorModes()` | 未选中 → `SelectColorModeAction`；已选中 → `showEditColorModeView`（msg12） |
| DPI 单选 | `getTopAppDpiConfigs()` | 未选中 → `SelectDpiModeAction`；已选中 → `showEditDpiView`（msg14，仅 `eac_dpi_type_3`） |
| 高对比开关 | `theme.displayConfig(topComponent).getDitherThreshold() != 128` | `toggleHighContrast` → `SetHighContrastEnableAction` |
| 图像平滑开关 | `theme.paintConfig(topComponent).isDitherBitmap()` | `toggleImageSmooth` → `SetImageSmoothEnableAction` |
| 防闪烁滑块 | `theme.refreshConfig(topComponent).getAntiFlicker()` | `showSliderEditDialog(ANTI_ALIASING)` → msg20 → `SliderEditEacDialog` → `SetEACValueAction` |
| 重置当前应用刷新模式 | — | `resetOtherAppConfig` → 广播 `sendResetRefreshConfigBroadcast`（隐藏面板后 300ms） |
| 刷新模式设置 tips | — | `goOtherApplication` → 广播 `sendShowRefreshSettingsUiBroadcast`（隐藏面板后 300ms） |
| Magic Code | — | `StartMagicCodeListAction`（仅 `supportMagicCode` 时显示） |

**showReaderEinkCenter(支持刷新配置?)**: 控制主题区/高对比/图像平滑/DPI/刷新模式/Magic Code 各区域的显隐；
`supportHighContrast()` 决定显示高对比还是图像平滑（按当前刷新模式的 mode/turbo 查 `ResourceDataMaps.supportHighContrast`）。

**事件总线**: `UpdateThemeUIEvent`（重绘全部按钮选中态）、`SlideViewProgressChangeEvent`（滑块拖动 → `SetEACValueAction`，回写文本）、
`UpdateThemeAndApplySuccessEvent`、`HasAppConfigNewUpdateEvent`（同 pkg → 主题按钮 NEW 角标 + `ShowUpdateSystemThemeTipAction`）、
`CommomMessageResultEvent`（"update_theme_config" → `QueryUpdateAppConfigAction`）、`OnyxMessageEvent`。
`onAttachedToWindow` 还执行 `QueryCloudAppConfigChangeAction`（云端配置变更检查）。

### 保存/应用链（所有设置最终汇聚点）

`BaseThemeAction.saveAndApply(t)`（flags 语义: 0=默认, 1=web config, 2=全部 `addAllChangeFlag`, 3=刷新模式 `onRefreshModeChanged`）:
```java
1. getTheme().save();                                                     // MMKV 持久化
2. EInkHelper.applyEACAppTheme(FastJSONUtils.toJson(theme), flags);        // Binder → OECService
3. EinkCenterBundle.singleton().setTheme(theme);                           // 更新单例
```

服务端（eink-framework）:
```
OECService.applyEACTheme(json, flags)                          OECService.java:2032
  → applyEACAppThemes(list, flags)
  → ApplyThemesAction：解析 theme json；仅 isActiveTheme 且 appConfig 非空才生效；
                      validateRefreshMode 按 SysUIConfig 重算 updateMode/turbo
  → applyAppConfigToService(configList, params{args_operation_flag=flags})
  → applyAppConfigToServiceImpl():
      遍历 config → EACConfigJsonUtil.getAppConfig → 覆盖 editableConfig.appConfigMap
      → applyConfigChangeImpl(old, new) → 遍历 eacImplMap 逐个调 onApplyConfig(...)
      → saveDeviceConfig(editableConfig, 5, false, saveToMMKV)
      → sendOECConfigChanged(...)（含 args_operation_flag=flags）+ updateSystemServerCacheDataImpl()
      → saveConfigToLocalImpl()
```

**TabletEACRefreshImpl.onApplyConfig**（刷新模式真正落地）:
```java
EACRefreshConfig refreshConfig  = newConfig.getRefreshConfig(currentTopComponent); // per-activity → app 默认
EACRefreshConfig fallbackConfig = deviceConfig.getFallbackRefreshConfig();          // 全局默认
applyAppRefreshModeImpl(topComponent, refreshConfig, false):
    mode = caculateRefreshConfig(refreshConfig).getMode()
    mode 1/2/4 (DU/A2/X)   → clearSFDebouncer() + applyAppScopeUpdate(pkg, mode)
                             // ViewUpdateHelper.applyAppScopeUpdate(pkg, true, 1, EACUtils.toEpdMode(mode), MAX)
    mode 0/3/5 (NORMAL/REGAL/REGAL_PLUS) → clearAppScopeUpdate()
applyEpdParameter(...)     // turbo / gcInterval / debouncer / antiFlicker
BroadcastHelper.sendRefreshModeChangeBroadcast(mode, turbo)   // 广播刷新模式变化
```

> ★ 作用域: 本面板修改的是**当前 app 的 `EACAppTheme`**（per-activity + app 默认），
> 与 QS Tile `RefreshModeTile` 修改的 `fallbackRefreshConfig`（全局默认）不同——见上文"通知栏刷新模式面板"。

### 关闭与上传

```
隐藏（Back/旋转/其他入口）→ hideAllViews()
  → BroadcastHelper.sendEInkCenterDialogCloseBroadcast()
EACActionReceiver 收到 onyx.action.REQUEST_UPLOAD_EAC_THEME（面板关闭后由系统/桌面发出）
  → EventBus RequestUploadEacThemeEvent → 延迟 300ms → UpdateThemeToServiceAction
      → 上传该 app 全部主题（args_theme_1/2/3，EACThemeUtil.requestUpLoadThemesToService）
      → 完成后 hideAllViews()
```

### 关键文件索引

| 文件 | 作用 |
|---|---|
| `eink-systemui/.../qs/tiles/EinkTile.java` | QS 磁贴（EinkWise），点击 showEinkCenter(0) + 收起面板 |
| `eink-systemui/.../statusbar/CommandQueue.java` | 消息分发（4390912 → showEinkCenter） |
| `eink-systemui/.../eink/EInkCenterUI.java` | 窗口管理器（viewStack、视图工厂、显示/隐藏、EventBus 入口） |
| `eink-systemui/.../eink/ui/EinkCenterMainView.java` | 优化引擎主面板 UI 与交互 |
| `eink-systemui/.../eink/ui/EinkCenterMainPhoneView.java` | phone 变体（标题显隐 + 圆角背景） |
| `eink-systemui/.../eink/ui/BaseEinkView.java` | 视图基类（Back 键隐藏、insets、消息封装） |
| `eink-systemui/.../eink/action/BaseLoadThemeDataAction.java` | 面板数据加载/校验（requestConfigData + validate*） |
| `eink-systemui/.../eink/action/BaseThemeAction.java` | saveAndApply 基类（save → applyEACAppTheme → setTheme） |
| `eink-systemui/.../eink/action/SelectRefreshModeAction.java` | 刷新模式选择（flags=3，SMOOTH 弹提示） |
| `eink-systemui/.../eink/action/SelectThemeAction.java` | 主题切换（备份→激活→loadConfig→apply flags=2） |
| `eink-systemui/.../eink/action/SaveAndApplyConfigAction.java` | 主题保存应用（Magic Code 结果用） |
| `eink-systemui/.../eink/action/UpdateThemeToServiceAction.java` | 面板关闭后上传主题到服务端 |
| `eink-systemui/.../eink/model/EinkCenterBundle.java` | 面板全局单例（当前 app/主题/能力位） |
| `eink-systemui/.../eink/utils/EACViewConfigs.java` | 面板各区域数据源（tab/刷新/颜色/DPI 配置） |
| `eink-systemui/.../recents/receiver/EACActionReceiver.java` | 外部广播入口（编辑视图/上传/服务端结果） |
| `eink-framework/.../optimization/EInkHelper.java` | Binder 封装（applyEACAppTheme） |
| `eink-framework/.../optimization/OECService.java` | 服务端应用配置（applyEACTheme → applyAppConfigToService） |
| `eink-framework/.../optimization/action/ApplyThemesAction.java` | theme json → appConfig 提取/校验 |
| `eink-framework/.../optimization/impl/TabletEACRefreshImpl.java` | 刷新模式落地（appScopeUpdate / epdParameter / 广播） |

## 优化引擎面板各区域深层逻辑（2026-08-26 三 agent 并行探索）

> 本节由 3 个只读 subagent 并行深挖 + 主循环补读验证整理，覆盖
> 主题 / 刷新模式 / 颜色 / DPI / 高对比 / 图像平滑 / 防闪烁 / Magic Code 各区域的深层实现。
> 所有引用均来自 `res/eink-systemui` 与 `res/eink-framework` 反编译源码。

### 0. 公共骨架（先看这个）

**所有设置 action 继承 `BaseThemeAction`**（eink/action/BaseThemeAction.java）：
- `initEditTheme()`（:37-41）：从 `EinkCenterBundle.singleton().getTheme()` **拷贝**一份 `new EACAppTheme(...)` 作为编辑对象（`mTheme`），`mComponentName` = 顶层组件克隆。所有改动都改在拷贝上，`saveAndApply` 后回写单例。
- **保存-应用三段链** `saveAndApply(t)`（:65-97）：
  ```java
  1. getTheme().save();                                      // MMKV 持久化（EACAppTheme.save → themeKey JSON）
  2. EInkHelper.applyEACAppTheme(FastJSONUtils.toJson(theme), this.flags);  // Binder → OECService
  3. EinkCenterBundle.singleton().setTheme(getTheme());      // 回写面板内存主题
  ```
- **flags 语义**（:21-31）：`onRefreshModeChanged()→3`（刷新/动画时长/GC 间隔/防闪烁/滚动延迟类）、`onWebConfigChanged()→1`（网页字体类）、`addAllChangeFlag()→2`（主题整体切换）、其余默认 `0`。flags 随 `applyEACAppTheme(json, flags)` → `OECService.applyEACTheme` → `"args_operation_flag"` → `sendOECConfigChanged` 广播**透传**（OECService.java:2032,2047-2051,588-596）；**systemui/framework 内未见对 flags 的分支消费，仅透传**。

**除 saveAndApply 外，直接调 EInkHelper（不走 flags）的 5 条路径**：
1. `SelectColorModeAction.apply()`：`setColorParameter` / `setGlobalContrast`+`setMonoLevel`（颜色单选即时生效）
2. `ChangeEACColorValue`：`applyEACAppTheme(json)` flags=0
3. `SetEACValueExternalAction`：`applyEACAppTheme(json)` flags=0
4. `ResetColorModeExternalAction`：`applyEACAppTheme(json)` flags=0
5. `SaveAndApplyConfigAction`（Magic Code 落地）：`applyEACAppTheme(json)` flags=0

### 1. 主题（SelectThemeAction / EACAppThemeManager / EACAppTheme）

**切换管线**（SelectThemeAction.create，action/SelectThemeAction.java:28-58）：
```
① copyCurrentTheme()  → 深拷贝当前 Bundle 激活主题（切换前的旧主题）   (:105-107)
② saveCurrentTheme()  → currentTheme.save() 回写 MMKV（保险性回写）     (:123-128)
③ EACAppThemeManager.setActiveThemeType(pkg, themeType)
                       → MMKV int "eac_active_theme_{pkg}"，默认 3     (EACAppThemeManager.java:80-82,76-78)
④ addAllChangeFlag()  → flags = 2                                     (:80-82)
⑤ loadConfig()        → 重新加载目标主题 + validateRefreshMode/ColorMode/DPI
                        （BaseLoadThemeDataAction.requestConfigData :232-262）
⑥ setDataToBundle()   → Bundle.resetVersion() + 写入新主题            (:109-121)
⑦ applyTheme()        → EInkHelper.applyEACAppTheme(json, flags=2)    (:98-103)
⑧ 发 UpdateThemeUIEvent → 主面板刷新
```

**主题类型 1/2/3 语义**（EACAppTheme.java:20-24 + strings）：
| 类型 | 常量 | 显示名 |
|---|---|---|
| 1 | EAC_APP_THEME_1 | "默认推荐"（Recommend） |
| 2 | EAC_APP_THEME_2 | "高刷速览"（Fast Refresh） |
| 3 | EAC_APP_THEME_3 / EAC_APP_THEME_CUSTOM | "个性化"（Customize） |

`themeRange = 1..3`（BaseLoadThemeDataAction.java:49），常量 `EAC_APP_THEME_4=4` 存在但 UI 不用。

**MMKV key 家族**（EACAppTheme.java:17-33,137-157 + EACThemeUtil.java:43-45）：
- 主题数据：`eac_theme_{pkg}@theme_type_{type}`（save/load）
- 激活类型：`eac_active_theme_{pkg}`（int）
- 默认主题：`default_app_theme_{pkg}@theme_type_{type}`
- 全局默认：`default_config_eac_app_theme_{type}`、Onyx 全局：`default_config_eac_onyx_app_theme_{type}`
- 底层均为 `com.onyx.internal.mmkv.MMKV`（BaseMMKV.java:22-24）

**EACThemeFactory 加载链**（loadThemeOrCreate，EACThemeFactory.java:45-80，依次命中即返回）：
```
1. loadThemeFromMMKV（用户保存的主题）
2. loadLowVersionAppConfigThemeOrNull（旧版 appConfig 迁移）
3. loadDefaultThemeFromMMKV（default_app_theme_...）
4. loadGlobalOnyxAppThemeOrNull（Onyx/系统 app 专用全局默认）
5. loadGlobalDefaultThemeFromMMKV
6. createTheme（全新创建）
```

**编辑视图**：
- 入口：主面板点已选中主题 → `showEditThemeView()` → msg13 → `showEditEacView` → `LoadActiveEACDataAction` → `getViewSafe(theme.isSupportOptimization() ? 2 : 7)`（EInkCenterUI.java:239-252）——**普通 app → view2 EinkCenterEditMainView；Onyx/系统 app → view7 EinkCenterEditSystemAppView**。
- EditMainView：顶部（返回/主题名/重命名/重置/更新）+ 底部 Tab（REFRESH/DISPLAY/COLOR_EAC/NOTE_EAC/OTHERS），`showTab` 每次切 Tab **重建该 Tab View**（:245-248）。
- **无集中保存按钮**：Tab 内每个控件操作即时触发 Action → `BaseThemeAction.saveAndApply`（SetEACValueAction :154-237 按 EACDataType 写值并 `onRefreshModeChanged()`→flags=3 / `onWebConfigChanged()`→flags=1，再 `saveAndApply`）。
- 重置：确认框（msg18 `reset_theme_config`）→ `ResetThemeAction` → `loadDefaultTheme` 链 + validate + save + applyEACAppTheme + 发 `ResetThemeEvent`（Tab 监听重绑）。
- 重命名：`StartRenameThemeActivityAction` → 隐式 Intent `onyx.action.RENAME_THEME` → 系统 RenameThemeActivity → 广播 `com.onyx.MODIFY_EAC_CONFIG_ACTION`（args_action=RENAME_THEME / RENAME_THEME_CANCEL）→ `RenameThemeAction`（**只改 alias**，不改 name）→ save + setTheme。

**云端更新链 + NEW 角标**：
```
主面板 onAttachedToWindow → QueryCloudAppConfigChangeAction
  → 广播 com.onyx.EAC_FETCH_FROM_CLOUD（EACThemeUtil.java:107-112）
  ← com.onyx 响应 REQUEST_SERVICE_RESULT_ACTION（SERVICE_EAC_CONFIG_CHANGED_RESULT, args_version）
  → CheckAppConfigUpdateAction: service 版本 vs MMKV "eink_app_config_version_{pkg}" 本地版本
  → service > local → HasAppConfigNewUpdateEvent → 主题 1 按钮显示 NEW 角标 + ShowUpdateSystemThemeTipAction
用户点"更新" → QueryUpdateAppConfigAction → 云端返回 UPDATE_APP_CONFIG_RESULT
  → ParseUpdateResultAndApplyAction: 解析 EACAppConfig → merge 到本地主题 1 → saveToDefaultConfig
      → saveAppVersion（本地=service）→ setActiveThemeType(pkg,1) → applyEACAppTheme → 发 UI 事件
```
关键：**NEW 角标永远挂在主题 1（默认推荐）上**（EinkCenterMainView.java:755,829-830）；每次 setDataToBundle 先 `resetVersion()`（本地/服务端版本归零），切主题后角标清除、重进面板重新查询。

**上传**（UpdateThemeToServiceAction + EACThemeUtil.requestUpLoadThemesToService :135-143）：
面板关闭后由外部广播 `onyx.action.REQUEST_UPLOAD_EAC_THEME` 触发（EACActionReceiver），延迟 300ms 后把该 app 的 3 个主题完整 JSON 以 **`args_theme_1/2/3` + `args_pkg` + `args_theme_index`** 广播给 com.onyx 云服务。

### 2. 刷新模式（SelectRefreshModeAction / 映射体系）

**三套枚举/映射**：
- `RefreshModeUI`（android.onyx.RefreshModeUI.java:7-12）：`HD / DU / REGAL_PLUS / SMOOTH / NEW_SPEED`，`lowCaseName()` = 小写别名。
- `RefreshModeIndex`（utils/RefreshModeIndex.java:6-12）：`NONE / REFRESH_MODE_1..5`。
- `RefreshMappingConfig`（android.onyx.RefreshMappingConfig.java）：per-pkg 别名覆盖表。`aliasToRealRefreshMode(pkg, alias, index)`（:72-81）：查 `appRefreshModeAliasMap[pkg].refreshModeAliasMap[alias]`，命中返回新 index，否则原样返回 index。持久化 `refresh_replaced_map_config`（MMKV），`migrateToMMKV()`（:83-89）在 MMKV 版本低于 JSON 配置（`ConfigLoader.load(...,"refresh_mapping")`）时覆盖。

**SysUIConfig 配置来源**（android.onyx.config.SysUIConfig.java）：
- 单例由 `ConfigLoader.load(SysUIConfig.class, "systemui")` 加载（:56）——即 `res/raw/<model>_systemui.json` 或等价资源（**仓库无 raw 文件，具体 mode/turbo 数值不可验证**）。
- 关键字段：`refreshConfigMap`（index→RefreshModeData）、`refreshConfig`（普通 app 索引列表）、`refreshConfigForSystemApp`、`refreshMigrateMap`/`refreshMigrateMapForSystemApp`（旧 updateMode → 新 index 迁移）、`enableXMode`、`supportAntiFlicker`、`newRefreshMode`。
- `getRefreshConfigByIndex(index)`（:196-198）：查 refreshConfigMap。
- `getRefreshModelIndexByConfig(mode, turbo)`（:200-215）：按 mode+turbo **反查 index**（找不到返回 NONE）。

**SelectRefreshModeAction 管线**（eink/action/SelectRefreshModeAction.java:33-70）：
```
filter  isRefreshModeIndexChanged()      // 当前 refreshModeIndex != 目标 realRefreshModeIndex（:137-139）
        ★ JADX 疑点：filter 在 loadRealRefreshModeIndex() 之前执行（realRefreshModeIndex 尚未赋值，
          比较结果恒真）——疑似反编译乱序，实际运行时行为以"变更即执行"理解
① loadRealRefreshModeIndex()             // RefreshMappingConfig.getRealRefreshMode(pkg, alias, index)（:127-129）
② 双写 per-activity：getTheme().refreshConfig(topComponent())
     .setRefreshModeIndex(real).setRefreshModeAlias(alias).setUpdateMode(mode).setTurbo(turbo)   (:88)
③ 双写 app 全局：getTheme().globalRefreshConfig() 同上（:94）
④ maybeShowRefreshModeTips()             // SMOOTH 且 isShouldRefreshModeTip → ShowRefreshModeTipsDialogEvent（:141-152）
⑤ onRefreshModeChanged() → flags=3
⑥ setChanged(true)
⑦ saveAndApply(action)                   // save → applyEACAppTheme(json,3) → setTheme
⑧ 发 UpdateThemeUIEvent
```

**服务端计算**（EACBaseRefreshImpl.caculateRefreshConfig，optimization/impl/EACBaseRefreshImpl.java:61-73）：
```
RefreshModeIndex.toEnum(config.getRefreshModeIndex()) != NONE 且
SysUIConfig.getRefreshConfigByIndex(index 小写) 命中 → 返回配置表条目（mode/turbo 以配置表为准）
否则 → 直接按 config.getUpdateMode()/getTurbo() 构造 RefreshModeData
```
其余联动（同文件）：`getConfigUpdateMode` 把 mode=3(REGAL) 覆盖为 0(NORMAL)（:134-137，"仅 key/motion 事件抬起时用 regal"）；`calculateScrollingRefreshMode` 仅 mode 2/4（A2/X）在滚动时透传，否则用设备默认（:43-59）。

**与 QS 磁贴 RefreshModeTile 的区别**：本面板改当前 app 的 `EACAppTheme`（per-activity + app 全局）；`RefreshModeTile`（qs/tiles/RefreshModeTile.java）`handleClick` → `sendEvent(1042)` → `OnyxStatusBarImpl.handleShowRefreshModeDialog` → `OnyxRefreshModeController` → `EInkHelper.setAppScopeRefreshMode(mode)` → 改 **fallbackRefreshConfig（设备全局默认）**。两者互不影响对方持久化，但生效优先级：per-activity > app 默认 > 全局 fallback。

### 3. 防闪烁 / 防抖 / GC（antiFlicker / debouncer / gcInterval）

**防闪烁 UI 链**：
```
EinkCenterMainView.preventFlashingArea 点击 → showSliderEditDialog(ANTI_ALIASING, 标题)（:311-321）
  → EventBus ShowSliderEditDialogEvent + msg20（data: args_type=ANTI_ALIASING, args_value=getAntiFlicker()）
  → EInkCenterUI case20 → showSliderEditEacDialog（EInkCenterUI.java:135-137,282-292）
  → SliderEditEacDialog（视图 9）滑块
  → 滑块回调 → SlideViewProgressChangeEvent(fromUser=true)
  → EinkCenterMainView.onBusEvent（:804-813）→ new SetEACValueAction(ANTI_ALIASING, value).execute()
  → SetEACValueAction case17：globalRefreshConfig + refreshConfig(topComponent) 双写 setAntiFlicker
      + onRefreshModeChanged()（flags=3）（SetEACValueAction.java:225-229）
  → saveAndApply
```

**SliderEditEacDialog**（p004ui/views/SliderEditEacDialog.java）：`SlideViewModel` 驱动的通用滑块对话框；`SlideViewModel` 用 `PublishSubject.throttleLatest(1000ms)` 做**1 秒节流**（SlideViewModel.java:44-52），高频拖动只发最后一次。

**服务端落地**：
```
TabletEACRefreshImpl.applyEpdParameter（:321-326 区，四步）:
  applyEpdAntiFlicker(config.getAntiFlicker())   → ViewUpdateHelper.antiFlicker(value)（EACBaseRefreshImpl.java:154-157）
  applyEpdTurbo(config.getTurbo())               → ViewUpdateHelper.setEpdTurbo(turbo)（:109-112）
  applyDebouncerParameter(...)                   → debouncer 配置
  applyGcInterval / 滚动相关
```

**输入事件防抖（debouncer）**（EACBaseRefreshImpl.handleInputEventImpl，:79-99）：
```
MOTION_EVENT / KEY_EVENT（action=1，按下）：
  若 refreshConfig 未启用 → 忽略
  increaseRepaintCount(): 仅 DEBOUNCER_UPDATE_MODE_MAP 含当前 updateMode 时 ViewUpdateHelper.debounceIncRefresh()
  EACUtils.applyDebouncerTransientUpdateMode(pkg, mode)  // 滚动/翻页期间的瞬态更新
DEBOUNCER_UPDATE_MODE_MAP = {3→0, 5→0, 0→0}（Constant.java:265-271，REGAL/REGAL_PLUS/NORMAL 才走防抖）
clearSFDebouncer() → ViewUpdateHelper.debouncer(false,0,0,0,0)
```

**RefreshModeTipsDialog 状态机**：`EinkCenterBundle.isShouldRefreshModeTip`（默认由 `show_refresh_mode_tips_dialog` MMKV bool 决定）——SMOOTH 首选时 `maybeShowRefreshModeTips` 弹一次并置 false；弹窗"不再提醒" → `ShowRefreshModeTipsNoMoreAction` 持久化。

**GC 间隔/滚动延迟**：SetEACValueAction case2（gcInterval）、case16（scrollRefreshDelay）均双写 global+per-activity 并 `onRefreshModeChanged()`（flags=3）；case18/19 是滚动按钮 start/end 百分比（`scrollArgs`）。

### 4. 颜色（SelectColorModeAction / EinkColorModeEditView）

**两套设备判定（易混）**：
- `EACThemeFactory.isColorDevice = ViewUpdateHelper.getColorType() > 0`（surfaceflinger GET_COLOR_TYPE，EACThemeFactory.java:394-396）——决定**预设来源**（`getColorModeSet()` 彩屏 / `getBwScreenModeSet()` 黑白）。
- `ResourceDataMaps.isCfaDevice() = DeviceController.isCfaDevice() = EACConfig.epdColorMode==1`（FW/hardware/DeviceController.java:595-597）——决定 **SelectColorModeAction.apply() 走哪条 EInkHelper 路径**。

**三档**（ResourceDataMaps.colorModeTypeMap :159-165）：`optimal→eac_color_type_1`、`vivid→eac_color_type_2`、`custom→eac_color_type_3`。`EACThemeFactory.fillColorModesToTheme`（:398-416）在 colorTypeConfigs 为空时由 `EACColorConfig{gammas=saturation, monoLevel, brightness}` 生成 `EACDisplayConfig{contrast=gamma, cfaColorSaturation=saturation, monoLevel, cfaColorBrightness=brightness}`。

**★ 档位名称随设备类型变化（"标准/浓墨" vs "均衡/鲜艳"）**（ResourceDataMaps.java:51-64,196-202）：
| 档位键 | 黑白屏文案（color_mode_*） | 彩屏文案（eink_color_mode_*） | 语义（官方教程 eink-home strings.xml:1660-1669） |
|---|---|---|---|
| eac_color_type_1 | **标准**（Standard） | 均衡（Optimal） | "Restore True Colors" 还原真实色彩（默认/出厂校准参数）/ "Soft and comfortable tones" |
| eac_color_type_2 | **浓墨**（Deep） | 鲜艳（Vivid） | "High-contrast dark color style" 高对比深色风格 / "Bright and vivid colors" |
| eac_color_type_3 | 自定义（Custom） | 自定义（Custom） | 手动调 5 个滑块 |

即：**"标准"= 用设备出厂默认的 gamma/对比度参数；"浓墨"= 对比度更高的深色预设**（黑更实、墨色更浓）。同一档位在彩屏上改叫"均衡/鲜艳"（因为彩屏多了饱和度/亮度两个维度）。

**预设参数从哪来**（EACColorConfig 4 字段：`gamma / monoLevel / saturation / brightness`，EACColorConfig.java:4-8）：
```
EACConfig.colorModeSet（彩屏）/ bwScreenModeSet（黑白屏）
  ← ConfigLoader.load(EACConfig.class, "eac_config")          EACConfig.java:52
     = framework-res raw 资源 <MODEL>_eac_config.json（无型号则回退 eac_config.json）  ConfigLoader.java:25-65
  ← 每档一个 EACColorConfig{gamma=对比度, monoLevel, saturation, brightness}
设备"当前色彩模式"读取器：EACConfig.getGamma()/getSaturation()/getBrightness()（:139-164）
  按 getColorMode() 从对应预设取——即设备层与 app 主题层用同一套档位表
```
可验证的默认常量（Constant.java:262-263,104-108；EACConfig.java:35-40）：
`GLOBAL_CONTRAST_DEFAULT=30`、`CFA_COLOR_SATURATION_DEFAULT=isCfaDevice?65:0`、`MONO_LEVEL_DEFAULT=10`（范围 0-175，OFFSET=80）、`antiFlickerDefault=10`；
`EACConstantDeviceConfig` 另供 CONTRAST_MIN/MAX/STEP（Constant.java:116-119）。**标准/浓墨各自的具体 gamma/monoLevel 数值在 `<model>_eac_config.json` 内，仓库无此文件，不可验证**。

**选择原理（为什么点一下"浓墨"整屏变深）**：`SelectColorModeAction` 把该档的 4 个显示参数整体覆盖到当前 app 的 `displayConfig`，并**直接调 EInkHelper 即时生效**（不走 flags）——黑白屏 `setGlobalContrast(contrast)+setMonoLevel(monoLevel)`，彩屏 `setColorParameter(...)`（:108-122）。随后持久化到主题 MMKV，Activity 恢复时由 `TabletEACDisplayImpl` 重新应用。

**SelectColorModeAction 管线**（action/SelectColorModeAction.java:24-46）：
```
① setCurrentColorType(key)
② EACThemeUtil.syncColorConfig(appConfig, 选中配置)   // 把 contrast/monoLevel/cfaColorBrightness/cfaColorSaturation 整体覆盖到 globalActivityConfig.displayConfig（EACThemeUtil.java:114-123）
③ setChanged(true)
④ saveAndApplyMode(): save() + apply()（直接 EInkHelper，不走 flags）+ setTheme
⑤ UpdateThemeUIEvent
apply()（:108-122）:
  彩色屏 → EInkHelper.setColorParameter(darkContrast, saturation, brightness)
  黑白屏 → EInkHelper.setGlobalContrast(contrast); EInkHelper.setMonoLevel(monoLevel)
```

**编辑视图 EinkColorModeEditView**（view4，msg12）：5 个滑块（DARK_CONTRAST / MONO_LEVEL / CFA_COLOR_CONTRAST / CFA_SATURATION / CFA_BRIGHTNESS），黑白与彩屏滑块组互斥显示（:135-138）；滑块 → `SetEACValueAction`（case 11-15 写 displayConfig global+per-activity，:203-219）；重置 → 确认框（reset_color_mode）→ `ResetColorModeAction`（用 `loadDefaultTheme` + `EACThemeFactory.getColorModeSet()["eac_color_type_3"]` 回填 4 字段，:99-142）。

**其他颜色路径**：
- `ChangeEACColorValue`（步进 ±5：contrast 0-100、monoLevel 0-175、brightness 0-5、saturation 0-100；:129-153）：**强制 currentColorType=eac_color_type_3** 后写值，`applyEACAppTheme` flags=0 + `UpdateThemeValueEvent`。直接调用者未在反编译源码中找到（可能为外部/快捷设置遗留）。
- `SetEACValueExternalAction`（OnyxCFAColorController:313 / OnyxGrayColorController:233 的滑块回调）：**实时重载** `EACAppThemeManager.getTheme(pkg, activeType)` 后强制 custom 写 displayConfig → save + applyEACAppTheme + UpdateThemeValueEvent；`ResetColorModeExternalAction` 同族。

**EACColorConfig 字段映射**：`gamma→contrast`、`saturation→cfaColorSaturation`、`brightness→cfaColorBrightness`、`monoLevel→monoLevel`（EACThemeFactory.java:408 区）。EACDisplayConfig 默认：contrast=EACConfig.getContrastDefault()、monoLevel=10、ditherThreshold=EACConfig.getDitherThreshold()、enhance=true（EACDisplayConfig.java:7-14）。

### 5. DPI（SelectDpiModeAction / EinkDpiModeEditView）

- **三档**（EACThemeFactory.createDummyDpiConfig :430-436 + EACAppTheme.java:31-33,53）：`eac_dpi_type_1`（关闭，dummy 0）、`eac_dpi_type_2`（350）、`eac_dpi_type_3`（自定义，dummy=当前 dpi）；真实值来自 `EACConfig.singleton().getDpiModeMap()`；默认 currentDpiType=eac_dpi_type_2。
- `EacCommonUtils.getDpiByKey(theme, key)`（:169-175）：从 `theme.getDipTypeConfigs()` 取，缺失从 dpiModeMap 回填并缓存。
- **SelectDpiModeAction 管线**：`setCurrentDpiType(mode)` → `appConfig.dpiConfig.setDpi(getDpiByKey(...)).setEnable(true)` → saveAndApply（flags=0）→ UpdateThemeUIEvent。
- **编辑视图**：已选中 custom → msg14 → `EinkDpiModeEditView`（view5），DPI 滑块 `SlideViewModel.debounceProgress()` 1s 节流，初始值 `appConfig.dpiConfig.getDpi()`；滑块 → `SetEACValueAction(DPI, value)`（case10：setDpi + dipTypeConfigs.put(currentDpiType, value)）；重置 → `ResetDpiAction`（loadDefaultTheme 默认 DPI → setDpi → saveAndApply）。
- **应用层**：DPI 走 `applyEACAppTheme(json)`（无独立 setApplicationDPI Binder）；`EInkHelper.getApplicationDPI()`（:248-263）是 **App 侧读取** API（isEnable=false 时返回 -1），方向相反勿混。

### 6. 高对比（SetHighContrastEnableAction）

```java
// action/SetHighContrastEnableAction.java —— 核心仅一行
getTheme().displayConfig(topComponent()).setDitherThreshold(this.enable ? 180 : 128);
// 然后 setChanged(true) + saveAndApply(flags=0)
```
- 状态判定：`isHighContrastEnable() = displayConfig(topComponent()).getDitherThreshold() != 128`（EinkCenterMainView.java:784-786）。
- 底层：`ViewUpdateHelper.setDitherThreshold(int)`（FW/ViewUpdateHelper.java:1461）——**threshold < 128 直接 return 忽略**，故"关闭"实际写 128 而非 0。
- **与刷新模式互斥**：`supportHighContrast()`（EinkCenterMainView.java:402-408）→ `ResourceDataMaps.configToRefreshKey(mode, turbo)` → `unSupportHighContrastOrPreventFlashingMode = {0,3,5}`（ResourceDataMaps.java:23-29,168-170）——当前刷新模式为 NORMAL/REGAL/REGAL_PLUS 时**不支持高对比**，UI 自动换成"图像平滑"开关（updateOtherViewEnabled :377-391）。

### 7. 图像平滑（SetImageSmoothEnableAction）

```java
// action/SetImageSmoothEnableAction.java
getTheme().paintConfig(topComponent()).setDitherBitmap(enable);  // + setChanged + saveAndApply(flags=0)
```
- 服务端落地 `TabletEACRefreshImpl.applyDither`（:135-144）：
  ```java
  boolean enable = activityConfig.getPaintConfig().isDitherBitmap();
  int mode = appConfig.getRefreshConfig(topComponent()).getUpdateMode();
  if (!DEBOUNCER_UPDATE_MODE_MAP.containsKey(mode) || !appConfig.isEnable()) enable = false;  // {3,5,0} 才真启用
  ViewUpdateHelper.enableDither(enable);   // surfaceflinger ENABLE_DITHER 事务（FW/ViewUpdateHelper.java:1420-1424）
  ```
  ★ 与直觉相反：**只有 NORMAL/REGAL/REGAL_PLUS（防抖模式集合）下图像平滑才真正生效**，DU/A2/X 下被强制关闭。

### 8. Magic Code

**完整链**：
```
EinkCenterMainView.inkMagicArea（仅 supportMagicCode 时显示）
  → StartMagicCodeListAction → EACThemeUtil.startMagicCodeListActivity
      → Intent "onyx.action.MAGIC_CODE_SETTING"（activity 在 com.onyx 包，本仓库无源码——输入/云端请求逻辑不可验证）
  ← 结果经广播 REQUEST_SERVICE_RESULT_ACTION（args_action=MAGIC_CODE_RESULT）→ EACActionReceiver → RequestServiceResultEvent
  → EInkCenterUI.onBusEvent（:566-590）→ ParseMagicCodeResultAction
  → MagicCodeResultDialog（view10，msg23）setData + showView
MagicCodeResultDialog positive（:133-147）:
  → 发广播 "com.onyx.EAC_CONFIG_APPLIED_ACTION"（args_pkg + code，:180-185）
  → theme.setThemeType(3)（强制落"个性化"档）
  → new SaveAndApplyConfigAction(theme).execute()
      filter(appTheme!=null)
      map: EACAppThemeManager.getTheme(pkg, themeType)（从 MMKV 重读）
      replaceThemeConfig: appTheme.getAppConfig() 非空 → 覆盖当前主题（SaveAndApplyConfigAction.java:106-111）
      setActiveThemeType(pkg, 3)
      validateColorMode / validateDpiMode（EacCommonUtils.java:213-278）
      setChanged → save → EInkHelper.applyEACAppTheme(json)（flags=0）
      updateThemeToBundle（topPkg 匹配才回写 EinkCenterBundle）
      发 UpdateThemeUIEvent + UpdateThemeAndApplySuccessEvent
  → 成功 → restartApp(pkg)（AlarmManager setExactAndAllowWhileIdle 拉起，:325-340）
      → 监听 "onyx.action.top.component.change" 在 app 重启后显示"success"（:200-235）
```
- `ParseMagicCodeResultAction`：Bundle 取 `args_pkg / theme_type / args_data(JSON) / tags`；`parseThemeJson` 反序列化 EACAppTheme 后 setPkg+setThemeType（:105-117）；`MagicCodeResultData{appName, tags, theme, themeNames}`（data/MagicCodeResultData.java:8-12）。
- **"应用后替换当前主题"语义**：新主题 JSON 覆盖 appConfig → 存 MMKV → active type=3 → 校验颜色/DPI → 应用到 OECService → 同步回 Bundle → 广播刷新 → 重启 app 生效。

### 9. 其他开关 action 一览（均继承 BaseThemeAction：改字段 + setChanged + saveAndApply，flags=0 除非标注）

| Action | 修改的配置字段 | 备注 |
|---|---|---|
| SetTextBoldEnableAction | paintConfig(top).setTextBold + globalPaintConfig | 字体加粗 |
| SetWebFontBoldEnableAction | appConfig.obtainCssConfig().setFontBold | **flags=1**（Web 类） |
| SetAntiAliasingEnableAction | paintConfig(top).setAntiAlisingType(1/0) + global | 抗锯齿 |
| SetHandwritingEnableAction | globalNoteConfig.setEnable + noteConfig(top) | 手写 |
| SetEnhanceDisplayEnableAction | displayConfig(top).setEnhance + globalDisplayConfig | 增强显示 |
| SetAppForceRotationEnableAction | appConfig.getRotationConfig().setEnable | 强制横竖屏 |
| SetSplashScreenEnableAction | appConfig.getExtraConfig().setAllowSplashScreen | 移除启动屏 |
| SetPageFuncAction | appConfig.getKeyboardConfig().setPageKeyMode(func) | 侧边键功能（翻页/音量/滚动） |
| UseGCForNewSurfaceEnableAction | globalRefreshConfig.setUseGCForNewSurface + refreshConfig(top) | 新 surface 用 GC |
| SetEACStrokeWidthAction | globalNoteConfig.globalStrokeStyle.setStrokeWidth + noteConfig(top) | 笔宽（float） |
| SetAppFreezeTimeoutAction | appConfig.getExtraConfig().setFullPMAccessTimeout(long) | 冻结超时（EinkListSelectDialog 选择） |

### 10. 反编译盲区汇总（勿当作已证实）

1. `res/raw/<model>_systemui.json` 不在仓库 → 5 个刷新模式的具体 mode/turbo 数值、refreshMigrateMap 内容不可验证。
2. `TabletEACRefreshImpl.applyAppRefreshModeImpl`（:226-268）JADX 还原失败（UnsupportedOperationException），仅寄存器伪码；其 `clearSFDebouncer + applyAppScopeUpdate / clearAppScopeUpdate` 行为由同一文件 :279-293 佐证。
3. `SelectRefreshModeAction` 的 filter（isRefreshModeIndexChanged）先于 `loadRealRefreshModeIndex()` 执行，比较恒真——疑似 JADX 乱序，实际语义按"变更即执行"理解。
4. Magic Code 输入 activity（onyx.action.MAGIC_CODE_SETTING）、云端 KCB_EAC_FETCH_FROM_CLOUD 接收方、RenameThemeActivity 均在 com.onyx 包，本仓库无源码。
5. flags（args_operation_flag）在 OECService 端仅透传广播，无本地分支消费代码；eink-home SDK 端 EACAppConfigChangeAction 反编译不完整。
6. `EACThemeFactory.isColorDevice`（getColorType>0）与 `ResourceDataMaps.isCfaDevice`（epdColorMode==1）是两套独立判定，设备上是否恒一致未验证。
7. `EinkCenterBundle.isSupportRefreshConfig / hasTcon` 与 `showReaderEinkCenter` 显示门控的完整条件未逐条核对；`OnyxRefreshModeDialog` 本体源码未定位。
8. `EACConstantDeviceConfig` 设备常量（含 antiFlicker 默认 10）的填充来源（本地/云端）未深挖。

## 对 E-Ink-Launcher 的实际意义

Launcher 作为常驻前台应用，系统 OECService 会自动管理其刷新模式。
但 Launcher 可以主动：
1. 在列表滚动时用 `applyTransientUpdate` 加速
2. 在数据变化后调 `repaintEverything()` 清残影
3. 通过 `EInkHelper.setAppScopeRefreshMode()` 切换自身全局模式
4. 监听 `REFRESH_MODE_CHANGED` 广播感知用户在系统设置中切换了模式

 注意: `ViewUpdateHelper` 和 `EInkHelper` 是 Onyx 私有 framework API，
> 编译时需要 `provided`/`compileOnly` 引入 framework jar，运行时仅 Onyx 设备可用。
> `EpdController` 来自 Onyx SDK (`com.onyx.android.sdk`)。

---

# 灰阶控制系统 — 实现参考

## 概述

E-Ink 灰阶由**硬件波形**和**软件参数**两层共同决定：
- 硬件层：UpdateMode 选择的 EPD 波形决定物理灰阶级数上限
- 软件层：monoLevel / contrast / dither 等参数在 SurfaceFlinger 中做后处理映射

## 硬件层：波形模式 = 物理灰阶级数

| 波形模式 | 灰阶级数 | 说明 |
|---------|---------|------|
| DU / A2 (ANIMATION) | **2 级** | 纯黑白，粒子全推到底/顶 |
| GC4 / DU4 | **4 级** | |
| GU / GC / GCC / DEEP_GC / REGAL / REGAL_PLUS | **16 级** | 全灰阶 |

物理原理：E-Ink 微胶囊中黑/白粒子可在中间位置停留（由电压精确控制）。
16 级灰阶 = 粒子有 16 种停留位置；DU 用极端电压 → 只有全黑/全白。

## 软件层：ViewUpdateHelper 灰阶控制命令

来源: `res/eink-framework/.../android/onyx/ViewUpdateHelper.java`

| Binder 命令 | 常量值 | 方法 | 作用 |
|------------|--------|------|------|
| `SET_GRAYSCALE_MODE` | 16711778 | `setGrayscaleMode(mode)` | 灰阶模式（0=彩色, 1=纯黑白） |
| `APPLY_MONO_LEVEL` | 16711779 | `applyMonoLevel(level)` | 单色化阈值（灰→黑分界线） |
| `APPLY_GAMMA_CORRECTION` | 16711695 | `applyGammaCorrection(apply, value)` | 暗部伽马校正/对比度 |
| `SET_DITHER_THRESHOLD` | 1048704 | `setDitherThreshold(threshold)` | 抖动阈值 |
| `ENABLE_DITHER` | 1048692 | `enableDither(bool)` | 图像平滑（空间抖动） |
| `ENABLE_BW_MODE` | 1048696 | `setBWMode(mode)` | 纯黑白模式（仅 CFA 彩色屏） |
| `ENABLE_ENHANCE` | 1048705 | `enableEnhance(bool)` | 边缘增强 |
| `SET_ENHANCE_STRATEGY` | 1048697 | `setEnhanceStrategy(delta, threshold, disable)` | 增强策略 |
| `APPLY_DITHER_FILTER_TOLERANCE` | 1048720 | — | 抖动过滤容差 |

### 关键常量

来源: `res/eink-framework/.../android/onyx/optimization/Constant.java`

```java
MONO_LEVEL_DEFAULT = 10;        // 默认单色化级别
MONO_LEVEL_MIN_VALUE = 0;
MONO_LEVEL_MAX_VALUE = 175;     // UI 滑块最大值
MONO_LEVEL_MAX = 255;           // 硬件最大值
MONO_LEVEL_OFFSET = 80;         // offsetMonoLevel(): level>0 时 +80 发给 SF

CONTRAST_DEFAULT = 30;          // 默认对比度 (伽马)
CONTRAST_MIN_VALUE = 0;
CONTRAST_MAX_VALUE = 100;

DITHER_NORMAL = 128;            // 普通抖动
DITHER_HIGH_CONTRAST = 180;     // 高对比度抖动
DITHER_THRESHOLD_DEFAULT = 255; // 关闭抖动（framework 默认）

BW_MODE_DEFAULT = 0;            // 黑白模式默认关闭
CFA_GRAY_SCALE_MODE_COLOR = 0;  // 彩色屏: 彩色模式
CFA_GRAY_SCALE_MODE_BW_ONLY = 1;// 彩色屏: 纯黑白模式
```

### offsetMonoLevel 逻辑

```java
// ViewUpdateHelper.offsetMonoLevel()
public static int offsetMonoLevel(int inputLevel) {
    if (inputLevel > 0) return inputLevel + 80;  // 加偏移后发给 SurfaceFlinger
    return 0;  // 0 = 不做单色化处理
}
```

### Dither 相关标志位（UpdateMode 组合用）

```java
EINK_DITHER_MODE_DITHER   = 256;   // 启用抖动
EINK_DITHER_MODE_NODITHER = 0;     // 不抖动
EINK_DITHER_COLOR_Y1      = 2048;  // 1-bit 抖动 (2级 = 黑白)
EINK_DITHER_COLOR_Y4      = 0;     // 4-bit 抖动 (16级)
EINK_DITHER_X             = 16777216; // X 模式抖动
```

## 显示配置数据模型

来源: `res/eink-home/sources/com/onyx/android/sdk/eac/data/p056v2/EACDisplayConfig.java`

```java
public final class EACDisplayConfig {
    int contrast;           // 对比度 (伽马校正) [0-100, 默认30]
    int monoLevel;          // 单色化级别 [0-175, 默认10]
    int cfaColorSaturation; // 彩色饱和度 (CFA设备) [0-100]
    int cfaColorBrightness; // 彩色亮度 (CFA设备) [0-5]
    int ditherThreshold;    // 抖动阈值 [128/180/255]
    int bwMode;             // 黑白模式 [0/1, 仅CFA]
    boolean enhance;        // 边缘增强 [默认true]
}
```

## 应用逻辑（EACBaseDisplayImpl）

来源: `res/eink-framework/.../optimization/impl/EACBaseDisplayImpl.java`

```java
public void applyDisplayConfig(EACDisplayConfig config) {
    applyGammaCorrection(config.getContrast());       // 所有设备
    if (isCfaDevice) {                                // 彩色屏 (Kaleido)
        applyBrightness(config.getCfaColorBrightness());
        applySaturationMin(config.getCfaColorSaturationMin());
        applySaturation(config.getCfaColorSaturation());
        applyBwMode(config.getBwMode());              // ★ 纯黑白开关
    } else {                                          // 黑白屏
        applyMonoLevel(config.getMonoLevel());        // ★ 灰阶阈值
    }
    applyDitherThreshold(config.getDitherThreshold()); // 所有设备
    applyEnhance(config.getEnhance());                 // 所有设备
}
```

## UI 入口

### 1. EInk Center "显示控制" 面板

来源: `res/eink-systemui/.../settings/OnyxGrayColorController.java`

- **深色对比度** 滑块 (0~100) → `EInkHelper.setGlobalContrast(value)`
- **浅色对比度/monoLevel** 滑块 (0~175) → `EInkHelper.setMonoLevel(value)`
- 通过 `SetEACValueAction` 保存到 per-app EACDisplayConfig

### 2. QS Tile "黑白模式"

来源: `res/eink-systemui/.../qs/tiles/BWModeTile.java`

```java
// 仅 CFA 彩色屏设备可用
Settings.Global.putInt(contentResolver, "view_update_bw_mode", on ? 1 : 0);
ViewUpdateHelper.enableBWMode(on);
```

### 3. EInk Center "图像平滑"

来源: `EinkCenterMainView.java`

```java
// 抖动开关 (dither bitmap)
boolean isDitherBitmap = getTheme().paintConfig(topComponent()).isDitherBitmap();
// 切换后通过 applyEACAppTheme 应用
ViewUpdateHelper.enableDither(enable);
```

### 4. 颜色模式选择

来源: `res/eink-systemui/.../eink/action/SelectColorModeAction.java`

```java
private void apply() {
    if (ResourceDataMaps.isCfaDevice()) {
        // 彩色屏: 对比度 + 饱和度 + 亮度
        EInkHelper.setColorParameter(darkContrast, saturation, brightness);
    } else {
        // 黑白屏: 对比度 + monoLevel
        EInkHelper.setGlobalContrast(darkContrast);
        EInkHelper.setMonoLevel(lightContrast);
    }
}
```

## 纯黑白实现的 3 种方式

| 方式 | 适用设备 | 机制 | 效果 |
|------|---------|------|------|
| DU/A2 波形 | 所有 | 硬件 2 级波形 | 物理纯黑白，速度最快 |
| monoLevel 拉高 | 黑白屏 | 软件阈值二值化 | 16级波形下强制归为黑/白 |
| bwMode = 1 | 彩色屏 (CFA) | 丢弃灰阶/色彩 | `CFA_GRAY_SCALE_MODE_BW_ONLY` |

## 灰阶 vs 纯黑白的权衡

| | 灰阶 (REGAL/GC) | 纯黑白 (DU/A2) |
|---|---|---|
| 灰度级数 | 16 级 | 2 级 |
| 刷新速度 | 慢 (~500ms) | 快 (~100-200ms) |
| 残影 | 少 (REGAL) / 需定期全刷 (GC) | 有，需偶尔 GC 清除 |
| 适用场景 | 阅读、图片 | 列表滚动、快速翻页 |
| Dither 效果 | 平滑过渡 | 无意义（已是二值） |

## 关键文件索引

| 文件 | 作用 |
|---|---|
| `eink-framework/.../android/onyx/ViewUpdateHelper.java` | 所有灰阶 Binder 命令 |
| `eink-framework/.../optimization/Constant.java` | 参数范围/默认值常量 |
| `eink-framework/.../optimization/impl/EACBaseDisplayImpl.java` | 显示配置应用逻辑 |
| `eink-framework/.../optimization/impl/TabletEACDisplayImpl.java` | setMonoLevel 等具体实现 |
| `eink-framework/.../optimization/EInkHelper.java` | 对外 API 封装 |
| `eink-home/.../eac/data/p056v2/EACDisplayConfig.java` | 显示配置数据模型 |
| `eink-home/.../eac/data/EACConstant.java` | SDK 层常量 |
| `eink-systemui/.../settings/OnyxGrayColorController.java` | 灰阶 UI 控制器 |
| `eink-systemui/.../qs/tiles/BWModeTile.java` | 黑白模式 QS 磁贴 |
| `eink-systemui/.../eink/action/SelectColorModeAction.java` | 颜色模式切换 Action |
| `eink-systemui/.../eink/configs/EACConstantDeviceConfig.java` | 设备级默认配置 |

---

# 内置设置系统 — 实现参考

## 概述

Onyx 采用"前端替换 + 入口劫持"策略：
- Launcher (eink-home) 内置完整设置 UI（130+ Fragment/Activity）
- 原生 `com.android.settings` **未被删除或禁用**，但被从桌面隐藏
- 深层功能（蓝牙配对、位置等）仍跳转原生设置

## 实现机制

### 1. Launcher 内置设置系统

包结构: `com.onyx.common.setting` + `com.onyx.android.libsetting`

入口 Activity:
```xml
<!-- AndroidManifest.xml -->
<activity android:name="com.onyx.common.setting.p091ui.SettingsActivity"
    android:launchMode="singleInstance">
    <intent-filter>
        <action android:name="com.onyx.action.SETTING"/>
        <action android:name="com.setting.action.CHILD_APP_MANAGEMENT"/>
        <category android:name="android.intent.category.DEFAULT"/>
    </intent-filter>
</activity>
```

设置列表由 **JSON 原始资源** 驱动:
```java
// SettingConfig.java
RawResourceUtil.objectFromRawResource(context,
    context.getResources().getIdentifier(str.toLowerCase(), "raw", context.getPackageName()));
```

### 2. Intent 拦截（高优先级）

```xml
<!-- 抢占标准 Android 设置 Intent -->
<intent-filter android:priority="10">
    <action android:name="android.settings.WIRELESS_SETTINGS"/>
    <category android:name="android.intent.category.DEFAULT"/>
</intent-filter>
```

当任何应用发送 `android.settings.WIRELESS_SETTINGS` 时，Launcher 的 WiFi 页面优先响应。

### 3. 应用列表过滤（原生设置"消失"的原因）

```java
// ApplicationUtil.java
PREINSTALL_FILTER_APPS_DIR = Device.currentDevice().getSystemConfigPrefix() + "preinstall_filter_apps";

// LoadAppsOfUserRequest.filterApp():
if (CollectionUtils.safelyContains(companion.sharedInstance().getAppsFilter(), packageInfo.packageName))
    return true; // 从桌面过滤掉
```

`com.android.settings` 被列入设备配置的 `appsFilter` → 桌面不显示图标。

### 4. 自定义 Action 路由

SystemUI 和其他系统组件用 Onyx 自定义 action 打开设置:
```
onyx.settings.action.wifi          → WifiSettingActivity
onyx.settings.action.language      → SettingContainerActivity
onyx.settings.action.datetime      → SettingContainerActivity
onyx.settings.action.power         → SettingContainerActivity
onyx.settings.action.network       → SettingContainerActivity
onyx.settings.action.firmware      → SettingFirmwareUpdateContainerActivity
onyx.settings.action.bluetooth     → BluetoothSettingsActivity
onyx.settings.action.app.management → AppManagementActivity
com.onyx.action.SETTING            → SettingsActivity (主入口)
```

## 内置设置分类

来源: `com.onyx.android.libsetting.data.SettingCategory`

| 分类 ID | 名称 | 对应 Activity |
|---------|------|---------------|
| 0 | NETWORK | NetworkSettingActivity |
| 1 | USER_SETTING | UserSettingActivity |
| 3 | STORAGE | StorageSettingActivity (或原生) |
| 4 | LANGUAGE_AND_INPUT | LanguageInputSettingActivity |
| 5 | DATE_TIME_SETTING | DateTimeSettingActivity |
| 6 | APPLICATION_MANAGEMENT | ApplicationSettingActivity |
| 7 | POWER | PowerSettingActivity |
| 8 | SECURITY | SecuritySettingActivity |
| 11 | WIFI | WifiSettingActivity |
| 12 | BLUETOOTH | → 原生 `android.settings.BLUETOOTH_SETTINGS` |
| 13 | FIRMWARE_UPDATE | FirmwareOTAActivity |
| 15 | DEVICE_INFO | DeviceInfoActivity |
| 16 | CALIBRATION | → `com.onyx.tscalibration` |

## 与原生设置的关系

### 仍跳转原生设置的功能

```java
// 蓝牙详细配对
new Intent("android.settings.BLUETOOTH_SETTINGS")
// 位置信息
new Intent("android.settings.LOCATION_SOURCE_SETTINGS")
// 拼写检查
intent.setClassName("com.android.settings", "com.android.settings.Settings$SpellCheckersSettingsActivity")
// TTS 设置
"com.android.settings.TextToSpeechSettings"
// 字幕属性
"com.android.settings.CaptionPropertiesFragment"
// 时区选择
"com.android.settings.ZonePicker"
// 账号同步
new Intent("android.settings.SYNC_SETTINGS")
// 电池详情
new Intent("android.intent.action.POWER_USAGE_SUMMARY")
```

### 功能重叠 vs 独有

| 重叠（原生设置也有） | Launcher 独有（原生没有） |
|---------------------|-------------------------|
| WiFi / 蓝牙 / 网络 | 刷新模式 / EAC 配置 |
| 存储 | 翻页器按键映射 |
| 语言与输入法 | 手势导航设置 |
| 日期时间 | DPI / 系统字体 |
| 电池 / 电源 | 屏保 / 关机画面 |
| 应用管理 | 侧键自定义 |
| 安全 / 密码 | 状态栏设置 |
| 辅助功能 | 应用冻结管理 |
| 账号同步 | Onyx 账号 / 云同步 |
| OTA 升级 | 阅读统计 / 书城设置 |

## 权限

Launcher 持有系统级权限:
```xml
<uses-permission android:name="android.permission.WRITE_SETTINGS"/>
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"/>
<uses-permission android:name="android.permission.NETWORK_SETTINGS"/>
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
```

## 关键文件索引

| 文件 | 作用 |
|---|---|
| `eink-home/resources/AndroidManifest.xml` | Intent Filter 声明、权限 |
| `eink-home/.../common/setting/p091ui/SettingsActivity.java` | 设置主入口 |
| `eink-home/.../common/setting/p091ui/SettingsFragment.java` | 设置主列表 (1409行) |
| `eink-home/.../common/setting/p091ui/SettingContainerActivity.java` | 子设置容器 |
| `eink-home/.../android/libsetting/SettingConfig.java` | JSON 配置加载 (925行) |
| `eink-home/.../android/libsetting/data/SettingCategory.java` | 设置分类定义 |
| `eink-home/.../common/common/model/DeviceConfig.java` | appsFilter 等设备配置 |
| `eink-home/.../android/sdk/utils/ApplicationUtil.java` | 应用过滤逻辑 |
| `eink-home/.../android/sdk/utils/BaseConstant.java` | `ANDROID_SETTING_PACKAGE_NAME = "com.android.settings"` |

## 对 E-Ink-Launcher 的意义

不需要重建完整设置系统。只需关注:
1. **E-Ink 专属功能** — 刷新模式切换、灰阶控制（通过 EInkHelper/ViewUpdateHelper）
2. **常规设置** — 让用户通过原生设置访问，或简单跳转 `android.settings.*` Intent
3. **如需隐藏原生设置** — 可通过 `PackageManager.setApplicationEnabledSetting()` 或 launcher 过滤

---

# 刷新模式机制深度分析 — 三个并行 Agent 调研结论（2026-08-24）

> 由 3 个并行 subagent 对 res/eink-framework + res/eink-home 反编译源码的调研汇总。
> 覆盖：单次点击慢/连续点击快机制、全局模式（fallback）操作链、SDK 层反射、刷新间隔配置。

## 1. 操作频率 → EPD 波形选择（"单次慢、连续快、投屏正常"的答案）

三条并行机制，最终都经 Binder 打到 SurfaceFlinger（`ViewUpdateHelper.surfaceComposerData/transactData`，
ViewUpdateHelper.java:956/991）：

### A. SF Debouncer（核心机制）
- 应用切换时 `OECService.onResume`(OECService.java:1332) → `TabletEACRefreshImpl.onResume`(33-43)
  → `applyEpdParameter`(321-326) → `handleSFDebouncer`(146-152) → `applySFDebouncer`(182-189)
  → `EACUtils.applySFDebouncer`(176-184) → `ViewUpdateHelper.debouncer(enable, mode, shortDelay, longDelay, gcInterval)`(1372-1380, DEBOUNCER=16711780)
- **只对 mode∈{0,3,5}（NORMAL/REGAL/REGAL_PLUS）启用**，映射 `Constant.DEBOUCENER_UPDATE_MODE_MAP={3→0,5→0,0→0}`(Constant.java:265-271)
- mode 1/2/4（DU/A2/X 快速模式）走 `clearSFDebouncer + applyAppScopeUpdate`(TabletEACRefreshImpl.java:173-177)
- 每次触摸/按键 UP：`EpdEventListener.inputEventUpdate`(OECService.java:258-263) →
  `EACBaseRefreshImpl.onInputEventUpdate`(75-99) → `increaseRepaintCount`(101-107,仅 debouncer 模式) →
  `ViewUpdateHelper.debounceIncRefresh()`(1382-1385) + `applyDebouncerTransientUpdateMode`(= enableRegal(true)+setDebouncerTransientUpdateMode)
- SF 侧 shortDelay 窗口内合并多次更新（连续点击→快）；达 gcInterval 次强制 GC 全刷（单次后最终 GC→慢但干净）

### B. Transient Update（滚动/投屏）
- `EACScrollRefreshManager.deliverMotionEvent`(285-306) → `checkDragAndChangeRefreshModeImpl`(316-358)：
  拖动超 touchSlop 且 `ScrollableViewInfo.isValid` → `EInkHelper.beforeScroll`(355) → `ScrollHelper.beforeScroll`(29-38)
- `enterScrollRefreshModeImpl`(134-140)：scrollingRefreshMode 为 2/4 时 `applyTransientUpdate(toEpdMode(mode))`(1393-1397)
- 抬手后 `afterScroll`(40-85) 延迟 `Settings.Global.SCROLL_REFRESH_DELAY`（**默认 1000ms**）再 `clearTransientUpdate(gcAfterScrolling)`(142-154)
- 排除包 android/com.android.systemui(EACScrollRefreshManager.java:49-54)

### C. Fast Mode
- `ViewUpdateHelper.getFastModeIndex()`(496-503, IS_IN_FAST_MODE=1048656)：0=normal、1=system fast、2=app fast
- fast 模式下 debounce 刷新被跳过（EpdcUpdateDebounceWithDelay.java:77）

### D. 单次点击慢的具体路径（AccessibilityHelper.handleMotionWithSFDebouncer, optimization/AccessibilityHelper.java:127-172）
- DOWN 立即 `applySFDebouncer(150, 450, gcInterval)`(164)；UP/CANCEL 延迟 **150ms** 才恢复 debouncer(136-152)；
  **150ms 内再 DOWN 会 cancel 该 future**(157-160) → 连续点击保持 debouncer → 快；单次点击后最终 GC → 慢

### E. 投屏正常
- 架构隔离：EPD 控制是 app→ViewUpdateHelper→SurfaceFlinger 自定义 code transaction，只作用于 EPD 波形；
  投屏/HDMI 走 SurfaceFlinger 常规合成，不进 debouncer/GC 队列 → 画面天然正常

## 2. 全局模式（fallback）操作链 — 第三方应用可行性

### EInkHelper.setAppScopeRefreshMode(int)（EInkHelper.java:1009-1022）
**同时改当前 top app 配置 + 全局 fallback**：
- `TabletEACRefreshImpl.setAppScopeRefreshMode`(58-71)：
  - :61 `curAppConfig.setUpdateMode(curComponent, mode)` — 当前 app
  - :63 `deviceConfig.getFallbackRefreshConfig().setUpdateMode(mode)` — **★全局 fallback（device 默认）**
  - :65 setAppRefreshModeImpl（实际应用）→ :67 saveDeviceConfig → **MMKV 持久化**（onyx_config，
    per-app key `eac_app_<pkg>`，fallback key `eac_default_app_config`）
  - :66 发 `com.onyx.action.REFRESH_MODE_CHANGED`，:69 发 `onyx.action.oec.config.change`

### 权限结论：第三方应用**常规路径不可行**
- binder 层无 Java 权限检查（grep 零匹配），但 OECService 注册在系统 ServiceManager（`oec_service`），
  **第三方 getService 被 SELinux 拒绝**（EInkHelper.java:85 注释自证 "selinux forbidden"）
- 全局 fallback **无外部入口**：无广播/ContentProvider/settings key（仅有 SCROLL_REFRESH_DELAY、VIEW_UPDATE_BW_MODE）
- **root 可行**：su + app_process 可调通（实测 OK）——shell/root uid 被 SELinux 放行

### getAppScopeRefreshMode（EInkHelper.java:1024-1035）
返回当前 top activity 的 refreshConfig 计算后 mode（activity 匹配不到用 globalActivityConfig）

## 3. SDK 层反射与重要发现

### EpdController → Device → 反射 ViewUpdateHelper
- `SDMDevice.createDevice()`(SDMDevice.java:962-1205) 用 ReflectUtil 一次性反射
  `android.onyx.ViewUpdateHelper` 全部方法：setDefaultUpdateMode/applyAppScopeUpdate/applyTransientUpdate/
  clearTransientUpdate/repaintEverything/isInFastMode/setEpdTurbo 等（L1003-1119）
- **★ 关键：SDMDevice（msm8953/sdm660 等 SDM 平台）未覆写 `setSystemDefaultUpdateMode`**
  （仅 IMX6/IMX7/RK 系列覆写），SDM 走 BaseDevice 默认空实现返回 false（BaseDevice.java:1377-1379）
  → **SDM 设备上"设置系统默认波形"不可行**，全局模式只能走 OECService fallback（root）
- 框架侧 ViewUpdateHelper 所有调用最终是 SurfaceFlinger binder transaction：
  applyAppScopeUpdate=16711684、repaintEverything=16711700、带mode=16711715、
  applyTransientUpdate=16711782、clearTransientUpdate=16711783、debouncer=16711780、
  debounceIncRefresh=16711781、setGcRefreshInterval=1048658

## 4. 刷新间隔配置（可捕捉/可调）

| 配置 | 位置 | 默认值 |
|---|---|---|
| debounce shortDelay | TabletEACRefreshImpl.java:122-133,186（EACConfig.getMinAnimationDuration） | 10ms / 20ms(REGAL) |
| debounce longDelay | EACUtils.java:44-46：clamp(shortDelay*3, 400, 1200) | 400~1200ms |
| gcInterval（GC 触发次数） | EACRefreshConfig.java:15；Constant.java:81-82(0~50) | **20 次** |
| scroll_refresh_delay | ScrollHelper.java:165-167（Settings.Global） | **1000ms** |
| scrollingRefreshMode | EACDeviceExtraConfig.java:45,60,193 | **2(A2)** |
| gcAfterScrolling | EACDeviceExtraConfig.java:44,59 | false |
| accessibilityTouchEventDelay | EACDeviceExtraConfig.java:47 | 1500ms |
| DOWN/UP debounce 窗口 | AccessibilityHelper.java:164,136-152 | 150/450ms；UP 恢复延迟 150ms |
| setGcRefreshInterval | ViewUpdateHelper.java:1179-1183；EInkHelper 1192-1204 | 秒级 GC 周期 |
| SDK gcInterval 计数 | EpdDeviceManager.prepareInitialUpdate（api/device/EpdDeviceManager.java:139-174） | 每 N 次刷新一次 GC |

## 5. 对 E-Ink-Launcher 的实现结论

1. **launcher 自身 app scope**（`applyAppScopeUpdate(pkg, true, 0, mode, MAX)`）→ 已实现且实测有效
   （ViewUpdateHelper 直连 SF，不经过 OECService，第三方可调）。**任意 UI 模式值**（GU=2/DEEP_GC=108/X=16777220/MONO=33554436）。
2. **真全局（fallback）**：仅 `EInkHelper.setAppScopeRefreshMode`（6 逻辑模式 0-5），第三方被 SELinux 拦，
   **需 root helper**（launcher 用 su + app_process 执行含 EInkHelper 调用的 dex，或直接在 helper 里调）。
3. **setSystemDefaultUpdateMode 在 SDM 设备无效**（SDMDevice 未覆写），不可作为全局路径。
4. **刷新间隔捕捉**：系统已有 debouncer（shortDelay 10/20ms、longDelay 400-1200ms、gcInterval 20）。
   launcher 可自测点击/翻页间隔，间隔 > longDelay 可视为"单次操作"，可主动 applyTransientUpdate
   快速响应 + 延迟 GC；连续操作（间隔 < 150ms）系统 debouncer 已自动保持快速模式。

---


---


# 四、实测数据

# 实测：14 种刷新模式耗时（BOOX Poke6，2026-08-24 深夜）

> 方法：root + app_process 反射 `ViewUpdateHelper.repaintEverything(mode)` 后
> `waitForUpdateFinished()`（WAIT_FOR_UPDATE_FINISHED=16711703）阻塞计时。
> **这是"捕捉刷新间隔"的可行手段**（app_process 无窗口，repaint 走全局 EPD 队列）。

| 模式 | UI 值 | 耗时 | 分组 |
|---|---|---|---|
| DU / GU_FAST | 1 | 2047 ms | 慢（真实全刷） |
| GU | 2 | 2019 ms | 慢 |
| GC4 | 3 | 2018 ms | 慢 |
| AUTO | 5 | 727 ms | 中 |
| REGAL | 6 | 4 ms | 快 |
| REGAL_PLUS | 9 | 114 ms | 快 |
| GC | 98 | 1 ms | 快 |
| GCC | 107 | 3 ms | 快 |
| DEEP_GC | 108 | 2064 ms | 慢（深度全刷） |
| DU_QUALITY | 2305 | 2017 ms | 慢 |
| A2_QUALITY | 2308 | 2020 ms | 慢 |
| DU4 | 2312 | 643 ms | 中 |
| ANIMATION_X | 16777220 | 5 ms | 快 |
| ANIMATION_MONO | 33554436 | 3 ms | 快 |

## 解读

- **慢组（~2s）**：DU/GU/GC4/DEEP_GC/DU_QUALITY/A2_QUALITY —— 每次触发真实全刷，
  EPD 物理刷新 ~2s。**"单次点击像果冻"的根源**：单次操作后系统用这类波形刷一次。
- **快组（ms 级）**：ANIMATION_X / ANIMATION_MONO / REGAL / GC / GCC —— wait 立即返回
  （快速波形/队列不阻塞）。
- **拯救手段**：桌面/全局模式切到快组（ANIMATION_X 5ms、ANIMATION_MONO 3ms、REGAL 4ms）。
  注：数值为 app_process（无窗口）下的全局 repaint 队列耗时，实际 app scope 下可能不同，
  但相对分级可靠。

---

# 实验：连续 vs 单次操作的 EPDC 行为（2026-08-25，硬件故障设备上）

## 三组实验（dmesg update_err/power error 计数）
| 实验 | 结果 |
|---|---|
| 单次点击（间隔 5s） | 每次触发：`onyx_epdc_reset: set update_err` + `ERROR TPS6518x waiting for power good` |
| 连续点击（间隔 120ms × 4） | **0 次故障**（完全避开） |
| 手动开启 debouncer(0,10,400,20) + setDebouncerTransientUpdateMode(2308) 后单次点击 | **仍 2 次故障** |

## 结论
1. **连续操作避错是真实存在的**（0 故障），但**机制在 SurfaceFlinger native 层**
   （更新合并/缓冲调度），Java 侧无法看到
2. **手动开启 debouncer + transient 不能模拟连续状态**——连续避错可能依赖
   持续输入事件驱动（每次 DOWN/UP 重新 apply），或 SF 侧合并缓冲，纯配置无法复现
3. **hook 模拟"连续操作"不可行/不可靠**：全局快速模式（null scope）无效、
   debouncer+transient 手动开启无效，SF native 合并机制 Java hook 碰不到
4. **最终结论**：TPS6518x 供电硬件故障在每次独立 EPD 更新时触发，软件（模式切换、
   debouncer hook、全局 scope）均无法可靠绕过 → **售后维修是唯一可靠出路**

---


---

# 实验：模拟"连续操作状态"避错 + LSPosed hook 可行性（2026-08-25）

## 背景
连续点击（120ms×4）EPDC 0 故障；单次点击每次 update_err。尝试软件复现"连续状态"。

## 实验（dmesg update_err/power error 计数）
| 实验 | 结果 |
|---|---|
| 手动 debouncer(0,10,400,20) + setDebouncerTransientUpdateMode(2308)，等 5s 后单次点击 | 2 次（故障） |
| 完整序列：debouncer + **enableRegal(true)** + setDebouncerTransientUpdateMode(5)，等 5s 后点击 | 2 次（故障） |
| 完整序列 + **立即点击（0.3s 内）** | **0 次（避开！）** |
| 完整序列 + 立即点击 × 3 轮 | 0 / 2 / 0（**不稳定**） |

## 关键结论
1. **debouncer + enableRegal(true) + transient 状态激活时，EPDC 可避开故障**（立即点击 0 故障）
2. **状态会被系统重置**：等 5s 失效；不稳定原因 = `AccessibilityHelper.handleMotionWithSFDebouncer`
   在每次输入 **UP 后 150ms 恢复 debouncer**，冲掉手动设置的状态
3. **LSPosed hook 可行**（解决不稳定）：hook 掉"恢复 debouncer"逻辑，让 transient 状态
   在输入事件后保持 → 所有操作（含间隔操作）都处于"连续操作状态" → 避开 TPS6518x 故障

## LSPosed 模块 hook 点（设备 Magisk + Zygisk + LSPosed）
| Hook 目标 | 逻辑 |
|---|---|
| `android.onyx.optimization.AccessibilityHelper.handleMotionWithSFDebouncer` | UP 分支 150ms 恢复 → 不恢复/延迟 10s+ |
| `android.onyx.optimization.EACBaseRefreshImpl.onInputEventUpdate` | 每次输入强制 enableRegal(true) + setDebouncerTransientUpdateMode（绕过 DEBOUNCER_UPDATE_MODE_MAP 0/3/5 限制） |
| `android.onyx.optimization.EACUtils.applyDebouncerTransientUpdateMode` | 放宽模式判断兜底 |

## 代价
transient 不清残影 → 画面残影积累，靠 gcInterval（20）周期性强制 GC 兜底（周期性微卡）。

---


# 五、诊断与方案

# 诊断：BOOX Poke6 屏幕"如阻塞"根因 — EPDC 电源硬件故障（2026-08-24）

## 症状
- 单次/间隔三五秒操作：屏幕阻塞 ~2s，像"CPU 已渲染好在等墨水粒子"
- 连续操作：极速正常
- 投屏画面正常
- 切换任意刷新模式（app scope）无改善

## 实时 dmesg 证据（root，dmesg -c 后模拟间隔点击）

```
dump_epdc_status(): wf_status[99] wftask_wf_status[99] cb_state[99]
                    all_frames_completed[0]
onyx_epdc_reset(): set update_err.          # EPDC 控制器每次更新后报错
onyx_epdc_reset(): clean waveform_list...
Reg Enable: [0x1] 0xAF
Reg PowerGood: [0xf] 0xBA
ERROR TPS6518x waiting for power good!      # ★ EPD PMIC 上电失败
Retry 0 more times
onyx_epdc_reset(): reset end.
```

## 根因
- **TPS6518x** = EPD 墨水屏专用电源管理芯片（PMIC），PowerGood 寄存器未就绪（0xBA）
- 每次屏幕更新 → EPDC update_err → 强制 reset → TPS6518x 重新上电失败/重试
  → 完整流程 ~110ms+ 且失败，表现为"阻塞"
- 连续操作：SF debouncer 合并更新，EPDC 队列不耗尽，不触发 reset → 快
- 投屏：走 LCD 常规合成，不经 EPDC 电源 → 正常

## 结论与建议
- **硬件故障**（EPDC 供电电路/PMIC/面板），软件无法修复
- 建议返修/换屏；临时缓解有限（减少单次全刷触发）
- 与刷新模式、debouncer 配置等软件机制无关（模式实测数据见上文章节仍有效）

---

# 关键发现：系统 per-app 配置 updateMode 字段不生效（2026-08-25 实测）

## 验证过程（BOOX Poke6 + root + app_process）
1. launcher 桌面切 ANIMATION_MONO（applyAppScopeUpdate）→ 仅影响 launcher 窗口
2. Legado（com.legado.app.release）前台时 `EInkHelper.getAppScopeRefreshMode()` = 2（未变）
3. root 写 Legado per-app 配置：loadThemes 取现有 theme → 改 `updateMode=33554436` →
   `saveEACAppThemes` + `applyEACAppThemes` → **持久化成功**（load 读出 33554436）
4. 但 `getAppScopeRefreshMode` 仍 = 2 → **运行时未采用 updateMode**

## 根因
- 系统引擎按 **`refreshModeIndex`**（字符串，`refresh_mode_1~5`）映射波形，
  经 `RefreshModeUI`（HD/DU/REGAL_PLUS/SMOOTH/NEW_SPEED）5 档映射，
  见 `caculateRefreshConfig`（EACBaseRefreshImpl:61-73）、`RefreshMappingConfig`
- `updateMode` 字段只是缓存/次要，**自定义 UI 值（如 MONO=33554436）在系统 per-app
  配置中表达不了**（系统只有 5 档）
- Legado 原本 `refreshModeIndex=refresh_mode_3` → 运行时映射 mode 2（覆盖一切写入）

## 结论
- launcher"全局刷新模式"（applyAppScopeUpdate）本质是 app scope，只对 launcher 生效
- 要给第三方应用真正换模式，只能：
  a) 写系统 5 档 `refreshModeIndex`（被系统采用，但只有 HD/DU/REGAL_PLUS/SMOOTH/NEW_SPEED）
  b) SF 直连 `applyAppScopeUpdate(pkg, ...)` 临时生效（系统 Activity 切换时可能覆盖）
- **长按图标 per-app 功能若只改 updateMode 则无效**，需改 refreshModeIndex + refreshModeAlias

---

# 真正可用的全局刷新模式：null 包名 app scope（2026-08-25 实测）

## 写法
```java
ViewUpdateHelper.applyAppScopeUpdate(null, true, 0, mode, Integer.MAX_VALUE);
// pkg=null → SurfaceFlinger 写 -1 hash → 全局 scope（所有窗口）
ViewUpdateHelper.repaintEverything(mode); // 立即生效
```

## 实测验证（BOOX Poke6 + root app_process）
| 步骤 | getFastModeIndex |
|---|---|
| 设全局 MONO(33554436) 后 | 2（app fast） |
| clearAppScopeUpdate 后 | 0（normal） |
| 重设后 | 2 |
| 切到 launcher / Legado（Activity 切换）后 | 2（保持，系统不覆盖） |

## 结论
- **真正全局**：影响所有应用（含第三方，如 Legado），不是 launcher app scope
- **无需 root**：ViewUpdateHelper 直连 SurfaceFlinger，不经过 OECService/SELinux
- **任意 UI 模式值**：MONO/GU/DEEP_GC/ANIM_X 等（不受系统 5 档限制）
- **持久**：Activity 切换不被系统覆盖
- **可观测**：getFastModeIndex 2↔0 验证
- 已用于 RefreshModeHelper（提交 7c28610）：设置菜单"全局刷新模式"从 launcher app scope
  升级为真全局 scope（null 包名）

## 补充：长按 per-app 功能已放弃
- 原因：系统 per-app 配置只认 refreshModeIndex（5 档），自定义 UI 值（如 MONO）写
  updateMode 不生效；且 launcher app scope 只影响自己。真全局 null-scope 已覆盖需求。

---


---


# 六、保留路线

# 自动冻结机制 — 实现参考（保留路线，暂未实施）

> 状态：2026-08-24 调研完成，评估后**搁置**。原因：冻结目标较多时，图标保留方案会使桌面堆积
> 大量冻结图标，交互不如小黑屋/Canta 这类专用工具。以下为完整调研结论，未来如需集成可直接使用。

## 文石系统自带自动冻结（eink-framework，系统级）

### 调用链总览

```
EACAutoFreezeImpl.onResume(prev, cur)     ← Activity 切换时系统 hook
  → freezeController.addAppInFreezeSequence(info)   ← 上一个应用进入"待冻结序列"
  → ApplicationFreezeController.freezeDetectRunnable（每 30s 周期）
      → forceStopPackageWithoutPermissionCheck(pkg)  ← force-stop
      → removeTaskByPkgName(pkg)                     ← 清任务
      → ApplicationFreezeHelper.disableAppByPkgName(pkg)
          → setApplicationEnabledSettingAsUser(pkg, DISABLED_USER=3, 0, userId)  ← disable 应用
```

### 关键文件（res/eink-framework）

| 文件 | 作用 |
|---|---|
| `.../optimization/impl/EACAutoFreezeImpl.java` | 触发逻辑：Activity 切换时把上一个应用加入冻结序列 |
| `.../optimization/ApplicationFreezeController.java` | 冻结序列管理 + 30s 检测周期 + 条件判断 |
| `.../optimization/data/AppFreezeInfo.java` | 待冻结项（pkg + lastPausedTime + fullPMAccessTimeout） |
| `.../optimization/data/p008v2/EACAutoFreezeConfig.java` | per-app 配置（isSupportAutoFreeze / isAutoFreeze） |
| `.../optimization/freezeapp/` | 冻结配置变更 action |
| `.../utils/ApplicationFreezeHelper.java` | 实际 disable/enable 动作 + 保护逻辑 |

### 触发时机（EACAutoFreezeImpl.onResume）

1. 记录当前前台应用 `curComponent`，把上一个应用 `previousPkg` 放入冻结序列
2. 豁免场景：
   - 同一包内切换（`isSamePkgComponent`）
   - 权限管理界面（`isPermissionControllerPkg`）
   - 屏保 dream（`isDreamActivity`）
   - 输入法（`ActivityManagerHelper.isInputMethod`）
   - 应用配置未开启自动冻结（`!isSupportAutoFreeze` 或 `!isAutoFreeze` 且无超时）
3. 序列项带时间戳，`fullPMAccessTimeout` 实现**延迟冻结**（退出后超时才冻，避免马上又用）

### 冻结条件（ApplicationFreezeController.freezeDetetcImpl，每 30s）

- 不在多窗口模式
- `isTargetAppInBackgroundOrTimeout`：
  - 无前台 Activity（不在 top task 的 base/top）
  - 无前台服务（受 `Settings.Global["freeze_inactive_detect_include_foreground_service"]` 控制，默认含）
  - 已超过 `fullPMAccessTimeout`
- 满足 → force-stop + 清任务；若 `isAutoFreeze()` → disable 应用
- 系统应用不加入序列（`!isSystemApp`）

### 保护（ApplicationFreezeHelper.disableAppsAsUser）

| 保护 | 逻辑 |
|---|---|
| 系统白名单 | `OnyxSystemConfig.KEY_APP_FREEZE_WHITE_LIST` 内不冻 |
| 系统/Onyx 应用 | `isOnyxOrSystemApp(pkg)` 不冻 |
| 输入法 | `isInputMethod(pkg)` 不冻 |
| 已冻结 | `isApplicationEnabled` 检查跳过 |

### eink-home 设置 UI

- **APP_FREEZE 设置页**：per-app 自动冻结开关（`SettingsFragmentUtil.getSettingFragmentByFunction(SettingCategory.Function.APP_FREEZE)`），入口事件 `GotoAppFreezeManageEvent`
- **自动冻结延迟**：`GotoAutoFreezeDelayEvent`（"Automatically freeze apps that have not been used for a long time"）
- **冻结角标**：`app_show_freeze_badge_key`（"Show Freeze Badge"）
- **新装应用自动冻结**：`auto_freeze_newly_install_app_key`（默认 true）
- **批量操作**：`all_freeze`（Freeze All）/ `all_thaw`（Unfreeze All）
- 应用列表单项操作：`apps_item_freeze` / `apps_item_unfreeze`

### per-app 配置数据（eac_*.json 的 autoFreezeConfig）

```json
"autoFreezeConfig": {
  "enable": true,            // 配置项是否启用
  "supportAutoFreeze": false, // 系统决定该应用是否支持（UI 不可改）
  "autoFreeze": false         // 用户是否开启自动冻结（黑名单）
}
```

## Canta（Shizuku 免 root 卸载，参考）

- 原理：`ShizukuBinderWrapper` 包装系统服务 binder + `HiddenApiBypass` 绕过隐藏 API 限制，拿 `IPackageManager` 隐藏接口
- 动作：`PackageInstaller.uninstall`（卸载，非冻结）
- 关键文件：`.../util/shizuku/ShizukuPackageInstallerUtils.kt`、`MainActivity.kt:uninstallApp`
- 与冻结的关系：只借鉴其 UI/管理思路；**root 环境下冻结不需要 Shizuku**，直接 `su -c pm disable-user --user 0 <pkg>` 等效文石的 `setApplicationEnabledSettingAsUser(DISABLED_USER)`

## E-Ink-Launcher 集成方案（设计稿，未实施）

若未来启用，推荐实现：

1. **黑名单**：`Config` 存 `Set<String> autoFreezeApps`（SharedPreferences），仿现有 `hideApps`
2. **冻结动作**：`Runtime.exec("su -c pm disable-user --user 0 " + pkg)`（Magisk su；等效文石 disableAppByPkgName）
3. **触发**：launcher `onResume`（回桌面）→ 后台解析 `su -c logcat -d -b events -s am_pause_activity` 取上一个前台应用 → 在黑名单则入冻结序列；带延迟（参考 `fullPMAccessTimeout`，如 30s）防反复
4. **图标保留**：`loadApps` 用 `MATCH_DISABLED_COMPONENTS`（API 17+，兼容处理）查询 + 黑名单应用强制显示（即使 disabled）
5. **冻结角标**：bindItem 检查 `getApplicationEnabledSetting()==DISABLED_USER` → 半透明/角标（参考文石 freeze badge）
6. **点击解冻启动**：onItemClick 检测冻结 → `su -c pm enable` → startActivity
7. **保护**：黑名单 UI 禁止 launcher 自身/输入法/系统关键应用
8. **UI（简洁版）**：SettingFragment 加"自动冻结"入口 → 应用列表页（每行：图标+名称+勾选）

### 搁置原因（2026-08-24）

冻结目标较多时图标会大量保留在桌面 → 图标堆积，管理负担重；专用工具（小黑屋、Canta）的
列表式管理更适合多应用冻结场景。若后续需要"少量常驻应用自动冻结"，此方案可直接启用。

---

# 七、工作流参考（Workflow）

> 本项目（E-Ink Launcher + lsp-refresh-hook 模块）的标准工作方式。每次接到新任务按此流程，
> 避免重复调研、保证结论沉淀。

## ⛔ 红线规则（2026-08-26 增补，违反=严重失误）

1. **严禁钻 LSPosed 的牛角尖**：验证/排障时禁止在 LSPosed 注入机制上反复折腾
   （currentTop、Manager service、scope、db、zygote 注入、Manager UI 自动化……）
2. **不允许查看 lsp 日志**：`/data/adb/lspd/log/*`、lspd verbose/modules log、LSPosedManager 日志一律不看
3. **设备操作红线**：不因次要验证做破坏性设备操作——不动系统导航等 settings、不反复 `stop;start`/重启
   framework、不改 LSPosed db；确需操作前先评估对用户设备的影响并备份
4. 判断问题优先走：**ref.md 已有结论 → 系统参数与影响（settings/dmesg/fastModeIndex 等）→ res 反编译源码**

> 教训（2026-08-25/26）：在"验证 LSP 模块注入"上浪费大量轮次（native pipe/currentTop/Manager/db 反复折腾），
> 且 stop/start + 改 db 等操作导致用户设备**手势导航失效**。而"保持 debouncer 能否避错"用
> ref.md 已有实验（手动保持 debouncer → 间隔点击仍故障）即可定论，根本不需要 LSPosed 注入。

## 标准流程（四步）

1. **回顾 ref 有无已发现点**
   - 先查本文件是否已覆盖该方向（刷新模式、全局 scope、LSPosed hook、per-app 配置、冻结等）
   - 已有结论直接复用；未覆盖才进入调研，防止重复实验/踩坑

2. **去 res 寻找补全逻辑**
   - `res/eink-framework`、`res/eink-home`、`res/eink-systemui` 是反编译源码，**以它为准**
   - 用 grep + 行号定位真实实现：方法签名、字段名、调用链、常量值
   - 例：本次补全 lsp 模块时，靠 `AccessibilityHelper.java:143-152` 确认恢复任务、
     `View.java:14315` 确认调用点在 app 进程 → 发现原模块只 hook 第一个包（system_server）的缺陷

3. **将发现回写 ref**
   - 新结论/修复点/反编译行号对照及时更新进本文件，保持 ref.md 是唯一权威汇总
   - 标注日期与设备/版本上下文，方便后续回顾

4. **提交推送远程仓库，等待 CI 编译结果以及反馈**
   - 改动 **commit + push** 到远程仓库（如 `lsp-refresh-hook` 的 `origin/main`），
     push 自动触发 GitHub Actions（`lsp-refresh-hook/.github/workflows/ci_build.yml`）
   - 等编译结果 + 用户真机反馈再迭代，**一般无需本地编译**
   - 注意 push 前 `git status`/`git diff` 自查，只提交意图内改动

## 验证手段

- **adb 可链接对应设备**查看日志：
  - 模块日志：`adb logcat -s FlipOuterUnlock`
  - 故障计数（root）：`adb shell su -c 'dmesg -c | grep -c update_err'`
  - 系统设置读写、Activity 栈等常规 adb 操作
- **本地编译**：仅用于快速验证/检查语法错误时 `./gradlew :app:assembleDebug`
  （需 JDK 17 + Android SDK，见 `local.properties`）；正式验证交给 CI

---

# 八、getAppScopeRefreshMode 数据链与 currentTop 机制（2026-08-25 调研）

> 从 res/eink-framework 反编译源码补全的完整逻辑链。背景：LSP 模块注入成功后实测
> `getAppScopeRefreshMode()` 恒为 2、`getFastModeIndex()` 恒为 2，即使 Legado per-app 配置
> 已持久化为 `refresh_mode_1`（REGAL_PLUS/mode 5）。最终定位根因在 currentTop。

## 1. currentTop 更新机制（EpdEventListener native pipe）

```
EpdEventListener（native pipe）onActivityStateChanged(PipeMessage)
├── activityState==1 → handleActivityResumeAsync
│   └── handleActivityResumeImpl（OECService.java:1295 附近）
│       ├── setCurrentTopComponent(new ComponentName(msg.pkg, msg.cls))   ← currentTop 唯一更新点
│       ├── OptimizationBundle.singleton().isTopComponentChanged() 检查
│       └── eacImplMap.forEach: impl.onResume(prev, cur, deviceConfig)
│           → TabletEACRefreshImpl.onResume（TabletEACRefreshImpl.java:33-43）
│               ├── applyUpdateMode(curRefreshConfig, fallbackConfig)
│               │     mode 0/3/5 → clearAppScopeUpdate；1/2/4 → applyAppScopeUpdate
│               ├── applyEpdParameter(...)   ← turbo / gcInterval / debouncer
│               └── sendRefreshModeChangeBroadcast
└── activityState==4 → handleActivityTopResumedAsync → handleActivityResumeImpl
```

**★ 根因**：currentTop **完全依赖 native pipe 的 Activity 状态消息**（OECService.java:258-273）。
`su -c 'stop; start'` **软重启 framework 后 native pipe 不恢复** → 无 onActivityStateChanged →
`currentTop` 恒为 null → 所有走 currentTop 的接口（getAppScopeRefreshMode / setAppScopeRefreshMode /
onInputEventUpdate / onResume）全部落到**空 pkg 配置**。**必须完整重启设备（adb reboot）恢复**。

## 2. getAppScopeRefreshMode 完整数据链

```
OECService.getAppScopeRefreshMode()                      [OECService.java:560-561]
→ TabletEACRefreshImpl.getAppScopeRefreshMode(top, deviceConfig)   [:74-77]
→ caculateRefreshConfig(deviceConfig.getRefreshConfig(top)).getMode()
→ deviceConfig.getRefreshConfig(top)                     [EACDeviceConfig.java:62-66]
→ getAppConfigByComponentName → ensureAppConfig(pkg)     [:53-54, :86-87]
→ ★ EACAppThemeManager.getActiveTheme(pkg).getAppConfig()  ← 走 theme，不走 appConfigMap！
→ getTheme(pkg, getActiveThemeType(pkg))                 [active type 默认 3]
→ fillTheme(loadThemeOrCreate(pkg, type))
→ loadThemeFromMMKV("eac_theme_<pkg>@theme_type_<type>") ← MMKV theme key
→ EACAppConfig.getRefreshConfig(component)               [per-activity → globalActivityConfig]
→ caculateRefreshConfig(refreshConfig)                   [EACBaseRefreshImpl.java:61-73]
→ RefreshModeIndex.toEnum(refreshModeIndex)              [可解析 refresh_mode_1..5]
→ SysUIConfig.getRefreshConfigByIndex(name.toLowerCase())
→ RefreshModeData.mode                                  ← 最终逻辑模式
```

**★ 关键**：`ensureAppConfig(pkg)`（EACDeviceConfig.java:86）读的是 **theme（eac_theme_ key）**，
不是 `deviceConfig.appConfigMap`（eac_app_ key）。两者是**两套 MMKV 数据**，改错一套就白改。

## 3. 双数据源对照（MMKV /onyxconfig/mmkv/onyx_config）

| 数据源 | MMKV key | 谁读 | 谁写 |
|---|---|---|---|
| theme | `eac_theme_<pkg>@theme_type_<type>`（type 1/2/3） | getActiveTheme / loadThemes | saveEACAppThemes（EInkHelper/OECService） |
| appConfigMap | `eac_app_<pkg>` + `eac_app_pkg_set` + `eac_default_app_config` | EACDeviceConfig.load（启动时） | EACAppConfig.save / setAppScopeRefreshMode 等 |
| active type | `eac_active_theme_<pkg>`（默认 3） | getActiveThemeType | setActiveThemeType |

- **EACDeviceConfig.load()**（OECService 启动）读 `eac_app_pkg_set` → 每个 pkg `EACAppConfig.load()` 读 `eac_app_<pkg>`
- **ValidateAppConfigAction**（启动时对 appConfigMap 每项执行）会**改内存里的 appConfigMap**（实测把 Legado 的 refreshModeIndex 从 refresh_mode_1 改成 refresh_mode_4，**未写回 MMKV**）——所以 dumpsys 显示值与 MMKV 不同
- **fallback**：`eac_default_app_config`（EACDeviceConfig.getFallbackRefreshConfig → extraConfig.appDefaultConfig）

## 4. Poke6 5 档真实映射（SysUIConfig.refreshConfigMap，实测）

| refreshModeIndex | mode | turbo | 含义 |
|---|---|---|---|
| `refresh_mode_1` | **5** | 0 | REGAL_PLUS |
| `refresh_mode_2` | **3** | 0 | REGAL |
| `refresh_mode_3` | **2** | 5 | NEW_SPEED（A2）|
| `refresh_mode_4` | **0** | 0 | NORMAL |

> 默认：EACRefreshConfig 默认 updateMode=0 + refreshModeIndex=REFRESH_MODE_2；
> **空 pkg theme 默认 refresh_mode_3（mode 2）**——这就是 currentTop=null 时 get 恒为 2 的原因。

## 5. 关键结论与陷阱

1. **软重启（stop/start）陷阱**：OECService 的 currentTop 靠 native pipe，软重启后 pipe 不恢复 →
   getAppScopeRefreshMode/setAppScopeRefreshMode 全部操作空 pkg（mode 2）。**改配置后必须完整重启设备**
2. **setAppScopeRefreshMode（QS 磁贴）只写 updateMode + fallback**，运行时按 refreshModeIndex 映射 →
   对已有 per-app refreshModeIndex 的应用无效（ref.md §三 已记录，本调研再次验证）
3. **要改第三方应用模式**：写 theme 的 `refreshModeIndex`（`eac_theme_<pkg>@theme_type_<activeType>`）
   或 `eac_app_<pkg>`，然后**完整重启设备**让 OECService 重载
4. **可观测指标**：`getFastModeIndex()`（0=normal / 1=system fast / 2=app fast）是 SF 侧实际生效状态的
   唯一可靠指标；fastModeIndex=2 持续 ⇒ 实际仍是快速模式

## 6. 本次调试工具（root + app_process 反射）

```bash
# 编译 helper（JDK + SDK d8，android.jar 提供 org.json）
javac -source 8 -target 8 -cp <sdk>/platforms/android-36/android.jar Xxx.java
<sdk>/build-tools/37.0.0/d8.bat --release --lib <sdk>/platforms/android-36/android.jar --output . Xxx.class
adb push classes.dex /data/local/tmp/checkmode.dex
adb shell su -c 'CLASSPATH=/data/local/tmp/checkmode.dex app_process /system/bin --nice-name=x io.onyx.Xxx'
```

- `CheckMode`：getAppScopeRefreshMode + getFastModeIndex
- `GetTheme <pkg>`：读任意 pkg 的 active theme refreshConfig（idx/updateMode）
- `LoadThemes <pkg>`：dump 全部 3 个 theme JSON（读 eac_theme_*）
- `ModifyAppConfig <pkg>`：直写 eac_app_<pkg> 的 refreshConfig
- `SetLegadoRegal`：遍历 theme 改 refreshModeIndex=refresh_mode_1（saveEACAppThemes+applyEACAppThemes）
- `RefConfig`：打印 SysUIConfig 5 档真实映射
- 注意：**d8 每次只编传入的 class，会覆盖 classes.dex**——要一次性编全所有 helper class

> 状态：**已定论（2026-08-25/26）**。LSP hook"事件驱动式保持 debouncer"对间隔操作**无效**：
> 1. **注入层**：本设备 EPDC 硬件故障连带影响 native pipe（currentTop 恒 null）、logd、Manager service，
>    LSPosed 注入本身极不稳定（需手动拉起 Manager 才可能注入），不可依赖
> 2. **机制层**：hook 的"保持 debouncer 激活"与 ref.md 实验 2 的"手动保持"同类（仅 shortDelay 150 vs 10ms），
>    实验 2/3 证明保持 debouncer 激活对**间隔操作**无效（等 5s 后点击仍 2 次故障）；0 故障只出现在
>    "立即点击"（<150ms 连续操作，系统原生 debouncer 合并机制，无需任何干预）
> 3. **最终结论**：软件（模式切换 / debouncer hook / 全局 scope / LSP 保持）无法可靠绕过 TPS6518x 供电故障
>    → **售后维修是唯一可靠出路**（与 ref.md §五 第 1133 行结论一致，本次全链路调研再次验证）
> 4. lsp-refresh-hook 模块代码保留在仓库作为实验记录，不再推进注入/实测

---

# 九、Poke6 TPS6518x 故障 · 内核 wait 超时 5s→1s patch（2026-08-30 实测成功）

> 背景：TPS6518x 面板电源芯片 PowerGood 失败（`waiting for power good!` / `DISPLAY regulator enable -110`）
> 导致 EPDC 刷新卡死 → `wait all_lut_free timeout` → `onyx_epdc_reset` 循环（活跃时每 16~19s 一次）。
> 旧结论（§五/§八）：软件无法绕过供电故障。**本次突破**：虽无法修复供电，但**直接改内核把兜底超时 5s→1s**，
> reset 循环加快 ~10 倍、翻页响应从 ~8s 降到 ~2-3s，可用性大幅提升——软件"治标"可行。

## 1. 内核超时定位（关键坑）

- 设备: Poke6（高通，内核 `Linux 4.19.157-perf`，clang 10，HZ=100）
- 提取: `dd if=/dev/block/by-name/boot_$(getprop ro.boot.slot_suffix)` → boot.img（96MB）
- 解包: boot header v2（page_size=4096）→ gzip 内核（偏移 4096）→ 解压 34MB ARM64 Image
- 关键坑: 打印字符串 `3%s(): wait all_lut_free timeout %d ms!` 的**真实起点是 0x199501D**
  （首字节 `\x01` = printk level 前缀，'3' 在 0x199501E）——之前按 0x1E/0x1F 搜引用全部失败
- 唯一引用: `0x543148 add x0, x0, #0x1d`（adrp@0x543140→页 0x1995000）
- wait 实现: `0x543118 bl 0x122a258`（wait_event_timeout 包装，w1=超时 jiffies）

## 2. Patch 表（3 处机器码，@HZ=100：1s = 100 jiffies = 0x64）

| 偏移 | 原 4 字节 | 新 4 字节 | 含义 |
|---|---|---|---|
| 0x543110 | 52 80 3E 81 (`mov w1,#0x1f4` 500j=5s) | 52 80 0C 81 (`mov w1,#0x64` 100j=**1s**) | **wait all_lut_free 实际超时** |
| 0x542a70 | 52 80 3E 81 | 52 80 0C 81 | wait lut_free 超时（同函数另一等待） |
| 0x54314C | 52 82 71 02 (`mov w2,#0x1388`=5000ms) | 52 80 7D 02 (`mov w2,#0x3e8`=1000ms) | **日志 %d 参数**（硬编码，与 wait 无关） |

> 注意: 打印的 `%d ms` 参数是**独立硬编码**（0x54314C），只改超时不改它则日志仍显示 5000ms（曾因此误判 patch 未生效）。
>
> **版本对照（2026-09-07，四个 boot 镜像解压后字节 diff 实测还原，与上文互证）**：
> v1(boot_patched_1s.img) = 0x542A70 + 0x543110（两处 wait 5s→1s，`mov w1,#0x1f4→#0x64` 各仅 1 字节变化 3E→0C）；
> v2 = v1 + 0x54314C 日志参数（5000→1000，2 字节 88 13→E8 03）——即上表 3 处 = v1+v2 合计；
> v3 = v2 + 0x5F43AC/0x5F46C0（重试 3→1/5→1，各 1 字节 0F/17→07）；
> v4 = v3 + 0x5F46E8（`mov x0,x20`→`movz x0,#0x3e8` 即 1ms）+ 0x5F4408/0x5F4410/0x5F46B4/0x5F4718（sleep 压短）。

## 3. 重打包与刷入

- 重打包: gzip(9) 压缩新内核 → 重建 boot header（v2，改 kernel_size@8，无 second/dtb 段）→ 16.9MB
- 刷入: `dd if=/sdcard/boot_patched.img of=/dev/block/by-name/boot_b`（当前槽位 _b）
- 校验: 读回前 16.9MB md5 一致；bootloader `ro.boot.verifiedbootstate=orange`（解锁，不拒收）
- 回滚: `/sdcard/boot_b_backup.img`（刷前备份）或仓库 `boot_extracted.img`

## 4. 实测效果（重启后）

```
o_e_u_w_s(): wait all_lut_free timeout 1000 ms!     ← 日志正确
reset 间隔: 1.2s（原 16~19s，活跃时）
翻页等待:  ~2-3s（原 ~8s = 5s wait + reset + powerup 重试）
深度休眠时 reset 仍停止（机制未变）
```

## 5. 未改项: 0x545790（powerdown 等待，保持 5s）

- 属于 `onyx_epdc_fb_blank` 面板下电流程（超时分支打印 `%s(): No powerdown received!`）
- **影响很小**：仅熄屏/面板下电时，若 EPDC 卡住会多等最多 5s（不阻塞用户、不影响翻页/reset 循环）
- **保守不改的理由**：这是"给 EPDC 正常完成波形的保护性等待"，正常机上改短可能截断正在执行的波形（残影风险）；故障机上 EPDC 本来就完不成，等 1s/5s 都只是超时，收益仅熄屏快几秒

## 6. 工具产物（仓库 eink/ 目录）

- `boot_extracted.img` / `kernel_extracted.img`：原 boot / 解压内核
- `boot_patched_1s_v2.img`：成品（已刷入 boot_b）
- `_scratch_gs/`：解包/扫描/patch 脚本（capstone 定位、rebuild_boot、verify_boot 等）

### 7. 二次优化：powerup 重试 5.7s → <1s（2026-08-30 追加）

> 8s 翻页的瓶颈从 wait 转移到 **powerup 重试**：TPS6518x 每次失败走
> 外层 3 次（Retry 2/1/0，每次 ~1.9s）+ 内层 5 次重试，共 ~5.7s。
> 由于正常机 powerup 首次即成功（不进重试），**减少重试次数对正常机零副作用**。

| 偏移 | 原 4 字节 | 新 4 字节 | 含义 |
|---|---|---|---|
| 0x5F43AC | 71 00 0F 1F (`cmp w24,#3`) | 71 00 07 1F (`cmp w24,#1`) | 外层重试 3→1 次 |
| 0x5F46C0 | 71 00 17 1F (`cmp w24,#5`) | 71 00 07 1F (`cmp w24,#1`) | 内层重试 5→1 次 |

> 编码要点: `cmp w24,#imm` = `subs w31,w24,#imm`，rd 恒为 31（wzr）——按 rd=24 算会失败。

**实测（重启后）**：
```
Retry 2 more times     ← 只打 1 次（原 2/1/0 三次）
reset 周期 ~1.2s       （wait 1s + reset 0.1s + powerup 快失败）
翻页显示: tap 后 ~1-2s（原 ~8s）
```

**最终 5 处 patch 汇总**（boot_patched_1s_v3.img，已刷入 boot_b）：
| 偏移 | 作用 |
|---|---|
| 0x543110 / 0x542a70 | wait all_lut_free / lut_free 超时 5s→1s |
| 0x54314C | 日志 %d 5000→1000 |
| 0x5F43AC / 0x5F46C0 | powerup 重试 3/5→1 次 |

### 8. 三次优化：powerup 等待压短 + 风险说明（2026-08-30 追加）

> 思路转变（用户定调）：**不再期望普通刷新成功，直接把 reset 周期压到最短，让 reset 当"平A"（伪刷新）**。

> 故障机上普通刷新永远失败（TPS6518x 不上电），唯一显示更新路径 = 请求卡死 → wait 超时 → reset → 短暂窗口执行积压帧。

**v4 新增 5 处 patch（压短 powerup 失败路径等待）**：
| 偏移 | 原指令 | 新指令 | 说明 |
|---|---|---|---|
| 0x5F46E8 | `mov x0, x20`(0x1062560≈17s usleep 上限) | `mov x0, #0x3e8`(1ms) | ⚠️ 等 PowerGood 超时上限压到 1ms |
| 0x5F4408 | `mov w0, #0x15`(21) | `mov w0, #0x1` | 外层 sleep 21→1 |
| 0x5F4410 | `mov w0, #0x12c`(300) | `mov w0, #0xA`(10) | 外层 sleep 300→10 |
| 0x5F46B4 | `mov w0, w22`(21) | `mov w0, #0x1` | 内层 sleep 21→1 |
| 0x5F4718 | `mov w0, w21`(5) | `mov w0, #0x1` | 失败后 sleep 5→1 |

**实测**：Retry → powerup error 1.9s→1.6s，翻页 ~3-4s。剩余 1.6s 是 **regmap/i2c 写 TPS6518x 无响应的硬件超时**（0x76e6c0→0x99e1d8 通用 i2c 层）——改它影响所有 i2c 设备，风险大，**不建议继续压**。
> ⚠️ **2026-09-07 归因更正（详见第十一章 §8）**：此"1.6s = regmap/i2c 硬件超时不可压"已推翻——dmesg 时间戳证明 Reg Enable/PowerGood 寄存器读取 µs 级完成（i2c 总线正常；失败是 PowerGood 位 0xBA 不置位）；1.6s 真身 = retry-prep(0x5F4488) 内动态 `msleep([chip+0x84]+[chip+0x88])`≈1.55s + sleep21/30，位于**失败重试专属路径、可 patch**（v6：0x5F4508 `add w0,w9,w8`(0b080120)→`mov w0,#imm`，200/50/20ms 分档实验）。"动 i2c 层"结论仅适用 0x76e6c0 总线超时，与本瓶颈无关。

**⚠️ 风险说明（正常机未验证）**：
- `0x5F46E8` 1ms 超时是**最大风险点**：正常机上电若 PowerGood 需要 >1ms 可能被误判失败 → 正常机显示异常（wfe 事件唤醒机制下可能无影响，但未验证）
- sleep 21→1/300→10/5→1：正常机写寄存器稳定延时被压短，理论上影响写入可靠性
- 前 7 处 patch 安全（wait 5s→1s、重试次数 3/5→1：正常机不进重试路径）
- **建议**：v4 只在故障机使用；正常机回退 v2（仅 3 处安全 patch）；待正常机实测 v4 无副作用再通用

**10 处 patch 最终汇总**（boot_patched_1s_v4.img，当前刷入 boot_b）：
| 类别 | 偏移 | 作用 |
|---|---|---|
| wait 超时 | 0x543110 / 0x542A70 | wait all_lut_free / lut_free 5s→1s |
| 日志 | 0x54314C | %d 5000→1000 |
| 重试次数 | 0x5F43AC / 0x5F46C0 | powerup 外层 3→1、内层 5→1 |
| powerup 等待 | 0x5F46E8 / 0x5F4408 / 0x5F4410 / 0x5F46B4 / 0x5F4718 | 超时上限/各 sleep 压短 |

回滚：`dd if=/sdcard/boot_b_backup.img of=/dev/block/by-name/boot_b`（原始）或刷 v2/v3/v4 各版本镜像（仓库 eink/ 目录）。

### 9. 内核 reset/powerup 触发链反汇编补全（2026-09-07，kernel_extracted.img + kallsyms 静态分析）

> 符号映射修正：kallsyms 运行时地址 → 文件偏移 delta = **0xffffffa743c80000**（精确）。
> 此前误用 0x…fff8 差 8 字节；`onyx_epdc_reset` 真实入口 = 文件 0x5450f8（kallsyms 441c50f8 自洽）。

**触发链（全部调用点实测扫描，无遗漏）**：
- `onyx_epdc_reset`（0x5450f8）仅 2 个调用点，均在 `onyx_epdc_update_wb_sg`（0x542270，大状态机，~0x2E90 字节）内：
  - **0x542aa4**（状态4）：`produce_wf_segment` 后 `wait_event_timeout(wait lut_free, 500j)` → 超时打印 `wait lut_free timeout!`（无 %d）→ `bl onyx_epdc_reset`
  - **0x543168**（状态0x83）：同型 wait（`wait_event_timeout(all_lut_free, 500j)`）→ 超时打印 `wait all_lut_free timeout %d ms!`（%d 为独立硬编码 0x54314C=5000，即 patch 的日志参数）→ `bl onyx_epdc_reset`
  - **wait_event_timeout 条件真时提前返回**（标准语义）→ 成功窗口内正常完成的帧不会被超时截断
- **reset 本体**（0x5450f8-0x5454f4）：dump 寄存器/status → `onyx_epdc_clear_all_upd` → 置 update_err 标志([epdc+0x2670]=1) → 遍历 3 条链表逐个 `epdc_waveform_buf_clean` + `epdc_waveform_buf_release`（即 dmesg "clean waveform_list"）→ 唤醒等待队列 → **`msleep(100)`**（0x5454a0 `mov w0,#0x64; bl 0xf45a8`）→ 打印 "reset end"。reset 不触碰 TPS6518x 电源。
- **★ 超时→reset 后自动重排队全屏 GC16**（0x5431a8-0x5431d4）：新建更新描述符，坐标取 [epdc+0xe0/0xe4]（全屏），`get_waveform_mode_index(2)` → **mode=2 (GC16)** → 回到状态机继续提交 → 再次 powerup。**这就是"reset 当伪刷新执行积压帧"的结构级证据**，也是 reset 循环自持（无需新用户输入即可循环，直到休眠）的原因。
- `onyx_epdc_powerup`（0x540d40）：4 路 `regulator_enable`（0x5e0928，TPS6518x 各轨），任一失败 → 打印 + 置错误标志 [x23+0xa2]=1；全成功 → 置 [epdc+0x2494]=1（已上电标志，下次走已上电快速路径）+ `queue_delayed_work` 自动下电定时器。**"单次卡/连续快"的内核侧对应物**：连续操作时已上电快速路径跳过 powerup 赌博。
- powerup 调用点：`_onyx_epdc_submit_upd_work_func+0x6e8`、`epdc_task_new+0x164`；powerdown：`onyx_epdc_done_work_func`、`onyx_epdc_fb_blank`。
- TPS6518x 重试循环（0x5F4000 区域，"waiting for power good"+"Retry %d more times"）属 regulator 驱动（非 epdc kallsyms 子集），被 regulator_enable 间接调用。

**reset 触发失败风险评估**：触发依赖"有帧提交 + 状态机跑到 wait 状态"。帧提交由用户交互/重排队驱动；reset 循环自持后即使无输入也持续（休眠暂停）。未发现"卡死后无路径到达 wait"的死区（empirically 16-19s/1.2s 循环稳定触发）；残余风险 = 每次新提交的 powerup 都要重赌一次（自动下电定时器会关闭电源）。

**v5 候选（仅故障机 boot 前提下，未实施）**：
| 偏移 | v3 现值 | v5 候选 | 收益/风险 |
|---|---|---|---|
| 0x542A70 / 0x543110 | 100j (1s) | **40-60j (0.4-0.6s)** | 每周期省 0.4-0.6s；成功窗口帧 < 超时即提前返回不受影响；风险 = DEEP_GC(DU init+GC≈0.66s)/INIT(1.2-1.7s) 若在成功窗口执行且中间无 LUT 空闲点会被截断 → 残影，由重排队自愈 |
| 0x54314C | 1000 | 同步改为新 wait 值 ms | 仅日志一致性（防再误判） |
| 0x5454A0 | msleep(100) | 20-30ms | 每周期再省 ~0.08s；EPDC settle 裕度压缩，风险低但未实测 |
| 0x5F43AC / 0x5F46C0 | cmp #1 | cmp #0（可选） | 去掉最后一次重试 ~0.3-0.5s/次；cycle 加快后每秒尝试次数近似守恒，收益边际 |
| i2c/regmap 超时(1.6s) | 不动 | 不动 | 不在每周期路径上（v3 cycle 1.2s < 1.6s 证明），且与触摸等共用总线 |
| 0x5F46E8 (1ms cap) | —— | **仍禁止** | 破坏成功窗口（唯一显示更新时机），v4 教训 |

预期：v3 cycle ≈1.2s → v5(0.5s wait + 20ms sleep) ≈ **0.7-0.8s**，翻页 1-2s → 0.8-1.5s。上限仍由"powerup 何时成功"的硬件随机性决定。
> 预测依据：v3 实测 cycle 1.2s < powerup 失败序列 1.6s → 两者必然并发（不同线程），wait 是周期关键路径，压 wait 的收益可近似直接转化为周期缩短；不确定带 0.6-1.0s。
> 物理下限：powerup 恰好成功时，GC16 38 帧物理执行 ≈400ms + 提交开销 → "点按→出画"最快 ~0.5s，与 v3/v5 无关；v5 改善的是"首次 powerup 失败"的常见路径（1-2s → 0.6-1.2s）。

### 9.1 v4 五点处置结论（2026-09-07，重试循环反汇编定性）

> 对 0x5F4678 内层函数与 0x5F43AC 外层循环的路径归属分析（原始内核）：

**内层结构**（0x5F4678，每次 powerup 调用执行）：w20=0x1062560µs(17.1s cap)、w21=5ms、w22=21ms；
首次尝试：`[x19+0x90]==0` → regulator enable → **0x5F46E8 wait(上限17.1s，事件驱动)** → 置 `[x19+0x90]=1`（后续重试跳过 enable+wait）→ 0x5F46F4 读 PowerGood 寄存器（0x76e6c0 regmap/i2c）→ **返回 0 = 成功直接退出**；非 0 → 0x5F4718 sleep(5ms) → 0x5F46B4 sleep(21ms) → `cmp w24,#5` 重试。
**外层结构**（0x5F43AC）：`cmp w24,#3` 不等才打印 "Retry N more times" 并进入重试准备块（0x5F4408 sleep 21ms + 0x5F4410 sleep 300ms）→ 再调内层。dmesg Retry 行只在失败时出现 = 睡眠块为**失败专属路径**。

**逐点结论**：
| v4 偏移 | 路径归属 | 处置 |
|---|---|---|
| 0x5F46E8 (17.1s cap) | **唯一公共路径点**（每次 powerup 首次尝试必经，i2c 访问前的就绪等待） | **必须保持原样**。失败耗时由 i2c 超时主导（cap 不占大头，v4 实测仅省 0.3s 且来自 sleep）；改短 = i2c 在 PMIC 未就绪时硬读 → 全失败+waveform_desc NULL（v4 冻屏机制）。中值版本（如 100ms）零收益、非零风险 |
| 0x5F4410 (300ms) | 失败专属（外层重试准备块） | **v5 唯一可并入项**：300→30ms 安全（成功路径不经过），每失败 powerup 省 ~0.27s |
| 0x5F4408 (21ms) | 失败专属（同上） | 保持原样（收益 21ms 无意义） |
| 0x5F46B4 (21ms) | 失败专属（内层重试间） | 保持原样（收益 ≤21ms） |
| 0x5F4718 (5ms) | 失败专属（读到失败结果后） | 保持原样（收益 4ms） |

**原则**：v4 的错误不是"值太极端"而是"目标选错"——4 个失败专属点只能省毫秒级（大头是 i2c 硬件超时 1.6s），唯一公共路径点（cap）压短直接杀死成功窗口。真正值得压的 wait 1s→0.5s 与 msleep(100)→20ms 都不在 v4 的点集内。

### 9.2 v5 实施与实测（2026-09-07，boot_patched_05s_v5.img 已刷入故障机 boot_b）

**最终 7 处 patch**（脚本 `_scratch_gs/patch_kernel_v5.py` + `rebuild_boot_v5.py`，产物 `boot_patched_05s_v5.img`，md5 4c16b8e7d2f67cdeab7b4816efd177af）：
| 偏移 | 原 | v5 | 说明 |
|---|---|---|---|
| 0x542A70 / 0x543110 | mov w1,#0x1f4 (5s) | **mov w1,#0x32 (0.5s)** | 两处 wait 超时 |
| 0x54314C | 5000 | **500** | 日志 %d 同步 |
| 0x5454A0 | msleep(100) | **msleep(20)** | reset 内 settle |
| 0x5F43AC / 0x5F46C0 | cmp #3 / #5 | cmp #1 / #1 | 重试（同 v3） |
| 0x5F4410 | sleep 300ms | **sleep 30ms** | 外层重试准备（失败专属） |

**刷入**：刷前备份 = v3（设备 /sdcard/boot_b_pre_v5.img，md5 5d7c3c79…；仓库留 _scratch_gs/boot_b_pre_v5.img）。
**注意：§5"故障机已刷回原版 boot"记录过时——2026-09-07 刷 v5 前实测 boot_b 上是 v3**（header+全区域字节级比对确认）。

**实测结果（重启后 3.5h，dmesg）**：
- 日志 `wait all_lut_free timeout 500 ms!` ✓（日志参数与真实 wait 一致，消除误判源）
- reset 本体 30–40ms（v3 ~100-110ms）；新增日志行 `onyx_epdc_reset(): reset cause[update wb wait all_lut_free timeout].`
- **reset 周期 0.88–0.91s**（v3 1.2s，−25%）。分解 = wait 0.5 + reset 0.03 + powerup 失败/提交开销 ~0.35s——预测 0.7-0.8s 偏乐观（0.35s 开销此前被低估），实测与分解式吻合
- 失败 powerup（含重试）1.6s（v3 ~1.9s，300→30ms patch 生效）。注意：Retry 行打印数字公式独立于阈值（`2-w24`），patch 后仍显示 "Retry 2 more times" 但只打一次，勿据文本误判
- 功能验证（TestWaveform 98）：GC16 38 帧正常入 LUT（frame_cur[2]/38）→ 超时 → reset 清 2 条波形 → 重排队 → 再次 powerup，全链路正常，无 v4 式冻结
- 416 次 powergood 失败仅 4 次 reset：**多数失败更新在 powerup 阶段被丢弃、未入 LUT → all_lut_free 恒真 → wait 立即返回、无超时无 reset**。reset 循环只在帧真正卡进 LUT 时发生（部分成功/竞态）；点按延迟主要由失败 powerup 耗时（1.6s）+ 成功窗口命中率决定
- 回滚：/sdcard/boot_b_pre_v5.img（v3）或仓库各版本镜像；当前槽位 _b
- 待用户手感确认：翻页预期 1-2s → ~0.8-1.5s（未做视觉主观验证）

### 9.3 v6：reset 重排队波形 GC16 → DU（2026-09-23，**已刷入 boot_b 并实测有效**）

> ┌──────────────────────────────────────────────────────────────────────────┐
> │ **本节导读（2026-09-25 更新，先读这里）**                                │
> │                                                                          │
> │ 本节按时间顺序记录了 v5 → v6-DU → v6-A2 三代内核 patch 的完整探索。       │
> │ **最优结论在 §9.3.17~§9.3.21**，前面章节的中间推断有多处后来被             │
> │ 推翻，阅读时请以 §§9.3.17~21 为准。关键四条：                            │
> │                                                                          │
> │ 1. **v6-A2 内核（当前 boot_b）= 最终形态**，黑屏已解决，勿回退。          │
> │ 2. **A2 档（scope=4）在故障机必然 reset 循环** —— 根源是 native 依        │
> │    waveform mode 4 判定为 `update[1]` 全屏，在供电故障机上必然             │
> │    `wait all_lut_free` 超时。**kernel 与 launcher 均无法改**。            │
> │ 3. **规避方式：不选 A2 档即可**（实验证明 DU/GC16/NORMAL 全绿）。         │
> │    用户已决定：档位表保留 6 档现状，EAC 与 kernel 均不改动。               │
> │ 4. **残影问题（§9.3.21）**：只有 GC16 族清残影（同色态驱动率 0.23）；      │
> │    DU/A2/DU4 是差分模式（0.009~0.06）不清残影。且 **scope 通道 FULL 位      │
> │    不生效** ⇒ 连 GC16 都只清"变化区域"；整屏清残影只有                   │
> │    `repaintEverything()`，而 launcher 仅在切档时调一次。                  │
> │                                                                          │
> │ ★ **§9.3.24（2026-09-25 第二会话）追加三条重要修正**：                    │
> │  · **双刷机制 A 证伪**：`Reg Enable` 在 frame 推进段【之后】(非其前)，       │
> │    因果方向相反；`pending_cnt=2` 是双缓冲正常基线、非堆积。                │
> │  · **§6 的 17 个 dt 属性【全部不存在】**（仅 `epdc-waveform-load-delay`）   │
> │    ⇒「改 dtb 开 `epdc-power-fail-dont-update`」路径不成立。               │
> │  · **FULL 位修正**：**scope 通道**不生效（旧结论对）；但                     │
> │    **`repaintEverything(值)` 带参通道 FULL 【生效】**。但全屏 GC16 在        │
> │    故障机**必然 reset**（`update[1]`→`wait all_lut_free` 超时，与 A2 同路）  │
> │    ⇒ 整屏清残影与避免 reset **物理不可兼得**，launcher 现状不应改动。        │
> │                                                                          │
> │ 已被推翻的中间论断（详见各节"更正"）：                                    │
> │  · "改重排队波形号/坐标可根治循环" → 作废（§9.3.18④/§9.3.19⑦）          │
> │  · "A2 是 reset 的产物" → 循环论证错误（§9.3.18⑤）                       │
> │  · "`appScopeRefreshMode` 可判档位" → 该读数恒为默认 2，不可信（§9.3.17③）│
> │  · "设 98/108 即可全屏清残影" → 不成立，FULL 位在 scope 通道无效（§9.3.21④）│
> └──────────────────────────────────────────────────────────────────────────┘

> 起因：故障机出现**新失效模式 —— 大面积变黑**（USB 投屏内容正常，仅面板黑）。

**★ 勘误（重要）：reset 后重排队有【两处】，§9/§十一 此前只记录了 0x5431a8-0x5431d4 一处。**

用 `bl get_waveform_mode_index`（0x550ab0）的全部 **28 个调用点**反查，前置 `movz w0,#2` 的只有两处：

| 偏移 | 所属路径 | 结构 |
|---|---|---|
| **0x542b0c** | `wait lut_free timeout`（reset @0x542aa4 之后） | 与 B 完全同型 |
| **0x5431ac** | `wait all_lut_free timeout`（reset @0x543168 之后） | §9 已记录 |

两者同型：`str xzr,[desc+0x10]` → `movz w0,#2` → 读 `[epdc+0xe0/0xe4]` 全屏尺寸 → `bl get_waveform_mode_index` → `stp w0,w1,[desc+0x20]`。
**只 patch 0x5431ac 无效**——另一条路径仍会重排队 GC16。

**v6 = v5 的 7 处 + 新增 2 处 = 9 处**：

| 偏移 | v5 值 | v6 值 | 说明 |
|---|---|---|---|
| 0x542B0C | `0x52800040` (`movz w0,#2` GC16) | **`0x52800020`** (`movz w0,#1` DU) | 重排队 A |
| 0x5431AC | `0x52800040` | **`0x52800020`** | 重排队 B |

产物 `boot_patched_du_v6.img`：16965632 B，md5 `43a4e312aa97e0fa6febd96f7ff00e87`，解压内核 md5 `d044fbced3a8025a883b1965f5d6b3cc`。
校验：与 v5 内核**逐字节仅差 2 处**（0x542b0c/0x5431ac）；header/ramdisk 与 v5 完全一致。

**机制依据（本次新挖）**：
- 差分基准 `[epdc+0x750]` 确实在 `onyx_get_waveform_one_frame_segment_16bit`（0x55da30）被读取：
  `ldrb w18,[x16]` 读像素 → `ldrb w18,[x9,x18]` 用像素值索引波形表 → `lsl #4`/`orr` 组装 → 写 LUT。
  **PIB 在软件层，不是硬件。**
- 该缓冲写入路径全查：仅 `onyx_epdc_fb_probe` 分配（0x53e4cc）+ `onyx_epdc_draw_mode0`(INIT) `memset 0xFF`（0x536494）。
  **正常刷新路径无软件写入** → 内容更新依赖硬件 DMA（波形完整执行后回写）。
- `onyx_epdc_reset` 本体与 `onyx_epdc_clear_all_upd` **均不触碰** `0x730`–`0x768` 整组缓冲（0x740/0x750 是 8bpp/4bpp 图像对，0x768 是双缓冲索引，在 `epdc_task_new` 里 `(n+1)%2` 翻转）。
- 故故障机波形永不完整 → `0x750` 停在旧值（或 INIT 全白）→ GC16 的"白→黑"是摆动式 38 帧，被打断即停在中间态 → **大面积黑**；
  DU 仅 22 帧 → 完成概率 **×1.73**（A2 10 帧则 ×3.8）。
- 波形数据佐证（tr8=24°C+ 段，解码 `eink_waveform.wbf`）：GC16 覆盖 **243/256** state、同色态也驱动 **22.9%**；DU 仅 42/256、2.3%；A2 18/256、0.9%。

**刷入前预期**：reset 循环仍在（powerup 仍失败），但每周期波形 38→22 帧，黑屏应显著缓解；
风险 = 残影（差分基准可能陈旧），但不会比现状（全黑）更差。验证点：`dmesg` 的 `waveform[N] update[1] frame_total[22]`（而非 38）。

**回滚**：`dd` 回 v5（`boot_patched_05s_v5.img`）或 v3。

#### 9.3.1 ★ 实测结果（2026-09-23 20:48 刷入，设备 6C7F0E64）

**刷入**：`dd if=/sdcard/boot_patched_du_v6.img of=/dev/block/by-name/boot_b`；读回前 16965632 B md5
`43a4e312aa97e0fa6febd96f7ff00e87` 一致；刷前备份 `/sdcard/boot_b_pre_v6.img`（=v5）。

**① 机制生效（决定性）**：`dump_lut_list` 波形分布从 v5 的 `waveform[2] update[1] frame_total[38]`
变为 **`waveform[1] update[1] frame_total[22]`** —— 重排队确实落到 DU 槽。两处 patch 都生效
（若只改 0x5431AC 则仍会出现 `waveform[2] update[1]/38`）。

**② v5 后期致命错误全部消失**（这是"波形真的在完成"的最强证据）：

| 错误 | v5 黑屏期 | v6 |
|---|---|---|
| `e_r_w_task(): waveform_desc is NULL` | 431 次 | **0** |
| `cant get free waveform buf from waveform_free_list` | 163 次 | **0** |
| `serious error! waveform_desc is NULL 5 times fail` | 142 次 | **0** |
| `dump_epdc_status` 的 `frame[cur:done:sent]` | `3346:3342:3346`（done 落后 4） | `1939:1938:1939`（**无积压**） |

机理解释：GC16 38 帧在临界供电窗口内几乎必然被 wait 超时打断 → 波形 buf 永不归还 → 池耗尽
（v5 后期 171/171 全占）→ 连重排队都做不了 → 从"间歇黑"恶化为"永久黑"。
DU 22 帧完成概率 ×1.73 → buf 正常归还，池不耗尽。

**③ reset 频率（34 次样本 / 跨度 568s）**：

| 指标 | v5 | v6 |
|---|---|---|
| reset 频率 | 0.1104 次/s | **0.0598 次/s** |
| 平均间隔 | 9.06s | 16.71s（**改善 1.84×**，理论预期 38/22=1.73×） |
| 间隔中位 / 最大 | 稳定 0.65–1.2s 循环 | **6.43s / 130.61s** |
| >10s 安静期 | 无（稳定循环） | **11 次，合计 454s = 80% 时间无 reset** |

**④ 压力测试**：10 次快速滑动（间隔 400ms）→ reset 增量仅 **3** 次（v5 时期每次滑动几乎必卡）。

**⑤ 未解决 / 待验证**：reset 循环本身仍在（`onyx_epdc_powerup(): epdc power error!` 24 次、
`TPS6518x waiting for power good` 44 次）—— 硬件供电故障未修复，v6 只是把每次失败循环的
"波形代价"从 38 帧降到 22 帧。**大面积黑是否真正缓解仍需目视确认**（日志只能证明波形完成率
提升，不能证明面板观感）；残影程度亦需目视评估。

**后续可选**：若 DU 22 帧仍不足，可改 `0x542B0C`/`0x5431AC` 为 `movz w0,#4`（A2，10 帧，完成率
再 ×2.2）；代价是无灰阶 + 驱动像素更少（18/256，差分残影风险更大）。改 `movz w0,#8`（DU4，24 帧）
则保留 4 级灰阶但帧数减幅小。**注意：三处立即数改动都只影响 reset 重排队，正常刷新路径不受影响。**

#### 9.3.2 ★★★ A2 实测：最终方案（2026-09-23 21:11 刷入，故障机黑屏已解决）

**改动**：`0x542B0C` / `0x5431AC` 由 `movz w0,#1`(DU) 再改为 `movz w0,#4`(A2)。
产物 `boot_patched_a2_v6.img`：boot md5 `c62600dd4bb3a1abcba0a0c0caaf75c6`，
内核 md5 `a2ed97ff8c5f64304cd784e9e3213180`（与 DU 版逐字节仅差那 2 处：`0x20`→`0x80`）。

**三代对比实测（设备 6C7F0E64）**：

| 指标 | v5 (GC16/38帧) | v6-DU (22帧) | **v6-A2 (5帧)** |
|---|---|---|---|
| reset 总数 | 213 次 / 1930s | 34 次 / 568s | **1 次 / 4min**（启动早期 22.5s 后完全停止） |
| reset 频率 | 0.1104 /s | 0.0598 /s | **≈0.004 /s** |
| wait timeout | 426 | 多次 | **2** |
| 压力测试（10 次滑动） | 几乎每次必卡 | reset +3 | **reset +0** |
| `waveform_desc is NULL` | 431 | 0 | **0** |
| 波形 buf 池耗尽 | 163 | 0 | **0** |
| `epdc power error`（硬件故障本身） | 35+ | 24 | **28（未变）** |
| 帧计数 | — | — | 10s +194（19.4 /s，系统持续刷新） |

**★ 目视确认（用户实测）：内容正常显示，黑屏问题解决。**

**机制（为什么 power error 仍在、reset 却消失）**：
dmesg 可见 `onyx_epdc_powerup(): epdc power error!` 后常跟
`onyx_epdc_powerdown(): epdc power error, clear!` —— **失败的更新在 powerup 阶段即被丢弃、
未进入 LUT → `all_lut_free` 恒真 → wait 立即返回 → 无 timeout、无 reset**。
硬件供电故障本身分毫未变（28 次 power error 与 DU 版 24 次同级）；变的是**波形短到能在
powerup 偶然成功的窗口内跑完并归还 waveform buf**，从而不再自我维持 reset 循环。

**★ 重要教训（修正 §9.3 的担忧，务必沿用）**：
A2 的 state 覆盖仅 **18/256**（DU 42/256、DU4 84/256、GC16 243/256），静态分析曾据此担心
"reset 重排队会画不全内容"。**实测证伪 —— 内容显示正常。**
推断原因：18 个 state 覆盖的正是实际渲染中真实发生的 (from,to) 转换（真实转换种类远少于
理论 256 种），故**覆盖数少 ≠ 画不全**。
⇒ **教训：wbf 的 state 覆盖数不能直接外推为"画质可用性"，必须真机目视验证。**
（这条同时说明：§9.3 里"DU 42/256 已只能处理一部分"的悲观解读也应同样打折看待。）

**待观察**：A2 为无灰阶差分模式，长期残影累积（尤其图片场景）尚未评估；若不可接受，
改 `movz w0,#8`(DU4，24 帧，4 级灰阶) 折中 —— 但会回退部分完成率优势。

**回滚 / 切换**（均需刷入后 `adb reboot`）：
- 回 v5：`dd if=/sdcard/boot_b_pre_v6.img of=/dev/block/by-name/boot_b`
- 回 DU：`dd if=/sdcard/boot_b_du_v6.img of=/dev/block/by-name/boot_b`（或刷仓库 `boot_patched_du_v6.img`）
- 切 DU4：`python _scratch_gs/patch_kernel_v6.py 8 && python _scratch_gs/rebuild_boot_v6.py ../boot_patched_du4_v6.img`
  （`patch_kernel_v6.py` 接受 mode 参数：1=DU / 4=A2 / 8=DU4）

#### 9.3.3 scope 档位对照实测 + "卡住后堆积"现象定性（2026-09-23 晚）

> 起因：用户观察"画面刷新卡住，多次刷新后一次堆积"。**用户指出这是既有问题（非 A2 引入）——
> 经核实，判断正确**：v5 期 dmesg 同样有 `dump_pending_list(): magic[...]` 连续多行堆积。

**① 堆积机制（★ 深夜修正：不是"既有问题"，而是 scope 档位差异）**

> ⚠️ **本节初稿的两处错误，务必以修正为准**：
> (a) 曾据 `grep -c "pending_list(): magic"` 的**累计数**（347/468 条）断言"系统性堆积" ——
>     **方法错误**，累计数被 reset 次数放大。按 reset 事件切块后真实分布：**中位 2 条、平均 3.18 条**
>     （A2 scope，147 次 reset），即"偶发少量排队"，非持续堆积。
> (b) 曾据 (a) 附和用户"这是既有问题" —— **错误**。经受控对比（下方 ⑥），这是 **scope 档位差异**。

**② 受控对比实测（2026-09-23 深夜，同机 6C7F0E64，相同操作序列）**

操作：10 次滑动（间隔 350ms）；延长测试：30 次滑动（间隔 250ms）+ 3 次返回键。

| scope 档 | 更新类型 | LUT 卡点 | reset | pending | frame_cur 推进 |
|---|---|---|---|---|---|
| **NORMAL（GC16 簇）** | **`update[0]` 局部** | **无卡住** | **0** | **0** | 推进到 **14/38** |
| A2 | `update[1]` 全屏 | `frame_cur[2]/5` 恒卡 | 持续 | 中位 2 条/次 | 恒 2/5 |
| DU | `update[0]`+`update[1]` 混合 | `frame_cur[2]/22` 恒卡 | 持续 | 有 | 恒 2/22 |

实测 LUT 输出对照：
```
NORMAL: waveform[2] update[0] frame_cur[14] frame_total[38]   ← GC16 局部, 正常推进
A2:     waveform[6] update[1] frame_cur[2]  frame_total[5]    ← A2 全屏, 恒卡
```

#### ★ 关键结论（推翻本节初稿"卡点在波形长度"的判断）

**分界不在波形帧数（38/22/5），而在 update 类型（局部 `update[0]` vs 全屏 `update[1]`）。**

对应内核两个等待点（§9 已记录）：
| 等待点 | 打印 | 门槛 | 对应更新 |
|---|---|---|---|
| `0x542A70` | `wait lut_free timeout`（无 %d） | 等**某个** LUT 空闲 | 局部（门槛松）|
| `0x543110` | `wait all_lut_free timeout %d ms!` | 等**所有** LUT 空闲 | **全屏（门槛严 → 易超时 → reset → 堆积）** |

dmesg 的 reset cause 恒为 `wait all_lut_free timeout`，与"全屏更新才超时"一致。
> "update[0] 只走 wait lut_free"为**推断**（两个等待点 ↔ 两类 update 的对应），未逐条验证反向路径；
> 但实测方向一致（GC16 局部 0 reset，A2/DU 全屏持续 reset）。

**③ 最优配置 = GC16 簇 scope + A2 内核重排队（两者职责不重叠）**
- **GC16 簇 scope**：日常更新局部化 → 不堆积、不 reset（消除黑屏的触发条件本身）
- **A2 内核重排队（v6 patch）**：万一真 reset，5 帧快速跑完 → 避免黑屏（保底）

> 佐证：今日黑屏发生时（白天）scope 为 **DU**（全屏更新 → reset 循环 → 重排队 GC16 → 黑屏）；
> 21:12–21:37 用户确认"黑屏解决"时 scope 为 **REGAL**（GC16 簇），与此一致。

**④ 切换法**：`test_mode.sh 1`（NORMAL）/ `4`（REGAL）等；**注意**设置面板切档才是
`applyWithEac`（写 EAC，有 §12.7 Watchdog 风险）；改 prefs + 重启 launcher 只走 `apply()`（仅 scope，安全）。
**EAC 写入为 save-only，需 OECService 重载（≈设备重启）才生效** —— 本次实测 NORMAL 切换后有
数分钟延迟才见效（22:03 切，22:07 仍是 A2 波形，之后才转 GC16）。

**⑤ 补充：失败更新的去向 v5 vs v6（形态变化）**

先明确"堆积"这一现象的原始机制（与档位无关的部分）：
```
更新提交 → powerup 尝试 → ┬ 失败 → 更新留在 pending_list（表现为"卡住"）
                          └ 成功 → 把积压的更新一起执行（表现为"一次刷出"）
```
frame 增量实测印证：`+218 / +49 / +102`（多次合并执行）与 `+22 / +22 / +22`（正好 DU 一轮）
交替；`frame[cur:done:sent]` **三值始终相等** → 内部一致，无丢失。

| | v5 (GC16 重排队) | v6 (A2 重排队) |
|---|---|---|
| 失败更新去向 | **多数被直接丢弃**（未入 LUT；§9.2：416 次 powergood 失败仅 4 次 reset） | **堆积保留** |
| 用户感受 | 内容**不显示** | 内容**延迟后显示** |
| `waveform_desc is NULL` | 431 | **0~1** |
| 波形 buf 池耗尽 | 163 | **0** |
| `serious error` | 142 | **0** |

即：v5 是"丢"，v6 是"攒着一起给"。

**⑥ 早期 A2/DU scope 对照（仅供参考，已被 ② 的 NORMAL 数据超越）**

| scope | reset 率 | LUT update 类型 | 主观体验 |
|---|---|---|---|
| DU(2) | 0.152/s（97/640s） | 混合：`update[0]` 局部 33 次 + `update[1]` 27 次 | 基准 |
| A2(3) | 0.275/s（73/265s，1.8×） | **全部 `update[1]` 全屏** 38 次 | 用户实测"差不多，区别不大" |

> ⚠️ 本节初稿曾据此推断"reset 率 ≈ 更新提交频率 × powerup 失败率"，并把"三者都卡在
> `frame_cur[2]`"解读为"卡点不在波形长度"。**② 的 NORMAL 实测（0 reset、frame_cur 推进到 14/38）
> 已给出更准确的解释：分界是 `update[0]` 局部 vs `update[1]` 全屏，而非波形长度。**
> 初稿的"推测"不作为结论保留。

**⑦ 待办 / 观察要点**
- **保持 NORMAL（或 REGAL）GC16 簇 scope + A2 内核重排队** —— 实测最优组合（② ③）
- 长期观察残影累积（GC16 簇含周期性清屏，残影风险低于 A2/DU 纯差分）
- 真根因仍是 TPS6518x 供电故障（v6 期间 `epdc power error` 仍 24~28 次，与 v5 同级）

#### 9.3.4 ★ 五个刷新模式的「局部 / 全屏」分类（2026-09-23 深夜实测矩阵）

**方法**：脚本 `mode_matrix.sh` + `lut_probe.sh`（设备 `/data/local/tmp/`）逐档切换
（`test_mode.sh <idx>` → 等 launcher `apply()` → 清 dmesg → `input swipe` 8~10 次 → 采集
`dump_lut_list` 的 `waveform[]/update[]/frame_cur/total` + reset/pending 计数）。
`test_mode.sh` 参数 = `MODE_NAMES` 下标（0=None 1=NORMAL 2=DU 3=A2 4=REGAL 5=X 6=REGAL_PLUS）。

| idx | 档位 | scope ui | 波形槽 | **update 类型** | 帧数 | frame_cur | reset | 分类 |
|---|---|---|---|---|---|---|---|---|
| 0 | None | 清 scope | （回落系统） | — | — | — | 0 | 🟢 局部族 |
| 1 | NORMAL | 清 scope | `waveform[2]` | **`update[0]` 局部** | 38 | 14/38 | **0** | 🟢 局部 |
| 2 | **DU** | 2305 | `[6]/5`+`[1]/22` | **混合**（见下） | 5/22 | 卡 2 | **11** | 🟡 混合 |
| 3 | **A2** | 2308 | `waveform[6]` | **`update[1]` 全屏** | 5 | 卡 2/5 | **12** | 🔴 全屏 |
| 4 | **REGAL** | 6 | `waveform[2]` | **`update[0]` 局部** | 38 | **36→37/38** | **0** | 🟢 局部 |
| 5 | **X** | 16777220 | `waveform[6]` | **`update[1]` 全屏** | 5 | 卡 2/5 | **12** | 🔴 全屏 |
| 6 | **REGAL_PLUS** | 9 | `waveform[2]` | **`update[0]` 局部** | 38 | 1/38 | **0** | 🟢 局部 |

**结论**：
- **局部刷新**：REGAL、REGAL_PLUS（+ NORMAL/None 清 scope 后回落此族）
- **全屏刷新**：A2、X
- **混合**：DU

**① 分界标准 = `update[0]` 局部 vs `update[1]` 全屏，不是帧数**（§9.3.3 结论的矩阵级复核）。

**② 最有说服力的对照 —— 同为 GC16 38 帧，结局相反**：
| 场景 | update | frame_cur | 结果 |
|---|---|---|---|
| REGAL scope（本次） | `update[0]` 局部 | **36→37/38 跑完** | 正常显示 |
| v5 全屏重排队（白天） | `update[1]` 全屏 | 卡 **1~2/38** | 大面积黑 |
⇒ 帧数相同、结果天差地别 ⇒ **"帧数少就不卡"的方向是错的**。

**③ X ≡ A2**：两者表现逐项相同（`waveform[6]`/5 帧全屏、reset 均 12 次）——与
「X = ONYX_AUTO + A2」定义吻合，Poke6 上 X 未体现额外能力。

**④ DU 是混合，且部分更新落 A2 槽**（实测 8 次滑动）：
```
5 × waveform[6] update[1] frame_total[5]     ← A2 全屏
3 × waveform[1] update[1] frame_total[22]    ← DU 全屏
3 × waveform[1] update[0] frame_total[22]    ← DU 局部
```
⇒ **DU scope 不保证所有更新走 DU**（与 §12.6「起点读书 scope=A2 下波形混合」同型问题）。

> 复现脚本：`_scratch_gs/mode_matrix.sh`（逐档全矩阵）、`_scratch_gs/lut_probe.sh`（对"无 dump"
> 档位主动 `cat dump_list` 抓 LUT）。两者用法相同：`adb push` 到 `/data/local/tmp/` 后
> `su -c "sh /data/local/tmp/<脚本>"`，结果写入设备端 `mode_matrix.txt` / `lut_probe.txt`。
> ⚠️ push 后必须 `sed -i 's/\r$//'` 去 Windows CRLF，否则 shell 报错。

#### 9.3.5 ★★★ 全部 18 个 UpdateMode 的局部/全屏全量分类（2026-09-23 深夜）

**背景**：§9.3.4 只测了 launcher 暴露的 7 档，而 §348/§513 记载 Onyx 共有 **18 个 UpdateMode**，
且 scope 通道**直接接受 int 值** —— 故可绕过 launcher 限制全量测试。

**方法**：`SetScope`（设备 `/data/local/tmp/sscope.dex`，源码 `_scratch_gs/SetScope.java`）直接设
任意 scope 值 → 滑动触发 → `cat /sys/class/sepdc/debug/dump_list` 主动抓活动 LUT。
脚本：`_scratch_gs/full_matrix.sh`（全量）、`_scratch_gs/verify_modes.sh`（严格验证版，
额外用 `status` 节点的 `frame[cur:done:sent]` 增长证明"确实有刷新"，排除"值没生效"的假阴性）。

**全量结果**（值来源 §513 SDMDevice 映射表；frame 每轮增长 ~228 帧，证明有真实刷新）：

| UpdateMode | 值 | 波形 | update | 帧数 | frame_cur | reset | 分类 |
|---|---|---|---|---|---|---|---|
| **GU** | 2 | `waveform[2]` | `update[0]` | 38 | 35/38 | 0 | 🟢 局部 |
| **GC4** | 3 | `waveform[2]` | `update[0]` | 38 | 22/38 | 0 | 🟢 局部 |
| **GC** | **98** | `waveform[2]` | `update[0]` | 38 | 35/38 | 0 | 🟢 局部 |
| **GCC** | **107** | `waveform[2]` | `update[0]` | 38 | 36/38 | 0 | 🟢 局部 |
| **DEEP_GC** | **108** | `waveform[2]` | `update[0]` | 38 | 21/38 | 0 | 🟢 局部 |
| **X_DU** | **16777217** | **`waveform[1]`** | `update[0]` | **22** | 18/22 | 0 | 🟢 **局部（DU 短帧）** |
| **REGAL_D** | 4102 | `waveform[2]` | `update[0]` | 38 | 35/38 | 0 | 🟢 局部 |
| **HW_REPAINT** | 524290 | `waveform[2]` | `update[0]` | 38 | 21/38 | 0 | 🟢 局部 |
| DEFAULT/None | 5 | `waveform[2]` | `update[0]` | 38 | 32/38 | 0 | 🟢 局部 |
| REGAL | 6 | `waveform[2]` | `update[0]` | 38 | 36→37/38 | 0 | 🟢 局部 |
| REGAL_PLUS | 9 | `waveform[2]` | `update[0]` | 38 | 1/38 | 0 | 🟢 局部 |
| DU（裸值）| 1 | `waveform[1]` | `update[0]` | 22 | 10/22 | 0 | 🟢 局部 |
| **DU4** | **2312** | `[6]/5`+`[7]/24`+`[1]/22` | 混合 | 5/24/22 | 卡 2 | 8 | 🟡 混合 |
| **DU_QUALITY** | 2305 | `[6]/5`+`[1]/22` | 混合 | 5/22 | 卡 2 | 11 | 🟡 混合 |
| **A2_QUALITY** | 2308 | `waveform[6]` | `update[1]` | 5 | 卡 2/5 | 12 | 🔴 全屏 |
| **A2_PERF**（ANIMATION）| 4 | `waveform[6]` | `update[1]` | 5 | 卡 2/5 | 12 | 🔴 全屏 |
| **ANIMATION_MONO** | 33554436 | `waveform[6]` | `update[1]` | 5 | 卡 2/5 | 12 | 🔴 全屏 |
| **ANIMATION_X**（X_A2）| 16777220 | `waveform[6]` | `update[1]` | 5 | 卡 2/5 | 12 | 🔴 全屏 |

**★ 核心规律**：
1. **含 A2/ANIM 语义的值 → 全屏（`update[1]`）→ 卡住 + reset**；
   其余（GC16 族 / DU 族 / REGAL 族）→ **局部（`update[0]`）→ 0 reset**。
2. **11 个未测模式中 9 个是纯局部**，且**清残影族（GC/GCC/DEEP_GC）全部是局部**
   —— 意味着**存在"清残影 + 不卡"的最优档位**（见下条）。
3. **★★ 非 GC16 的纯局部短帧模式有两个**（`waveform[1]`/22 帧局部）：**裸 `DU`(1)** 与
   `X_DU`(16777217)。对比 `DU_QUALITY`(2305) 是**混合**（含 A2 全屏）—— 三者都"是 DU"，但：
   | 值 | 构成 | 实测 |
   |---|---|---|
   | 1（DU 裸值）| `UI_DU_MODE` | 局部 22 ✅ |
   | **16777217** | `UI_X_DU_MODE` = DU\|ONYX_AUTO(0x1000000) | **局部 22 ✅** |
   | 2305 | `UI_DU_QUALITY_MODE` = DU\|**0x900** | **混合（含全屏）** ❌ |
   > ⚠️ **2026-09-23 后续修正（见 §9.3.10）**：`X_DU` 与裸 `DU` 实测波形**完全相同**，
   > `0x1000000` 位无可见收益 → **应选裸 `DU`(1)，不用 `X_DU`**。此处"唯一"措辞已修正。
4. **推断（证据不足以定论）**：`0x900` 质量标志位对 DU 引入全屏混合，但对 A2 无影响
   （4 与 2308 表现完全相同）。样本较小，勿当结论。

**★ 场景建议（按实测修订 §12.10）**：
| 需求 | 推荐值 | 理由 |
|---|---|---|
| 文字阅读、要短帧且不卡 | **1（裸 DU）** | 22 帧局部，实测 reset 0（~~16777217 已弃用，见 §9.3.10~~）|
| 要灰度 + 不卡 | **257（DU\|DITHER_MODE）** | GC16 38 帧局部，16 级灰，实测 reset 0（§9.3.7）|
| 清残影最彻底 | **108 (DEEP_GC)** | GC16 38 帧局部，实测 reset 0 |
| 手写/低延迟 | 2 (GU) | 局部 38 帧 |
| 需要跟手动画 | 2308/4/33554436/16777220 | 全屏 5 帧，**但会卡（reset 12）** —— 故障机慎用 |

**风险提示**：以上测试只改 scope（不动内核），`ClearScope` / `test_mode.sh` 即可复原；
但**全屏族（A2/X）在故障机会触发 reset 循环**，长时间使用不建议。

> 复现：`_scratch_gs/full_matrix.sh` + `_scratch_gs/verify_modes.sh`（后者含 frame 增长校验）。
> 设备端需 `sscope.dex`（`io.onyx.SetScope`）/ `cscope.dex`（`io.onyx.ClearScope`）。

#### 9.3.6 ★★★ EAC 配置域调查：能否扩展？子路径模式由谁决定（2026-09-23 深夜，源码级定论）

> 起因：用户问"能否修改 EAC 的配置域"，以便 EAC 也能表达 18 个模式。
> 结论：**字段域不能扩展，但发现了更重要的事实 —— 子路径的模式根本不由 EAC mode 决定，且有绕过 EAC 的直通 API。**

**① EAC mode 字段域：硬约束，不可扩展**（两重归一化，均为 framework 硬编码）

来源 `res/eink-framework/.../optimization/EACUtils.java:52`：
```java
public static int toEpdMode(int mode) {
    if (mode == 1) return 2305;
    if (mode == 2) return 2308;
    if (mode == 3) return 6;
    if (mode != 4) return mode != 5 ? 5 : 9;   // ★ 其余一切值 → 5 (UI_DEFAULT_MODE)
    return ViewUpdateHelper.UI_X_A2_MODE;
}
```
⇒ 往 EAC 的 `updateMode` 写 98/108/16777217 等**会被静默归一化为 5**。

来源 `Constant.java`（DEBOUNCER_UPDATE_MODE_MAP 静态初始化）：
```java
DEBOUNCER_UPDATE_MODE_MAP = new HashMap<Integer, Integer>() {{
    put(3, 0);   // REGAL      → 0
    put(5, 0);   // REGAL_PLUS → 0
    put(0, 0);   // NORMAL     → 0
}};
```
⇒ 白名单**只有 {0, 3, 5}**，且全部映射到 0。

**② ★ 关键：子路径的模式是恒定值，EAC mode 只起"开关"作用**

`EACUtils.applySFDebouncer`（:176，防抖 + 周期 GC）：
```java
if (!DEBOUNCER_UPDATE_MODE_MAP.containsKey(updateMode)) return;   // ∈{0,3,5} 才继续
int applyMode = DEBOUNCER_UPDATE_MODE_MAP.get(updateMode);        // 恒为 0
ViewUpdateHelper.debouncer(enableDebouncer, toEpdMode(applyMode), shortDelay, longDelay, gcInterval);
//                                          ↑ toEpdMode(0) → 5 (AUTO) —— 恒定!
```

`EACUtils.applyDebouncerTransientUpdateMode`（:186，滚动/触摸瞬态）：
```java
if (updateMode == 0 || !DEBOUNCER_UPDATE_MODE_MAP.containsKey(updateMode)) return;  // ∈{3,5} 才继续
ViewUpdateHelper.enableRegal(true);
ViewUpdateHelper.setDebouncerTransientUpdateMode(toEpdMode(updateMode));
```

⇒ **子路径（周期 GC / 防抖 / 瞬态）的波形固定为 `toEpdMode(0)` = 5 (AUTO)**，
与 EAC mode 的具体值无关；EAC mode 只决定**是否启用**：
| EAC mode | 防抖/周期 GC | 瞬态更新 |
|---|---|---|
| 0 (NORMAL) | ✅ 启用 | ❌（`updateMode==0` 被排除）|
| 1 (DU) | ❌ **关闭** | ❌ 关闭 |
| 2 (A2) | ❌ **关闭** | ❌ 关闭 |
| 3 (REGAL) | ✅ 启用 | ✅ 启用 |
| 4 (X) | ❌ **关闭** | ❌ 关闭 |
| 5 (REGAL_PLUS) | ✅ 启用 | ✅ 启用 |

**③ 副产品：AUTO(5) 在故障机上实测安全**（§9.3.5）
`5 (DEFAULT/AUTO)` → `waveform[2] update[0] frame_cur[32]/38`、**reset 0**（GC16 局部，清残影 + 不卡）
⇒ **EAC 定死为 0/3/5 任一即可**，周期 GC 会走这个安全模式。

**④ ★ 附带发现：launcher 现存缺陷 —— DU/A2/X 三档会关闭周期 GC**

launcher 写 EAC 用的是逻辑 mode（`MODE_VALUES = {-1,0,1,2,3,4,5}`），故：
| launcher 档 | EAC mode | 周期 GC / 防抖 |
|---|---|---|
| NORMAL | 0 | ✅ |
| **DU** | 1 | ❌ **关闭** |
| **A2** | 2 | ❌ **关闭** |
| REGAL | 3 | ✅ |
| **X** | 4 | ❌ **关闭** |
| REGAL_PLUS | 5 | ✅ |

⇒ **切到 DU/A2/X 时，周期 GC 完全停止 → 残影永久累积、无自动清理**（此前未发现）。

**⑤ ★ 绕过 EAC 的直通 API（"修改配置域"的可行路径）**

`ViewUpdateHelper` 的这些方法**都是 `public static`**，可反射 + root 调用，且**是裸 Parcel API、不经 toEpdMode**：

| 方法（ViewUpdateHelper.java 行号） | 签名 | 作用 |
|---|---|---|
| `debouncer` (:1372) | `(boolean enable, int mode, int shortDelay, int longDelay, int gcInterval)` | 防抖 + 周期 GC，`mode` 可为**任意 UI 值** |
| `setDebouncerTransientUpdateMode` (:1387) | `(int mode)` | 瞬态更新模式（滚动/触摸）|
| `applyTransientUpdate` (:1399) | `(int mode)` | 施加瞬态更新 |
| `enableRegal` (:573) | `(boolean e)` | Regal 开关 |
| `applyAppScopeUpdate` (:517) | `(String pkg, boolean enable, int clearFlag, int mode, int count)` | scope（已在用）|
| `clearAppScopeUpdate` (:527) | `(boolean clear)` | 清 scope（已在用）|

实现示例（`debouncer` 即直接写 Parcel → SurfaceFlinger）：
```java
public static void debouncer(boolean z, int i, int i2, int i3, int i4) {
    Parcel p = surfaceComposerData();
    p.writeInt(z ? 1 : 0); p.writeInt(i); p.writeInt(i2); p.writeInt(i3); p.writeInt(i4);
    transactData(DEBOUNCER, p, null);
}
```

⇒ **想让周期 GC / 瞬态用任意模式（如 DEEP_GC 108），直接调这些 API 即可，无需经过 EAC 域。**

**⑥ 结论与建议**
- **EAC 定死为 3 (REGAL)**（或 0/5）：保证防抖 + 周期 GC **启用**，且其模式恒为 AUTO = GC16 局部（清残影 + 不卡）；`3` 还能额外启用瞬态更新。
- **切档只改 scope**（不再批量写 EAC）→ 消除 §12.7 的 Watchdog 重启风险。
- 若要自定义子路径模式 → 用 ⑤ 的直通 API，不要试图扩展 EAC 域。
- **待验证**：周期 GC 的实际触发间隔 `gcInterval`（launcher 未写该字段，用系统默认）；以及 `debouncer(mode=108)` 是否真能让周期 GC 走 DEEP_GC（需真机实测）。

#### 9.3.7 ★★★ DU 的 dither 位机制 + launcher DU 档的正确修法（2026-09-23 深夜）

> 起因：用户追问"du 不是靠填参数改变的吗？裸 du 不会没灰阶吧？xdu 不是 du 变种吧？"
> —— **三个判断全部正确**，且由此发现 launcher DU 档（2305）的正确替代是 **257 / 2049**，而非先前误荐的 16777217。

**① 位标志语义（`ViewUpdateHelper.java:43-49,90`）**
```java
EINK_DITHER_MODE_DITHER = 256;    // 0x100
EINK_DITHER_COLOR_MASK  = 2048;   // 0x800
EINK_DITHER_COLOR_Y1    = 2048;   // 0x800
EINK_DITHER_X           = 16777216; // 0x1000000（与 EINK_ONYX_AUTO_MASK 同值）
UI_DU_QUALITY_MODE      = 2305;   // = DU(1) | 0x900 = DU | DITHER_MODE | DITHER_COLOR_Y1
```
- **裸 DU(1)** = 纯 2 级黑白，**无灰度**
- **X_DU(16777217)** = `DITHER_X(0x1000000) | DU`，**不含 0x900** → 同样**无灰度**
  ⇒ 用户"xdu 是 du 变种"判断正确（且我的旧建议 2305→16777217 会**牺牲灰度**，是错的）
- **DU_QUALITY(2305)** 的"质量"就是 **dither 位**

**② ★ 实测发现：dither 位会把波形从 DU 提升为 GC16**

| scope 值 | 构成 | 实测波形 | update | reset |
|---|---|---|---|---|
| 1 | 裸 DU | `waveform[1]`（DU，22 帧，2 级）| `update[0]` | 0 |
| **257** | DU \| 0x100 | **`waveform[2]`（GC16，38 帧）** | `update[0]` **局部** | **0** ✅ |
| **2049** | DU \| 0x800 | **`waveform[2]`（GC16，38 帧）** | `update[0]` **局部** | **0** ✅ |
| **2305** | DU \| 0x900 | `[6]`A2 全屏 + `[1]`DU 混合 | **`update[1]` 全屏** | **4~8** ❌ |
| 16777217 | DITHER_X \| DU | `waveform[1]`（DU，无灰度）| `update[0]` | 0 |

⇒ **dither 的实现方式 = 改用 GC16 波形**（GC16 有 16 级灰度能力）；
⇒ **单一位（0x100 或 0x800）→ 纯 GC16 局部（有灰度 + 不卡）**；
⇒ **0x900 组合 → A2/DU 全屏混合（有灰度但会卡）**。

**⚠️ 未完全解释**：为何 0x100/0x800 单独都 → GC16 局部，而 0x900 组合 → 全屏混合。
推测完整 dither（抖动模式 + 颜色模式齐备）会触发 SF 全屏重绘，**证据不足，待深挖**。

**③ 副产品：A2 全屏混合的来源 = `scrollingRefreshMode` 默认值**
```java
EACDeviceConfig.java:28:  private int scrollingRefreshMode = 2;   // ← 默认 A2!
EACDeviceExtraConfig.java:45,60: this.scrollingRefreshMode = 2;
```
⇒ 滚动/动画时系统用 A2（`waveform[6]` 全屏 5 帧）→ 这正是 §9.3.4/§9.3.5 中
"DU/DU4 出现 `[6]`/5 全屏混合"的来源，也解释了 §12.5 遗留的"滚动特判是否吃 scope"疑问方向。

**④ 副产品：dither 全局开关默认关闭**
```java
EACPaintConfig.java:17:  private boolean ditherBitmap = false;      // ← 默认 false
TabletEACRefreshImpl.java:137: boolean enable = activityConfig.getPaintConfig().isDitherBitmap();
                              if (!DEBOUNCER_UPDATE_MODE_MAP.containsKey(mode) || !appConfig.isEnable())
                                  enable = false;                    // ← mode ∉{0,3,5} 强制关
                              ViewUpdateHelper.enableDither(enable);
```
⇒ `enableDither` 默认不生效；launcher DU 档看到的灰度来自 **mode 参数里的 0x900 位**（另一套机制）。
⇒ **且 EAC mode ∉ {0,3,5} 时 dither 会被强制关闭** —— 与 §9.3.6 的周期 GC 门禁同一白名单。

**⑤ 结论：launcher DU 档的正确修法**
| 候选 | 灰度 | 速度 | 稳定性 | 评价 |
|---|---|---|---|---|
| 2305（现用）| ✅ | 快（DU 参与）| ❌ reset 4~8 | 应替换 |
| **257 / 2049** | ✅（GC16）| 中（38 帧）| ✅ reset 0 | **最优：保灰度 + 不卡** |
| 16777217 | ❌ | 快（22 帧）| ✅ reset 0 | 仅适合纯文字、不需要灰度的场景 |
| 1（裸 DU）| ❌ | 快 | ✅ | 同上，且无 X 位 |
> 若追求"短帧 + 有灰度"，需另找值（如 DU|0x100|适当位）；**短帧 + dither 的位组合未穷举**。

**⑥ 待验证**：257/2049 的**灰度观感**是否与 2305 相当（需目视）；以及"短帧+dither"的位组合能否两全。

#### 9.3.8 代码落地：scope-only 切档 + EAC 定死（2026-09-23 深夜，commit c9cf002）

**依据**：§9.3.4（局部/全屏分类）、§9.3.5（18 模式矩阵）、§9.3.6（EAC 域调查）、§9.3.7（dither 位）。

**改动**（`E-Ink-Launcher`，已 push 到 `v0.x`）：

1. **档位集改用 scope UI/EPD 值域，仅保留 `update[0]`（局部刷新）族**
   ```java
   private static final int[] SCOPE_VALUES = {-1, -1, 1, 2, 4, 2312};   // commit 2f4a674（最终）
   // 档位(6): None / NORMAL(清 scope) / DU(1) / GC16(2) / A2(4) / DU4(2312)
   ```
   **最终形态 = 4 个基础波形全暴露**（依据 §9.3.15/§9.3.16："Poke6 只有 4 种独立波形，
   全屏与局部通道都不会更多"）：
   | 档 | 值 | 波形 | 故障机（2026-09-24 实测复核） |
   |---|---|---|---|
   | DU | 1 | 22帧·1bpp 纯黑白 | ✅ `waveform[1]` `update[0]` 局部，reset 0 |
   | GC16 | 2 | 38帧·4bpp 16级灰 | ✅ `waveform[2]` `update[0]` 局部，reset 0 |
   | A2 | 4 | 5帧·单脉冲 | ❌ **`waveform[6]` `update[1]` 全屏，reset 8** |
   | DU4 | 2312 | 24帧·2bpp（理论） | ⚠️ **Poke6 未提供 → 实测回落 `waveform[2]` GC16** |

   > ⚠️ **本文档早期版本曾写"仅保留局部族、去除 A2/X"** —— 那是把 A2/DU4 当作"该删的"
   > 处理。**最终决定保留全部 4 档**：本 launcher 不止用于故障机，A2/DU4 在正常机是正常
   > 选项，标注差异后**由用户按设备状况自选**（"给予选择的权力"）。
   > 中途曾用 `{257, 1, 6, 9}`（DU/DU_RAW/REGAL/REGAL_PLUS）—— 该表**已作废**：257/6/9
   > 三者物理等价（同落 GC16），且未暴露 A2/DU4。
   - ⚠️ **索引迁移**：旧 7 档 → 新 6 档，**index ≥2 全部位移**，`prefs` 需重选。

   **★ 最终表实测复核（2026-09-24，故障机 6C7F0E64，纯波形号 6 轮滑动/档）**：

   | 值 | 档 | 实测波形 | update | reset | 判定 |
   |---|---|---|---|---|---|
   | 1 | DU | `waveform[1]`/22 | `update[0]` 局部 | **0** ✅ | 可用 |
   | 2 | GC16 | `waveform[2]`/38 | `update[0]` 局部 | **0** ✅ | 可用 |
   | 4 | A2 | `waveform[6]`/5 | **`update[1]` 全屏** | **8** ❌ | 故障机禁用 |
   | 8 | DU4(裸) | **`waveform[2]`（回落）** | `update[0]` 局部 | 0 | ⚠️ 等价 GC16 |
   | 2312 | DU4(UI值) | `[7]`/24+`[6]`/5+`[1]`/22 混合 | 混合 | 9 ⚠️ | ⚠️ 混合态 |
   | 6 | REAGL | `waveform[2]`（回落） | `update[0]` 局部 | 0 | ⚠️ 等价 GC16 |
   | 9 | REGAL_PLUS | `waveform[2]`（回落） | `update[0]` 局部 | 0 | ⚠️ 等价 GC16 |

   > **★ 更正（2026-09-24）**：早先记"DU4 = 混合态 reset 8"是**用 UI 值 2312** 测的；
   > **裸波形号 8 实测回落 `[2]` GC16** ⇒ Poke6 的 scope 通道**未提供 DU4**（印证 §9.3.14）。
   > ⇒ 结论：**4 个"基础波形"里 scope 通道真正生效的只有 DU / GC16**。
   - ⇒ 证实 **A2 在故障机必然触发全屏 reset**（与 §9.3.5 / §9.3.20 一致）。

   > ⚠️ **一次实验事故记录**：本表首次测试时遇 adb daemon 错乱 —— `adb devices` 误报当前设备为
   > 另一台机器（`345299d ruyi_global 2405CPX3DG`，无 sepdc 节点），导致全档位假阴性（全 `reset=0`）。
   > 已加 `getprop ro.product.model` + `dump_list` 存在性双重校验后重测（`basic4v2.sh`）。
   > ⇒ **教训：数据异常"全绿"时须先验证设备身份与节点存在性**，勿直接采信。
   > 另：脚本内 `dmesg` 会因 SELinux domain 差异失败（`klogctl: Permission denied`，返回空），
   > **多次造成假阴性**；统计须用逐条 `su -c "dmesg | ..."` 或先重定向到文件再 pull。

2. **切档只走 scope**：`applyWithEac(index)` 改为 `apply(index)` 的别名；删除
   `doApplyEacFallback` / `collectThirdPartyPkgs` / `parseCount` / `MODE_VALUES` /
   `LOGIC_TO_SCOPE` / `LM_*` 常量（共 −229 行）。
   ⇒ 消除"批量写 12 个 app → 窗口重建风暴 → system_server WTF → Watchdog 60s 重启"（§12.7）。

3. **EAC 定死**：新增 `applyFixedEac()`，一次性调 `official 3`（REGAL）。
   - 3 才启用周期 GC + 滚动瞬态（0 仅周期 GC；1/2/4 全关 —— 即旧 DU/A2/X 档会**关掉周期 GC**）
   - 其子路径模式恒为 `toEpdMode(0)`=AUTO=GC16 38帧局部，实测 reset 0
   - ⚠️ 它会顺手设 per-app scope，可能覆盖全局 scope → 调用方需在其后重新 `apply(int)`
   - ⚠️ **当前无调用点**（待接入口；EAC 为 save-only，需重启后由 OECService 重载耐久生效）

4. **per-app 长按菜单改用 scope 通道**：新增 `RefreshModeHelper.applyPerApp(pkg, index)`，
   `Launcher.applyPerAppRefreshMode` 委托之；删除 `buildPerAppThemeJson`。
   - ✅ 好处：scope 接受任意值，per-app 也能用 257 / 16777217（原走 EAC 域被 toEpdMode 归一化）
   - ⚠️ **代价：scope 是运行时状态，per-app 设置重启后丢失**（原先写 EAC 是持久的，无需重启即持久）

**验证**：本地 gradle 编译因**证书吊销检查失败**（受限网络，`CRYPT_E_NO_REVOCATION_CHECK`）无法执行；
已做静态自检：`{}` 平衡（0 差异，与改动前一致）、全部 6 个外部符号引用存在、无已删 API 残留、
未使用 import 已清理。**最终编译验证依赖 CI**。

**补充（commit 1f54de8）**：`applyFixedEac()` 已接入 **Launcher.onCreate** —— 恢复全局 scope 后，
由后台线程调用 `applyFixedEac()`，成功后重新 `apply(mode)` 一次（official 会顺带设 per-app
scope、覆盖全局 scope）。用后台线程是因为 su + app_process 较慢，不应阻塞启动。

**遗留（已决策）**：
- ~~`applyFixedEac()` 无调用点~~ → **已接入 Launcher.onCreate**（1f54de8）
- `PerAppRefreshHelper.java`：**保留**（仍可被手动 su 调用，是个可用的 EAC 写入工具）
- per-app 持久性退化（见 4.）：**用户已确认接受**（scope 是运行时状态，重启丢失）

#### 9.3.9 本轮初始诊断 + 排除项 + 事故记录（2026-09-23）

> 本节收录 §9.3 之前的诊断数据与**否定性结论**（避免后续接力者重复尝试）。

**① 大面积变黑的初始诊断（v5 期，2026-09-23 白天，设备 6C7F0E64）**

`dmesg` 全量分析（uptime 0–1930s，69490 行）：

| 现象 | 计数 | 说明 |
|---|---|---|
| `TPS6518x waiting for power good!` | 70（仅 170–424s） | 老毛病，后期不再出现 |
| `onyx_epdc_powerup(): epdc power error!` | 35 | |
| `wait all_lut_free timeout 500 ms!` | **426** | v5 patch 生效（日志与真实值一致） |
| `onyx_epdc_reset(): reset cause[...]` | **213** | 周期 ≈0.65s |
| `e_r_w_task(): waveform_desc is NULL` | **431**（252s 起） | ★ **v5 后期新失效模式** |
| `o_e_p_w_s(): cant get free waveform buf` | **163** | ★ 波形 buf 池耗尽 |
| `serious error! waveform_desc is NULL 5 times fail` | **142** | |

**关键时间线（`dump_lut_list` 的 `frame_cur/frame_total`）**：
```
245–415s: GC16 能推进 —— 15/38, 20/38, 17/38, 415s 最后一次 36/38
~424s 后: 恒为 frame_cur[1] frame_total[38]（2000s 后 117 次采样全是 1/38）
```
**与 2026-09-07 对比**：当时 frame_cur 可达 21~37/38（内容基本能显示，仅残影）；
本次仅 1~2/38 → **退化为大面积黑**。

**结论**：波形 buf 池被"永不完成的那帧"占满（实测 171/171 全占）→ 连重排队都无法执行
→ 从"间歇黑"恶化为"永久黑"。`waveform_desc is NULL` 是**结果而非原因**。

**② 免重启即时恢复手段 —— 全部无效（★ 排除项，勿重复尝试）**

| 手段 | 结果 |
|---|---|
| `cat /sys/class/sepdc/debug/panel_init` | 权限 `-r--r--r--`（**只读**），cat 返回 `ok`，dmesg 无新日志 → **状态显示函数，非动作**，重置 EPDC 无效 |
| `panel_clean` / `panel_last` | 同上（只读，返回 `ok`） |
| `echo 1 > debug/reset_test` | 权限 `--w-------`，写入后**无任何新日志、无行为变化** |
| 熄屏 → 唤醒（`input keyevent 26` ×2，确认 `Asleep`→`Awake`） | **reset 循环照旧**（frame 计数 3447→3534 持续增长）→ 唤醒不存在"供电 mini-window" |
| `TestWaveform 98` ×3 轮（tw.dex） | 仍卡 `frame_cur[1]/38` |

⇒ **免重启路线走到头，唯一即时手段是重启**（重新初始化 EPDC + 抢开机早期供电窗口）。

**③ 事故：切档触发 Watchdog 重启（21:37:59 案，完整证据链）**

时间线（毫秒级对齐，dropbox 时间戳换算）：
```
21:37:28  system_server_wtf 开始密集（重启前 2 分钟共 33 个）
21:37:58  apply: DU -> OK                      ← 用户在设置面板切档
21:37:59  applyWithEac: EAC fallback async start
21:38:01  eac-fallback: unify 12 pkgs to NONE+updateMode=1
21:38:03  batch output: ok=4 skip=0            ← 12 个 app 全部写入 → 窗口重建
   ↓ 71 秒（Watchdog 阈值 60s）
21:39:14  重启开始
21:39:19  system_server_crash: NullPointerException
            at RootWindowContainer.getTopDisplayFocusedStack() … startHomeActivity
            （栈中**无任何 epdc/eink 调用**，纯 framework）
21:39:29  am_wtf: [UserspaceRebootLogger, "Userspace reboot is not supported."]
            ← ★ Watchdog 触发的标准痕迹（Android 11 尝试 userspace reboot → 不支持 → 全量重启）
```

**排除内核改动嫌疑的三条证据**：
| 证据 | 结果 |
|---|---|
| `ro.boot.bootreason` | **`reboot`**（框架层主动重启；内核崩溃会是 `panic`） |
| `/sys/fs/pstore/` | **空**（ramoops 已注册但无崩溃记录写入） |
| `Kernel panic` / `Unable to handle kernel` / `Internal error: Oops` | **全部 0** |
| 反证 | 21:20–21:34 **整整 15 分钟零 WTF**（A2 内核 + 系统空闲）→ 若内核有问题不可能安静 15 分钟 |

**WTF 内容**（400 个 `system_server_wtf` + 29 `system_app_wtf`）全为 framework 层：
`PackageManagerService.executeSharedLibrariesUpdateLPr`（SharedLibraryInfo 告警）、
WMS `WindowToken` wakelock tag 校验不匹配（§12.7）。

⇒ **与内核 patch 无关**；根因是"切档批量写 EAC"这一既有设计（§12.7 早有记载，本次撞上）。
**规避**：用 `test_mode.sh`（改 prefs + 重启 launcher，只走 `apply()` scope）替代设置面板切档。
> ⚠️ 本次改动（§9.3.8）已从根上移除该路径：`applyWithEac` 不再写 EAC。

**④ 设备操作留痕（本轮改动过的状态，均已恢复）**
- `debug_level`：为验证 sysfs 写路径改为 3（**改前未先读原值，疏忽**），后恢复为 1（重启后系统默认 0）
- `reset_test` 写 1 → 已归 0；`/data/local/tmp/*.txt` 实验产物已清理
- `cut_frame_num` / `update_disable` 始终为默认 0（未动）
- 设备端保留的可复用脚本：`test_mode.sh`、`mode_matrix.sh`、`lut_probe.sh`、`verify_modes.sh`、
  `full_matrix.sh`、`sscope.dex`、`cscope.dex`、`tw.dex`

#### 9.3.10 ★★★ 模式值的位结构：为什么"很多模式是重复的"（2026-09-23，全值校验通过）

> 起因：用户问"这些模式好像很多是重复的，1/38 是什么意思"。经完整位分解推导，
> **18 个模式的 UI 值 = 基础波形号 | 标志位组合，实际只有 9 个基础波形号**；
> 再经设备驱动的波形号→槽映射塌缩，实测只剩 **5 种物理波形**。

**① UI 值 = 基础波形号 | 标志位（全部校验通过）**

位常量来源 `ViewUpdateHelper.java`（ref §43-62）。逐值分解（低 4 位 = 基础波形号）：

| UI 值 | 模式 | 分解 | 校验 |
|---|---|---|---|
| 1 | DU | `EINK_WAVEFORM_MODE_DU` | OK |
| 2 | GU | `EINK_WAVEFORM_MODE_GC16` | OK |
| 3 | GC4 | `EINK_WAVEFORM_MODE_GC4` | OK |
| 4 | ANIMATION | `EINK_WAVEFORM_MODE_ANIM` | OK |
| 5 | DEFAULT/AUTO | `EINK_WAVEFORM_MODE_AUTO` | OK |
| 6 | REGAL | `EINK_WAVEFORM_MODE_REAGL` | OK |
| 9 | REGAL_PLUS | `EINK_WAVEFORM_MODE_REAGL_PLUS` | OK |
| **98** | GC | `GC16(2) \| WAIT(64) \| FULL(32)` = 2+64+32 | OK |
| **107** | GCC | `GCC16(11) \| WAIT \| FULL` = 11+64+32 | OK |
| **108** | DEEP_GC | `DEEP_GC16(12) \| WAIT \| FULL` = 12+64+32 | OK |
| **2305** | DU_QUALITY | `DU(1) \| DITHER_MODE(256) \| DITHER_COLOR_Y1(2048)` | OK |
| **2308** | A2_QUALITY | `ANIM(4) \| DITHER_MODE \| DITHER_COLOR_Y1` | OK |
| **2312** | DU4 | `DU4(8) \| DITHER_MODE \| DITHER_COLOR_Y1` | OK |
| 4102 | REGAL_D | `REAGL(6) \| REAGL_D(4096)` | OK |
| 524290 | HW_REPAINT | `GC16(2) \| EPDC_FLAG_HANDWRITE_GU(524288)` | OK |
| 5242886 | REGAL_SHUTDOWN | `REAGL(6) \| FLAG_SHUTDOWN(5242880)` | OK |
| 5242978 | GC_SHUTDOWN | `GC16(2) \| WAIT(64) \| FULL(32) \| FLAG_SHUTDOWN(5242880)` | OK |
| **16777217** | X_DU | `DU(1) \| ONYX_AUTO/DITHER_X(16777216)` | OK |
| 16777220 | X_A2 | `ANIM(4) \| ONYX_AUTO/DITHER_X` | OK |
| 33554436 | MONO_A2 | `ANIM(4) \| ONYX_GC/APPLY_MONO(33554432)` | OK |

**①b 可用标志位全集**（`ViewUpdateHelper.java`，含 MASK 配对）

| 位值 | 常量 | MASK 常量 | 语义 |
|---|---|---|---|
| 1–15 | `EINK_WAVEFORM_MODE_*` | **`EINK_WAVEFORM_MODE_MASK = 15`** | ★ **低 4 位 = 基础波形号**（已证实） |
| 16 | `EINK_AUTO_MODE_AUTOMATIC` | `EINK_AUTO_MODE_MASK` | 自动模式=逐区域 |
| 32 | `EINK_UPDATE_MODE_FULL` | `EINK_UPDATE_MODE_MASK` | ★ 全屏；0=`PARTIAL` 局部 |
| 64 | `EINK_WAIT_MODE_WAIT` | `EINK_WAIT_MODE_MASK` | 等待完成；0=`NOWAIT` |
| 128 | `EINK_COMBINE_MODE_COMBINE` | `EINK_COMBINE_MODE_MASK` | 合并更新 |
| 256 | `EINK_DITHER_MODE_DITHER` | `EINK_DITHER_MODE_MASK` | ★ 抖动（`DU_QUALITY` 用） |
| 512 | `EINK_INVERT_MODE_INVERT` | `EINK_INVERT_MODE_MASK` | 反色 |
| 1024 | `EINK_CONVERT_MODE_CONVERT` | `EINK_CONVERT_MODE_MASK` | 颜色转换 |
| 2048 | `EINK_DITHER_COLOR_Y1` | `EINK_DITHER_COLOR_MASK` | ★ 抖动色阶 Y1（0=`Y4`） |
| 4096 | `EINK_REAGL_MODE_REAGLD` | — | Reagl-D 变体（`REGAL_D` 用） |
| 524288 | **`EPDC_FLAG_HANDWRITE_GU`** | — | 手写 GU 重绘（`HW_REPAINT` 用）★ 本次查明 |
| 2097152 | （未单独命名） | — | MERGE（见 `MERGE_UPDATE_MODE_BY_COUNT/TIMEOUT`） |
| 5242880 | `EINK_FLAG_SHUTDOWN` | — | 关机刷新（`*_SHUTDOWN_MODE` 用） |
| 16777216 | `EINK_ONYX_AUTO_MASK` = `EINK_DITHER_X` | `EINK_ONYX_AUTO_MASK` | Onyx 自动决策 / X 位 |
| 33554432 | `EINK_ONYX_GC_MASK` = `EINK_APPLY_MONO` | `EINK_ONYX_GC_MASK` | GC / 单色 |

**② 按基础波形号分组 —— "重复"的第一层来源**

| 基础波形号 | SDK 常量 | 同族 UI 值 |
|---|---|---|
| 1 | `DU` | DU(1)、**257**(DU\|DITHER_MODE)、**2049**(DU\|DITHER_COLOR_Y1)、DU_QUALITY(2305)、**X_DU(16777217)** |
| 2 | `GC16` | **GU(2)**、GC(98)、HW_REPAINT(524290) |
| 3 | `GC4` | GC4(3) |
| 4 | `ANIM` | ANIMATION(4)、A2_QUALITY(2308)、X_A2(16777220)、MONO_A2(33554436) |
| 5 | `AUTO` | DEFAULT(5) |
| 6 | `REAGL` | REGAL(6)、REGAL_D(4102)、REGAL_SHUTDOWN(5242886) |
| 8 | `DU4` | DU4(2312) |
| 9 | `REAGL_PLUS` | REGAL_PLUS(9) |
| 11 | `GCC16` | GCC(107) |
| 12 | `DEEP_GC16` | DEEP_GC(108) |

⇒ **18 个 UI 值只有 10 个基础波形号**，其余差异全在标志位（FULL/WAIT/DITHER/ONYX_AUTO/MONO…）。
⇒ ★ **反直觉点：`GU`(2) 的"2"不是"手写专用波形"，而是 `EINK_WAVEFORM_MODE_GC16`** —— GU 在 SDK 域就是 GC16 波形。

**③ "重复"的第二层来源：驱动层波形号 → sg 槽塌缩**

Poke6 的 sg 波形库把多个基础波形号映射到同一物理槽（ref §3292 已记"`waveform[]` 编号是 sg 库内部枚举，
≠ SDK 常量"；§7.4 已证 REGAL 全刷实际落 GC16）：

| SDK 基础波形号 | sg 库物理槽 | 实测 frame_total |
|---|---|---|
| 1 `DU` | `waveform[1]` | 22 |
| 2 `GC16` | `waveform[2]` | 38 |
| **6 `REAGL` / 9 `REAGL_PLUS`** | **`waveform[4]`**（受控验证，见 §9.3.11）| 38 |
| 11 `GCC16` / 12 `DEEP_GC16` | `waveform[2]` | 38 |
| 4 `ANIM` | `waveform[6]` | 5~10 |
| 8 `DU4` | `waveform[7]` | 24 |

⇒ **两层叠加后，18 个 SDK 模式实测落到 5 个 sg 槽**：
`waveform[1]/22`(DU 族)、`waveform[2]/38`(GC16/GCC/DEEP_GC 族)、**`waveform[4]/38`(REGAL 族)**、
`waveform[6]/5~10`(A2 族)、`waveform[7]/24`(DU4)。

> ⚠️ **2026-09-23 深夜重大修正（详见 §9.3.11）**：本表原写"`2/6/9/11/12` → 全部 `waveform[2]`"，
> 那是用**主动 `cat dump_list`** 抓的，抓到的是**背景 GC16 刷新**而非被测值对应的刷新 —— **方法错误**。
> 受控验证（`TestWaveform repaintEverything(值)`）证实 **REGAL(6)/REGAL_PLUS(9) 实际落 `waveform[4]`**。
> 换言之：**Poke6 的 wbf 里没有独立 REAGL 波形，`mode4` 列是 GC16 的副本**，故 REGAL 表现为 GC16 ——
> 这正是 §7.4「REGAL 全刷实际是 GC16 38帧」的**根因**（此前只知现象，不知原因）。

**④ 「1/38」的含义 —— frame_cur / frame_total，★ 不能用来比较完成度**

`dump_lut_list` 的字段：
- `frame_total` = 该波形**需要执行的完整帧数**（GC16=38、DU=22、A2=5、DU4=24；由 wbf 温度段决定）
- `frame_cur` = **当前已执行到第几帧**（正常跑完会推进到 frame_total）

**★ 关键：该 dump 只在 reset 触发时打印**（异常时刻），所以它记录的是"卡住瞬间"的快照。
`1/38` = 波形才刚执行第 1 帧就被打断 —— 但这**反映的是 dump 抓取时机，不是该模式的完成能力**。

反证（同一物理槽、同一配置）：
| 值 | frame_cur | 说明 |
|---|---|---|
| REGAL(6) | **36→37/38** | 恰好抓到接近完成 |
| REGAL_PLUS(9) | **1/38** | 恰好抓到刚开始 |

两者基础波形号不同（6/9）但**都落 `waveform[2]`/38帧**，frame_cur 的差异纯属抓取时机。
⇒ **勿据 frame_cur 判断"哪个模式跑得完"**；判断完成度应看 `reset` 计数与 `pending` 堆积（§9.3.3/9.3.4）。

**⑤ 用户判断核实：`X_DU` 不如裸 `DU`（★ 成立）**

推导证实 `X_DU(16777217) = DU(1) | ONYX_AUTO/DITHER_X(0x1000000)` —— **基础波形号同样是 1（DU）**，
即"在 DU 之上叠了一个位"（该位同时是 `EINK_DITHER_X` 与 `EINK_ONYX_AUTO_MASK`）。

| 值 | 构成 | 灰度 | 实测波形 | 结论 |
|---|---|---|---|---|
| 1（裸 DU）| `DU` | ❌ 2 级 | `waveform[1]`/22帧/`update[0]` | ✅ 最简 |
| 16777217（X_DU）| `DU \| 0x1000000` | ❌ 2 级 | `waveform[1]`/22帧/`update[0]` | ❌ **无增量收益** |
| 257 | `DU \| 0x100` | ✅ 16 级 | `waveform[2]`/38帧/`update[0]` | ✅ 有灰度 |

⇒ 实测两者波形**完全相同**，X_DU 多出的 `0x1000000` 在 Poke6 上无可见收益
（该位语义为 ONYX_AUTO，正常机可能有自动决策作用，故障机未体现）→ **档位表应去掉 X_DU，只留裸 DU(1)**。

# 十、Poke6 波形帧数调研 + wbf 逆向（2026-09-05，核心已完成；新接力点见本章末）

> 📌 **章节导航**（本组小节曾多次追加致编号乱序，内容完整，按下列顺序阅读）：
> - **1. 关键结论**（原始实测：REGAL=5/DU=22/GC16=38 —— ⚠️ REGAL=5 已证伪，见 7.4/9 节）
> - **2. v4 内核教训**　→　**3. wbf 逆向进展**（早期）
> - **8. ★ wbf 静态逆向突破**（unicorn 模拟 0x552930，8×14 帧数矩阵）
> - **4. 工具**　→　**5. 设备状态**　→　**6. 接力记录**（故障机重启实测×2）
> - **7. ★ 正常机实测**（sg 库 10/20/24/38、REGAL 复核、两机 wbf 相同修正）
> - **9. ★ 全模式耗时复测**（wait 假象、5帧=A2 系、精确时长 9.6、flag 语义 9.7、灰阶分档 9.8）
> - **10. 接力者起点**（文件末尾，2026-09-06 交接）

#### 9.3.11 ★★★ 四层编号系统解构 + 12 个波形模式的物理区别（2026-09-23 深夜）

> 起因：用户问"这 12 个基础波形号对应的 SDK 常量？它们的区别？"
> 结论：**有 4 套互不相同的编号系统**，且**两处发生冲突/缺失** —— 这才是"很多模式重复"的真正根源。

**① 四层编号系统（务必区分，勿混用）**

| 层 | 来源 | 取值数 | 取值 |
|---|---|---|---|
| **L1 wbf 标准表** | E Ink / inkwave `main.c:44-61` | **12** | `0x0`–`0xB` |
| **L2 SDK 常量** | `ViewUpdateHelper.EINK_WAVEFORM_MODE_*` | **11** | 0–12（**缺 7、10**）|
| **L3 Poke6 wbf 实际列** | `eink_waveform.wbf`（`mc=7`）| **8 列** | `mode0`–`mode7` |
| **L4 sg 驱动槽** | `dump_lut_list` 的 `waveform[N]` | 实测 5 个 | `[1] [2] [4] [6] [7]` |

**② L1 wbf 标准表：12 个 mode 及其物理定义**（inkwave 官方描述，★ 最有信息量）

| mode | 名称 | 官方描述（物理差异核心） | bpp |
|---|---|---|---|
| 0x0 | INIT | panel 初始化 / **清屏到白** | — |
| 0x1 | **DU** | 直达更新，**灰→黑/白**转换 | **1bpp** |
| 0x2 | **GC16** | 高保真，**闪烁(flashing)** | 4bpp |
| 0x3 | GC16_FAST | 中等保真 | 4bpp |
| 0x4 | A2 | 动画更新，**最快、保真最低** | — |
| 0x5 | GL16 | 高保真，**从白转换** | 4bpp |
| 0x6 | GL16_FAST | 中等保真，**从白转换** | 4bpp |
| 0x7 | **DU4** | 直达更新，中等保真，**文本→文本** | **2bpp** |
| 0x8 | **REAGL** | **不闪烁(non-flashing)**，残影补偿 | — |
| 0x9 | REAGLD | 不闪烁，残影补偿**+抖动** | — |
| 0xA | GL4 | **从白转换** | 2bpp |
| 0xB | GL16_INV | 高保真，**用于黑转换** | 4bpp |

**三个物理维度**：`bpp`（灰阶能力 1/2/4bit）、`是否闪烁`、`转换方向`（全范围 / 从白 / 从黑）。

**③ L2 SDK 常量：11 个，且 ★ 从值 3 起与 L1 完全错位**

| SDK 值 | SDK 常量 | L1 同编号的标准名 | 是否一致 |
|---|---|---|---|
| 0 | `EINK_WAVEFORM_MODE_INIT` | INIT | ✅ |
| 1 | `EINK_WAVEFORM_MODE_DU` | DU | ✅ |
| 2 | `EINK_WAVEFORM_MODE_GC16` | GC16 | ✅ |
| **3** | `EINK_WAVEFORM_MODE_GC4` | GC16_FAST | ❌ **冲突** |
| 4 | `EINK_WAVEFORM_MODE_ANIM` | A2 | ✅（ANIM≡A2）|
| **5** | `EINK_WAVEFORM_MODE_AUTO` | GL16 | ❌ **冲突** |
| **6** | `EINK_WAVEFORM_MODE_REAGL` | GL16_FAST | ❌ **冲突** |
| **7** | —（**缺**） | DU4 | ❌ **SDK 无此值** |
| **8** | `EINK_WAVEFORM_MODE_DU4` | REAGL | ❌ **冲突** |
| **9** | `EINK_WAVEFORM_MODE_REAGL_PLUS` | REAGLD | ⚠️ 近似 |
| **10** | —（**缺**） | GL4 | ❌ **SDK 无此值** |
| **11** | `EINK_WAVEFORM_MODE_GCC16` | GL16_INV | ❌ **冲突** |
| 12 | `EINK_WAVEFORM_MODE_DEEP_GC16` | —（L1 无）| — |
| — | `EINK_WAVEFORM_MODE_MASK = 15` | — | 低 4 位取波形号 |

⇒ **SDK 的 `EINK_WAVEFORM_MODE_*` 是 Onyx 自定义枚举，不是 L1 标准编号。**
⇒ 同一数字（如 6）在两层含义完全不同（SDK=REAGL / L1=GL16_FAST）—— 这是混淆主要来源。

**④ L3 Poke6 wbf 实际列 + ★ 厂商冗余实测**

| mode | L1 标准名 | 帧数(tr8) | state 覆盖 | 驱动率 | **同色态驱动率** | 段长 |
|---|---|---|---|---|---|---|
| 0 | INIT | 113 | 193/256 | 0.743 | **0.750** | 244 |
| 1 | DU | 22 | 42/256 | 0.026 | 0.023 | 338 |
| 2 | GC16 | 39 | 243/256 | 0.219 | 0.229 | 4705 |
| **3** | GC16_FAST | 39 | 244/256 | 0.221 | 0.240 | 4802 |
| **4** | A2 | 39 | 244/256 | 0.221 | 0.240 | 4802 |
| **5** | GL16 | 39 | 244/256 | 0.221 | 0.240 | 4802 |
| 6 | GL16_FAST | **10** | 18/256 | 0.004 | 0.009 | 94 |
| 7 | DU4 | 24 | 84/256 | 0.056 | 0.062 | 957 |

> ⚠️ 上表「帧数」是 **wbf 逆向值 `P`**（每 state 的 packed byte 数），**不等于**设备实测的
> `frame_total`：mode2 逆向 39 vs 设备 **38**；mode6 逆向 10 vs 设备 **5**（偏差原因未明）。
> 需实际帧数请用 §9.3.12 的设备实测表。

**★★ 实测：`mode3 == mode4 == mode5` 波形数据逐字节完全相同**（RLE 解码后比对）。
⇒ 厂商生成工具把 **GC16 的波形复制给了 GC16_FAST / A2 / GL16 三列** —— **文件冗余**，不是三种不同波形。

**★★ Poke6 的 wbf 只放了 8 列（0–7），L1 标准表的 `mode8 REAGL` / `mode9 REAGLD` /
`modeA GL4` / `modeB GL16_INV` 四列【不存在】。**

⇒ **这解释了 §7.4 的悬案**：为什么"REGAL 全刷实际是 GC16 38帧"？
因为 **Poke6 根本没有独立 REAGL 波形**，SDK 的 `REAGL(6)` 只能回落到现有列
—— 受控验证证实它落 **`waveform[4]` = wbf `mode4` = GC16 副本**（38 帧、同色态驱动 0.240）。
⇒ 同理 `REAGL_PLUS(9)` 也落 `waveform[4]`，故两者实测表现一致（§12.10 已记录"Poke6 上等价"）。

**⑤ 深挖：物理区别的两个量化维度（同色态驱动率是"闪烁"的量度）**

「同色态驱动率」= 对 `from == to`（**像素颜色没变**）的 state，波形仍强行驱动的比例。
- **高**（INIT 0.750、GC16 族 0.23–0.24）→ 颜色不变也要摆动 → **这就是"闪烁"**
- **低**（DU 0.023、A2 0.009、DU4 0.062）→ 只驱动真正变化的像素 → 不闪

| 特征 | INIT | DU | GC16 族(mode2-5) | A2(mode6) | DU4(mode7) |
|---|---|---|---|---|---|
| 帧数(tr8) | 113 | 22 | 39 | 10 | 24 |
| state 覆盖 | 193/256 | 42/256 | 243~244/256 | 18/256 | 84/256 |
| 驱动率 | 0.743 | 0.026 | 0.219~0.221 | 0.004 | 0.056 |
| **同色态驱动（闪烁度）** | 0.750 | 0.023 | **0.229~0.240** | 0.009 | 0.062 |
| bpp（L1 定义） | — | **1** | **4** | — | **2** |

⇒ 三者关系清晰：**bpp 越高 → state 覆盖越广 → 同色态驱动越多（越闪）**。
⇒ GC16 的"高保真"代价就是"全屏闪烁 + 38 帧"；DU 的"低 bpp"换来"22 帧不闪但无灰度"。

**⑥ ★ 方法论修正（重要）**

§9.3.5 用"主动 `cat /sys/class/sepdc/debug/dump_list`"抓 SDK值→槽映射，**结论有误**：
主动抓会抓到**背景刷新**（launcher/系统自身的 GC16 局部更新），而非被测值对应的刷新
—— 故当时得出"8 个基础波形号全部落 `waveform[2]`"的错误结论。

**正确方法**：用 `TestWaveform <值>` 的 `repaintEverything(值)` **受控触发** + 密集抓取。
本次验证结果（`_scratch_gs/sdk_slot2.sh`）：
| 触发值 | 抓到的槽 |
|---|---|
| **2 (GU)** | `waveform[2] update[0]` **38 帧（干净单槽）** |
| **6 (REGAL)** | **`waveform[4]`** + `[6]` + `[1]`（后两者为背景）|
| **9 (REGAL_PLUS)** | **`waveform[4]`** + 背景 |
| 98/107/108 | `[2]`/`[6]`/`[1]` 混合（背景干扰明显）|

⇒ **REGAL 落 `waveform[4]` 已确证**；其余因背景干扰未能完全分离，**需更干净的方法**（如停掉 launcher
或选无背景刷新的场景）—— 标注为**待精确化**，勿再简单引用 §9.3.5 的映射结论。

#### 9.3.12 ★★★ Poke6 波形库 12 列全表（12 个 wbf mode 的实测数据汇总）

> 12 列 = L1 wbf 标准表的全部 mode（0x0–0xB）。**Poke6 只提供了其中 8 列（mode0–7）**，
> REAGL / REAGLD / GL4 / GL16_INV **四列缺失**。
> 帧数用**设备实测**（`waveform[N]` 的 `frame_total`）；wbf 逆向的 `P` 值与设备实测存在
> 未解释偏差（mode2: 39 vs 38、mode6: 10 vs 5），故一律以设备值为准。

| wbf mode | L1 标准名 | Poke6 提供 | 帧数 | state 覆盖 | 驱动率 | **同色态驱动**(闪烁度) | bpp(L1) | ★ 该列实际装的内容 | 被哪个波形号使用 |
|---|---|---|---|---|---|---|---|---|---|
| **0x0** | INIT | ✅ | 113* | 193/256 | 0.743 | **0.750** | — | 初始化 / 清屏→白 | 0 |
| **0x1** | DU | ✅ | **22** | 42/256 | 0.026 | 0.023 | 1 | DU（同标准）| 1 |
| **0x2** | GC16 | ✅ | **38** | 243/256 | 0.219 | 0.229 | 4 | GC16（同标准）| 2、**11/12（回落）** |
| **0x3** | GC16_FAST | ✅ | 38 | 244/256 | 0.221 | 0.240 | 4 | ⚠️ **GC16 副本** | 未使用 |
| **0x4** | **A2** | ✅ | 38 | 244/256 | 0.221 | 0.240 | — | ⚠️ **GC16 副本**（名不符实）| **6、9（REAGL 族回落到此）** |
| **0x5** | GL16 | ✅ | 38 | 244/256 | 0.221 | 0.240 | 4 | ⚠️ **GC16 副本** | 未使用 |
| **0x6** | **GL16_FAST** | ✅ | **5** | 18/256 | 0.004 | 0.009 | 4 | ⚠️ **A2 式快波形**（名不符实）| **4（ANIM/A2 族）** |
| **0x7** | DU4 | ✅ | **24** | 84/256 | 0.056 | 0.062 | 2 | DU4（同标准）| 8 |
| **0x8** | **REAGL** | ❌ **缺失** | — | — | — | — | — | — | — |
| **0x9** | **REAGLD** | ❌ **缺失** | — | — | — | — | — | — | — |
| **0xA** | **GL4** | ❌ **缺失** | — | — | — | — | 2 | — | — |
| **0xB** | **GL16_INV** | ❌ **缺失** | — | — | — | — | 4 | — | — |

\* mode0 用 wbf 逆向值（INIT 不通过正常刷新路径，无法设备实测）。
mode3/4/5 与 mode2 同族（数据几乎相同），故帧数取 38。

**① 波形号（低 4 位）→ sg 槽 实测映射表**（★ sg 槽号 = wbf mode 号，已由帧数吻合确证）

| 波形号 | 含义 | → sg 槽 | = wbf 列 | 该列实际内容 |
|---|---|---|---|---|
| 1 | DU | 1 | mode1 DU | DU ✅ |
| 2 | GC16 | 2 | mode2 GC16 | GC16 ✅ |
| **4** | ANIM / A2 | **6** | mode6 GL16_FAST | **A2 式快波形** |
| **6** | REAGL | **4** | mode4 A2 | **GC16 副本** |
| 8 | DU4 | 7 | mode7 DU4 | DU4 ✅ |
| **9** | REAGL_PLUS | **4** | mode4 A2 | **GC16 副本** |

⇒ ★★ **厂商把 wbf 列内容重排了**：`mode4`（标准名 A2）装的是 **GC16 数据**，
`mode6`（标准名 GL16_FAST）装的是 **A2 数据** —— 两张列的名字与实际内容**错位**。

**② ★★★ 由此解开两个历史悬案**

| 悬案 | 答案 |
|---|---|
| **§7.4**「REGAL 全刷实际是 GC16 38 帧」| 因为 `REAGL(波形号6)` → 槽4 → **该列装的是 GC16 数据** ⇒ 表现为 GC16。不是"REGAL 没有独立短波形"，而是**它的目标列被填成了 GC16 副本** |
| **§12.10**「REGAL_PLUS 与 REGAL 在 Poke6 上等价」| 两者都 → 槽4，**同一份数据**，必然等价 |

**③ ★★★ 三条实用结论**

1. **Poke6 完全没有 REAGL 族（不闪烁波形）** —— `mode8/9` 缺失，`mode4/5` 被 GC16 副本占据。
   ⇒ **"不闪烁 + 有灰度"在 Poke6 上物理不可得**（要灰度只能用 GC16 族，必然闪烁 0.229~0.240）。
   这是**波形库限制**，非软件可调。
2. **`GCC16(11)` / `DEEP_GC16(12)` 的波形号超出 wbf 列范围（>7）→ 回落**。
   实测显示它们落 `waveform[2]`（GC16）⇒ **"压缩 GC"与"深度 GC"在 Poke6 上等同 GC16，无独立效果**。
3. **可用物理波形实为 5 种**：`mode1`(DU 22帧)、`mode2`(GC16 38帧)、`mode4`(GC16 副 38帧)、
   `mode6`(A2 5帧)、`mode7`(DU4 24帧)，其中 `mode2` 与 `mode4` **同为 GC16**。

**④ 三个物理维度的量化（回答"这 12 个的区别"）**

| 维度 | 表现 |
|---|---|
| **bpp / 灰阶** | 1bpp(DU) → 2bpp(DU4) → 4bpp(GC16 族)；bpp 越高灰阶越多 |
| **是否闪烁** | 用「同色态驱动率」量化：GC16 族 **0.229~0.240**（颜色不变也摆动 23%）/ DU 0.023 / A2 0.009 |
| **覆盖广度** | GC16 族 243~244/256（几乎全组合）/ DU 42 / DU4 84 / A2 18 |

⇒ 三者单调相关：**bpp↑ → 覆盖↑ → 同色态驱动↑ → 越闪、帧数越多**。
⇒ 这是 E Ink 物理必然：**要更多灰阶就得全屏摆动**，不存在"高灰阶 + 不闪"的波形。

#### 9.3.13 ★★★ 局部刷新族最终分类：只有 2 种物理波形（2026-09-23，回答"按簇不重复的有几个"）

> 用户要的极简结论：**局部刷新族 15 个值，实测只有 2 种不同表现。**

**① 基础波形号 → 物理波形（实测）**

| 基础波形号 | 名称 | 实测落槽 | 帧数 | 结论 |
|---|---|---|---|---|
| **1** | DU | `waveform[1]` | **22** | ✅ DU |
| **2** | GC16 | `waveform[2]` | **38** | ✅ GC16 |
| **3** | GC4 | `waveform[2]` | 38 | → 归 GC16 |
| **5** | AUTO | `waveform[2]` | 38 | → 归 GC16 |
| **6** | REAGL | `waveform[4]` | 38 | → mode4 是 GC16 副本 ⇒ 归 GC16 |
| **9** | REAGL_PLUS | `waveform[4]` | 38 | → 同上 |
| **11** | GCC16 | `waveform[2]` | 38 | → 归 GC16 |
| **12** | DEEP_GC16 | `waveform[2]` | 38 | → 归 GC16 |
| **8** | DU4 | **`waveform[2]`**（★ 本次实测）| **38** | ⚠️ **不是 DU4 波形！** → 归 GC16 |

★ **裸值 `8` 实测落 `waveform[2]`/38帧**（3 次滑动，reset 0），**而非 `waveform[7]`/24帧** ——
即 scope 通道下"DU4"拿不到 4 级灰波形，也退化为 GC16。仅 `DU4_QUALITY(2312)` 能触发 `[7]/24`，
但那是**混合态**（§9.3.5：`[6]/5`+`[7]/24`+`[1]/22`，reset 8）。

**② 极简结论**

| 物理波形 | 帧数 | 灰阶 | 闪烁(同色态驱动) | 覆盖 | 包含的值 |
|---|---|---|---|---|---|
| **DU** | **22** | 1bpp 纯黑白 | 0.023（几乎不闪）| 42/256 | **1**、16777217 |
| **GC16** | **38** | 4bpp 16 级 | 0.229（闪烁）| 243/256 | **其余全部**（2,3,5,6,9,11,12,8,98,107,108,257,4102,524290…）|

⇒ **局部刷新族经四层塌缩后，物理上只有 2 种波形**：DU(22帧/黑白) 与 GC16(38帧/16级灰)。
⇒ 这正是"很多模式重复"的最终答案 —— 15 个 UI 值、8 个波形号，**实际只有 2 种表现**。

**③ 对"扩展全局刷新选项"的直接含义**

| 现状问题 | 说明 |
|---|---|
| 档位表里 `DU(257)` / `REGAL(6)` / `REGAL_PLUS(9)` **三者物理等价** | 都落 GC16 38帧（mode2 或 mode4，数据同为 GC16 副本）|
| 真正不同的只有 `DU_RAW(1)` | 22帧纯黑白 |

⇒ **能扩展的物理波形只有 2 种**，扩充档位不会再增加新表现。
⇒ **唯一可能挖出的新档 = DU4 的 4 级灰 24 帧**（`waveform[7]`），但当前已知值下
（裸 8 → GC16；2312 → 混合）**尚未找到纯局部触发方式**，标为**待验证**。

#### 9.3.14 DU4 纯局部值挖掘：结论「不可得」（2026-09-23，已穷举验证）

> 承接 §9.3.13：用户要求找能否扩展出 DU4（4 级灰 24 帧）。**已穷举，结论为不可得。**

**① 试过的路径（三组实验）**

| 实验 | 方法 | 结果 |
|---|---|---|
| **A. 标志位组合穷举** | `TestWaveform repaintEverything(值)` × 10 个值 | 见下表 |
| **B. 通道对比** | `repaintEverything(264)` vs `scope=264`+滑动 | **结果不同！** |
| **C. 滚动模式** | `EInkHelper.setScrollingRefreshMode(1/0)` | **调用无效**（读回仍 2）|

**实验 A：10 个值的结果**（`repaintEverything` 受控全刷）

| 值 | 构成 | 结果 |
|---|---|---|
| 8 | DU4 裸值 | `[2]`/38 GC16 |
| 24 / 72 | 8\|AUTO(16) / 8\|WAIT(64) | 同 GC16 或空 |
| 40 / 104 | 8\|FULL(32) / 8\|FULL\|WAIT | `[7]`/24 **出现** + `[1]`/22 混合 |
| **264** | **8\|DITHER_MODE(256)** | **`[7]`/24 ✅** + `[6]`/5 + `[1]`/22 |
| 2056 | 8\|DITHER_COLOR_Y1(2048) | 空 |
| **2312** | 8\|256\|2048 | **`[7]`/24 ✅** + 混合 |
| 16777224 | 8\|ONYX_AUTO | 空 |
| **16777480** | 8\|256\|ONYX_AUTO | **`[7]`/24 ✅（主导 9 次）** + 混合 |

⇒ **触发条件：`DU4(8) + DITHER_MODE(256)`**（与 §9.3.7 同源：dither 位使波形"升到有灰度的波形"，
对 DU 升到 GC16、对 DU4 升到 DU4 自身）。候选值 **264 / 2312 / 16777480**。

**② ★★ 但关键在通道 —— scope 通道拿不到 DU4**

| 通道 | 值 264 落入 | 说明 |
|---|---|---|
| `repaintEverything(264)`（显式全刷）| **`waveform[7]`/24（DU4）** | ✅ 能拿到 |
| **`scope=264` + 合成更新** | **`waveform[2]`/38（GC16）** | ❌ **拿不到 DU4** |

实测（scope=264 + 8 次滑动）：`5 × waveform[2] update[0] frame_total[38]`。

⇒ **`repaintEverything` 是"全屏刷新"路径（§9.3.5 已证会卡，reset 12/轮）**，
而**全局刷新模式设置走的是 scope 通道** —— 故 **DU4 无法作为全局档位**。

**③ 实验 C：`setScrollingRefreshMode` 无效（工具已验证存在但改不动）**
```
su -c "CLASSPATH=/data/local/tmp/ssmode.dex app_process /system/bin io.onyx.SetScrollMode 1"
  → scrollingRefreshMode: 2 -> 2      ← 读回不变
```
`EInkHelper.setScrollingRefreshMode(int)`（EInkHelper.java:1141）存在且调用无异常，
但 `getScrollingRefreshMode()` 读回始终 2 —— 疑被系统重写或需 OECService 重启后才生效。
⇒ **无法用此路径消除 A2 全屏混合**（该混合来源的推断因此**未能验证**，勿当结论）。
> 工具保留在设备 `/data/local/tmp/ssmode.dex`（源码 `io.onyx.SetScrollMode`），供后续接力验证。

**④ 最终结论**

⇒ **局部刷新族在 scope 通道下确实只有 2 种可用波形：DU(22帧/1bpp) 与 GC16(38帧/4bpp)。**
⇒ **无法扩展出 DU4**（它只在显式全刷路径出现，而那条路径会卡）。
⇒ **Poke6 上"全局刷新模式"的可调维度已穷尽**，扩充档位不会产生新表现。

#### 9.3.15 ★★★ 修正：波形库里有 4 种独立波形（2026-09-23，回答 mode2/mode4/mode6 之问）

> ⚠️ **修正 §9.3.13 的表述**：那里说"只有 2 种物理波形"，**混淆了两个层面** ——
> 那是 **scope 通道下可用的** 2 种；**波形库内的独立波形有 4 种**（此前漏算了 A2）。

**① 波形库 4 种独立波形（按驱动特征判定，非按名字）**

| 槽 | 名字(标准) | 帧数 | bpp | 驱动 state | 驱动特征 | 独立？ |
|---|---|---|---|---|---|---|
| `mode1` | **DU** | 22 | 1 | **42**/256 | **多次脉冲**（`B.B.B.B.B.B`）| ✅ 独立 |
| `mode2` | **GC16** | 38 | 4 | **243**/256 | 全摆动（`W.W.W` 长串）| ✅ 独立 |
| `mode3/4/5` | GC16_FAST/A2/GL16 | 38 | 4 | 244/256 | 同 GC16 | ❌ **GC16 的副本/微变体** |
| `mode6` | GL16_FAST(名) | **5** | — | **18**/256 | **单次脉冲**（仅 1 或 3 个 sub-frame）| ✅ **独立（真 A2）** |
| `mode7` | **DU4** | 24 | 2 | 84/256 | — | ✅ 独立 |

⇒ **本质不同的波形 = 4 个：DU / GC16 / A2 / DU4**。

**② `mode2`(GC16) vs `mode4`（名为 A2）—— 差别极小，属同一族**

逐 state 逐 sub-frame 比对（均 P=39 → 156 sub-frame）：

| 项 | 数值 |
|---|---|
| **完全相同 state** | **206/256（80%）** |
| 不同 state | 50/256（每处仅差 **3 或 6 个 sub-frame**）|
| mode4 相对 mode2 新增 | 推黑 92 次 / 推白 91 次 / 其他 150 次 |

差异样例（state 6，仅位置 53-55 三个 sub-frame 有无之差）：
```
mode2: ......BBB....   ← 有 BBB（3 个 sub-frame 推黑）
mode4: ............   ← 无
```

⇒ **`mode4` 不是独立波形，而是 GC16 的 from/to 组合微调版**（差异量级约 1%，疑为抖动/相位微调）。
⇒ 故 `REAGL(6)`/`REAGL_PLUS(9)` 落 mode4 时，表现与 GC16 几乎无差
（解释了 §7.4 与 §12.10 的历史观察）。

**③ `mode6`(A2) 的波形本质 —— 独立设计，非"基于某基础波形"**

A2 的 18 个驱动 state 全部列出（P=10 → 每 state 40 sub-frame）：

```
state    0 (0x00): .............................BBB........   驱动 3
state   24 (0x18): W.......................................   驱动 1
state   26 (0x1a): .............BBB........................   驱动 3
state   49 (0x31): ........................W...............   驱动 1
state   51 (0x33): .....................................BBB   驱动 3
state   75 (0x4b): ........W...............................   驱动 1
state   77 (0x4d): .....................BBB................   驱动 3
state  100 (0x64): ................................W.......   驱动 1
state  103 (0x67): .....BBB................................   驱动 3
state  126 (0x7e): ................W.......................   驱动 1
state  128, 152, 154, 177, 179, 203, 205, 228  ← 与上表同型循环
```

三个特征：
1. **每个驱动 state 只推 1 或 3 个 sub-frame** → **单次到位，无脉冲序列**
   （与 DU 的多次脉冲 `B.B.B.B.B.B` 截然不同）
2. **覆盖极窄**：仅 18/256 state（DU 42、GC16 243）
3. **与 DU 的驱动 state 交集仅 2 个**（42 个 vs 18 个）→ **独立的 state 定义**

⇒ **A2 是独立的第 3 种波形**，不能表述为"基于 DU"或"基于 GC16"。
⇒ 物理语义：**"一次推到位"的最快驱动**，代价 = 灰度能力低 + 残影大（覆盖窄）。

**④ 最终准确表述（替代 §9.3.13 的简化说法）**

| 层面 | 数量 | 内容 |
|---|---|---|
| **波形库内的独立波形** | **4** | DU(22帧/1bpp)、GC16(38帧/4bpp)、**A2(5帧/单脉冲)**、DU4(24帧/2bpp) |
| GC16 族的副本/微变体 | 3 | `mode3`、`mode4`（与 mode2 差约 1%）、`mode5` |
| **scope 通道下可用于日常的** | **2** | DU、GC16 —— A2 走全屏 `update[1]` 会卡（reset 12/轮）；DU4 的触发值在 scope 下拿不到（§9.3.14）|

⇒ §9.3.13 的"只有 2 种"应理解为 **"scope 通道可用的只有 2 种"**，而非"波形库只有 2 种"。

**⑤ ★ DU4 与 DU 的关系核实（回答"DU4 是不是 DU 叠加其他"）**

用户质疑"DU4 应该是 DU 叠加而来"。**已做波形级严格比对，结论：同族但非叠加。**

| 指标 | DU (mode1) | DU4 (mode7) |
|---|---|---|
| 每 state sub-frame | 88 | 96 |
| 驱动 state 数 | **42** | **84**（正好 2 倍）|
| 驱动 state 交集 | — | 仅 **16** 个 |
| **驱动相位总数** | **576** | **1364** |
| **相位重叠** | — | **仅 60**（占 DU **10.4%**、占 DU4 **4.4%**）|
| DU 驱动序列是 DU4 前缀的 state | — | **仅 4/256** |

实例（同为共有 state，波形完全不同）：
```
state 0:   DU : .............................BBB....   (推黑 3)
           DU4: ..W...W.W...........W.W.W.W....   (推白 7)
state 46:  DU : B.B.B.B.B.B.B.........B.B.B.B.B.B.B.BBBB   (推黑 18)
           DU4: W.W.W.W.W...W.W.W.W.W.W.W.WWWW...         (推白 17)  ← 方向相反!
```

**但"族"层面确实相关** —— 看 state 覆盖密度：
```
DU  (1bpp): 0, 10, 11, 22, 23, 34, 35, 45, 46, 57, 58, 69...   间隔 ~11.5
DU4 (2bpp): 0, 3, 6, 10, 14, 17, 20, 21, 24, 28, 31, 32...     间隔 ~3.5（密度 3.3×）
```
⇒ **密度差异正对应位深**：1bpp 区分 2 级 → 42 个驱动 state；2bpp 区分 4 级 → 84 个（翻倍）。

**准确表述**：
| 层面 | 关系 |
|---|---|
| **概念族** | ✅ **同族**（都属 `direct update`：局部驱动、不闪烁、无摆动）|
| **波形数据** | ❌ **非叠加**（相位重叠仅 4~10%，独立生成；若为叠加应接近 100%）|
| **位深** | DU=1bpp / DU4=2bpp → 决定 DU4 状态覆盖更密 |

⇒ 结论：**DU 与 DU4 是"direct update"族的两个不同位深实现** —— 同族、同不闪、同局部驱动，
但波形各自独立生成，DU4 以更密的 state 覆盖换取 4 级灰度。

#### 9.3.16 全面刷新（FULL 位）覆盖测试：不会带来新基础波形（2026-09-23）

> 起因：用户问"如果考虑上全面刷新模式，会有另外多的基础波形吗？"
> 方法：`TestWaveform repaintEverything(波形号 | FULL(32))`，波形号 0–12 共 11 个值全覆盖。

**实测结果**（`_scratch_gs/full_sweep.sh`；`update[0]`=局部 / `update[1]`=全屏）

| 触发值 | 波形号 \| FULL | 落到的槽 |
|---|---|---|
| 32 | 0 (INIT) \| FULL | `[6]`/5 + `[2]`/38 |
| 33 | 1 (DU) \| FULL | `[1]`/22 + `[2]`/38 |
| 34 | 2 (GC16) \| FULL | `[2]`/38 + `[1]`/22 + `[6]`/5 |
| 35 | 3 (GC4) \| FULL | `[2]`/38 |
| 36 | 4 (ANIM) \| FULL | `[2]`/38 + `[1]`/22 |
| 37 | 5 (AUTO) \| FULL | `[2]`/38 + `[6]`/5 |
| **38** | 6 (REAGL) \| FULL | **`[4]`/38** + `[1]`/22 |
| 39 | 7 (无定义) \| FULL | `[6]`/5 + `[2]`/38 |
| 40 | 8 (DU4) \| FULL | `[2]`/38 + `[1]`/22 |
| 43 | 11 (GCC16) \| FULL | `[2]`/38 |
| 44 | 12 (DEEP_GC16) \| FULL | `[1]`/22 + `[2]`/38 |

**★ 结论：11 个值全部只落在 `{1, 2, 4, 6}` 四个槽，无任何新槽。**

| 期望但未出现 | 原因 |
|---|---|
| **`[0]` INIT** | panel 初始化专用，**不经刷新路径**；即使传 `INIT\|FULL`(32) 也只落 `[6]`/`[2]` |
| **`[3]` / `[5]`** | 这两列数据与 mode2/4 **本就相同**（§9.3.15：`mode3==mode4==mode5`），即使命中也是同一波形 |
| **`[7]` DU4** | 印证 §9.3.14：DU4 的触发值在 scope/全刷路径下都拿不到 |

**三层硬约束（为什么槽位不可能更多）**
```
1. wbf 文件只提供 8 列 (mode0-7)   → 槽位上限 0–7
2. 其中 mode3/4/5 是 GC16 副本      → 实际只有 1 份数据
3. 波形号 >7 的 (GCC16=11/DEEP_GC16=12) → 超出列范围，回落
```
⇒ **基础波形数量是固定的 4 个**（DU / GC16 / A2 / DU4），**全屏刷新也变不出新的**。

**附带验证**：`value=38`（波形号 6 = REAGL | FULL）落 **`[4]`/38** —— 再次确认
§9.3.11 的映射（REAGL → 槽 4，槽 4 装的是 GC16 副本）。

**对"扩展全局刷新选项"的最终意义**
⇒ 无论**局部刷新**还是**全屏刷新**通道，可利用的基础波形都**只有那 4 个**（且故障机上 A2/DU4 因
全屏会卡、scope 拿不到而不可用）⇒ **扩展档位无法产生新表现，可调维度已穷尽。**

#### 9.3.17 ★★★ 分离实验定论：**只要不选 A2 scope，就不会出全屏 reset**（2026-09-24）

> 起因：用户问"更换还需要考虑换 EAC mode 或者把 reset 的 A2 换掉吗？还是只要不选 A2 的 scope 就不会出现这种现象？"

**① 四组分离实验（故障机 6C7F0E64，每组 12~15 轮混合输入 + 高频 dump）**

| # | EAC 逻辑 | scope | 实测波形 | reset | timeout |
|---|---|---|---|---|---|
| 1 | 3 (launcher 定死) | **GC16(2)** | `[2] update[0]` 局部 | **0** | 0 |
| 2 | **0 (NORMAL)** | GC16(2) | `[2] update[0]` 局部 | **0** | — |
| 3 | 3 | **全清 (NORMAL)** | `[2] update[0]` 局部 | **0** | 0 |
| 4 | 3 | **DU(1)** | `[1] update[0]` 局部 | **0** | — |
| 对照 | 3 | **A2(4)** | `[6] update[1]` **全屏** | **8** | 高 |

⇒ **EAC 取何值完全不影响**（实验 1 vs 2 同结果）；
⇒ **清 scope/选 DU/选 GC16 全部局部、reset 0**（实验 1/3/4）；
⇒ **只有 scope=A2(4) 触发全屏**（对照）。

**② 结论：只要不选 A2 档，就不会出现全屏 reset 循环。**

| 用户选项 | 是否必要 |
|---|---|
| 换 EAC mode | ❌ **不必要** —— 实验 2 证明 EAC=0 与 EAC=3 同样干净 |
| 换掉 kernel 的 A2 重排队 | ❌ **不必要** —— 重排队只在上层已全屏卡住时才发生；上层不走 A2 就没有触发源 |
| **只要不选 A2 scope** | ✅ **充分** —— 实验 1/3/4 全绿 |

**③ 附带澄清一处读数陷阱**

`CheckMode` 读到的 `appScopeRefreshMode=2` **不是生效值**：
- 该 `2` 是 **EAC 逻辑域**的 A2（`updateModeToString`）
- 但 ref §2252 已记：**软重启后 native pipe 不恢复 → `currentTop` 恒为 null → get 恒返回空 pkg 的默认值 2**
- 实验 1 反证：该值显示 2，而实测**零 A2 全屏** ⇒ 确为**无效默认读数**，不可作为判据

⇒ **判断实际生效档位应看 `fastModeIndex`（0=normal）与实测波形**，不看 `appScopeRefreshMode`。

**④ 纯波形号对照表（本次最干净的矩阵，6 轮滑动/档）**

| scope | 波形号 | 实测波形 | update | reset | 评价 |
|---|---|---|---|---|---|
| **1** | DU | `[1]` 22帧 | `update[0]` | **0** | ✅ 可用 |
| **2** | GC16 | `[2]` 38帧 | `update[0]` | **0** | ✅ 可用 |
| **4** | **A2** | `[6]` 5帧 | **`update[1]`** | **8** | ❌ 故障机禁用 |
| 6 | REAGL | `[2]`(回落) | `update[0]` | 0 | ⚠️ 与 GC16 等价 |
| 8 | DU4 | `[2]`(**回落**) | `update[0]` | 0 | ⚠️ **Poke6 未提供，等价 GC16** |
| 9 | REAGL_PLUS | `[2]`(回落) | `update[0]` | 0 | ⚠️ 与 GC16 等价 |

⇒ **4 个"基础波形"里，Poke6 scope 通道实际只有 DU/GC16 两个真正生效**；
A2 生效但强制全屏；DU4 不生效（回落 GC16）—— 与 §9.3.14/§9.3.15 互相印证。

**⑤ 位标志无效性验证（推翻"改 flags 可改 update"）**

| 值 | 位分解 | 实测 update |
|---|---|---|
| 2 | wave=2 | `update[0]` |
| **98** | wave=2 + **FULL(32)** + WAIT(64) | **`update[0]`** ← 显式带 FULL 位仍是局部! |
| 257 | wave=1 + DITHER | `update[0]` |
| 513 | wave=1 + 0x800 | `update[0]` |
| 2305 | wave=1 + DITHER + 0x800 | 混合，含 `update[1]` |

⇒ **`update` 位与 flags 的 FULL 位无关** —— 由 native 依 waveform mode 判定，用户态改不了。

**⑥ ★ 最终处置决定（用户 2026-09-24 决策）**

用户问："更换还需要考虑换 EAC mode 或者把 reset 的 A2 换掉吗？还是只要不选 A2 的 scope 就不会出现这种现象？"
→ **答：只要不选 A2 scope 即可，EAC 与 kernel 均无需改动。**

| 用户考虑的选项 | 裁决 | 依据 |
|---|---|---|
| 换 EAC mode | ❌ **不必要** | 实验 2（EAC=0）与实验 1（EAC=3）结果相同 |
| 换掉 kernel 的 A2 重排队 | ❌ **不必要** | 重排队仅在上层已全屏卡住时发生；不走 A2 则无触发源 |
| **只要不选 A2 scope** | ✅ **充分** | 实验 1/3/4 全绿（reset 0、timeout 0）|

**→ 档位表保留现状 `{-1, -1, 1, 2, 4, 2312}`（6 档），不做代码改动。**
理由：本 launcher 不止用于故障机，A2 在正常机是合法选项；由用户按设备状况自选。
（副作用提示：故障机上选 A2 会进入全屏 reset 循环，选 DU4 会回落 GC16。）

#### 9.3.18 ★★★ 逆向定论：`update[0]/[1]` 由**用户态 ioctl 传入**，kernel 只透传（2026-09-24）

> 起因：用户怀疑"是否 EAC 与 scope 解绑引发"，并要求"直接逆向 update[0]/[1] 判定逻辑"。
> **结论：`update` 位完全由用户态 ioctl flags 决定，kernel 不做任何决策 —— 故 patch kernel
> 无法改变 update 类型，只能改波形号与坐标。**

**① 完整数据流（ARM64 反汇编确证）**

```asm
; 步骤1: ioctl 结构 → 内部结构 (0x532dbc)
00532dbc: ldr  w8, [x1, #0x30]      ; x1 = ioctl 传入结构, +0x30 = update 位
00532dc0: str  w8, [x0, #0x80]      ; 存入内部结构 +0x80

; 步骤2: 内部结构 → LUT 描述符 (0x532cd0)
00532cd0: ldr  w9, [x1, #0x80]      ; 读 update
00532cd4: str  w9, [x0, #0x30]      ; 写 LUT 描述符 +0x30

; 步骤3: dump 打印 (0x534410)
00534410: ldr  w2, [x8, #0x38]      ; magic
00534418: ldr  w3, [x8, #0x10]      ; lut 索引
00534420: ldp  w4, w5, [x8, #0x2c]  ; waveform=[0x2c], update=[0x30]  ← 本文关注
00534424: ldrb w6, [x8, #0x35]      ; frame_cur
00534428: ldrb w7, [x8, #0x34]      ; frame_total
```
⇒ **纯拷贝链，无计算、无分支** ⇒ `update` = 上层给的 flags 原值。

**② 波形号 与 update 是【两条独立通道】**

```asm
; get_waveform_mode_index (0x550ab0) —— 只查波形
00550ac4: ldrsw x2, [x8, #0x830]      ; 温度段索引
00550ad4: cmp   w19, #0x10            ; mode 号上限
00550ae8: madd  x8, x2, x8, x9        ; 表基址 + 温度*0x4c
00550aec: add   x8, x8, w19, sxtw #2  ; + mode*4
00550af0: ldr   w8, [x8, #8]          ; ★ mode → 波形索引
```
**表 `0x1f69240`（步长 0x4c）实测内容**：
```
温度段[0][1]: mode 0→0, 1→1, 2→2, 3→3, 4→4, 5..13→-1(不支持), 14→0
温度段[2]   : mode 2→-1, 7→3（温度相关禁用）
温度段[3]   : mode 3→-1, 5→3
```
⇒ **该函数只返回"波形索引"，不含 update 信息。**

**★ 由此推翻 §9.3.18 ⑦ 的"方案 A/B"**：
- ❌ "改重排队波形号让 update 变局部" —— **不可能**，波形号与 update 无关
- ❌ "改 wait 模式为 NOWAIT" —— 治不了根，`update[1]` 的全屏属性仍在

**③ 回到用户怀疑：EAC/scope 解绑是否引发？**

| 项 | 判定 |
|---|---|
| **「解绑」本身** | ❌ **不是原因** —— EAC 定死 3(REGAL)，`toEpdMode(3)=6`，走 `update[0]` 局部 |
| **解绑后的新档位表** | ⚠️ **间接原因** —— 新表把 **A2(4)** 暴露为可选档；A2 经 scope 通道传入 `update[1]` 全屏 → reset 循环 |
| **实测佐证（2026-09-24 对照）** | GC16 档翻页：`update[0]` 局部、reset 0、A2 0 次<br>A2 档翻页：`update[1]` 全屏 26 次、**reset 20**、timeout 42 |

⇒ **准确表述：解绑本身无副作用；但解绑后新增的 A2 档在故障机上是致命选项。**

**④ 修复的可行边界（kernel 层）**

| 想改什么 | 是否可行 |
|---|---|
| 波形号 | ✅ 可（已在做，`movz w0,#N`）|
| **`update` 位** | ❌ **不可** —— 由 ioctl 透传，kernel 无决策点 |
| 重排队坐标 | ⚠️ 可改，但**非必要** —— 见下方更正 |

> **★ 更正（2026-09-24 晚，§9.3.17 分离实验后）**：本节初稿称"唯一可能根治的内核改法 = 改重排队坐标"
> 以及 §9.3.19 的"方案 A/B"（改 wait 模式 / 改波形号）—— **均已作废**：
> 1. **重排队不是循环的触发源** —— 它只在上层已提交 `update[1]` 帧、该帧卡住之后才发生
>    （重排队路径里 `update` 字段本就是 `str wzr` = **0 局部**，见 §9.3.19 反汇编）。
> 2. **只要上层不提交 A2，就没有全屏帧可卡、没有重排队、没有循环** —— 实验 1/3/4 已证（§9.3.17）。
> 3. 故 **kernel 无需任何改动**；改坐标属于"治标且无触发场景"。

**⑤ 方法论修正（自我纠错记录）**

| 之前的错误论断 | 修正 |
|---|---|
| "A2 全屏出现在 reset 后 3ms ⇒ 是 reset 产物" | ❌ **循环论证** —— `dump_lut_list()` 只在 reset 内部打印，必然只在 reset 附近出现。应看 **reset 之前** 的 A2（中位 738ms）|
| "`appScopeRefreshMode=2` 是 GC16" | ❌ **读错语义** —— EAC 逻辑域 `2`=A2（`updateModeToString`）；scope 值域的 `2` 才是 GC16。**且该读数本身不可信**（currentTop 恒 null → 恒返回默认 2，见 §9.3.17 ③）|
| "patch 重排队波形号可消除循环" | ❌ **方向错** —— update 与波形号独立（本文②）|
| "改重排队坐标可根治" | ❌ **作废** —— 重排队非触发源（本文④更正）|

#### 9.3.19 ★★★ v6 patch 的副作用：A2 重排队 → **全屏自持 reset 循环**（2026-09-24 实测定位）

> 起因：用户报告"出现堆积现象，请查看当前 eac 与 scope 并根据日志判断原因"。
> **结论：堆积 = reset 自持循环；根因是 v6 内核 patch 把重排队设为 A2，而重排队固定走
> `update[1]` 全屏 —— 全屏必须 `wait all_lut_free`（等所有 LUT 空闲），在供电故障机上必然
> 超时 → 又 reset → 又重排队 A2 全屏 → 自持。与 EAC / scope 档位无关。**

**① 现场数据（`dmesg`，62800 行，活跃时钟 16571~18781s 覆盖 5.2h）**

| 项 | 计数 |
|---|---|
| `reset cause[update wb wait all_lut_free timeout]` | **58** |
| `wait all_lut_free timeout 500 ms` | 116 |
| `ERROR TPS6518x waiting for power good!` | **360** |
| `Retry 2 more times` | 180 |
| `epdc power error` | 246 |
| `cant get free waveform buf` | 3 |
| `waveform_desc is NULL` | **0**（v6 有效抑制）|

**reset 密度**（按 10 分钟桶）：
```
t=16800s: ##################################### 37   ← 爆发
t=18000s: ######## 8
t=16200s: ####### 7
t=18600s: #### 4
t=17400s: ## 2
```
**reset 间隔**：中位 **0.75s**（<1s 有 39 个，<5s 有 50 个）—— 不是"偶发"，是**自持循环**。

**② ★ 决定性证据：A2 全屏紧跟在 reset 之后**

```
每个 RESET 之后 3s 内出现的波形： A2全屏 46~48 次  Δt 中位 = 3ms
每个 RESET 之前 3s 内出现的波形： A2全屏 38 次
A2 全屏出现在【非 reset 时刻】：  0 次
```
⇒ **A2 全屏 100% 与 reset 绑定**（48/49 在 reset 后 3ms）。
   若 A2 来自应用或滚动，必会出现在非 reset 时刻 —— 实际 0 次 ⇒ **只能是 reset 内部产物**。

reset 后 dump 出的"卡住 LUT"分布（即 reset 前未完成的那帧）：

| 卡住的波形 | 次数 | update |
|---|---|---|
| `waveform[6]` (A2) | **40** | `update[1]` **全屏** |
| `waveform[1]` (DU) | 5 | `update[1]` 全屏 |
| `waveform[2]` (GC16) | 3 | `update[1]` 全屏 |
| 局部 (`update[0]`) | 1 | — |

⇒ **卡住的几乎全是全屏更新**，且以 A2 为主。

**③ 完整因果链（三层叠加）**

```
第 1 层（触发源 · 硬件）：TPS6518x 供电故障          ← ref §9.2 既有结论
  powerup 失败（360 次）→ 波形无法建立 → 等 500ms → 超时 → reset
        ↓
第 2 层（放大器 · v6 patch）：reset 重排队被设为 A2，且重排队固定用全屏坐标
  patch_kernel_v6.py: 0x542B0C / 0x5431AC  movz w0,#2(GC16) → movz w0,#4(A2)
  重排队新建描述符 [x=0 y=0 w=1448 h=1072] = 全屏（ref §2424）
        ↓
  全屏 ⇒ update[1] ⇒ 必须 wait all_lut_free（等【所有】LUT 空闲）
        ↓
  供电故障导致其它 LUT 卡住 ⇒ 必然超时 ⇒ reset ⇒ 又重排队 A2 全屏 ⇒ 自持循环
        ↓
第 3 层（无关变量）：scope / EAC 档位
  当前 scope=GC16(2)【局部】——正常刷新路径完全正常
  但【重排队是内核固定行为，不走 scope】⇒ 换档位无法阻止循环
```

**④ 为什么 v6 当初"看起来有效"（黑屏消失）**

| | v5（GC16 重排队） | v6（A2 重排队） |
|---|---|---|
| 重排队波形 | GC16 38 帧 | A2 5 帧 |
| 单轮能画完吗 | **画不完**（38 帧必须被超时打断）| **能画完**（5 帧快）|
| 画面表现 | **大面积黑** | ✅ 内容正常 |
| reset 循环 | 有 | **仍有**（根源是"全屏等待"，与帧数无关）|

⇒ **v6 把"黑屏循环"变成了"不黑屏的 reset 循环"** —— 黑屏解决了，但循环的真因
（全屏 `wait all_lut_free`）未被触及。这就是本次"堆积"的来源。

**⑤ 与档位无关的证明**

- 用户当前档位：`launcherRefreshMode=3` → 新表 index 3 = **GC16(2)**，实测 `appScopeRefreshMode=2`
- 正常滚动实验（GC16 下 6 次滑动 + 6 次翻页）：`reset=0`、A2 全屏 **0 次** ⇒ 正常路径干净
- 但 dmesg 里 49 次 A2 全屏**全部**来自 reset 重排队 ⇒ 循环不经过 scope 层

**⑥ 当前状态**：循环在**空闲时暂停**（需帧提交驱动，ref §2429）；实测末尾 13 分钟无新 reset。

**⑦ 修复方向 —— ★ 已作废（2026-09-24 晚更正）**

> **本节初稿给出的 A~E 五方案全部不再需要**，因为 §9.3.17 的分离实验证明：
> **只要 scope 不选 A2，就没有全屏帧、没有 reset、没有重排队循环。**
> 用户已于 2026-09-24 决定：**档位表保留现状，EAC 与 kernel 均不改动。**

| 原方案 | 状态 |
|---|---|
| A. 改 wait 模式为 NOWAIT | ❌ 作废 —— 无触发场景；且 §9.3.18 证明 update 位不由 kernel 决定 |
| B. 重排队波形 A2 → DU | ❌ 作废 —— 同为 `update[1]`；且波形号与 update 独立 |
| C. nop 掉重排队 | ❌ 作废 —— 重排队非触发源（其 update 本就是 0 局部）|
| D. 回滚 v5 / stock | ❌ 不作 —— v6 的黑屏修复有效，不该回退 |
| E. 接受现状 | ✅ **即最终决定** —— 但表述应改为"不选 A2 即无此现象"|

**★ 核心洞察（修正版）**：v6 patch 达成"不黑屏"；而 A2 档的 reset 循环
**根源在上层提交 `update[1]` 全屏帧**（native 依 waveform mode 4 判定），
**不在 kernel、不在重排队**。⇒ 规避方式就是**不选 A2 档**，无需任何代码或内核改动。

#### 9.3.20 ★★★ 「周期 GC」到底是什么 —— 输入计数触发，不是计时（2026-09-23，源码确证）

> 起因：用户问"关闭周期 GC 会发生什么？这里的周期是指过多少秒刷新，还是动作输入后在页面刷新后清残影？"
> **答案：都不是"多少秒"** —— 它是**输入事件计数**触发，且**不是"页面刷新后"**，而是"输入累计到阈值后的下一次刷新时"。

**① 完整调用链（源码确证）**

```java
// 1) 输入事件入口 —— 每次触摸/按键都会走到
EACBaseRefreshImpl.handleInputEventImpl (EACBaseRefreshImpl.java:79)
    if (MOTION_EVENT_TYPE && eventAction == 1)          // 手指离开（UP）
        increaseRepaintCount(refreshConfig);
        EACUtils.applyDebouncerTransientUpdateMode(...)  // 顺带设瞬态模式
    if (KEY_EVENT_TYPE && eventAction == 1)              // 按键按下
        increaseRepaintCount(refreshConfig);

// 2) 计数 + 白名单门禁
EACBaseRefreshImpl.increaseRepaintCount (:101)
    if (!DEBOUNCER_UPDATE_MODE_MAP.containsKey(updateMode)) return;   // ← {0,3,5} 才继续
    ViewUpdateHelper.debounceIncRefresh();                            // → SF 事务，计数器 +1

// 3) 阈值来自 gcInterval（**计数**，非秒）
EACRefreshConfig.gcInterval = 20   (默认)
EACRefreshConfig.obtainLegalGcInterval()  → gcInterval>0 ? gcInterval : MAX_VALUE
TabletEACRefreshImpl:188
    EACUtils.applySFDebouncer(pkg, mode, shortDelay, longDelay, obtainLegalGcInterval())
        → ViewUpdateHelper.debouncer(enable, mode, shortDelay, longDelay, gcInterval)
```

**② 准确语义**

| 问题 | 答案 |
|---|---|
| 是"过多少秒就刷新"吗？ | ❌ **不是**。`gcInterval = 20` 是**次数**（`obtainLegalGcInterval` 直接返回该 int，无时间单位）|
| 是"输入动作后、页面刷新后清残影"吗？ | ⚠️ **部分对**：触发源是**输入事件**（触摸 UP / 按键 DOWN）；但**不是立刻清**，而是 SF 侧的**计数器累积到 `gcInterval` 次后，在下一次刷新时插入一次 GC 全刷** |
| 实际行为 | **每累计 N 次输入事件（默认 20），触发一次 GC 全屏刷新清残影** |

⇒ 所以它是 **"按交互次数的周期性清残影"**：**读得越久/翻页越多 → 越接近下次 GC**；反之不动它就不计。
⇒ 与"时间"无关：**放着不动永远不会触发**（因为没有输入事件累加）。

**③ 关闭它会发生什么**

| 场景 | 后果 |
|---|---|
| **关闭**（EAC mode ∉{0,3,5}，如旧代码的 DU/A2/X 档）| ① `increaseRepaintCount` 直接 return → 计数器不增 → **永不触发 GC 清残影**<br>② `applyDebouncerTransientUpdateMode` 也不执行 → **滚动瞬态加速也失效**<br>③ `applyDither` 里同样门禁 → **dither 被强制关**（§9.3.7）<br>⇒ **残影无上限累积 + 滚动变慢 + 失去抖动灰度** |
| **启用**（mode ∈{0,3,5}，当前定死 3）| 每 20 次输入触发一次 GC 全刷（其模式恒为 `toEpdMode(0)`=AUTO=GC16 局部，实测 reset 0）|

**④ 由此解释的历史现象**

- §9.3.3「用户观察：切到 DU/A2 后残影明显」← **因为那些档位关闭了 GC 计数**，不再自动清残影
- §9.3.14 候选值 `264`/`2312` 等带 dither 位但不含 `FULL` 时，若 EAC mode 不在 {0,3,5}，
  GC 与 dither 双双失效 —— 这也是**必须把 EAC 定死在 3** 的直接理由（§9.3.6 结论的补充论据）

**⑤ 修正：ref §1806 的旧表述**
> 旧记：「`gcInterval`（GC 触发次数）… **20 次**」—— 方向正确，但**未点明"计数由输入事件驱动"**，
> 且 §1806 附近另列了 `setGcRefreshInterval`（**秒级**，`ViewUpdateHelper.java:1179`）——
> **那是另一个独立机制**（时间周期 GC），与本节讨论的 `gcInterval`（输入计数）**不是同一个东西**，勿混。

## 1. 关键结论（已实测，足够指导使用）

**故障机刷新模式实测帧数**（`dump_lut_list` 的 `frame_total` = EPDC 波形表真实值，最可靠）：

| 模式 | UI 值 | EPDC waveform | 帧数 | 故障机能否跑完 |
|---|---|---|---|---|
| **REGAL** | 6 | 6 (REAGL) | **5** ⚠️见下 | ✅ 能（5 帧短，供电撑得住，实测稳定无 reset） |
| DU | 1 | 1 | **22** | 部分（供电中断时卡 2/22） |
| GC16 = GU(2)/GC(98)/DEEP_GC(108)/GCC(107) | — | 2 | **38** | ❌ 不能（实测卡 2/38） |

**launcher 使用结论**：6 个可选模式中**只有 REGAL 是 5 帧短波形**，其他 4 个（GU/GC/DEEP_GC/GCC）在 Poke6 波形表全部落到 GC16=38 帧 → 故障机必然跑不完、双击叠残影。**故障机应固定用 REGAL**。（⚠️ 该结论 2026-09-05 晚已证伪：REGAL=GC16 38帧，见下注记与 7.4/9 节）

> ⚠️ **REGAL=5 帧记录 2026-09-05 复核未能复现（见第 6/7 节），倾向误记，本行与上段结论存疑**：
> - 三次独立复核（故障机重启窗口 ×2 + launcher 真实路径 + 正常机）中，UI=6 (REGAL) 与 launcher idx3(REGAL) 触发的全刷**全部实测为 waveform[2]/[4] frame_total=38（GC16）**，frame_cur 可完整推进到 37/38
> - 从未出现 frame_total[5]；REAGL(5 帧) 只存在于 9/5 上午的一次旧 dump，其上下文（当时 patch boot？其它路径？）无法考证
> - 故障机供电正常（重启早期窗口）时 **GC16 38 帧也能完整跑完**（frame_cur 到 37），并非"必然卡 2/38"——卡是供电不足时的表现，不是波形本身不可完成
> - 若 REGAL 实际就是 GC16(38 帧)，则 launcher 6 模式中**不存在"短波形可选"**，故障机日常只能依赖供电充足窗口；此结论修正需用户确认后落实到 launcher 策略

**正常机 frame 计数器测帧数不可靠**（REGAL 显示 76、GC16 152，与真实帧数 5/38 无对应——计数器数 LUT 操作单元非帧数）。

## 2. v4 内核教训（重要，勿重蹈）

- **v4 的 `0x5F46E8` patch（等 PowerGood 超时 17s→1ms）有害**：导致 powerup 全失败 + `waveform_desc is NULL`（波形表不加载）→ 屏幕实际冻结（不闪≠正常，是没刷新）
- v3/v2（无该 patch）EPDC 能正常上电执行 → **v4 已废弃**，当前故障机刷回**原始 boot**（`boot_extracted.img`）
- 教训：powerup 路径的"等待"是硬件稳定必要延时，不可盲目压短；v3 重试压缩（3/5→1）也降低偶发成功概率，原版 15 次重试窗口更多

## 3. wbf 逆向进展（待接力）

**wbf 文件**：`eink_waveform.wbf`（256KB，正常机 6C1BF7D9 提取，md5 见 git），已放仓库根目录。
**目标**：从 wbf 读全部 12 个 EPDC waveform mode 的帧数（波形表固定值，与设备状态无关，一次拿全）。

**已破解**：
- 解析函数 = 内核 `0x552930`（被 `ldr [struct+0x22E8] → bl 0x552930` 调用；x0=struct, x1=固件buffer, x2=大小）
- wbf header 逐字节拷入 struct：`[0..3]`魔数(a76b12d4)、`[4..7]`文件大小(256003 LE)、`[0x24→struct+0x2a]`、`[0x25→struct+0x2b]`、`[0x26→struct+0x2c]`（随后 +1 → 8/14）
- 双层索引（2026-09-05 精读确认）：`struct+0x24 = u24(wbf[0x20..0x22]) = 0x5f` = **表1 文件偏移**；表1 @wbf+0x5f 共 8 项（每项 3B 小端偏移+1B 校验和）；表2 = 8 组×14 项（组 g 的表位于 `表1[g]`，每项 4B 同上），共 8×14 项偏移 → 存全局 `0x2180238`/`0x2180278`
- 校验和逻辑（wbf[8..0x1e] 累加 vs struct+0x20；wbf[0x20..0x2e] vs struct+0x35；wbf+0x30 温度点段校验）
- `struct+0x2e = wbf[0x28] = 0xff`、`struct+0x2f = wbf[0x29] = 0xfc` —— **解析器终止/字面标记值**（勿再猜成 3/0xff）
- **修正**：`struct+0x36` 不是波形数据，而是 `memcpy(struct+0x36, wbf+0x41, wbf[0x40]=29)` 的名称串 `320_R110_AE4D21_ED060KD1C3_TC`(+NUL)；wbf+0x30 起 14 字节是**温度点数组** 0,3,…,39°C（拷栈用于逐温度解析）
- 数据块解析：`0x552930` 内 0x552e50 计数循环 + `0x552528` 展开器，按 (mode×14+temp) 从表2 取每 (g,t) 块偏移逐块处理；块终止 = 项 a==0xff，后随 1 字节校验（块内 a/b/字面字节和+0xff）——**g6t0 校验实测通过**（终止@0x38c6a+276，校验 0xe1 = Σ0xe2+0xff &0xff），解析边界可靠

**待接力（2026-09-05 晚 unicorn 模拟已解决核心）**：
- ✅ **已解决**：用 **unicorn 2 模拟执行内核 `0x552930`**（真实机器码运行 + hook printk/memcpy/kzalloc/0x552528 返回点），**一次读出 8×14 (波形 mode × 温度) 帧数全矩阵**（两机共用同一 wbf，md5 f463661b 已设备端验证）——与驱动实测精确匹配（见第 8 节）。之前 (a,b) RLE 解不出 5/22/38 的原因是静态启发式不可行，模拟执行直接给出真值
- ✅ 参考：正常机与故障机 wbf **逐字节相同**（见 7.3 修正；此前"md5 58c1… 不同"为拉取 CRLF 污染假象）

## 8. ★ wbf 静态逆向突破（2026-09-05 晚，unicorn 模拟执行 0x552930）

#### 9.3.21 ★★★ 残影机制全解：清残影 = 全摆动，只有 GC16 族具备（2026-09-25）

> 起因：用户观察"现在刷新残影多，我觉得是因为没有选择清除残影的模式？"
> **结论：用户判断基本正确 —— 4 个波形档里只有 GC16 具备清残影能力；DU/A2/DU4 都是差分
> 模式，不清残影。但还有更深一层：scope 通道的 `FULL` 位不生效，导致连 GC16 也只能
> 清"变化区域"，无法清理整屏残影。**

**① 全部模式的「基础波形 + 位命令」分解表（`UI 值 = 波形号 | 标志位`）**

低位掩码 `EINK_WAVEFORM_MODE_MASK = 15` ⇒ **低 4 位 = 基础波形号**。

| UI 值 | 模式 | 分解 | 波形号 |
|---|---|---|---|
| 1 | DU | `DU(1)` | 1 |
| 2 | GU | `GC16(2)` | 2 |
| 3 | GC4 | `GC4(3)` | 3 |
| 4 | ANIMATION / A2 | `ANIM(4)` | 4 |
| 5 | DEFAULT / AUTO | `AUTO(5)` | 5 |
| 6 | REGAL | `REAGL(6)` | 6 |
| 9 | REGAL_PLUS | `REAGL_PLUS(9)` | 9 |
| **98** | **GC** | `GC16(2) \| WAIT(64) \| FULL(32)` | 2 |
| **107** | **GCC** | `GCC16(11) \| WAIT \| FULL` | 11 |
| **108** | **DEEP_GC** | `DEEP_GC16(12) \| WAIT \| FULL` | 12 |
| 257 | DU+DITHER | `DU(1) \| DITHER_MODE(256)` | 1 |
| 2049 | DU+Y1 | `DU(1) \| DITHER_COLOR_Y1(2048)` | 1 |
| 2305 | DU_QUALITY | `DU(1) \| DITHER \| Y1` | 1 |
| 2308 | A2_QUALITY | `ANIM(4) \| DITHER \| Y1` | 4 |
| 2312 | DU4 | `DU4(8) \| DITHER \| Y1` | 8 |
| 4102 | REGAL_D | `REAGL(6) \| REAGL_D(4096)` | 6 |
| 524290 | HW_REPAINT | `GC16(2) \| HANDWRITE_GU(524288)` | 2 |
| 5242886 | REGAL_SHUTDOWN | `REAGL(6) \| SHUTDOWN(5242880)` | 6 |
| 5242978 | GC_SHUTDOWN | `GC16(2) \| WAIT \| FULL \| SHUTDOWN` | 2 |
| 16777217 | X_DU | `DU(1) \| ONYX_AUTO(16777216)` | 1 |
| 16777220 | X_A2 | `ANIM(4) \| ONYX_AUTO` | 4 |
| 33554436 | MONO_A2 | `ANIM(4) \| ONYX_GC(33554432)` | 4 |

**可用标志位全集**（`ViewUpdateHelper.java`）：

| 位 | 常量 | 语义 |
|---|---|---|
| 1–15 | `EINK_WAVEFORM_MODE_*` | ★ **基础波形号** |
| 16 | `EINK_AUTO_MODE_AUTOMATIC` | 自动模式（逐区域） |
| 32 | `EINK_UPDATE_MODE_FULL` | ★ 全屏（0=PARTIAL 局部）|
| 64 | `EINK_WAIT_MODE_WAIT` | 等待完成（0=NOWAIT）|
| 128 | `EINK_COMBINE_MODE_COMBINE` | 合并更新 |
| **256** | `EINK_DITHER_MODE_DITHER` | ★ 抖动 |
| 512 | `EINK_INVERT_MODE_INVERT` | 反色 |
| 1024 | `EINK_CONVERT_MODE_CONVERT` | 颜色转换 |
| **2048** | `EINK_DITHER_COLOR_Y1` | ★ 抖动色阶（0=Y4）|
| 4096 | `EINK_REAGL_MODE_REAGLD` | Reagl-D 变体 |
| 524288 | `EPDC_FLAG_HANDWRITE_GU` | 手写 GU 重绘 |
| 2097152 | （未命名）| MERGE |
| 5242880 | `EINK_FLAG_SHUTDOWN` | 关机刷新 |
| 16777216 | `EINK_ONYX_AUTO_MASK` = `EINK_DITHER_X` | Onyx 自动决策 / X 位 |
| 33554432 | `EINK_ONYX_GC_MASK` = `EINK_APPLY_MONO` | GC / 单色 |

**② ★ 决定「残影」的物理量：同色态驱动率（闪烁度）**

「**同色态驱动率**」= 对 `from == to`（**像素颜色未变**）的 state，波形仍**强行驱动**的比例。
即：颜色没变也要让它摆动一次 → 这就是「闪烁」，也是**清除既有残影**的机制。

| 波形 | 同色态驱动（闪烁度）| state 覆盖 | 帧数 | 清残影能力 |
|---|---|---|---|---|
| INIT (mode0) | **0.750** | 193/256 | 113 | 最强（仅初始化用）|
| **GC16 族 (mode2–5)** | **0.229~0.240** | 243~244/256 | 38 | ✅ **强** |
| DU4 (mode7) | 0.062 | 84/256 | 24 | ⚠️ 弱 |
| **DU (mode1)** | **0.023** | 42/256 | 22 | ❌ 极弱 |
| **A2 (mode6)** | **0.009** | 18/256 | 5 | ❌ 几乎无 |

⇒ **规律：同色态驱动率越高 → 越"闪" → 清残影越彻底。**
⇒ **只有 GC16 族在做全摆动**；DU / A2 / DU4 均为**差分模式**（只驱动真正变化的像素），
**不具备清除既有残影的能力**。

**③ 对当前 launcher 6 档表的结论**

```
SCOPE_VALUES = {-1, -1, 1, 2, 4, 2312}
                None NORMAL DU GC16 A2 DU4
                            ↑
                        唯一清残影
```

| 档 | 波形 | 清残影 |
|---|---|---|
| DU(1) | mode1 | ❌ 差分 |
| **GC16(2)** | mode2 | ✅ **唯一** |
| A2(4) | mode6 | ❌ 差分（且故障机全屏 reset）|
| DU4(2312) | 实测回落 mode2 | ✅（但因回落 GC16 才有）|

⇒ **用户的判断成立**：4 个波形档里，**只有 GC16 清残影**。
   选 DU / A2 时残影必然累积（ref §9.3.20 的周期 GC 会兜底，但周期 GC 走的是
   `toEpdMode(0)`=AUTO ⇒ 实际仍是 GC16 局部，见下条局限）。

**④ ★ 更深一层：`FULL` 位在 scope 通道【不生效】**

ref §9.3.5 全矩阵 + §9.3.17 复核（2026-09-24 实测）一致表明：

| 值 | 位分解 | 实测 update | 结论 |
|---|---|---|---|
| **98** | `GC16 \| WAIT \| **FULL(32)**` | **`update[0]` 局部** | ❌ **显式带 FULL 位仍是局部!** |
| 107 | `GCC16 \| WAIT \| FULL` | `update[0]` 局部 | ❌ 同上 |
| 108 | `DEEP_GC16 \| WAIT \| FULL` | `update[0]` 局部 | ❌ 同上 |

⇒ **`update[0]/[1]` 由 native 依 waveform mode 判定，与 flags 的 FULL 位无关**（§9.3.18）。

> ⚠️ **★ 修正（2026-09-25 第二会话，§9.3.24④）**：本表**仅对 scope 通道成立**。
> 实测 `repaintEverything(98)`（**带参**，走 `REPAINT_EVERY_THING_WITH_MODE`）会得到
> `waveform_mode=2, update_mode=1` —— **FULL 位在该通道【生效】**。
> 准确表述：**scope 通道 FULL 不生效；`REPAINT_EVERY_THING_WITH_MODE` 通道 FULL 生效。**

**⇒ 后果：通过 scope 通道设 98/107/108（GC/DEEP_GC）【清不了整屏残影】**
   —— 它们只对**变化区域**做 38 帧摆动，屏幕其他区域（状态栏、旧内容）的残影不被触及。
   ⇒ 这很可能是"残影多"的**真正根源**：不是没选 GC16，而是 **GC16 也是局部的**。

**⑤ 唯一的整屏全刷手段：`repaintEverything()`**

```java
// ViewUpdateHelper.java:441
public static void repaintEverything() {           // 无参：按当前 scope 波形重画全部窗口
    transactData(REPAINT_EVERY_THING, ...);
}
// ViewUpdateHelper.java:446
public static void repaintEverything(int mode) {   // 带参：指定模式重画全部
    data.writeInt(mode);
    transactData(REPAINT_EVERY_THING_WITH_MODE, ...);
}
```

**launcher 现状**（`RefreshModeHelper.java:251`）：

| 项 | 现状 |
|---|---|
| `fullRefreshScreen()` → `repaintEverything()` | 存在 |
| 调用时机 | **仅切档时一次**（`doApplyWithBypass` 内）|
| 日常使用 | ❌ **无任何周期性/手动全屏清残影入口** |

⇒ 用户当前若长期停留在某一档而不切档，**残影只会累积，不会被整屏清除**。

**⑥ 潜在改进方向（仅记录，未实施 —— 设备断开无法验证）**

| 方向 | 做法 | 风险 |
|---|---|---|
| A | 加**手动「全屏清残影」**入口（调 `fullRefreshScreen()`）| ⚠️ **安全但无效** —— 实测无参版不带 FULL 位（§9.3.24④）|
| B | **周期性自动全刷** | ❌ **会周期性闪烁 + 周期性 reset**（故障机不可取）|
| C | 档位表增加 98/108 并标注"仅清残影用" | **低效** —— §④ 已证 scope 通道 FULL 位不生效 |
| D | 用 `repaintEverything(98)` 带参版指定 GC 模式全刷 | ✅ **FULL 生效**，但 ❌ **故障机必然 reset**（5 次中 3 次，§9.3.24⑤）|

> ✅ **★ 已实测（2026-09-25 第二会话，§9.3.24④⑤）**：
> - **D 的疑问已解**：带参版 `REPAINT_EVERY_THING_WITH_MODE` 与 scope 通道**不同**，
>   **FULL 位生效**（`update_mode=1`）。
> - **但代价已量化**：全屏 ⇒ `update[1]` ⇒ `wait all_lut_free` ⇒ 故障机 **5 次中 3 次 reset**
>   （`waveform[2] update[1] frame_cur[1] frame_total[38]`，与 A2 同一条路径）。
> - **A 的疑问已解**：无参 `repaintEverything()` 输出 `waveform_mode=255, update_mode=0`
>   ⇒ **launcher 的「切档后全刷」其实只是局部重画，并非整屏清残影**（重要认知修正）。
>
> ```
> ⇒ 净结论：故障机上「整屏清残影」与「避免 reset」物理不可兼得。
>   launcher 现状（无参 repaint）恰是故障机唯一安全选择，不应改动。
>   正常机应无此问题 ⇒ 可为正常机提供清残影入口（§6.6 待验证）。
> ```

**⑦ 复现所需数据来源**

- 位分解表：`ViewUpdateHelper.java`（ref §9.3.10 已全值校验）
- 同色态驱动率：`_scratch_gs/wbf_shape.py` / `wbf_phase.py`（解码 `eink_waveform.wbf`）
- FULL 位不生效：`_scratch_gs/full_matrix.sh` + §9.3.17 分离实验
- 周期 GC 语义：§9.3.20（输入计数触发，`toEpdMode(0)`=AUTO=GC16 局部）

#### 9.3.23 ★★★ 死机重启 + 刷新停滞的完整证据链（2026-09-25，故障机 6C7F0E64）

> 起因：用户报告"出现死机重启现象，这几个模式都无法解决刷新停滞导致一次性刷新重叠问题"。
>
> **★ 核心结论：两个现象根因不同，且【都与刷新模式选择无关】：**
> 1. **重启** = §12.7 的 WMS WTF 老毛病复发（`WakeLock tag:WindowManager ... is forbidden`），
>    但**本次证据显示它不是 Watchdog 触发**，而是另有触发源（见③）。
> 2. **刷新停滞** = **上层根本没有提交帧**（`SET_EBC_SEND_UPDATE = 0`），
>    ⇒ 不是"模式不给力"，而是**没有东西可刷**。换模式必然无效。

**① 现场状态（2026-09-25 01:51 重启后采集）**

| 项 | 值 |
|---|---|
| uptime | 204s（刚重启，`bootreason=reboot` 软件重启） |
| 内核 | `-dirty #79 SMP PREEMPT Mon Mar 16 18:22:03`（= v6 patched） |
| 当前档位 | `launcherRefreshMode=2` → 新表 index 2 = **DU(1)** |
| prefs 时间线 | 01:04 NORMAL → 01:29 GC16 → 01:35 DU → 01:39 None → 01:46 DU → 01:48 DU |

**② ★ 刷新停滞的证据：`SET_EBC_SEND_UPDATE = 0`**

```bash
# 启动后 452s 的 dmesg 全量统计
SET_EBC_SEND_UPDATE  : 0      ← ★ 完全没有帧提交!
CLEAR_ALL_UPDATE     : 0
dump_lut_list        : 6      ← 仅在 reset 时打印
reset cause          : 3      ← 全部在启动 25~27s
epdc power error     : 57
TPS6518x 失败        : 116
Unable to enable DISPLAY: 57
Pending Wakeup Sources: 24（含 epdc_power）
```

**⇒ 无帧提交 ⇒ 无 LUT ⇒ 无波形 ⇒ 无从谈"模式"** —— 这解释了"这几个模式都无法解决"：
**不是模式无效，而是根本没有刷新请求到达 EPDC。**

**③ 重启时刻的 WTF 密度（推翻 §12.7 的 Watchdog 结论）**

dropbox 全量：**944 个 `system_server_wtf`**（跨 09-24 00:07 ~ 09-25 01:57），栈恒定：

```
Subject: PowerManager
android.util.Log$TerribleFailure: WakeLock tag:WindowManager from [android] is forbidden
    at PowerManager$WakeLock.acquire(PowerManager.java:2423)
    at WindowManagerService.setHoldScreenLocked(WindowManagerService.java:5620)
    at RootWindowContainer.performSurfacePlacementNoTrace(RootWindowContainer.java:939)
```

**10 分钟桶分布（找爆发）**：
```
09-24 23:50 : 136    ← 爆发
09-25 00:10 : 233    ← 峰值
09-25 00:20 :  73
09-25 01:50 :  63    （重启后）
```

**★ 关键判定 —— WTF 时间线【无中断】**：
```
最后 WTF: 09-25 01:57:25
重启时刻: 09-25 01:48（由 launcher 日志 "== init ==" 判定）
```
⇒ 01:48 重启后 WTF **继续累积**（01:48~01:57 共 132 个），说明
**重启不是 WTF 爆发到 Watchdog 阈值所致**（若如此，重启前一瞬应有尖峰、重启后归零）。
⇒ **§12.7 的"WTF 刷屏 → Watchdog"论断本次【未被证实】**，重启另有触发源（未定，需 events 日志）。

**④ ★ ★ 时间相关性：WTF 爆发窗口与 launcher 切档【不重合】**

| WTF 爆发窗口 | launcher 切档记录 |
|---|---|
| 09-24 23:50 (136) | ❌ **无任何记录**（日志从 12:20 直接跳到 09-25 01:04）|
| 09-25 00:10 (233) | ❌ **无任何记录** |
| 09-25 00:20 (73) | ❌ **无任何记录** |

⇒ **WTF 爆发与"切档"无关**（§12.7 曾归因于"EAC 批量写 12 app"，但当前 launcher 已改为
scope-only 切档，**已无批量写 EAC**）。⇒ 需另找爆发源（候选：USB 投屏切换、SystemUI 窗口动画）。

**⑤ §12.7 对策的有效性复核**

| §12.7 对策 | 状态 | 本次观测 |
|---|---|---|
| "EAC 写入改 save-only" | ✅ 已实施（`applyFixedEac` 只在启动调一次）| 切档已无批量写 |
| "消除最大批量窗口重建源" | ✅ 已实施 | 但 **WTF 仍达 944 个** ⇒ 该源不是唯一/主要源 |
| "避免瞬时批量窗口操作" | ⚠️ | WTF 爆发窗口（23:50/00:10）无 launcher 操作 ⇒ 是**系统自身**行为 |

**⑥ 对用户问题的直接回答**

| 用户假设 | 判定 |
|---|---|
| "这几个模式都无法解决刷新停滞" | ✅ **现象成立，但归因错误** —— 停滞原因是**零帧提交**（②），换任何模式都无效 |
| "刷新停滞导致一次性刷新重叠" | ⚠️ **无证据支持"重叠"** —— dmesg 里 `pending_list` 仅 15 条且都在 reset 时打印，**无堆积**（对比 §9.3.3 的"中位 2 条/次"基线）|
| "需要选清残影模式"（上一轮） | ⚠️ 与本轮重启**无关** —— 重启发生在 25s power error 期，非残影问题 |

**⑦ 待查（设备已可用，可继续）**

| # | 待查项 | 方法 |
|---|---|---|
| 1 | **重启触发源** | `logcat -b events` 的 `boot_progress` / `watchdog` 记录；`/data/misc/reboot/` |
| 2 | **WTF 爆发源** | WTF 时间戳与 `logcat -b events`（am_/wm_ 事件）对齐 |
| 3 | **"零帧提交"是否持续** | 实机操作（翻页）后看 `SET_EBC_SEND_UPDATE` 是否出现 |
| 4 | **power error 与 blackout 的关系** | `epdc_power` wakelock 长期持有 + `Unable to enable DISPLAY regulator.err = 0xffffff92` |

**⑧ 复现 / 采集命令**

```bash
# 重启原因
adb shell su -c "getprop ro.boot.bootreason; cat /proc/uptime"
# WTF 数量与分布
adb shell su -c "ls /data/system/dropbox/system_server_wtf* | wc -l"
adb shell su -c "grep -m1 TerribleFailure /data/system/dropbox/system_server_wtf@<ts>.txt"
# 帧提交（滞停关键指标）
adb shell su -c "dmesg > /data/local/tmp/dm.txt"
adb shell su -c "grep -c SET_EBC_SEND_UPDATE /data/local/tmp/dm.txt"
```

**⑨ ★★ 追加实测（2026-09-25 02:00，用户报告"停滞"期间）

**用 root 注入 input 验证**（`adb shell su -c "input swipe ..."`，`rc=0` 成功）：

| 项 | 值 |
|---|---|
| 前台 app | `com.qidian.QDReader/.ui.activity.QDReaderActivity`（起点阅读）|
| `mWakefulness` | `Awake`（屏幕亮着）|
| **`SET_EBC_SEND_UPDATE`** | **0** ← ★ 翻页操作后仍为零 |
| `appScopeRefreshMode` | 2（= EAC 逻辑 A2，**但此读数不可信**，§9.3.17③）|
| **`fastModeIndex`** | **2**（app fast mode —— 说明 SF 侧处于快速模式）|

**dmesg 实录（swipe 后连续）**：
```
[644.963] Reg PowerGood: [0xf] 0xBA
[644.963] ERROR TPS6518x waiting for power good!
[645      Retry 2 more times
[646.595] ERROR TPS6518x waiting for power good!
[646.596] Unable to enable DISPLAY regulator.err = 0xffffff92
[646.596] onyx_epdc_powerup(): epdc power error!
[650.972] (同上循环)
[652.575] onyx_epdc_powerup(): epdc power error!
[657.601] (同上循环)
```
⇒ **每 2~5 秒一次 powerup 失败循环**，且**全程零帧提交**。

**⑩ ★★ 结论修订：停滞根因 = 供电失败导致 EPDC 无法接收更新**

| 环节 | 状态 |
|---|---|
| 用户操作 | ✅ 正常（swipe 注入成功、前台是阅读器）|
| 上层合成 | ✅ 正常（`fastModeIndex=2` 说明 SF 活跃）|
| **EPDC powerup** | ❌ **持续失败**（`TPS6518x` + `err=0xffffff92`）|
| **帧提交到 EPDC** | ❌ **0 次** |
| ⇒ 结果 | **画面停滞**（有输入、有合成、无显示）|

**⇒ 这不是"模式问题"，也不是"残影问题"** —— 是 **TPS6518x 供电故障的又一次表现形态**。
   ⇒ 换 DV/GC16/A2/DU4 **一律无效**（用户观察"这几个模式都无法解决"得到解释）。

**⑪ 对"一次性刷新重叠"的判定**

| 用户描述 | 证据 |
|---|---|
| "刷新停滞" | ✅ 成立（零帧提交）|
| "一次性刷新重叠" | ⚠️ **未观测到** —— powerup 成功时会一次性补刷累积内容，视觉上可能表现为"重叠"；但 dmesg 里 **`pending_list` 无堆积**（§⑥）|

⇒ 推测：**powerup 偶尔成功时，把积压的画面一次性刷出** → 视觉上像"重叠/跳变"。
   本质仍是**供电间歇性成功**，非软件堆积。

**⑫ 待验证（下一步）**

| # | 假设 | 验证方法 |
|---|---|---|
| 1 | powerup 成功时确有"一次性补刷" | 抓 powerup 成功瞬间的 `SET_EBC_SEND_UPDATE` + LUT dump |
| 2 | `err=0xffffff92` 的具体含义 | 查 `regulator` 错误码（`-0x6E` = `-ENODATA`?）|
| 3 | 是否与电池电量/温度相关 | 对比 `heal thd` 的 l/v/t 与 powerup 成功率 |

**⑬ ★★★ 决定性实验（2026-09-25 02:10，前台=Legado 阅读页，用户要求重测）

**★ 用 `/sys/class/sepdc/debug/status` 的 `frame[a:b:c]` 三值作为主指标**（比 dmesg 可靠，
不受 `debug_level=0` 影响）：

```
t(0.4s采样)  frame[a:b:c]          解读
0~6    15290:15290:15290     停滞 2.8s（三值相等）
7      15318:15317:15316     ★ a>b>c  开始推进
8      15328:15328:15328     追平 +38（GC16 一帧完成）
9      15364:15363:15362     ★ a>b>c
10~19  15404:15404:15404     停滞 3.6s
20     15440:15439:15438     ★ a>b>c
21     15480:15480:15479     ★
22     15523:15522:15521     ★
23~24  15556:15556:15556     追平
```

**⇒ 停滞-突增的量化周期 = 约 38 帧/次**（正是一个 GC16 波形）：
```
停滞 2.8~3.6s → 推进 +38帧 → 停滞 → +38帧 → ...
```

**⑭ ★★★ 根因定论：`update_err=1` 使 EPDC 每帧都要"重试上电"**

`/sys/class/sepdc/debug/update_err` **恒为 1**（我持续采样 25 次全是 1）。

结合 dmesg 的完整模式：
```
[1148.933] Reg Enable: [0x1] 0xAF
[1148.933] Reg PowerGood: [0xf] 0xBA
[1148.933] ERROR TPS6518x waiting for power good!
[1148.933] Retry 2 more times                          ← 第 1 次重试（可能成功）
[1150.564] Reg Enable: [0x1] 0xAF
[1150.564] Reg PowerGood: [0xf] 0xBA
[1150.564] ERROR TPS6518x waiting for power good!
[1150.564] Unable to enable DISPLAY regulator.err = 0xffffff92  (-ETIMEDOUT)
[1150.564] onyx_epdc_powerup(): epdc power error!      ← 第 2 次重试失败
```

**⇒ 链条**：
```
TPS6518x power good 位永不置起（PowerGood 回读恒 0xBA，无变化）
  → 每次 powerup 需重试 2 次（每次 ~1.6s i2c 超时）
  → 帧虽然提交了（frame 计数增长），但【每帧要等 1.6~3.2s 的供电重试】
  → 期间新提交的帧堆积（drm_commit_pending_cnt[2]）
  → 重试成功的那一帧一次性刷出【累积的所有变化】
  ⇒ 视觉表现 = "停滞 → 一次性刷新重叠"
```

**⑮ ★ 推翻我上一轮的两个错误结论（重要）**

| 上一轮结论 | 本次证据 | 修正 |
|---|---|---|
| "`SET_EBC_SEND_UPDATE = 0` ⇒ 零帧提交" | `frame[]` 持续增长（每次 +38~137）| ❌ **错** —— 该打印仅在 `debug_level≥1` 时输出，**与帧提交无关** |
| "停滞 = EPDC 收不到帧" | frame 确实在增长，但**慢且跳变** | ❌ **错** —— 是**帧提交后执行被供电重试拖慢** |

**⇒ 正确诊断：帧提交正常，执行被 TPS6518x 供电重试拖成"停顿式"**

**⑯ 关键对照实验（证明与"操作"无关）**

| 场景 | dmesg 行数 | Reg Enable 次数 |
|---|---|---|
| 翻页 4 次（~4s） | **19** | 2 |
| **idle 30 秒** | **41** | 更多 |

⇒ **翻页时的 EPDC 活动比 idle 还少** —— 说明"供电重试"是**持续背景行为**，
   与用户操作无关；操作只是让**积压的帧**在重试成功时被一次性刷出。

**⑰ 与刷新模式的关系（回答用户"几个模式都无法解决"）**

| 档位 | 对停滞的影响 |
|---|---|
| DU(1) | ❌ 无效 —— 停滞源于供电重试，非波形 |
| GC16(2) | ❌ 无效 |
| A2(4) | ❌ 无效（且会引入全屏 reset 循环，§9.3.17）|
| DU4(2312) | ❌ 无效（回落 GC16）|
| **任意档** | **❌ 都无效** —— 根因在电源硬件，模式层无法触及 |

⇒ **用户观察"这几个模式都无法解决"完全正确**，原因已查明。

**⑱ 待验证（下一步方向）**

| # | 假设 | 验证 |
|---|---|---|
| 1 | 提高 i2c 重试的**超时容忍**（v5 patch 已从 5s→0.5s）是否反而有害 | 对比 v5/v6 下 `Retry 2 more times` 的分布 |
| 2 | 降低 `powerup` 频率（合并帧）能否减少重试 | 调 `cut_frame_num` 观察 |
| 3 | `PowerGood: [0xf] 0xBA` 是否**恒为固定值**（硬件状态字不更新）| 已证：24 次回读全部 `0xBA` |
| 4 | 能否通过 **`panel_clean` / `reset_test`** 节点强制恢复 | 节点存在（`panel_clean` 只读值 `ok`，`reset_test` 可写）|

**⑲ ★★★ 新现象实测：所有模式都"双刷"（2026-09-25 02:20，用户报告）

> 用户报告："所有模式都会一次性刷新两次。就算不用 launcher 也会。"

**① 受控实验：单次翻页 → 两组刷新**（20ms 采样 `frame[]`）

```
采样 0~9   (0~200ms)     : 17858 → 17894   +36帧  ← 第 1 组
采样 10~111(200~2240ms)  : 静止 2.0s
采样 112~140(2240~2820ms): 17899 → 18008  +109帧  ← 第 2 组
```
⇒ **一次操作确实产生两组刷新，中间停 2.0 秒** —— 用户观察成立。

**② ★ 决定性对照：完全不操作时，同样双刷**

```
采样 0~19  (0~380ms)     : 18046 → 18084   +38帧
采样 20~119(380~2380ms)  : 静止 2.0s
采样 120~129(2380~2580ms): 18086 → 18122  +36帧
```
⇒ **不操作也双刷**（周期约 2.0~2.4s）。

**③ ★★ 决定性实验：停掉 launcher 后仍然双刷**

```bash
adb shell su -c "am force-stop cn.modificator.launcher"
adb shell su -c "pidof cn.modificator.launcher"   # → 空（已停）
```

停止后带时间戳采样（`/proc/uptime` 为时钟）：
```
kt=3752.505 → 3752.803   +37帧  (0.30s 内)
[停 4.47s]
kt=3757.269 → 3757.683   +37帧
[停 2.41s]
kt=3760.092 → 3760.505   +38帧
```

连续 `status` 观察（无 launcher）：
```
kt=1769.49 frame=18959
[停 3.73s]                       ← 停滞
kt=1773.22 frame=18962  (+3)
kt=1773.31~1774.09 frame 18962→19035 (+73, 0.9s 内连续推进)
```

**⇒ 结论：双刷与 launcher【完全无关】（用户判断正确）。**
   **⇒ 也与刷新模式无关** —— 是**系统级后台行为**。

**④ 双刷的量化特征**

| 指标 | 值 |
|---|---|
| 每组帧数 | **37~38 帧**（= 一个 GC16 波形）|
| 停滞时长 | **2.0 ~ 4.5 秒**（波动）|
| 是否需操作 | ❌ **不需要**（idle 也有）|
| 是否需 launcher | ❌ **不需要**（force-stop 后仍有）|
| 每组内部 | 连续推进约 0.3~0.9s |

**⑤ 与供电故障的关系（推断）**

结合 §⑭（`update_err=1`、每次 powerup 重试 2 次、每次 ~1.6s i2c 超时）：
```
一个 GC16 需 38 帧 → 需要 1 次成功的 powerup
powerup 失败 → 重试（1.6s）→ 再失败 → 重试 → 成功
成功的平均间隔 ≈ 2.0~4.5s
⇒ 每次 powerup 成功 = 刷出积压的一批帧（≈38帧）
⇒ 视觉上 = "刷一次 → 停 → 再刷一次"（双刷的来源之一）
```

**⑥ ★ 对"双刷"的两种可能机制（待区分）**

| 机制 | 说明 | 验证方法 |
|---|---|---|
| **A. 供电重试** | powerup 失败→重试→成功，成功时刷一批 | 对齐 powerup 时间戳与 frame 跃变 |
| **B. 系统双发** | 系统本身对同一变更提交两次（如 EAC 的 GC + 应用刷新）| 查 `dump_lcdc_state` 的 commit 计数 |

**⇒ 现有证据偏向 A**（`update_err=1` 恒定 + powerup 重试日志密集 + 停 launcher 后双刷依旧）。

**⑦ 待办**

| # | 事项 |
|---|---|
| 1 | 停 launcher + 无操作时，对齐 `Reg Enable` 时间戳与 `frame` 跃变（需解决脚本内 dmesg 权限问题）|
| 2 | 逆向 `update_err` 的清除条件（该标志是否为"上次失败未恢复"的粘滞位）|
| 3 | 确认 `frame[a:b:c]` 三值的语义（`a≠b≠c` 时的含义）|

**⑧ ★ 工具教训（本轮踩坑，务必遵守）**

| 坑 | 现象 | 正确做法 |
|---|---|---|
| **脚本内 `dmesg` 失败** | 脚本里 `dmesg > f` 得到空文件或 `klogctl: Permission denied`；同命令经 `su -c` 直连正常 | **先 `adb shell su -c "dmesg > /data/local/tmp/x.txt"` 导出，再让脚本只读该文件** |
| `echo > file` 被 PowerShell 截获 | `su -c "echo xxx > file"` 时 `>` 在宿主机执行，脚本内容损坏 | 用 `write_file` 写好脚本再 `adb push` |
| `input swipe` 权限 | 非 root 注入报 `INJECT_EVENTS permission` | 必须 `su -c "input ..."` |
| `reset_test` 不可写 | `--w-------` 但 `echo 1 >` 仍 `Permission denied` | 该节点被 SELinux 保护，**不可用** |

**⑨ 节点可写性实测一览（2026-09-25）**

| 节点 | 权限 | 可写 | 语义 |
|---|---|---|---|
| `status` | `-r--r--r--` | ❌ | 状态（frame/luts/wb 等）|
| `update_err` | `-r--r--r--` | ❌ | 错误标志（恒 1）|
| `panel_clean` | `-r--r--r--` | ❌ | 只读取值 `ok`（**非命令节点**）|
| `panel_init` / `panel_last` | `-r--r--r--` | ❌ | 同上 |
| `dump_list` | `-r--r--r--` | ❌ | cat 即触发 dump |
| `reset_test` | `--w-------` | ⚠️ **实测被 SELinux 拒** | 调试用强制 reset |
| `submit_upd_work` | `--w-------` | ⚠️ 未测 | 手动提交更新 |
| `night_mode` | `--w-------` | ⚠️ 未测 | 夜间模式 |
| `update_snapshot` | `--w-------` | ⚠️ 未测 | 快照 |
| **`debug_level`** | `-rw-r--r--` | ⚠️ **实测 writable 但被拒** | 日志级别 |
| **`cut_frame_num`** | `-rw-r--r--` | ⚠️ 未测 | 截断帧数 |
| **`update_disable`** | `-rw-r--r--` | ⚠️ 未测 | 禁用更新（值 0）|
| `time_test_level` | `-rw-r--r--` | ⚠️ 未测 | 时间测试级别 |

⇒ **`panel_clean` 等"清残影节点"不存在**（只读状态指示）；`reset_test` 被拒。

**⑳ ★★★ 机制 A 验证：双刷 = powerup 重试周期（2026-09-25，待明日补完）

**① 时间对齐证据（两份独立数据，均为 idle 无操作）**

**数据源 1 — frame 跃变序列**（`/proc/uptime` 为基准，50ms 采样）：
```
kt=2381.08 → 2381.49  : 21463 → 21499  (+36)  持续 0.41s
[停 5.00s]
kt=2386.49 → 2386.89  : 21507 → 21537  (+30)  持续 0.40s
[停 27.5s]                                      ← 长间隔
kt=2414.39 → 2414.90  : 21539 → 21575  (+36)  持续 0.51s
```

**数据源 2 — powerup 周期**（dmesg，同源 `kt` 时钟）：
```
kt=1946.465  Reg Enable → Retry 2 more times     ← 第 1 次重试
kt=1948.194  Reg Enable → epdc power error!      ← 第 2 次重试也失败  [Δt=1.73s]
kt=1952.503  Reg Enable → Retry 2 more times     ← 新周期开始        [停 4.31s]
kt=1954.196  Reg Enable → epdc power error!      ← 失败              [Δt=1.69s]
kt=1955.130  powerdown ... clear!                ← 周期结束
kt=1959.844  下一轮 Reg Enable                   [停 4.71s]
```

**② ★ 关键比对：两组数据的"停滞时长"同一量级**

| 指标 | frame 跃变 | powerup 周期 |
|---|---|---|
| 停滞时长 | 5.00s / 27.5s | 4.31s / 4.71s |
| 活动时长 | 0.40~0.51s | 1.69~1.73s（2 次重试）|

⇒ **停滞期 ≈ powerup 失败重试期**（都是 4~5 秒量级，与 §⑭ 的"每次重试 ~1.6s"吻合）。

**③ 机制 A 成立性判定**

```
一个 GC16 波形需 38 帧 → 需 1 次成功 powerup
powerup 失败 → 重试 2 次（各 ~1.7s）→ 若都失败则放弃、等下一轮
下一轮间隔 ≈ 4~5s
成功的那些轮次 = 执行 38 帧 = frame +36~38
⇒ frame 跃变（+36）与 powerup 周期（4~5s）在时间尺度上对应
```

**⇒ 机制 A（供电重试驱动）【成立】：双刷是 powerup 重试节奏的直接体现。**

**④ 但有一处待解释**

| 现象 | 说明 |
|---|---|
| 观察到 27.5s 的长停滞 | 远超 4~5s 周期 ⇒ 可能是**连续多轮 powerup 全失败**，或系统进入某种省电态 |
| frame 跃变恒为 +30~38 | 与 GC16 的 38 帧吻合；但**为何 idle 也持续刷 38 帧**？—— 疑为系统周期性重绘（时钟/状态栏）触发 |

⇒ **待明日验证**：抓 27.5s 长停滞期间的完整 dmesg（是否有多轮连续失败）。

**⑤ 明日待办清单**

| # | 事项 | 方法 |
|---|---|---|
| 1 | 补完 27.5s 长停滞的解释 | 抓长停滞期完整 dmesg |
| 2 | 确认 idle 为何持续刷 38 帧 | 停 launcher + 停所有前台 app 后观察 |
| 3 | 验证 `epdc-power-fail-dont-update` 属性 | 该 dt 属性（内核有解析代码）疑似控制"供电失败时不更新"，**若为 0 则失败也照刷 → 可能正是双刷来源** |
| 4 | 逆向 `a2-clean-mode` / `a2_frame_num` | 内核 `onyx_epdc_parse_dt` 有解析，可能可调 |
| 5 | 试写 `cut_frame_num` / `update_disable` | 唯一两个可写且未测的节点 |

**⑥ ★ 本次逆向新发现的 EPDC 可配置项（供明日用）**

从内核 `onyx_epdc_parse_dt`（0x53f7c0 区域）挖出的**完整 dt 属性清单**：

| 属性名 | 结构体偏移 | 推测作用 |
|---|---|---|
| `epdc-init-mode-force-disable` | — | 强制禁用 init 模式 |
| **`epdc-power-fail-dont-update`** | — | ★ **供电失败时不更新**（若为 0 → 失败也刷）|
| `epdc-panel-v3p3-always-on` | — | 面板 v3p3 常开 |
| `epdc-waveform-load-delay` | — | 波形加载延迟 |
| `panel-pwrdown-delay` | — | 掉电延迟 |
| `panel-pwrdown-wait` | — | 掉电等待 |
| `sf-rotation` | — | SF 旋转 |
| `cfa_mode` | — | 彩色滤光片模式 |
| **`a2-clean-mode`** | — | ★ **A2 清残影模式** |
| **`a2_frame_num`** | — | ★ **A2 帧数** |
| `cut-frame-fill-zero` | — | 截帧填充 0 |
| `power-timeout-deteck` | — | 供电超时检测 |
| `temp-deteck-enable` | [x21+0x271 附近] | 温度检测开关 |
| `temp-deteck-from-pmic` | `[x21,#0x271]` | 温度来源=PMIC |
| `dither-set-disable` | `[x21,#0x272]` | 禁用 dither |
| `epdc-gu-regal-enable` | `[x21,#0x273]` | GU REGAL 启用 |
| `temp-diff` | `[x21,#0x270]` | 温度差阈值 |

> **解读**：这些是**内核按 `of_property_read_*` 从设备树读取**的（`bl #0xaecf58` = dt 属性读取函数，
> 返回 0 表示属性不存在 → 存入结构体的值为 0/默认）。
> ⇒ **属性不存在时功能关闭**；若要启用需**改设备树**（dtb），而非改 sysfs。

> ⚠️ **★ 重大修正（2026-09-25 第二会话实测，见 §9.3.24②）**：上表 17 个属性在**本机设备树中
> **全部不存在**（仅 `epdc-waveform-load-delay` 存在，值 4000）。故上述「改 dtb 启用」的路径
> **不成立** —— 不是"有开关没打开"，而是"设备树从未定义这些接口"。

---

#### 9.3.24 ★★★ 第二会话实测：推翻 3 条旧结论 + FULL 位新机制（2026-09-25，故障机 6C7F0E64）

> 起因：按 HANDOFF §6 的候选任务清单逐项执行（§6.1 双刷机制 / §6.2 dt 属性 / §6.3 重启触发源 /
> §6.4 清残影入口 / §6.5 可写节点）。
> 设备：内核 v6-A2（`#79`），uptime 从 ~33000s 起。
> **dmesg 基准标定**：`K = uptime_at_dump − dmesg末行kt ≈ **27687.6**`（三份独立采集解出同一值）。

**① §6.1 双刷机制 A【证伪】**

用 SDM 无关的三路独立数据（frame 采样 / dmesg 电源事件 / `pending_cnt`）对齐：

| 证据 | 数据 | 结论 |
|---|---|---|
| **时序方向** | `Reg Enable` 全部出现在 frame 推进段**开始之后**（lag +0.01 / +0.67 / +1.44 / +3.13 / +10.43s） | ❌ **不是「powerup 驱动刷新」**，而是**「刷新触发 powerup」** —— 原判据蕴含了错误因果方向 |
| **`pending_cnt` 性质** | `=2` 出现 3.2~3.7% 采样，且与 4 个推进段**逐段对齐**；末期回 0 | ❌ **不是"堆积"** —— 是**双缓冲流水线正常状态**（一个在写、一个在等）|
| **idle vs 操作** | 纯 idle 60s 内 3 组自发刷新（`+19~22` 帧 / 0.3s，间隔 ~32s）；注入 swipe 后 1 组（`+20` 帧）**幅度相同** | ❌ 操作与 idle **无差别** |
| **空档期性质** | `epdc_active_luts` 每组后归零；两组之间**零** reset / power error / LUT 活动 | **EPDC 完全空闲**（非供电重试、非停滞）|

⇒ **机制 A（双刷 = powerup 重试周期）证伪。**
⇒ 每组帧数 `+17~22` = **一个 DU 波形**（非 GC16 的 38）⇒ 与"一个 GC16 需 38 帧"的推理也不符。
⇒ **新假设（未验证）**：**上层（SF / Onyx 框架）周期性自发提交同幅刷新**；建议下一步查
   EAC 的 `gcInterval` / debouncer / 系统时钟重绘源。

> ⚠️ HANDOFF §6.1 原定判据「每次 frame 跃变前 ~1.7s 应有 `Reg Enable`」**不成立**。

**② §6.2 设备树 EPDC 属性：16/17 不存在（上文 §6 清单作废）**

全树遍历 `/sys/firmware/devicetree/base` 的结果：

```
[MISSING] epdc-power-fail-dont-update      [MISSING] epdc-panel-v3p3-always-on
[MISSING] epdc-init-mode-force-disable     [MISSING] a2-clean-mode / a2_frame_num
[MISSING] panel-pwrdown-delay / -wait      [MISSING] epdc-gu-regal-enable
[MISSING] dither-set-disable               [MISSING] cfa_mode / sf-rotation
[MISSING] cut-frame-fill-zero              [MISSING] power-timeout-deteck
[MISSING] temp-deteck-enable / -from-pmic  [MISSING] temp-diff
[FOUND]   epdc-waveform-load-delay = 00 00 0F A0 (= 4000)   @ /soc/sepdc_mfd
```

节点 `soc/sepdc_mfd`（`compatible = "onyx,sepdc_mfd"`）只有 3 个属性：
`compatible` / `epdc-waveform-load-delay` / `status`。dtb 在独立分区 `dtbo_a`/`dtbo_b`（`dtbo_idx=2`）。

⇒ **HANDOFF §6.2「最有价值的软件缓解点」不存在** —— `of_property_read_*` 全部返回非 0，
   功能处于默认关闭，且**上游从未定义**这些属性 ⇒ **优先级应大幅下调**。

**③ §6.3 重启触发源：全部软件 reboot，无崩溃无 ANR 无 watchdog**

```
ro.boot.bootreason = reboot        sys.boot.reason = reboot
persist.sys.boot.reason.history =
    reboot,1790272078
    reboot,1790170752
    reboot,shell,1790169156      ← shell 发起（开发期手动）
    reboot,shell,1790167707
dropbox 类型统计（1000 文件）: system_server_wtf 963 / system_app_wtf 23 /
                              data_app_wtf 13（全为 com.qidian.QDReader）/ SYSTEM_BOOT 1
崩溃 / ANR / native_crash / tombstone : 【0】
logcat -b events grep watchdog      : 【0】
```

⇒ **WTF 与重启无因果**（WTF 时间线跨重启无中断，再次否定 §12.7 的 Watchdog 假说）。
⇒ `reboot,`（无 `shell` 前缀）= 由 **system_server / init 调用 `reboot()`**。
⇒ **触发源仍未定位** —— 当前 `events` buffer 仅约 40 分钟，不足覆盖重启时刻。
   **建议**：常驻 `logcat -b events` 落盘，待下次重启抓 `reboot_requested`。

**④ ★★ §6.4 `FULL(32)` 位在「带参 `repaintEverything`」路径上【生效】**

新观测手段：SDM 的 `update_to_display` 日志**直接给出** SF→EPDC 的真实 `waveform_mode` /
`update_mode`，**不受 `dump_lcdc_state` 淹没影响**：

```bash
adb logcat -d | grep 'update_to_display'
#  → update_to_display[1/0] -- marker[N] waveform_mode = X, update_mode = Y, Rect[…], flags = Z
```

完整对照表（每项单独触发，`logcat -c` 后观测）：

| 调用 | `waveform_mode` | `update_mode` | 全屏？ |
|---|---|---|---|
| 仅设 `scope=98` + swipe | 255 | 0 | ❌ |
| 仅 `scope=2` + swipe | 255 | 0 | ❌ |
| **无参 `repaintEverything()`**（launcher `fullRefreshScreen()` 现用） | 255 | 0 | ❌ |
| 带参 `repaintEverything(2)`（纯 GC16） | 2 | 0 | ❌ |
| **带参 `repaintEverything(98)`**（GC16\|WAIT\|FULL） | **2** | **1** | ✅ |
| **带参 `repaintEverything(108)`**（DEEP_GC16\|WAIT\|FULL） | **12** | **1** | ✅（**未回落**）|
| scope=A2(4)（历史对照） | 6 | 1 | ✅ |

⇒ **推翻「FULL 位一律不生效」** —— 结论应修正为：
   **scope 通道 FULL 不生效（§9.3.17⑤ 正确）；但 `REPAINT_EVERY_THING_WITH_MODE` 通道 FULL 生效。**
⇒ **连带修正**：launcher 的「切档后全刷」实际只是**按当前 scope 局部重画**，**并非整屏清残影**。

**⑤ ★★★ 全屏 GC16 在故障机【必然 reset 风险】—— 与 A2 同一条路径**

连续 5 次 `repaintEverything(98)`（间隔 9s）：**C2 / C3 / C5 共 3 次 reset**，C1 / C4 干净完成。

reset 三连的完整链（C2 为例）：
```
kt=7774.950  o_e_f_f_u(): Flush updates timeout! updates_active[1]. caller = __onyx_epdc_buf_put_queue+0x5c0
kt=7774.951  dump_lut_list(): magic[8128] lut[0] waveform[2] update[1] frame_cur[1] frame_total[38]!
kt=7775.450  o_e_u_w_s(): wait all_lut_free timeout 500 ms!
kt=7775.450  onyx_epdc_reset(): reset cause[update wb wait all_lut_free timeout].
kt=7775.451  onyx_epdc_reset(): set update_err.
```

⇒ **卡在第 1 帧（`frame_cur[1]`）**，`update[1]` 全屏 → 必须 `wait all_lut_free`（等所有 LUT 空闲）
   → 供电故障致其它 LUT 卡住 → 500ms 超时 → reset。
⇒ **触发 reset 的是 `update[1]` 全屏属性，与波形是 GC16 还是 A2 无关。**

**⑥ 对「手动清残影入口」的最终裁决**

| 方案 | 裁决 |
|---|---|
| A. 手动按钮调**无参** `repaintEverything()` | ✅ 安全但**无效**（实测不带 FULL 位）|
| B. 手动按钮调**带参** `repaintEverything(98)` | ⚠️ **有效但会 reset**（故障机 3/5 失败）；**正常机可考虑** |
| C. 档位表加 98/108 | ❌ 无效（scope 通道 FULL 不生效，本轮复核确认）|
| D. 周期性自动全刷 | ❌ 会周期性闪烁 **+ 周期性 reset** |

```
⇒ ★ 净结论：故障机上「整屏清残影」与「避免 reset」物理上不可兼得 ——
   整屏清残影必须 update[1] 全屏，而全屏必然 wait all_lut_free 超时。
⇒ 当前 launcher 现状（无参 repaint，局部重画）恰是故障机唯一安全选择，【不应改动】。
⇒ 用户若嫌残影：现实手段是**改用 GC16 档**（清"变化区域"），整屏清除需换硬件。
```

**⑦ §6.5 可写节点：4 个全部可写（修正 HANDOFF）**

| 节点 | 权限 | 原值 | 写回 | 结果 |
|---|---|---|---|---|
| `cut_frame_num` | `-rw-r--r--` | 0 | rc=0 | ✅ 可写 |
| `update_disable` | `-rw-r--r--` | 0 | rc=0 | ✅ 可写 |
| `time_test_level` | `-rw-r--r--` | 0 | rc=0 | ✅ 可写 |
| **`debug_level`** | `-rw-r--r--` | 0 | rc=0 | ✅ **可写**（修正 HANDOFF「写入被拒」）|

> ⚠️ **`debug_level` 可写是重要发现**：写 1 可恢复 `SET_EBC_SEND_UPDATE` 等被抑制的 printk，
> **便于日后诊断**。但会加剧 dmesg 压力（当前已被 `dump_lcdc_state` 淹没）——
> **本轮刻意未写非 0 值**，避免污染现场；建议在专门诊断会话中启用。

**⑧ 本轮最大工具收获：两个新观测手段**

```bash
# (a) 直接读 SF→EPDC 的真实波形/更新模式（不受 dmesg 淹没影响）
adb logcat -d | grep 'update_to_display'

# (b) 分离调用两个 repaintEverything 重载（新增工具，源码 _scratch_gs/RepaintAll.java）
CLASSPATH=/data/local/tmp/repaint.dex app_process /system/bin io.onyx.RepaintAll       # 无参
CLASSPATH=/data/local/tmp/repaint.dex app_process /system/bin io.onyx.RepaintAll 98    # 带参
```

**⑨ 工具踩坑补充（本轮新增）**

| 坑 | 现象 | 正确做法 |
|---|---|---|
| `su -c "…\r…"` 嵌套引号 | PowerShell 把 `tr -d "\r"` 消化成 `tr -d "r"`，**删掉脚本内所有字母 r** → 语法错误 | **整脚本 push，脚本内不嵌套 `su -c`**；外层用 `su -c sh\ /path` |
| `dmesg` 被 `dump_lcdc_state` 淹没 | 1204/2013 行 = 60%，约 17 条/s ⇒ 缓冲仅存 ~2 分钟 | 采样后**立即**导出；或改用 `logcat \| grep update_to_display` |
| `dmesg` 时间戳 ≠ uptime | dmesg `kt=6582` vs uptime `34270` | 用 `K = uptime_at_dump − dmesg末行kt` 标定（本机 **27687.6**，稳定可复用）|
| 设备端 `awk` 缺 `asorti`/`strftime` | `awk: calling undefined function` | 改用 `sort` + shell 循环 |
| `d8.bat` 用绝对路径失败 | `Illegal char <:> at index 0` | 用**相对路径**调 d8 |
| `javac` 默认 GBK | 中文注释报「非法字符」 | 加 `-encoding UTF-8` |

### 8.1 方法
- 工具：`_scratch_gs/relay_uc552930.py`（unicorn2 ARM64 模拟）+ `relay_uc_matrix.py`
- 直接把 `kernel_extracted.img` 映射为内存，调用 `0x552930(struct, wbf_ptr, size)`；hook：printk(0xce928)→返回、memcpy(0x120d7c0)→拷贝、kzalloc(0x2108e0)→模拟堆、**0x552528 返回点(0x5525b4)→记录 x0=每块帧数**
- 112 次展开调用（14 温度 × 8 波形）全部返回，x0=0 成功

### 8.2 正常机 8×14 帧数矩阵（单位：帧；行=温度°C，列=waveform mode0..7）

| 温度 | mode0 | mode1 | mode2 | mode3 | mode4 | mode5 | mode6 | mode7 |
|---|---|---|---|---|---|---|---|---|
| 0°C | 152 | 73 | 131 | 131 | 131 | 131 | 35 | 84 |
| 3°C | 136 | 65 | 116 | 116 | 116 | 116 | 31 | 75 |
| 6°C | 120 | 57 | 102 | 102 | 102 | 102 | 28 | 65 |
| 9°C | 108 | 51 | 90 | 90 | 90 | 90 | 24 | 58 |
| 12°C | 92 | 43 | 77 | 77 | 77 | 77 | 21 | 49 |
| 15°C | 163 | 37 | 66 | 66 | 66 | 66 | 17 | 42 |
| 18°C | 139 | 31 | 55 | 55 | 55 | 55 | 15 | 35 |
| 21°C | 125 | 26 | 46 | 46 | 46 | 46 | 12 | 29 |
| **24°C** | 113 | **22** | **38** | **38** | **38** | **38** | 10 | 24 |
| **27°C** | 87 | 19 | **38** | **38** | **38** | **38** | 10 | 24 |
| **30°C** | 79 | 17 | **38** | **38** | **38** | **38** | 10 | 24 |
| **33°C** | 71 | 15 | **38** | **38** | **38** | **38** | 10 | 24 |
| **38°C** | 63 | 13 | **38** | **38** | **38** | **38** | 10 | 24 |
| **43°C** | 59 | 12 | **38** | **38** | **38** | **38** | 10 | 24 |

### 8.3 列映射（对照驱动实测 dump_lut_list frame_total）
- **mode2..5 = 38 帧**（4 个同帧数的模式 → 驱动实测 GC16 族 GU/GC/GCC/DEEP_GC 及 REGAL 全落此类，waveform[2]/[4] frame_total=38 ✓）
- **mode6 = 10 帧** = A2（实测 A2_QUALITY/X_A2/MONO_A2 → waveform[6] 10 ✓）
- **mode7 = 24 帧** = DU4（实测 2312 → waveform[7] 24 ✓）
- **mode0/mode1 = 温度相关大帧数**（0°C 时 152/73 → 43°C 时 59/12；低温帧多=更新慢）——mode1 疑似 DU（实测 20/22 帧落在其温度区间附近，正常机 22@24°C 与故障机 DU=22 吻合，正常机实测 20 疑为温度/版本差）
- **整个矩阵无 5 帧模式** → REGAL(UI6/launcher idx3)=38 帧 GC16 获结构级证实；"REAGL 5 帧" 在该 sg 波形库中不存在，旧记录判定误记

### 8.4 副产品
- 温度点数组 = 0,3,…,39°C（14 点）；高温段（≥24°C）GC16/A2/DU4 帧数恒定
- kallsyms（kptr_restrict=0）已导出 120 个 onyx_epdc 符号 → `_scratch_gs/kallsyms_epdc.txt`（dump_lut_list/onyx_epdc_load_waveform_and_init_work_func/onyx_epdc_produce_wf_segment 等），后续可对照符号名精读
- 外部参考：`HTM.htm`（NXP 论坛 WBF 转换/REGAL 讨论，确认 REGAL=HW waveform engine GLD/GLR 模式，无格式细节）

### 8.5 ★ inkwave 交叉验证：wbf = 标准 E Ink waveform 格式（2026-09-05 晚，代理拉取仓库）
- 经 127.0.0.1:7890 代理克隆三个参考仓库到 `_scratch_gs/refs/`：**inkwave**（fread-ink，wbf→wrf 转换+元数据）、**eink-waveforms**（Szybet，空仓库待补）、**NekoInk**（zephray，含 mxc_waveform_dump/asm、wbf_flash_decompress 等工具）
- **inkwave 揭示 wbf = 标准 E Ink `waveform_data_header`**：mc@0x25（8 modes）、trc@0x26（14 temp ranges）、pointer=3B 偏移+1B 校验（b0+b1+b2）、xwia（0x40 文件名串 `320_R110_..._TC`）、mode 表在 xwia 后=0x5f、每 mode→temp 表→波形段、段尾 0xff+校验 2 字节（与我们的发现完全一致）；MODE 常量：INIT=0/DU=1/GC16=2/GC16_FAST=3/A2=4/GL16=5/GL16_FAST=6/DU4=7/REAGL=8/REAGLD=9/...
- parse_waveform 语义 = 0xfc 单字节字面区(count=1) + 2 字节 RLE(count=b+1)，每 count×4 状态；`phases = state_count/256`
- **python 移植** `_scratch_gs/relay_inkwave_py.py` 解析 normal wbf：每 mode×temp range 的 phases 与 **unicorn 矩阵 ×4 逐值吻合**（mode2tr0: 524/4=131=unicorn ✓；全部列偏差 ≤1，mode7tr13 因段边界共享除外）→ **正常机帧数矩阵获得标准解析器独立验证，100% 可靠**
- 内核每段帧数 = E Ink 段总状态 ÷ 1024（inkwave phases/4）；之前 count_loop 静态模拟的偏差源于段边界假设错误（应全局排序，非同行下一温度）与 0xfc 字面处理细节，已无关紧要
- ~~故障机 wbf 表格式为另一代~~（已撤销：两机 wbf 逐字节相同，见 7.3；当时"0x98 起表区不同"是拉取 CRLF 污染所致）；用户提示的 inkwave/eink-waveforms/NekoInk 仓库经 127.0.0.1:7890 代理已克隆到 `_scratch_gs/refs/`（eink-waveforms 为空仓库）
## 4. 工具（已就绪，供接力）

- `_scratch_gs/TestWaveform.java`（+ classes.dex）：app_process 反射触发**任意 UI 值全刷**（`CLASSPATH=/data/local/tmp/tw.dex app_process /system/bin io.onyx.TestWaveform <UI值> [轮数]`）
- `_scratch_gs/`：capstone 逆向脚本（find_fwbuf/disasm_wbf_* 等）
- 故障机实测方法：TestWaveform 多轮触发（每轮 4-5 次）碰 powerup 成功窗口 → `dmesg -c` 清后抓 `dump_lut_list` 的 `frame_total`；原版内核（15 次重试）窗口多于 v3

## 5. 当前设备状态（2026-09-05）

- 故障机 6C7F0E64：~~原始 boot 已刷回 boot_b~~ → **2026-09-07 已刷 v5**（boot_patched_05s_v5.img，见 §9.2；刷前实测为 v3 而非原版）
- 正常机 6C1BF7D9：launcherRefreshMode 恢复 1（GU）
- EPDC 硬件仍故障（powerup 间歇失败，窗口少但存在——重启后早期窗口较多）

## 6. 接力记录（2026-09-05 夜，重启实测 ×2 + 静态解剖推进）

### 6.1 故障机重启实测 ×2（均原版 boot，方法：重启 → 等 boot → printk 8 → 逐 UI 值 TestWaveform 多轮 → 抓 dump_lut_list）

**观测原始行**（dmesg `dump_lut_list(): magic[..] lut[..] waveform[..] frame_cur[..] frame_total[..]`）：

| 轮次 | 触发 UI 值(意图) | waveform[] | frame_total | frame_cur 观测（波动） |
|---|---|---|---|---|
| R1 | 1 (DU) | 1 | **22** | 1→2→2→9（完成度不同） |
| R1 | 2 (GU) / 3 (GC4) | — | 无 dump | — |
| R1 | 6 (REGAL) | 2 | 38 | 卡 2/38 |
| R1 | 9 (REGAL_PLUS) / 98 (GC) / 107 (GCC) / 108 (DEEP_GC) | 2 | **38** | 卡 2/38 |
| R2 | 6 (REGAL) / 1 (DU) | 4 | 38 | 卡 2/38 |
| R2 | 2 (GU) | 1 | **22** | 1→2→2→7（完成度不同） |
| R2 | 9 / 98 / 107 / 108 | 2 | **38** | 卡 2/38 |

要点：
- **frame_total 只出现两种恒定值：22（DU 族）与 38（GC16 族）**，与第 1 节既有记录一致；`frame_cur` 每次不同（上电失败 → 完成度不同，如 1/2/7/9），**判断帧数以 frame_total 为准，勿用 frame_cur**
- UI=6 (REGAL) 触发时队列先见 **GC16 初始化全刷（38 帧，供电不足卡 2/38）**；当时据此认为 REGAL 真身是 5 帧短波形——**2026-09-05 晚已证伪**（REGAL=GC16 38帧，见 7.4/9 节）
- dump 的 `waveform[]` 编号是队列 LUT 槽分类（4/2/1 浮动），**不是** EPDC waveform mode 号，勿用于组号映射
- 窗口期 = boot 后约 40–170s（两轮均在此区间出 dump，之后 powerup 持续失败）

### 6.2 launcher 刷新模式 → 帧数结论（核心交付）

launcher 6 项（RefreshModeHelper MODE_VALUES: None=-1, GU=2, DEEP_GC=108, REGAL=6, GC=98, GCC=107）：

| launcher 模式 | UI 值 | 底层 EPDC 波形 | 帧数(frame_total) | 故障机能否跑完 |
|---|---|---|---|---|
| None(恢复默认) | -1 | 系统 per-app | — | — |
| GU | 2 | GC16(2) | **38** | ❌（供电不足时卡 2/38；重启窗口内可跑完） |
| DEEP_GC | 108 | GC16(2) | **38** | ❌ |
| REGAL | 6 | GC16（sg 库无 REAGL 段） | **38** | ❌（同 GC16；"5 帧"为 9/5 早误记，见 7.4/9 节） |
| GC | 98 | GC16(2) | **38** | ❌ |
| GCC | 107 | GC16(2) | **38** | ❌ |
| （另 DU 相关） | 1 | DU(1) | **22** | 部分（供电中断时卡 2/22，窗口内可推进至 7–9） |

**结论修正（2026-09-05 晚，见 7.4/9.8）**：launcher 全局模式全部为 38 帧 GC16（REGAL 无独立短波形），供电不足时都会卡、供电足（重启早期窗口）时都能跑完；**不存在"只有 REGAL 5 帧能跑完"的短波形选项**。故障机缓解需依赖供电窗口或硬件修复，或可选项切 A2(5帧无灰)仅用于不介意丢灰阶的快速场景。

### 6.3 静态逆向推进（详见第 3 节修订）
- 修正 struct+0x2e/0x2f = 0xff/0xfc（终止/字面标记）；struct+0x36 实为名称串；表1 偏移在 wbf+0x5f（u24@0x20）
- g6t0 块解析边界+尾部校验字节已被验证正确（终止@+276，校验 0xe1 匹配）
- **未通**：8 组×14 温度全扫，按 (a,b) RLE 无一组解出 5/22/38（g6t0 得 140 帧等）→ 帧内容格式理解仍有错，待接力（见第 3 节"待接力"）

### 6.4 新增工具（`_scratch_gs/`）
- `relay_autocap.sh` / `relay_cap_quick.sh` / `relay_cap2.sh`：重启+自动逐 UI 抓 frame_total（**注意 Windows Git Bash 需 `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`，否则 CLASSPATH 被改写**）
- `relay_dump_552930.py` / `relay_sim_v2.py` / `relay_trace_552e50.py` / `relay_map_groups.py` / `relay_wbf_tables.py` 等：capstone 反汇编 + 块布局解剖 + 解析仿真
- 故障机波形 = 正常机同一文件（md5 f463661b，设备端验证；旧 `faulty_waveform.wbf` 为 CRLF 污染假象，已删除）

## 7. ★ 正常机实测新发现（2026-09-05，6C1BF7D9，onyx waveform sg）

### 7.1 方法突破：不再依赖"卡住" dump
- 故障机上 `dump_lut_list` 只在 LUT 卡住/异常时打印；**正常机刷新能跑完 → 无 dump**，此前以为正常机无法实测
- **新方法**：`cat /sys/class/sepdc/debug/dump_list`（sepdc sysfs 节点，只读、cat 即触发）会主动调 `show_dump_list()` 全量 dump（含 `dump_lut_list(): magic[..] lut[..] waveform[..] frame_cur[..] frame_total[..]`）
- 做法：后台跑 `app_process TestWaveform <值> 1` 同时设备端循环 `cat dump_list`（~300–500 次）捕获刷新进行中的活动 LUT → 正常机也能稳定拿到 **frame_total 与完整 frame_cur 推进**（供电正常，frame_cur 可到 37/38）

### 7.2 正常机 frame_total 实测（sg 波形库，共 4 种值）

| 触发 value | dump waveform[] | frame_total | 推断波形 |
|---|---|---|---|
| 2 (GU) / 98 (GC) / 107 (GCC) / 108 (DEEP_GC) | 2 | **38** | GC16 族 |
| 6 (REGAL) / 9 (REGAL_PLUS) / 5242886 (REGAL_SHUTDOWN) | 4 | **38** | REGAL 全刷实际走 GC16（38 帧） |
| 2305 (DU_QUALITY) / 16777217 (X_DU) / 4 | 1 | **20** | DU |
| 2312 (DU4) | 7 | **24** | DU4 |
| 2308 (A2_QUALITY) / 16777220 (X_A2) / 33554436 (MONO_A2) / 4 | 6 | **10** | A2 |
| 1 / 3 / 5 / 8 / 11 / 0 | — | 无 LUT dump（无效或未触发全刷） | — |

- `waveform[]` 编号是 **sg 库内部枚举**（waveform[6]=A2=10 帧，≠ SDK EINK_WAVEFORM_MODE 号 6=REAGL）
- frame_cur 正常推进至 frame_total（37/38、19/20、9/10、23/24）→ frame_total 是完整执行帧数（与故障机"卡住" dump 同源字段）

### 7.3 两机帧数对比（★ 修正：两机 wbf 逐字节相同，无波形库差异）
- **2026-09-05 晚重大修正**：故障机 `/waveform/eink_waveform.wbf` 用二进制安全方式（设备端 base64 + md5）重拉验证：**md5 f463661b… = 与正常机/仓库 eink_waveform.wbf 逐字节相同**！此前"两机 wbf 不同（256283B, md5 58c1…）"是 `adb shell cat > 文件` 拉取时被 **Windows CRLF 污染**（插入了 280 个 0x0d）造成的假象；污染文件已删除
- 因此 **正常机 8×14 帧数矩阵（第 8 节）同样适用于故障机**；故障机"表格式代差 / 0x98 起不同 / unicorn 模拟失败"等结论全部撤销（均为污染文件所致）
- 两机实测差异仅剩 DU：故障机 22 vs 正常机 20 —— 同一 wbf 下只能是**温度段选择不同**（mode1 帧数随温度 73→12 变化；矩阵无 20 整值，疑与驱动温度点取整/cut_frame_num 有关，待考）

| 波形 | 帧数（两机同 wbf，按温度段） |
|---|---|
| GC16 族 (mode2-5) | **38**（≥24°C 恒定） |
| A2 (mode6) | **10** |
| DU4 (mode7) | **24** |
| DU (mode1) | 温度相关 73@0°C → 12@43°C（实测 22/20） |
| INIT (mode0) | 温度相关 152@0°C → 59@43°C |

### 7.4 ★ REGAL=5 帧记录存疑 → 已复核：REGAL 全刷实际是 GC16(38 帧)
- 正常机 **UI=6 (REGAL) 与 UI=9 (REGAL_PLUS) 实测均为 waveform[4] frame_total=38（GC16）**；故障机重启窗口期（boot 后 ~50s 内供电正常）用 **launcher 真实路径复核**（`test_mode.sh 3` → launcher 重启自动 `RefreshModeHelper.apply(REGAL)`，日志确认 `apply: REGAL -> OK` + byPass）与 TestWaveform 6 多轮，抓到的活动 LUT **全部是 waveform[2]/[4] frame_total=38**，frame_cur 完整推进 1→37/38（供电足时 GC16 能跑完）
- **frame_total[5] 在三次独立复核中从未出现** → 第 1 节"REGAL(REAGL)=5 帧"记录（9/5 上午故障机）倾向**误记**或来自无法考证的特殊上下文（当时 patch boot？）；已在该节加 ⚠️ 注记
- 若 REGAL 实为 GC16(38 帧)，则故障机 launcher 6 模式**没有短波形可依赖**，且供电充足窗口（重启早期）下 GC16 也能跑完——"故障机应固定用 REGAL(5 帧)"的旧策略需要重新评估
- ✅ 已解决（9/5 晚）：Poke6 sg 波形库**不存在 REAGL 独立段**（unicorn 全矩阵无 5 帧列 + inkwave 交叉验证），REGAL/REGAL_PLUS 全刷落 GC16(槽4/38帧)；"5 帧"实为 A2/ANIMATION 系

### 7.5 正常机探测记录（供接力）
- sysfs：`/sys/class/sepdc/debug/`（dump_list/status/cut_frame_num/panel_init/night_mode 等）、`/sys/devices/platform/onyx_epdc_fb.0/waveform_version` = "onyx waveform sg"（Poke6 用的是 sg 波形库）
- 正常机 SELinux **Enforcing**：magisk 域无法读其它 app 的 /data/data（改 launcher prefs 需 run-as/launcher uid）——故障机侧操作 prefs 的脚本不能直接用于正常机
- `status` 节点：`epdctask_status[..] epdc_active_luts[..] all_frames_completed[..] frame[a:b:c]`（运行态计数，非波形帧数）
- 工具：`_scratch_gs/relay_normal_batch.sh`（推送到设备 /data/local/tmp/nb.sh 执行：逐 value 触发 + 高频 dump 抓 frame_total）

## 9. ★ 全模式耗时复测 + 5 帧真身确认（2026-09-05 深夜，正常机，Legado 前台保持 EPDC active）

### 9.1 waitForUpdateFinished 计时 = 假象（修正 8/24 耗时表）
- 复测 16 个 UI 值 `repaintEverything + waitForUpdateFinished()` 计时：**全部 3–6ms**（含 8/24 慢组 DU/GU/GC4/DEEP_GC/A2_QUALITY）
- 期间 frame 计数 5202→5914（刷新真实执行）→ **waitForUpdateFinished 是"入队即返回"，不等待 EPDC 物理完成**；8/24 耗时表的"慢组 ~2s / 快组 ms"分级**不可靠**（快组=wait 不阻塞假象；慢组 2s 疑为当时环境（无前台 activity、EPDC 每次 powerup）或口径差异）

### 9.2 可靠测法：dump_lut_list 时间戳 span
- 触发 + 高频 `cat /sys/class/sepdc/debug/dump_list`，取同一 magic LUT 的 frame_cur 首尾 dmesg 时间戳差 = 执行 span
- 工具：`_scratch_gs/relay_timing2.sh / relay_timing3.sh`（设备端 `_scratch_gs/TestTiming.java` → tt.dex 计时版另存）

### 9.3 ★ 全模式帧数 + 执行 span 实测表（正常机）
| UI 值 | 模式 | waveform total | 执行 span | 稳定次数 |
|---|---|---|---|---|
| 98 (GC) / 107 (GCC) | GC 族 | **38 帧** | 157–414ms | 稳定 |
| 6 (REGAL) / 9 (REGAL_PLUS) | REGAL 族 | **38 帧** | 390–470ms | 稳定 |
| 108 (DEEP_GC) | GC 族 | 38（+DU 初始化 22） | ~416ms | 稳定 |
| 2305 (DU_QUALITY) | DU | **22 帧** | 0–289ms | 稳定 |
| 2312 (DU4) | DU4 | **24 帧** | ~300ms | 稳定 |
| 2308 (A2_QUALITY) | A2 | **5 帧** | 15–31ms | 6/6 |
| 16777220 (ANIMATION_X) | X | **5 帧** | 17–33ms | 6/6 |
| 33554436 (ANIMATION_MONO) | MONO_A2 | **5 帧** | 17–32ms | 6/6 |
| 4 (A2_PERFORMANCE) | A2 性能 | **5 帧** | 21–33ms | 6/6 |
| 1 / 2 / 3 / 5 / 0 / 16777217 | DU/GU/GC4/AUTO/INIT/X_DU | **无全刷 LUT dump**（走局部/被忽略；DU/GU 在故障机窗口期有 22 帧记录） | — | — |

### 9.4 ★ 结论
- **5 帧短波形真身 = 全部 A2/ANIMATION 系**（2308/16777220/33554436/4，24/24 次稳定，15–33ms）——之前"5 帧难复现"只是它太快、dump 轮询错过
- **9/5 上午"REGAL=5 帧"误记的最可能来源**：当时把 A2 系的 5 帧 LUT 记到了 REGAL 名下（REGAL/REGAL_PLUS 实际 = GC16 38 帧，已多次证实）
- REGAL 与 GC/GCC 同为 38 帧 GC16 族（波形槽 4 vs 2，内容差异仍未闭环）
- 8/24"快组（REGAL 4ms 等）"为 wait 假象，不可再引用
- 遗留：wbf 静态 mode6=10 vs 运行时 A2 5 帧的差异（waveform 槽≠wbf 列或驱动裁剪），待考

### 9.5 真实 UI 操作实测：翻页/滚动/动画全部落 GC16 38 帧（2026-09-05 深夜补充）
- **Legado 阅读翻页**（8/8 页稳定）：每页一个 LUT = `waveform[2] frame_total=38`，frame_cur 推进 32–37
- **com.onyx.clock 秒/分针动画**（35s daemon dump）：每 1–2s 一次新 LUT，3 区域并行 3 个 LUT，**全部 waveform[2]=38 帧 GC16**（"e-ink 时钟每秒闪"的根源）
- **Legado 主界面（书架列表）手动滑动**：`waveform[2]=38 帧 GC16`
- **结论**：该 ROM 上普通 app 的翻页/滚动/动画更新**均不走 A2/DU 短波形，全部落 GC16 38 帧全刷**；5 帧 A2 短波形只在显式 A2 系请求（2308/16777220/33554436/4）出现
- 附带发现：`topAppIsSystemOrOnyxApp`（AccessibilityHelper）——Onyx 对系统/`com.onyx.*` app 特殊处理（至少 debouncer 排除）；外部 SetScope(null 全局 scope) 不改变 CheckMode 的 `appScopeRefreshMode`（恒 2，读 OECService 状态，语义≠launcher 全局刷新模式）
- 意义：**全局刷新模式选项（GU/GC/GCC/DEEP_GC/REGAL）在真实使用中是否改变实际波形仍未闭环**——普通 app 更新恒 GC16 38，可能是 Onyx 把大多数更新都映射到 GC16，或 scope 未作用于测试对象

### 9.6 全模式精确完整执行时长（2026-09-05 深夜，正常机，frame_cur[1]→完成）
- 方法：触发时并行高频 dump，只统计**同 magic LUT 从 frame_cur[1] 到 frame_cur≥total-1 的完整跨度**（剔除部分采样）；工具 `relay_precise_all.sh`/`relay_precise_fix.sh`
- **结论修正**：此前"GU ~400ms vs GC 158ms"是采样假象——完整帧追踪证明 **GC16 系 38 帧统一 ≈400–470ms**

| 簇 | 模式 (UI值) | wf槽 | 帧数 | 完整时长 | 备注 |
|---|---|---|---|---|---|
| GC16 | GU(2) / GC(98) / GCC(107) / DEEP_GC(108) | 2 | 38 | 396–444ms（98 采样漏首帧，同段推断同值） | 同段同时长 |
| GC16 | REGAL(6) / REGAL_PLUS(9) | 4 | 38 | 388–467ms | 独立槽但同时长 |
| DU | DU_QUALITY(2305) / DU(1) | 1 | 22 | 部分采样 0–289ms | 完整帧常漏首帧 |
| DU4 | 2312 | 7 | 24 | 298–310ms | — |
| A2 | 2308 / 4 / 16777220 / 33554436 | 6 | 5 | 15–41ms | 帧间隔仅 3–6ms |
| — | 3(GC4)/5(AUTO)/0(INIT)/16777217(X_DU) | — | — | 正常机不产生全刷 LUT | 无数据 |

- 帧间隔推算：GC16 ≈10–12ms/帧、DU4 ≈13ms/帧、A2 ≈3–6ms/帧
- **GU vs GC 差异**：98=0x62=GC16(2)|FULL(32)|WAIT(64) 组合，GU=裸 GC16；波形/时长等价，仅修饰位（FULL 强制全屏、WAIT 等待）语义不同

### 9.7 X / MONO / A2 / DU flag 语义追踪（2026-09-05 深夜）
**已确认（java 层）**：
- `0x1000000` = `EINK_ONYX_AUTO_MASK`（同值别名 `EINK_DITHER_X`）——UI_X_A2_MODE(16777220) 自带；SDK 各 Device(IMX6/7/RK31/32/33/SDM) 反射缓存，但 java 层无分支消费 → 消费在 native(SF/EPDC HAL)/内核
- `0x2000000` = `EINK_ONYX_GC_MASK`（同值别名 `EINK_APPLY_MONO`）——UI_MONO_A2_MODE(33554436) 自带；同上
- 组合真相：**A2/X/MONO 驱动层执行同一 5 帧槽6 波形**，差异在 flag：A2_QUALITY(2308=0x904 带 dither)、X_A2(16777220=0x1000004 AUTO_MASK+ANIM)、MONO_A2(33554436=0x2000004 GC_MASK+ANIM)
- SDK 逻辑层区分：UPDATE_MODE_A2=2(FAST)、UPDATE_MODE_X=4(FAST_X)、REGAL=3 —— **X 与 A2 是不同逻辑模式**
- `keyUpdateMode`：列表含 mode==98(GC) 时 GC 最高优先级（防抖合并 GC 胜出）
- UpdateMode 枚举四档动画：ANIMATION / ANIMATION_QUALITY / ANIMATION_MONO / ANIMATION_X
**视觉实测**（repaint，正常机）：DU_QUALITY(2305)=抖动灰略低于16级可用；DU(1)=黑白丢图标；DU4(2312)=极淡只剩特征；A2/X/MONO=丢图标；GC16 系=唯一完整 16 级。**灰阶 = waveform 基础能力 + dither(0x100/0x800) 注入**，GC16 真 16 级无需 dither，直写族(DU/DU4)低灰靠 dither/呈现异常

### 9.8 灰阶分档视觉验证 + launcher 全局模式选项集落地（2026-09-05 深夜）
**视觉分档定稿**（repaint 逐模式看屏确认）：
| 档 | 模式 | 视觉 |
|---|---|---|
| 16级（唯一完整） | GU/GC/GCC/DEEP_GC/REGAL/REGAL_PLUS（等价组） | 图标最清晰 |
| ~16级（抖动） | DU_QUALITY(2305) = DU+dither | 图标在、略低于 GC16 |
| 4级（2bit） | DU4(2312) | 极淡、只剩特征（二值化特征） |
| 黑白 | DU(1) 纯 | 图标消失只剩字 |
| 无灰/2态 | A2/X/MONO(4/2308/16777220/33554436) | 图标消失只剩字 |

**关键修正与方法教训**：
- **refreshScreen(region,mode) 的 mode 参数不生效**——2/2305/16777217 全被规范成槽1/22帧(区域DU)；此前"GU=22帧黑白毁图标"是该路径假象，改用 repaint 视觉验证后 GU=38帧GC16 16级正常
- **灰阶 = waveform 低4位基础 + dither(0x100/0x800) 注入**；GC16 自带真16级；DU 带 dither(2305)≈16级抖动、不带(1)=黑白；A2 基础2态，dither 无效
- **MONO flag 无法自定义组合**：0x2000000|2(33554434) 实测仍按普通 GC16 16级执行（flag 仅预定义值生效）→ "黑白 GC" 不可行
- DU 与 A2/X/MONO 的差别：DU=22帧黑白完整渲染(能看内容)、A2/X/MONO=5帧动画(只适合运动过程)；SF 端 AUTO/MONO flag 不改变内核波形
**launcher 选项集落地（commit c4bef37）**：RefreshModeHelper 改为 None + GU + GC + DEEP_GC + REGAL_PLUS（16级组）+ A2(4,无灰5帧) + DU(1,黑白22帧)；LABELS 标注灰阶/实测特性；移除等价冗余 REGAL/GCC；prefs index 语义随顺序变化需重选一次

## 10. 接力者起点（2026-09-06 交接）

**已闭环的核心结论**（读 8/9 节即可，勿再怀疑）：
1. wbf = 标准 E Ink waveform（md5 f463661b，两机同文件）；8×14 帧数矩阵 = unicorn 模拟 0x552930 + inkwave 双验证
2. 真实 UI 更新（翻页/滚动/动画）全部落 GC16 38帧；REGAL 无独立 REAGL 段（"5帧"=A2系，旧记录已证伪）
3. waitForUpdateFinished 是假象（入队即返回）；真实时长：38帧≈400ms / 24帧≈300ms / 5帧≈25ms
4. 灰阶 = waveform 低4位 + dither(0x100/0x800)；16级仅 GC16 系视觉完整；launcher 选项已按档重设（commit c4bef37）

**遗留可接力点**：
- **[C] GC16 槽2(GC系) vs 槽4(REGAL/REGAL_PLUS) 内容级像素对比**：帧数/灰阶同但未逐字节比 LUT 内容——决定 GC 与 REGAL_PLUS 选项是否真等价（可 kprobe/读驱动 LUT 或截图 diff）
- **[D] DU 实测 20 vs 22 帧的温度/口径差异**（9.6 表 vs 8.2 矩阵 mode1 列）——温度段选择规则
- **[E] X(0x1000000)/MONO(0x2000000) 在 SF/EPDC HAL native 的最终消费**（java 层与内核均已排除，剩用户态 native；需要 SF 二进制或 HAL 源码）
- **[F] launcher 新选项集 APK 实机验证**：app-debug.apk(3.9MB) 未安装——装上后切各档确认 scope 生效/日志变化
- **[G] 8/24 前"REGAL 快组 4ms"环境差异溯源**（当时无 scope 的 repaint 为何 wait 假象不同）——低优先

**工具**（eink 根 `_scratch_gs/`）：relay_uc552930.py（unicorn 模拟）、relay_inkwave_py.py（wbf 标准解析）、relay_crosscheck.py；设备端 dex：tw.dex(TestWaveform)/ss.dex(SetScope)/tl.dex(TestLocal)/tt.dex(TestTiming)/cm.dex(CheckMode)；捕捉法：cat /sys/class/sepdc/debug/dump_list 主动 dump（正常机需 Legado/时钟前台保持 EPDC active）
**设备**：正常机 6C1BF7D9（Legado 前台可测）、故障机 6C7F0E64（原版 boot，仅重启早期窗口）


---

# 十一、故障机 v3/v5 实测 + reset 反汇编 + "powerup 默认失败"方案评估（2026-09-07）

## 1. 故障机 boot 时间线更新（2026-09-07）
- **v3 刷入实测**：`boot_patched_1s_v3.img`（md5 `cd08e9167a9296f89df5a1ad1c96fe28`）dd 刷入 `boot_b` → 重启后 dmesg `o_e_u_w_s(): wait all_lut_free timeout 1000 ms!` 确认生效（原版为 5000ms）
- **v5 刷入实测**：用户侧产出 `boot_patched_05s_v5.img`（本地 Sep 7 20:35，16965632B）并已刷入故障机 → wait = **500ms**（"05s" = 0.5s）；当前故障机运行 v5
- 回滚备份仍在设备 `/sdcard/boot_b_before_v3.img`（96MB 原始 boot）
- ⚠️ 第十章末"故障机=原版 boot"已过时（→ v5）

## 2. v5 实测日志（uptime 4min 被动 + TestWaveform 98 主动触发）
**被动（空闲，boot 后 4min）**：`wait all_lut_free timeout` 0 次 / `onyx_epdc_reset` 0 次 / `update_err` 0 次；`waiting for power good` 44 次 / `epdc power error` 27 次 —— **空闲零 reset 风暴**（对比 v3 活跃期 ~1.2s/reset）
**主动触发 GC(98) 一次全刷**（关键时间线）：
```
318.53  waiting for power good（powerup 失败）
319.12  LUT 建立 waveform[1] frame_total[22]，frame_cur 1→2（卡住）
319.62  o_e_u_w_s(): wait all_lut_free timeout 500 ms!   ← v5=500ms
319.62  onyx_epdc_reset(): reset cause[...] → clean waveform_list → reset end（单次干净完成 ~0.1s）
320.17  下一轮 waiting for power good（新一轮 powerup 重试）
```
- 失败-重置周期 ~1.6s，**主导是 powerup 的两次 i2c 写等待（间隔 ~1.6s = regmap 无响应硬件超时）**，wait 500ms/reset 0.1s 只占小头
- sysfs：`all_frames_completed[0]`（boot 至今无一帧完成）、`frame[1260→1336]` 仅 reset/队列计数；无供电窗口时刷新永不完成
- 触发到 app_process 返回 ~1.8s（repaint 入队即返回假象）

## 3. ★ reset 反汇编结论（代码级铁证）
**tracefs 限制**：设备内核 `/sys/kernel/tracing` 存在但**无 kprobe events（CONFIG_KPROBE_EVENTS=n）、无 function tracer filter**（events 列表无 kprobes；set_ftrace_filter 不存在）——真机 kprobe/ftrace 验证**不可行**，只能静态反汇编

**定位方法（含坑）**：
- onyx_epdc 打印字符串集中页 = `0x1995000`（wait 串 0x199501d、reset 串 0x19957a4 起）
- 用 capstone 扫 adrp+add 引用（reset cause fmt@lo0x7a4、名字串 onyx_epdc_reset@lo0x7bc、clean wf list@0x871、reset end@0x8f8）
- ⚠️ **capstone 坑**：ARM64 `adrp` 的 imm operand = **目标页绝对地址**（如 0x1995000），勿再 `(addr & ~0xfff) + imm`（会全错）；早期"10 处引用命中"全为此假象
- reset 函数 = 文件 **0x5450F8–0x5454F0**（~1KB，xref 实锤；文件↔虚拟地址非线性，勿用 kallsyms 差换算）

**onyx_epdc_reset 函数结构**（0x5450F8 入口 → 0x5454F0 ret）：
```
printk "reset cause[%s]"（原因）
清场 helpers：0x534448/0x534368/0x533ea8/0x534688/0x54cc00×3/0x560950/0x53dcc0/0x55b4c0
printk "set update_err"
遍历清理各队列/bitmap：clean waveform_release_list / clean waveform_list /
    release work_waveform_desc（spinlock 0x122a458/0x122a4a8 保护；0x4e8958 判空）
sleep(0x64 = 100ms)
printk "reset end"
```
- **bl 目标集合内：powerup 区(0x5F3F00-0x5F4900)零调用；regmap/i2c(0x76e6c0/0x76e650)零出现**
- **结论**：reset = 纯软件清场（清队列/释放波形内存）+ sleep(100ms)，**不触发任何电源事件、不推帧**；推帧唯一路径 = reset 之后**更新请求 → powerup 成功 → 波形执行**
- dmesg 佐证：reset end(319.66) 到下一轮 powerup(320.17) 间隔 ~0.5s = reset 后任务重新调度处理积压请求才触发新 powerup，非 reset 内嵌

## 4. "powerup 默认失败直接触发 reset"方案评估（讨论稿 2026-09-07）
用户设想（字面）：让 powerup 被调用时**默认直接失败**，并**直接触发 reset**（省掉失败路径的等待），靠高频 reset 节拍干活。对照第 3 节证据分两种语义：

| 语义 | 做法 | 后果 |
|---|---|---|
| (a) powerup 入口直接返回失败（不做 i2c 真实尝试） | 跳过整个 powerup 体 | 省 ~1.6s i2c + 0.5s wait → 周期 ~0.2s；但 **PowerGood 永不检测 = 失去抽奖**；reset 空转不推帧（第 3 节）→ 供电窗口恢复也无人知晓，屏幕永不更新 |
| (b) 保留一次真实 powerup，失败即主动 reset（跳过 500ms wait） | 失败路径 fail-fast | 周期 ~1.7s（i2c 1.6s 不可省，只省 0.5s）；抽奖保留 |

- **结论**：(a) 与"完全禁用 powerup"等价，被第 3 节证据否定（reset 不推帧）；(b) 收益仅 0.5s/轮，意义有限
- 遗留开放问题（若用户坚持实验 (a) 需真机验证）：reset 后紧邻的更新请求在供电偶发恢复窗口能否命中（§8"reset 后短暂窗口执行积压帧"或为 reset→下一请求 powerup 紧邻命中，非 reset 自身推帧）——唯一提速途径仍是 i2c 层（0x76e6c0 超时），风险见 §九
- 若做 v6 patch 需先定语义 (a)/(b)，且 v4 教训（压坏 powerup 等待 → waveform 不加载冻结）勿重蹈

### 5. (a) "短路 powerup" patch 点定位（2026-09-07 晚，只准备未刷；待用户择机验证）
**机制闭环确认**（接第 4 节）：实际链条 = 帧入队 → powerup 真试(i2c~1.6s 失败) → LUT 仍建立并卡住 → wait 500ms 超时 → reset 兜底。reset **仅两个触发点**：`0x542AA4`（wait lut_free 超时）与 `0x543168`（wait all_lut_free 超时，即 v3/v5 wait patch 处），均 bl `onyx_epdc_reset(0x5450F8)`。

**关键定位**：
- `onyx_epdc_powerup` 文件入口 = **0x5F4198**（`sub sp,#0x70`+保存 x20-x28；`mov w20,#-0x6e`=返回码默认 -110；epilogue 0x5F4440-0x5F4474 `mov w0,w20; ret`）
- powerup **无直接 bl 调用者**（扫描 0x460000-0x4C2000/0x540000-0x620000 全空）→ 经**函数指针/回调**分发，故"失败即 reset"无法在调用者接线，只能 (i) 短路 powerup 入口靠 wait 自动兜底，或 (ii) 在 powerup 内部嵌 bl reset（重定位 bl 复杂，暂不采用）

**(a) 最简 patch（A-1，8 字节短路）**@ 0x5F4198 覆盖原 prologue 头两条：
```
movn w0, #0x6d      ; w0 = -110（默认失败码）  A0 0D 80 12
ret                                     C0 03 5F D6
```
**预期行为推断**（待实测确认）：帧入队 → powerup 立即 -110（省 i2c 1.6s）→ LUT 仍建立尝试执行并卡住（v5 日志显示 powerup 失败后 LUT 独立建立）→ wait 500ms → reset → **循环周期 ~0.7s**（原 ~2.2s），"reset 平A"频率提升 ~3 倍。若 LUT 因 powerup 失败不建立 → 帧不卡 → wait 不触发 → 系统静默无平A（则需另寻接线）。
**观察点**：dmesg 是否仍周期性出现 wait timeout→reset（预期 ~0.7s/轮）；`epdc_active_luts`/`frame` 计数；无新 UI 操作时是否仍自动循环。
**⚠️ 风险**：
- 仅限故障机实验；**正常机严禁刷**（短路 powerup=永不上电=波形表不加载，v4 冻结同因）
- 与现状的差别仅是"失败更快"，故障机已处于全失败态 → 冻结风险低于 v4（v4 是压坏正常机路径）
- 回滚：`dd if=/sdcard/boot_b_before_v3.img of=/dev/block/by-name/boot_b`

### 6. ★ powerdown 触发链反汇编（2026-09-07，定位完成；idle 延迟数值待续）
**背景**：讨论"保持 EPDC active（不自动 powerdown）"能否放大故障机稀缺的唤醒成功窗口——先定位 powerdown 由谁、何时触发。

**powerdown 相关字符串池**：页 0x1994000 lo 0x31f-0x434（`3%s(): epdc power error!`、`Unable to enable DISPLAY/VCOM regulator...`、`%s(): do onyx_epdc_powerdown!`、`onyx_epdc_done_work_func`、`onyx_epdc_powerdown`、`%s(): EPDC Powerdown Cancel!`、`3%s(): epdc power error, clear!`、`/%s/waveform/`、`eink_waveform.wbf`、`request_firmware_nowait`——后三者证明 waveform 固件加载在**同池/同源文件**（v4 冻结 `waveform_desc is NULL` 与其相关））

**触发链（xref + 反汇编）**：
```
onyx_epdc_done_work_func（延迟 work 回调，文件 0x53FA68）
  ├─ 读全局 0x217f000+0x8b0 bit31 → 0x53FAA4 打印 "do onyx_epdc_powerdown!"
  └─ bl 0x540FC0（powerdown/收尾执行函数）
```
**0x540FC0 函数结构**（已确认 onyx_epdc_powerdown 本体：0x5410B8/0x541114/0x541130/0x54114C 分别打印 `End!`/`Begin!`/`EPDC Powerdown Cancel!`/`epdc power error, clear!`，全部用名字 `onyx_epdc_powerdown`@lo0x3e0）：
```
spinlock(0x122a458) → 状态检查（[x19+0x23f0+0xa0]/0x2494/0x2498/[x21+0x220] 等）
→ bl 0x5e0ae8 释放多个句柄（0x23d8/0x23d0/0x23e8/0x23e0，疑 regulator/设备 put）
→ blr [x8,#0x68]（回调）→ bl 0xa5998/0xf3250/0x733238（wake/资源收尾）
→ sleep（0x2614≥1 时 bl 0xf45a8）→ 解锁
→ 若条件不满足（仍有活跃/pending）→ 0x54112C 打印 "EPDC Powerdown Cancel!" 并跳过关闭
```
- **cancel 逻辑内嵌**：`%s(): EPDC Powerdown Cancel!` 唯一引用 @0x54112C（在 0x540FC0 函数内）——powerdown work 触发时自检，若 EPDC 仍活跃/有更新则不真正关电。新更新"取消 powerdown"通过置状态位实现（无独立打印）
- **EPDC active 期间帧执行不经 powerup**（呼应 9.x"保持 EPDC active"）；powerdown 后下次更新才需唤醒(powerup)

**patch 候选（保持 active/禁自动 powerdown）**：
- (i) 0x53FA68（done_work）入口短路返回 → 永不发起 powerdown 清理
- (ii) 0x540FC0 入口短路返回 → powerdown 清理不执行
- ⚠️ **2026-09-07 已被 §7 否决**：本机真实 powerdown 从未成功完成（error-clear 分支是必要的状态复位/抽奖入口），保持 active patch 反而会禁掉 error-clear 复位点；且驱动原生已实现"powerup 成功后走 [x19+0x2494]==1 快速路径不再抽奖"——两条 patch 候选均不再投入
- ⚠️ 0x540FC0 兼做资源释放/回调/前光控制等收尾（0x5e0ae8/0xf3250），短路副作用待真机观察；powerdown 对墨水屏双稳态正常，禁掉仅影响"空闲节电+下次唤醒成本"
- **未决**：done_work 的调度点与 idle 延迟数值（谁 queue_delayed_work、多少 ms）——下一步定位或实测（无操作后多久出现 `onyx_epdc_powerdown` 日志）

### 7. ★ 两条理论探索评估结论（2026-09-07，反汇编 + 真机 dmesg 实证定论）

> 评估对象：§4/§5"powerup 默认失败短路"（A-1）与 §6"保持 EPDC active 禁自动 powerdown"。

**实证补充（故障机 v5，uptime 3.5h dmesg）**：
- `do onyx_epdc_powerdown!` **0 次**、`EPDC Powerdown Cancel!` **0 次**、`onyx_epdc_powerdown(): epdc power error, clear!` **8 次**（间隔 20–120s 不规则）
- 结论：**真实 powerdown（掉电路径）在本机从未成功完成**——powerdown 例程周期性运行但每次都走到尾部失败分支打印 "power error, clear"（0x54114C，位于 0x540FC0 尾部、释放类调用 0x541008-0x54109C 之后）并返回，bit31 门条件从未满足（无 "Begin!"/"do powerdown!" 打印）。⚠️ 0x540FC0 前半的 0x5e0ae8×N/blr[x8+0x68]/0x733238 是否含真实关电动作**语义未定**——"从未成功"确定，"从未尝试"需谨慎表述
- `0xf20c0` 反汇编确认**不是** queue_delayed_work（是自定义注册/跟踪辅助，读 [x0+0x18]/[x0+8] 比较 [x0+0x10]）——§6 未决的"调度点"仍未定位；done_work 触发间隔实测 ~60–120s

**理论 1（powerup 默认失败 / A-1 短路 0x5F4198）→ 不可行，否决**：
1. §3 已证 **reset 不推帧**（纯软件清场），屏幕更新的唯一路径 = powerup 成功 → 波形执行。A-1 把"间歇性成功"变成"结构性永不成功"，连实测存在的供电窗口（frame_cur 37/38 完整跑完、TestWaveform 成功轮）也永久关闭 → **屏幕永远不更新**。§5"故障机已处于全失败态、冻结风险低于 v4"的前提**不成立**
2. 预期的"0.7s 平A循环"不会成立：powerup 失败后 LUT 卡住是**少数情况**（416 次失败仅 4 次 reset；空闲时 44 次失败 0 次 reset——多数失败更新未入 LUT，all_lut_free 恒真，wait 立即返回，无超时无 reset）；空闲下循环根本不转
3. 变体 (b)（保留一次真实 powerup + 失败即 reset）与 v5 等效：实测 powerup 尝试串行间隔 ~1.6s（i2c 超时决定抽奖节奏），500ms wait 不在关键路径上，跳过它对抽奖频率收益 ≈ 0

**理论 2（保持 active 禁自动 powerdown）→ 前提在本机不成立，无收益，否决**：
1. **本机事实上已经是"保持 active"**：3.5h 真实 powerdown 0 次，电源从未被驱动主动关掉；"每次更新都要重新 powerup"不是因为 powerdown 关了电源，而是 **power error, clear 周期性清掉 powered/error 标志**，迫使下次更新重新尝试 enable
2. 该清标志行为是**必要的抽奖入口**：波形中途掉电（frame_cur 2/22、9/22 卡死）证明 PMIC 播不稳已上电状态——不清标志走"已上电快速路径"只会让后续帧在无电状态下执行失败。保持 active patch（短路 done_work/0x540FC0）反而会**连周期性 error-clear 一起禁掉**，状态机失去复位点，风险为负
3. 若 PMIC 某次 enable 成功，后续更新本就走 [x19+0x2494]==1 快速路径（反汇编实证）——"成功后不重复抽奖"这一理论目标**驱动已原生实现**，无需 patch

**最终结论**：两条理论的共同盲点是假设"reset/active 本身能出画面"或"powerdown 造成了重复抽奖"——代码（reset 不推帧）与实测（powerdown 从未执行、抽奖由 error-clear + i2c 超时驱动）都否定。**唯一有效杠杆仍是 v5 已用的：压短失败代价 + 提高尝试频率，上限 PMIC 物理概率**。可选后续实验：读 /sys/class/regulator/ 各显示轨 state，确认失败间隔期电源轨物理状态（为"跳过 re-enable"变体提供最后判据，预期收益仍低）。
> ⚠️ 本段"下限 1.6s i2c 硬件超时"表述已被 §8 更正：1.6s 实为 retry-prep 内动态 msleep，**可 patch**（v6 候选）。

### 8. ★ 1.6s 真身更正 + v6 候选（2026-09-07 深夜，反汇编定论）

**此前归因错误**：§二/§九"失败间隔 1.6s = regmap/i2c 写无响应硬件超时"**不成立**——dmesg 时间戳证明 Reg Enable/PowerGood 寄存器读取在 **µs 级完成**（0xAF/0xBA 是芯片真实应答值，i2c 总线工作正常）；失败是 PowerGood 位不置位（0xBA & 0x0f ≠ 0x0f），不是总线超时。

**1.6s 真身 = retry-prep 内动态 msleep**：
- 唯一调用链：Retry 打印 → 0x5F43CC `bl 0x5F4488`（全镜像唯一调用者）→ 0x5F4504-0F450C `msleep([chip+0x84] + [chip+0x88])`（两个运行时字段求和，疑来自 DT 属性，≈1.55s）→ + sleep21 + sleep30(v5) ≈ **1.60s 与实测精确吻合**
- 0x5e53c8 = `return dev->[0x540]`（chip 数据访问器）
- **0x5F4488 仅此一个调用者且位于失败重试准备块 → 失败专属路径（结构级证明，与 v5 其他 patch 同类）**

**v6 patch 点**：0x5F4508 `add w0,w9,w8`(0b080120) → `mov w0,#imm`：
- 保守 200ms = 00198052；中档 50ms = 40068052；激进 20ms = 80028052
- 预期：失败尝试间隔 1.6s → 0.1-0.3s，**抽奖频率 ~5-10×**；该延时疑为"放电/重启等待"，压太短理论上可能降低临界窗口成功率 → 建议分档实验并统计成功率
- 验证法：刷入后 dmesg 相邻 Retry 行间隔 ≈ patch 值 + 51ms

### 9. 后续思路清单与建议顺序（2026-09-07 深夜定稿）

**仍有效的三条路（按建议顺序）**：
1. **充电器/电量相关性实验（零成本，先行）**：若根因是系统电源轨在 PMIC 负载下凹陷而非 PMIC 芯片本身坏，插充电器/满电可能显著提高成功率。方法：插充电器 → TestWaveform（tw.dex）连发 ~20 轮统计成功次数（dump_lut_list frame_cur 推进至 total）→ 对比不插。结果同时佐证根因（供电 vs 芯片），指导 v6 选档与维修方向
2. **v6：压掉 retry-prep 1.55s 动态 msleep（§8）**：v5 基础上加一条 0x5F4508 `add→mov`；分档 200/50/20ms 实验并统计各档成功率（该延时疑为放电等待，激进档可能降低临界窗口成功率）。软件侧最后一块大肉，做完只剩硬件
3. **硬件维修（唯一真解）**：TPS6518x 回流焊/换芯片（易购便宜）、检查输出轨电容（尤其 VCOM）

**不再投入**：launcher 合并刷新（SF debouncer 已覆盖）、LSPosed hook（§八定论）、i2c 层超时（归因已更正，问题不在总线）、理论 1/理论 2（§7 否决）。

### 10. 真实翻页 scope 生效性实测：DU scope 不生效，翻页恒 GC16（2026-09-08）
**测试**：故障机 6C7F0E64（uptime ~7min，非"重启早期"）、Legado(com.legado.app.release) 前台、launcher 全局模式=DU（refresh_mode.log 确认 `apply: DU -> OK`、`globalScope value=1`）。
**方法**：8 轮真实翻页（input tap ×4 + swipe ×4）→ `cat dump_list` 触发 + 读 dmesg `dump_lut_list`。

**结果（决定性）**：10+ 个活动 LUT（magic 320-367）**全部 `waveform[2] update[0/1] frame_total=38`（GC16 槽2）**，`frame_cur` 推进至 21/31/34/35/36——零 DU(22)/A2(5)。TestWaveform(98) 显式 repaint 亦 38 帧。

**结论**：
1. **launcher 全局 scope（null 包名 applyAppScopeUpdate, value=DU）对第三方阅读 app 的真实合成翻页更新不生效**——翻页恒 GC16 38 帧。§9.5"全局模式是否改变真实波形"未闭环疑点在故障机闭合：**不改**（scope 只影响显式 repaint/自有路径，SF 合成更新走 GC16 映射）
2. "A2/DU 短帧易中奖"策略在真实翻页下**不可行**（翻页根本不落 A2/DU）——短波形只能用于显式 A2 请求场景
3. **附带**：uptime 7min 仍连续多轮完整执行 38 帧至 frame_cur 35/36——供电窗口远长于此前记录的"重启早期 40-170s"，窗口规律（充电/温度/操作）待考
4. 待查：为何恒定 GC16——SF/HAL 层更新 mode 映射逻辑（见 §11）

### 11. ★ 根因：为何真实翻页恒定 GC16（2026-09-08，res 源码 + 8/25 既有调研闭合）
**结论**：翻页更新 mode 由 **OECService/EAC per-app 配置决策**，launcher 全局 scope 不在此通道。

**证据链（eink-framework 反编译 + ref §八.4）**：
1. `EACBaseRefreshImpl.caculateRefreshConfig`（:61-73）：**per-app `refreshModeIndex` 优先**——非 NONE 且 `SysUIConfig.getRefreshConfigByIndex()` 有值即返回其 `RefreshModeData`，**updateMode 字段（launcher/scope 写入侧）被覆盖**
2. `getConfigUpdateMode`/`calculateScrollingRefreshMode`（:86/52）：真实合成更新取 per-app config 的 mode；mode==3(REGAL) 还被强制改 0(NORMAL)
3. Legado 无自定义 theme → **空 pkg 默认 `refresh_mode_3`**（ref §八.4：空 pkg theme 默认 refresh_mode_3）→ `SysUIConfig.refreshConfigMap` 映射 **updateMode=2 → 内核 GC16(槽2/38帧)** ——与本次实测（翻页 waveform[2]=38）精确吻合
4. launcher 全局 scope（`applyAppScopeUpdate(null,true,0,DU,...)`）走 **ViewUpdateHelper→SF scope/fastIndex 通道**（可观测 getFastModeIndex、影响显式 refreshScreen/repaint 的 mode 查询），**与 OECService 的 per-app EAC config 决策通道不相交** → 对第三方 app 真实合成翻页无效（§10 实测）
5. §八.4 结论 3 同源：改第三方 app 模式需写其 `eac_theme_<pkg>`/`eac_app_<pkg>` 的 refreshModeIndex（系统 5 档内）并**完整重启**让 OECService 重载

**⚠️ 表注修正**：§八.4 表把 `refresh_mode_3 → mode 2` 标为 "NEW_SPEED（A2）"——mode 2 实为 GC16（内核槽2 38帧，本次翻页实测 + §9.3 GU/GC 族同槽佐证）；该档实为**默认 GC16 档**而非 A2，"A2/NEW_SPEED"标注疑为早期别名混淆（turbo=5 是独立维度）。已用实测修正此语义。

**推论**：launcher 全局刷新模式对第三方阅读 app 无效是**结构性**的（通道不同），非 bug；要让 Legado 翻页走短波形，只能(a) 写其 refreshModeIndex 到支持档并重启，或(b) 用显式 A2 系请求路径（如系统手势/阅读器自身 EPDC 选项）

### 12. "全局模式 = 统一各 app 配置档位"方案：机制精化 + 验证准备（2026-09-08）
**动机**：launcher 全局 scope（SF 通道）对第三方 app 真实翻页结构性无效（§10/§11）。用户新方案：改 launcher 全局模式实现为**遍历每个 app 的 Onyx per-app 配置文件统一档位**——直接在 OECService 决策通道上动手。

**关键精化（否则无效）**：
- ❌ 只统一 `refreshModeIndex` 5 档**换不到短帧**：§八.4 实测映射逐档推断全为 GC16 族（mode 5/3/2/0 → REGAL_PLUS/REGAL/GC16/NORMAL，无 DU/A2）——系统档位本身无短波形
- ✅ 需 **`refreshModeIndex=NONE` + `updateMode` 直通写 UI 值**（1=DU 22帧 / 4=A2 5帧）：依据 `EACBaseRefreshImpl.caculateRefreshConfig`（:61-73）——refreshModeIndex 非 NONE 且系统有映射才覆盖 updateMode，**NONE 时走 else 分支 `data2.setMode(updateMode)` 直通**
- 8/25"写 updateMode 无效"实验的盲点 = 未清 refreshModeIndex（Legado 默认 refresh_mode_3 覆盖一切写入）；NONE 直通路径**尚未实测**

**数据模型/API（res 反编译确认）**：
- 双 MMKV 源（FastJSON 序列化，非 org.json）：theme `eac_theme_<pkg>@theme_type_<1..3>`（决策源，`EACAppThemeManager.loadThemes(pkg)` 读）与 appConfig `eac_app_<pkg>`+`eac_app_pkg_set`+`eac_default_app_config`（fallback）
- `EACRefreshConfig` JSON 字段：enable/updateMode/animationDuration/animationType/gcInterval/useGCForNewSurface/turbo/supportRegal/**refreshModeIndex**/refreshModeAlias/antiFlicker
- `RefreshModeIndex` 枚举：`NONE/REFRESH_MODE_1..5`（设 "NONE" 或空串即直通）
- 层级：`EACAppTheme`(themeType/pkg) → `EACActivityConfig` → `refreshConfig`
- 调用 API（均 public static，反射可调）：`EInkHelper.loadThemes(pkg)`→List<String>；`saveEACAppThemes(List<String>)`；`applyEACAppThemes(List<String>)`
- **生效要求**：完整重启让 OECService 重载（§八.4 结论 3；currentTop native pipe 软重启陷阱）

**验证准备（Legado 单 app 最小验证）**：目标=改 Legado 三个 theme 的 refreshConfig → refreshModeIndex=NONE + updateMode=1(DU) → save+apply → **完整重启** → 翻页抓 LUT 判是否 22 帧（通过则"全局统一"可行，再扩展遍历全部 app）。
- 工具：`_scratch_gs/DumpThemes.java`（反射 EInkHelper.loadThemes dump theme JSON，已编译 classes.dex 1892B）→ 先看 Legado theme 实际 JSON 结构再写修改 helper
- 未决：themeType 1/2/3 哪个 active（需 3 个都改）；NONE 直通运行时是否真采用（本验证回答）

### 13. ★ NONE 直通实测：Legado 翻页成功落 DU 22 帧（2026-09-08，验证通过）
**测试**：故障机 6C7F0E64、完整重启后 30s sys.boot_completed、Legado ReadBookActivity 前台、sepdc 正常。
**改动**：`DumpThemes` 确认 3 个 theme（themeType 1/2/3）真实 JSON 结构，refreshConfig 均位于 `appConfig.globalActivityConfig.refreshConfig`（原值：t1 idx=refresh_mode_3/updateMode=33554436/turbo=0；t2 idx=refresh_mode_3/updateMode=2/turbo=5/alias=new_speed；t3 idx=refresh_mode_1/updateMode=5/useGCForNewSurface=true）。`LegadoSetDU`（`_scratch_gs/LegadoSetDU.java`，ldu.dex）将 3 theme 全部改写为 `refreshModeIndex=NONE + updateMode=1` → saveEACAppThemes + applyEACAppThemes → **完整重启**（DumpThemes 复查持久化确认后才重启）。

**实测（改动后翻页）**：捕获 3 个活动 LUT **全部 `waveform[1] update[0] frame_total=22`（DU）**：
- `magic[202] lut[0] frame_cur[18]/22`（执行中被抓）
- `magic[204] frame_cur[1]/22`
- `magic[216] lut[1] frame_cur[21]/22`（完整推进至末帧前）
dmesg 全程无 `wait all_lut_free timeout`/`reset cause`/`update_err`，status 干净、epdc_active_luts 归零。

**对照**：改动前同机同操作（§10）10+ 活动 LUT 恒 `waveform[2] frame_total=38`（GC16）。

**结论**：
1. **NONE 直通路径真实生效**：`refreshModeIndex=NONE` 时 `caculateRefreshConfig` 走 else 分支 `setMode(updateMode)`，内核落 waveform[1]=DU 22 帧——§12 方案核心假设被真机坐实；8/25"写 updateMode 无效"盲点（未清 refreshModeIndex）确认
2. **"全局模式=统一各 app 配置档位"机制可行**：单 app 最小验证通过，可扩展为遍历全部 app 写 NONE+目标 updateMode
3. 次要观察：magic[204] 多次采样 frame_cur 停 1（疑采样时机，无卡死证据，后续 LUT 正常完成）；追加一轮 8 tap 无新 LUT（疑书末/翻页去重，非配置回退）

**设备当前状态**：Legado 3 theme = NONE + DU(1)，保留生效中。回滚=写回上表原值或经 Onyx 优化面板重置。
**工具**（`_scratch_gs/`）：LegadoSetDU.java/classes.dex（2992B，设备 /data/local/tmp/ldu.dex）；page_du_test.sh / page_du_test2.sh（root 翻页+LUT 采样，页面右侧 85%/50% tap）。

### 13.1 热生效实验：EAC per-app 写入**均需完整重启**，免重启不可行（2026-09-08）
**动机**：若 applyEACAppTheme 等 API 可运行时热生效，则"全局统一"切档无需重启。
**实验 1（单 API）**：`EInkHelper.applyEACAppTheme(单theme JSON)` 对 3 个 theme 依次改 updateMode=98，不重启翻页 → 仍 DU 22 帧；且 DumpThemes 复查 **98 未被持久化**（theme[0/1] 仍 NONE+1，theme[2] 被系统回退为默认 `refresh_mode_3+updateMode=2`）→ 98 非 EAC 直通合法值，调用被拒/触发系统回退；该 API 既不热生效也不可靠持久化。
**实验 2（全链对照）**：LegadoSetDU 全链（loadThemes→改 NONE+updateMode=2→saveEACAppThemes+applyEACAppThemes）持久化确认后**不重启**翻页 → 用户目测"无闪快速刷新"=DU 特征（运行仍 22 帧，非 38 帧全刷）→ **全链写入亦不热生效**，运行配置 = OECService 启动时加载值。
**结论**：
1. 所有 EAC per-app 写入路径（单 apply 与 save+apply 全链）对**已运行中**的前台 app 均不即时改变合成翻页波形；**完整重启（OECService 重载）是唯一生效途径**
2. updateMode 直通合法值域窄：1(→DU22)已验证；98 被拒；2 属系统默认档值（合法），但未重启无法观察其波形映射
3. "全局模式=统一各 app 档位"落地形态定为**重启式**：切档 → root 遍历全部已装 app 写 NONE+目标 updateMode → UI 提示完整重启生效（无免重启捷径）
4. 设备端现留工具：HeatApply.java（单 API 热应用探针，设备 /data/local/tmp/ha.dex）；heat_samp.sh（90s 被动采样）；当前 Legado 3 theme 已恢复 NONE+updateMode=1(DU)，持久化与运行态一致

# 十二、★★ scope 通道才是真通道 —— §10/§11/§13 重大勘误（2026-09-08 两台真机确证）

## 1. 勘误结论（先读这个）
- **scope（ViewUpdateHelper.applyAppScopeUpdate，含 null-pkg 全局）是改变第三方 app 真实合成翻页波形的【唯一】有效通道**。scope 未设时翻页恒系统默认 GC16 38帧。
- **§13"EAC NONE+updateMode 直通让 Legado 翻页变 DU22" = 误归因**：当时故障机 scope=DU 一直被旧版桌面自动维持，DU22 全归 scope；清除 scope 后 EAC NONE+1 翻页回 GC16（见下 E1）。
- **§10/§11"scope 对第三方真实翻页结构性无效" = 错误**（基于故障机一次未生效的 scope，当时无 fastModeIndex 佐证）。
- EAC per-app refreshConfig（refreshModeIndex 档 或 NONE+updateMode 直通）**不影响真实合成翻页波形**，只影响显式 repaint/滚动特判(mode 2/4)/周期 GC 等子路径。

## 2. 确证实验矩阵（2026-09-08）
正常机 6C1BF7D9（旧版桌面 byPass 版）：
| scope | Legado EAC | 真实翻页 LUT | 判读 |
|---|---|---|---|
| GU(2) | refresh_mode_3（原） | waveform[2]/38 GC16 | scope 生效（GU 映射 GC16 族）|
| DU(1) | NONE+98 | waveform[1]/22 DU（magic143/185/512）| scope 生效 |
| DU(1) | refresh_mode_3（还原） | waveform[1]/22 DU（magic180）| **scope 覆盖 EAC 档** |
| A2(4) | refresh_mode_3 | waveform[6] update[1]/5 A2（magic369/23）| **scope 主导实锤** |

故障机 6C7F0E64（旧版桌面同版）：
| scope | Legado EAC | 真实翻页 LUT | 判读 |
|---|---|---|---|
| DU(1) | NONE+1（§13 遗留）| waveform[1]/22 DU（magic3747/3750/3755）| 与正常机一致 |
| **清除**（fastModeIndex=0）| NONE+1 | **waveform[2]/38 GC16**（magic3864/3872）| **E1：EAC 直通独立无效** |

## 3. 机制解释（与 §11 修订）
- scope 写 SF 层，合成翻页取窗口 scope（有则用之、无则默认 GC16）；EAC per-app 决策(caculateRefreshConfig)输出不进入合成翻页通道（或优先级低于 scope，对照"scope 覆盖 EAC 档"）。
- §10 故障机 scope=DU 却 GC16 的历史异常：当时 scope 实际未生效（无 fastModeIndex 佐证；今测故障机 scope=DU 后 fastModeIndex=2 且翻页 DU22）。
- 旧版桌面（byPass 版）启动自动 apply config 档 → scope 每次开机恢复 → 全局刷新模式日常可用；A2=5帧/GU=GC16/DU=22 为实测有效的 scope 值。

## 4. 对代码方向的后果
- EAC 全链重写（GlobalEacRefreshHelper 遍历写 NONE+updateMode，c707645）**前提不成立，应回滚**；恢复并行 scope 完善版（byPass+7 档灰阶+切档全屏刷新，c629099 线）。
- scope 设置无需遍历写任何 app 配置；"统一各 app 档位"无必要——scope 天然全局。

## 5. 待办/余疑
- §11 推导的 caculateRefreshConfig 决策链仍适用于【显式/滚动特判】子路径，勿全盘否定；滚动 mode∈{2,4} 的 calculateScrollingRefreshMode 是否也吃 scope 待测。
- scope 值→内核 waveform 映射（GU=GC16/DEEP_GC/GCC/REGAL_PLUS 族 vs DU/A2 短帧）以 ref §9 帧数表 + 本表为准。

### 10. 故障机剩余优化点补充清单（2026-09-08 思考稿，均未验证）

> 在 §9 三条路（充电器实验 / v6 / 硬件维修）之外的新候选，按价值排序：

**① A2/短波形日常化（乘法效应，最有价值）**：临界供电窗口能否完成一次刷新取决于波形长度——GC16 38 帧 ≈400ms vs A2 5 帧 ≈25ms（16×）。若全局 scope 设 A2（launcher 已有选项），同等边际窗口下完成率大幅提高，与 v6（提高尝试频率）构成乘法组合。**前置验证**：§9.5 未闭环——正常机上普通 app 更新恒 GC16，scope 是否真让普通更新走 A2 未证实；需在故障机设 A2 后翻页抓 dump_lut_list 看 frame_total=5 还是 38。**代价**：A2 无灰阶（图标丢失）+ 残影积累（GC 罕见成功则残影长期化）——文本阅读可接受，图片不可。

**② /sys/class/sepdc/debug/cut_frame_num（免刷机裁帧旋钮）**：节点存在（§7.5）语义未探。若可在运行时裁减波形帧数（如 GC16 38→N），等效于①且保留灰阶。实验法：cat 当前值 → echo 新值 → dump_lut_list 观察 frame_total 变化。零风险（重启即恢复）。

**③ ~~唤醒 mini-window 测试~~（2026-09-23 已测，结论否定）**：重启有 40-170s 供电好窗口（§6.1）；深度睡眠唤醒后是否存在类似 mini-window —— **已测：不存在**。熄屏→唤醒（确认 `Asleep`→`Awake`）后 reset 循环照旧（frame 计数持续增长），无供电窗口。见 §9.3.9 ②。

**④ PG 0xBA 半轨分析（诊断+维修靶点）**：`Reg PowerGood: [0xf] 0xBA` = mask 0x0F 下 **4 轨中 bit1/bit3 好、bit0/bit2 差**——是部分轨不达，非全灭。两个方向：(a) 诊断"迟到"——若 bit0/bit2 是慢爬升（弱电容），enable 后加固定等待可转化失败为成功（patch 变体：0x5F46E8 `mov x0,x20`→`mov x0,#0x64` + 0x5F46EC `bl 0x120d3e0`→`bl 0xf45a8`(msleep,100ms)，两条指令；**v4 教训在此处，必须先诊断后动手**）；(b) 结合 TPS6518x datasheet 定位 bit0/bit2 对应轨 → 维修直接查那两轨的电容/焊接。

**⑤ 关停周期性 GC**：每次周期 GC 都是注定失败的 400ms 尝试+错误churn；降低 gcInterval（launcher 侧）减少无效尝试。

**明确拒绝**：放宽 PG mask（接受 2/4 轨就绪即开刷）——缺轨驱动面板有硬件损伤风险。

**建议执行顺序**：设备下次连接时——②（cat 节点+单值实验）→ ① 验证（设 A2 翻页抓 dump）→ ③（唤醒窗口）→ 充电器/前光/温度批量（§9）→ v6。①② 任一成立即可先落地日常方案，再叠加 v6。

### 11. ★ cut_frame_num 运行时裁帧旋钮实证（2026-09-08 晚，故障机 v5 实测定论）

**语义（受控矩阵 3×3 + 交叉验证）**：`echo N > /sys/class/sepdc/debug/cut_frame_num`
- 对 **slot-1 波形（DU 类，22 帧，update[0] 局部更新）做尾部减帧，实时生效**：cut=5→17、15→7、18→4（严格 =22−N）
- **slot-2（GC16）全豁免**（update[0] 与 update[1] 均实测 38 不变）；A2(5帧) 未捕获到独立 dump（太快或豁免）
- **⚠️ N≥22 危险**：cut=25 实测 total=253（22−25=−3 按字节回绕）；真实滑动 cut=30→248（=−8 mod 256）——减过头产生 2.5s 灾难波形，**必须 N<22**
- 更新落哪个波形槽由前台 app/刷新模式决定：同是滑动，一次落 DU-22（可裁）一次落 GC16-38（豁免）——旋钮只惠及落 DU 槽的更新
- 重启后归零（sysfs），需开机重写

**价值评估**：局部/滚动类更新（DU 22 帧 ≈220ms）裁到 4-10 帧（40-100ms）→ 边际供电窗口完成率提升数倍；**免刷机、可脚本化**（launcher 开机 `su -c 'echo N > ...'` 一行即可集成，同 RefreshModeHelper su-fallback 模式）。局限：GC16 全刷（Legado 翻页等）不受影响。

**当前状态（2026-09-08 晚终局）**：用户评估**收益仅 ~0.1s、代价（画质/覆盖面）不明确，已还原默认值 0，路线终止**，不集成 launcher。旋钮语义保留备查（未来若需"牺牲画质换边际窗口完成率"可再启用，严禁 N≥22）。

**遗留**：A2/slot-6 是否可裁未验证；INIT(slot-0) 是否受 cut 影响未验证（panel_init 裁剪有残影风险，建议维持 N 较小以规避）；"哪些 app 更新落 slot-1"的判定规则未挖（疑与 update region/驱动分类有关，静态可查 0x542270 状态机的波形选择逻辑）。

### 12.6 双管齐下实现 + 持久化行为（2026-09-10，代码 ae0a08d 已推送）
**背景**：起点读书（普通网络小说 app，无"无动画"选项）实测：scope=A2 下翻页 LUT 混合
（A2 偶发 + DU/GC16 为主，update[1] 类更新多），与 Legado（scope=A2 时全 A2）不同 →
普通 app 多更新类型（动画过渡/内容定格）中**部分更新不走 scope**。scope 仍是合成翻页
唯一主通道，但对这类 app 覆盖不全 → 采用**双管齐下**。

**设计（E-Ink-Launcher RefreshModeHelper，ae0a08d）**：
- `apply(index)`：scope 主通道（byPass→applyAppScopeUpdate(null)→恢复→整屏全刷），
  Launcher.onCreate 启动自动恢复用（scope 档位持久化在 launcher Config）
- `applyWithEac(index)`：scope 成功后**后台**遍历第三方 pkg 写 EAC theme
  （refreshModeIndex=NONE + updateMode=同档 UI 值，GlobalEacRefreshHelper root app_process
  全链 save+apply），None 档从 /data/local/tmp/eac_bak 备份 restore；设置面板切档走此入口
- 排除 com.onyx/com.android/自身；仅第三方 pkg

**持久化行为对比（三层）**：
| 层 | 持久化 | 机制 |
|---|---|---|
| 档位选择（launcher Config）| ✅ | SharedPreferences KEY_REFRESH_MODE |
| scope 状态（SF）| ❌ 重启丢 | 运行时 code transaction；靠 launcher onCreate 启动重放（实测日志每次 boot 后 auto apply config 档）|
| EAC theme 写入 | ✅ | saveEACAppThemes → 系统 MMKV，OECService 启动加载，无需重放 |

**互补性**：scope 即时强效但易失（依赖 launcher 开机自动重放）；EAC 持久（系统 MMKV
天然生效）但只在 EAC 决策子路径起作用。双管 = 即时 + 持久互补，None 档两通道各自恢复
（scope 清 + EAC restore 备份）。

**验证状态**：ae0a08d 已推远端，CI 构建中；真机验证（起点读书/普通 app 在双管后翻页
波形分布是否收窄到目标档）待 CI 产物安装后执行。

### 12.7 不定时重启调查：WMS 窗口风暴 → Watchdog 重启（2026-09-10 结论）
**现象**：故障机不定时重启（bootreason=reboot 软件重启，非 panic；pstore 空）；"频率低被忽视"。
**证据链**：
1. dropbox 2 天内 546 system_server_wtf（512 PowerManager）+ 79 system_app_wtf（25 SystemUI 音频
   RingtonePlayer under-lock，benign 无关）+ 6 UserspaceRebootLogger
2. PowerManager WTF 栈恒定：WindowToken.setExiting → removeWindowToken →
   WMS.setHoldScreenLocked acquire WakeLock("WindowManager") from android → PowerManager 拒
   （Onyx 定制 WMS/PowerManager 的 tag 校验不匹配）→ **每次窗口移除都打一个 WTF**
3. 重启前 events（01:02:57）：UserspaceRebootLogger "Userspace reboot is not supported" =
   Watchdog 触发的标准痕迹（Android 11 尝试 userspace reboot → 不支持 → 全量 reboot）
4. 触发模式：平时低频 benign（~1/5min）；**瞬时大量窗口增删**（EAC 批量 apply 12 app 重建 /
   USB 投屏切换）→ WTF 刷屏爆发拖卡 system_server → Watchdog 60s → reboot。01:01 撞上
   （EAC apply+投屏叠加）重启；01:06 同 EAC 操作无投屏未重启 = 概率性
**对策**：EAC 写入改 save-only（已改，ae0a08d 之后的 605d356+ 分支）消除最大批量窗口重建源；
SystemUI/框架层 tag 不匹配不可直接改（Onyx 定制）；避免瞬时批量窗口操作。
**监控建议**：后续若再重启，看 /data/system/dropbox/system_server_wtf 是否在重启前分钟级爆发 +
events UserspaceRebootLogger 时间戳，可复现定位。

### 12.8 ★ 通知栏磁贴"立即切换"机制 —— 官方同款即时链（2026-09-10）
**问题**：通知栏刷新引擎磁贴切档为何立即生效（当前 app 翻页马上变），无需重启？
**调用链（ref §633-660 + OECService/TabletEACRefreshImpl）**：
```
OnyxRefreshModeController → EInkHelper.setAppScopeRefreshMode(mode)
  → OECService.setAppScopeRefreshMode(mode, apply=true)
  → TabletEACRefreshImpl.setAppScopeRefreshMode(prev,cur,deviceConfig,mode):
      ① 当前 top app refreshConfig.setUpdateMode(mode)   （内存 EAC 配置）
      ② deviceConfig.fallbackRefreshConfig.setUpdateMode(mode)  （全局默认）
      ③ setAppRefreshModeImpl() → applyAppScopeUpdate() / clearAppScopeUpdate() （★立即 SF scope）
      ④ BroadcastHelper.sendRefreshModeChangeBroadcast(mode,turbo)
      ⑤ saveDeviceConfig()（持久化 MMKV）
```
**立即性来源 = ③ scope 当场应用**（与 launcher 全局 scope 同通道）；全局其它 app 即时性来自
② fallbackRefreshConfig（无自定义 app 的决策源，运行中即读，非逐 app theme 等重载）。
磁贴 mode 为**逻辑 mode 0-5**（Normal=0/DU=1/A2=2/Regal=3/X=4/Regal+=5），内部
EACUtils.toEpdMode 转 UI/EPD 值后 applyAppScopeUpdate。
**对照**：Onyx 官方**不用 applyEACAppThemes 逐 theme 批量**（我们窗口风暴来源），走
setAppScopeRefreshMode 单路径（改 fallback+当前 app 内存 + 当场 scope + 持久化）。
**升级方向**：双管 EAC 侧可由 save-only（等重启）升级为官方同款
EInkHelper.setAppScopeRefreshMode(逻辑mode)（root 可调），使 EAC 内存配置即时生效；
scope 主通道保留（UI 级 7 档直接 applyAppScopeUpdate(null) 精细控制）。

**⚠️ 升级冲突评估（2026-09-10，暂缓）**：直接以 EInkHelper.setAppScopeRefreshMode(逻辑mode)
替换 save-only 会与 launcher 7 档全局 scope 主通道冲突：
- setAppRefreshModeImpl：mode∈{1,2,4} → clearSFDebouncer + applyAppScopeUpdate(当前pkg,逻辑mode)；
  mode=0 → clearAppScopeUpdate + onResume → **会清除/覆盖全局 null scope**
- 逻辑 mode 0-5（磁贴仅 4 档）无法表达 launcher 的 GU/GC/DEEP_GC 等 7 档 UI 语义
- E1 实证 EAC 不参与合成翻页（scope 唯一）→ EAC 即时性仅影响小众显式路径，收益小于冲突代价
待用户决策：保持 save-only+scope（现状）/ 真机实证官方调用副作用后再定 / 实现后实验调平。

### 12.9 逻辑 mode 域族谱 + DU 归属 + launcher 档位改造（2026-09-10，代码 0bf8ed5）
**四个 mode 值域并存（混用根源）**：
| 域 | 取值 | 定义源 |
|---|---|---|
| **逻辑 mode** | 0=DEFAULT/NORMAL(同值)、1=DU、2=A2、3=REGAL、4=X、5=REGAL_PLUS、-1=UNKNOWN | `Constant.java:152-159`（Onyx EAC 内部枚举）|
| RefreshModeIndex | NONE, REFRESH_MODE_1..5 | `android.onyx.utils.RefreshModeIndex` |
| RefreshModeData.mode/turbo | 出厂配置定义 | `SysUIConfig.refreshConfigMap` ← `ConfigLoader.load(SysUIConfig,"systemui")`（`<MODEL>_systemui` JSON）|
| UI 组合值 | 1/2/4/6/9/98/107/108/2305/2308/16777220/33554436 | `ViewUpdateHelper.UI_*` |
（另有内核 EPD 波形槽：1=DU/2=GC16/4=ANIM/6=REGAL…）
**转换链**：`caculateRefreshConfig`（index≠NONE→配置档；NONE→updateMode 直通）→
`EACUtils.toEpdMode`（逻辑→UI：1→2305/2→2308/3→6/4→16777220/5→9/其余→5）→ ViewUpdateHelper → 内核波形。
**DU 归属**：逻辑 DU(1) → toEpdMode **2305 = UI_DU_QUALITY_MODE = UI_DU_MODE(1) | 0x900(质量标志)**；
基础是标准 DU，**不是 DU4**（DU4=UI 2312/EPD 8，逻辑域不可达）。实测旁证：UI DU=1 → waveform[1]/22 帧。
**launcher 改造（0bf8ed5）**：全局档位改为逻辑域 `None + NORMAL(0)/DU(1)/A2(2)/REGAL(3)/X(4)/REGAL_PLUS(5)`；
- EAC 写入值 = 逻辑 mode（与官方 setUpdateMode 同域）
- scope 值经 toEpdMode 等价转换（`LOGIC_TO_SCOPE={5,2305,2308,6,16777220,9}`）
- None/NORMAL(0) → clearAppScopeUpdate（官方 mode 0 同款：清 scope 交系统）
- 待设备重连实测校准：2305/2308/16777220 在 scope 通道与已知裸值 1/4 的等效性

### 12.10 五档帧数对比 + scope 值全量校准（2026-09-10 故障机实测，代码 10bd609）
**测试法**：故障机起点读书阅读页翻页 → dump_lut_list 抓 waveform/frame_total（双防御全链生效状态）。
| 档位 | 逻辑 | scope 值(toEpdMode) | 实测波形/帧数 | 灰阶 | 速度 | 残影 |
|---|---|---|---|---|---|---|
| DU | 1 | 2305 | `waveform[1]` **22帧**（纯 DU）| 2级黑白 | 中 | 累积 |
| A2 | 2 | 2308 | `waveform[6]` **5帧** + DU22/GC16 38 混合 | 无灰 | **最快** | 累积最快 |
| REGAL | 3 | 6 | `waveform[2]` **38帧**（纯 GC16）| 16级灰 | 慢 | 低(全刷) |
| X | 4 | 16777220 | 同 A2（**5帧** + 22/38 混合）| 动画无灰+内容自适应 | **最快** | 同 A2 |
| REGAL_PLUS | 5 | 9 | `waveform[2]` **38帧**（纯 GC16）| 16级灰 | 慢 | 低(全刷) |
（NORMAL(0) → clearAppScopeUpdate 交系统默认，未单独实测）
**结论**：
1. **五档 scope 值全部校准通过**：`LOGIC_TO_SCOPE={5,2305,2308,6,16777220,9}` 均正确落地对应波形；
   2305/2308/16777220 的 0x900 质量位不改变基础波形（与裸值 1/4 等效）
2. 帧数三档：**5帧(A2/X) < 22帧(DU) < 38帧(GC16 族)**；REGAL 与 REGAL_PLUS 在 Poke6 上等价（同落 GC16 38帧）
3. **A2/X 在带动画 app（起点读书）上呈混合**（动画 5帧 + 内容 22/38帧，update flag=1）——
   不是"不走全局"，而是按更新类型分层；DU 档纯 22帧、REGAL 族纯 38帧（update flag=0）
4. 双防御全链（全局 scope + 官方 setAppScopeRefreshMode + theme save-only）工作正常，
   切档后系统稳定（uptime 11h+ 无重启）
**场景建议**：文字阅读→DU(1)；要画质/清残影→REGAL_PLUS(5)/REGAL(3)；滚动动画多→A2(2)/X(4)；不动系统→NORMAL(0)。

# 十三、桌面增强：一键切换 Onyx 原始桌面 + 卸载前自愈（2026-09-10，代码 b965019/42f5274 系）
**背景**：launcher（cn.modificator.launcher）替代了 Onyx 原始桌面（com.onyx），后者常被禁用/冻结
（实测 enabled=3 DISABLED_USER，query-intent 不列出其 HOME 组件）。

## 1. 一键启用原始桌面（仿"一键锁屏/WiFi 名字"虚拟条目）
- 虚拟包名 `E-ink_Launcher.OnyxHome`（AppDataCenter.ONYX_HOME_PACKAGE_NAME）→
  createOnyxHomeIcon()（默认图标 R.mipmap.ic_launcher；自定义图标 `E-ink_Launcher.OnyxHome.png`）
- 点击 → `Launcher.launchOnyxHome()`：
  1) `ensureOnyxHomeEnabled()`：**先判定** `getApplicationInfo("com.onyx",0).enabled`，未启用才 `su pm enable com.onyx`
  2) 显式组件 `com.onyx/.StartupActivity` + `CATEGORY_HOME` 启动
- 关键组件（实测）：com.onyx 的 HOME activity = **`com.onyx/.StartupActivity`**
  （声明 MAIN+LAUNCHER+LAUNCHER_APP+HOME）；启动后内部转 `com.onyx.reader.main.ui.MainActivity`

## 2. 卸载前自愈（app 无自身卸载回调，DeviceAdmin 是唯一挂钩点）
- App 被卸载时进程被杀，无回调；但带 DeviceAdmin 的 app 卸载前系统先走 admin 停用流程
  → 触发 `AdminReceiver.onDisableRequested` / `onDisabled`（此时 app 尚在运行）
- 在其中执行（同样先判定 enabled）：`pm enable com.onyx; cmd package set-home-activity com.onyx/.StartupActivity`
  → 把默认 HOME 恢复为 Onyx 原始桌面，避免卸载后无桌面（只剩 FallbackHome）
- 主动入口：长按桌面"原始桌面"图标 → 菜单（① 切换到原始桌面 ② 设为默认桌面（卸载前恢复））
- 兜底：卸载默认 HOME 后系统自动 fallback 到其它可用 HOME（需 com.onyx 已启用）

## 3. 实测证据（正常机 6C1BF7D9，2026-09-10）
- `cmd package set-home-activity com.onyx/.StartupActivity` → **Success**；
  `cmd package resolve-activity -c HOME` 返回 `com.onyx/.StartupActivity` ✓
- 恢复 `... cn.modificator.launcher/.Launcher` → Success，resolve 回 launcher ✓
- `pm enable com.onyx` → enabled=1；`am start -n com.onyx/.StartupActivity` → 焦点
  `com.onyx/com.onyx.reader.main.ui.MainActivity`（原始桌面呈现）✓
- 注：本 ROM `cmd package get-home-activity` 不存在（Unknown command），用 `resolve-activity` 验证

## 4. 崩溃时启用 com.onyx 兜底（2026-09-10，代码 fabaa1d）
**背景**：`CrashCapture` 原本已有"崩溃后启动兜底桌面"（startFallbackLauncher），但实现缺陷：
只检查 com.onyx 包存在性，再以 `ACTION_MAIN + CATEGORY_HOME + setPackage("com.onyx")` 启动——
而 com.onyx 常处于 DISABLED_USER（enabled=3）状态，**禁用/冻结时组件解析不到 → 兜底静默失效**
（表现为崩溃后无接管桌面，靠系统自动 fallback 才没黑屏）。

**修复后流程**（uncaughtException 时执行）：
1. 判定 `getApplicationInfo("com.onyx",0).enabled`；
   未启用 → `su -c "pm enable com.onyx"`（崩溃兜底前提；包不存在则静默返回）
2. 以**显式组件** `com.onyx/.StartupActivity` + CATEGORY_HOME 启动（比 setPackage 解析稳，
   新增常量 FALLBACK_ACTIVITY）
3. 写崩溃日志（/sdcard/Android/data/cn.modificator.launcher/files/crash/）+ 跳转崩溃详情页（原逻辑）

**生效时机**：launcher 未捕获异常时自动执行——保证"崩溃 → 有可用桌面接管"，不再黑屏。

## 5. 虚拟条目崩溃事故与教训（2026-09-10，修复 c35f860）
**事故**：新增"一键切换原始桌面"虚拟条目后 launcher 启动即崩：
```
RuntimeException: Unable to start activity ...Launcher
Caused by: NullPointerException: PackageItemInfo.nonLocalizedLabel on a null object reference
  at ResolveInfo.loadLabel ← AppSortComparator.compareByName ← Collections.sort ← Launcher.onCreate
```
**根因**：新增虚拟包名 `ONYX_HOME_PACKAGE_NAME` 未被 `AppSortComparator.isVirtual()` 识别
（该处只判断 LOCK/WIFI）→ 虚拟条目走 `loadLabel`；其 `ActivityInfo.applicationInfo` 为 null → NPE。
（WIFI/LOCK 之所以一直正常：isVirtual 让它们跳过 label 比较。）
**修复（双保险）**：
1. `AppSortComparator.isVirtual()` 增加 `ONYX_HOME_PACKAGE_NAME`（虚拟条目固定排末尾）
2. `AppDataCenter.createOnyxHomeIcon()` 补 `applicationInfo`（packageName + nonLocalizedLabel
   ="Onyx 桌面"），使排序/显示路径（AppItemBinder.loadLabel）安全
**教训**：新增虚拟条目必须同时 ①加入 `isVirtual` 判定 ②提供 `applicationInfo.nonLocalizedLabel`。

## 6. 首屏图标不刷新修复（2026-09-10，代码 4a72f48）
**现象（老问题）**：冷启动首次进入桌面时部分/全部图标不显示，点菜单"管理应用"后恢复正常。
**原因**：首屏绘制时图标缓存/自定义图标（IconCache 磁盘缓存、外置存储扫描）尚未就绪，
且此后没有刷新回调；"管理应用"入口触发 `dataCenter.refreshAppList()`（重建列表 + 重新取图标）
恰好补齐。
**修复**：`Launcher.onCreate` 末尾 `getWindow().getDecorView().postDelayed(1200ms)` →
`refreshIcons()` + `dataCenter.refreshAppList()`（与"管理应用"同款动作），
带 `isFinishing()/isDestroyed()` 保护；仅进程启动执行一次，不影响 onResume 常规刷新。
**可调**：若 1.2s 偏早/偏晚（仍缺图标或可见重绘）可调延迟，或改为"检测未就绪再刷新"。

---

# 十五、手势设置「写入报错 / 全部显示无」根因与修复（2026-09-10）

## 症状
launcher「手势设置」读出来**全部显示"无"**，点「保存并应用」报 **"保存失败（root/写入错误）"**；
而设备实际手势为 底部左=任务切换 / 底中=HOME / 底右=BACK。

## 排查（逐层排除，全部实测）
- `gestures_config` **存在且内容正确**（778B）：`bottom_middle=HOME / bottom_left=TASK_SWITCH /
  bottom_right=BACK / left_*=BACK / tree_point_down=SCREENSHOTS` —— 与用户描述一致，**数据无问题**
- **SELinux 不是根因**：Enforcing 下 `cat`/`cp` 备份/覆写/新建/`kill $(pidof systemui)` 全部成功、
  无 avc denial（magisk 域对 app_data_file 有完整读写权限）
- launcher uid **10126 已授权 Magisk su**（magisk.db `policies`: `10126|2|0|1|1`，policy=2=allow）
- UI 解析逻辑（`SettingFragment` `"pos":"` indexOf）正确，无 bug

## ★ 真根因：su 二进制的 PATH 查找失败
本机 Magisk 的 su **只存在于 `/debug_ramdisk/su`**（`/system/bin/su`、`/system/xbin/su` 均不存在），
而 **app 进程的 PATH 不含 `/debug_ramdisk`**：
```
env -i PATH=/system/bin:/system/xbin sh -c 'su -c ...'            → "su: inaccessible or not found"
env -i PATH=/system/bin:/system/xbin /debug_ramdisk/su -c echo ok → ok
```
→ `Runtime.exec(new String[]{"su","-c",cmd})` 抛 IOException →
`GestureConfigHelper.load()` 返回 null（UI 全回退 "NONE"）、`save()` 返回 false（保存失败 Toast）。
**两个症状同一根因**。adb shell 里 su 可用是因为 shell 的 PATH 含 `/debug_ramdisk`，故此前仅在 app 内失败。

## 修复（本次提交）
- **新增 `SuHelper.java`**：按 `/debug_ramdisk/su` → `/system/bin/su` → `/system/xbin/su` → `/sbin/su`
  → `"su"` 顺序探测并缓存可用 su；提供 `execOk / execRead / start / isAvailable`
- **全部裸 `"su"` 调用点切换 SuHelper（6 处）**：
  `GestureConfigHelper`（load/save/isRootAvailable）、`GestureNavHelper`（导航模式回退）、
  `Launcher`（per-app 主题写入 / 设为默认桌面 / 启用 com.onyx）、`CrashCapture`（启用兜底桌面）、
  `RefreshModeHelper`（runRoot / hidden_api_policy）
- 同类隐患一并消除：这些功能此前在 app 进程内同样会静默失败（表现各异：手势读空、模式切换无效、
  启用 Onyx 桌面失败等）

## 验证方式
- 手动：`/debug_ramdisk/su -c 'cat /data/data/com.android.systemui/gestures_config'`
- 装包后进「手势设置」应正确显示当前各项手势并可保存（保存后 systemui 自动重启生效）

---

## ★★ 真根因修正（2026-09-10 深夜，实测定位）：su 二进制 SELinux label 错误

> 上文"su 路径（PATH 不含 /debug_ramdisk）"只是**第一层表象**；装上 SuHelper 后仍失败，运行期日志
> 与 SELinux 实验定位到真正根因。

### 运行期日志证据（launcher 侧，`files/su_debug.log`）
```
execRead len=0 done=true  cmd=cat /data/data/com.android.systemui/gestures_config
execOk   rc=1 err=cp: bad '/data/data/com.android.systemui/gestures_config': No such file or directory
```
→ 命令**执行了**但**没有 root 权限**（看不到 systemui 私有文件）——即 su 未真正提权。

### SELinux 实验链（设备端）
| 实验 | 结果 |
|---|---|
| `su 10126 -c 'id'`（外层 root→切 uid 10126，域仍 magisk） | `uid=0` ✅（说明 uid 10126 授权有效、Magisk su 可提权） |
| launcher 真实域 = `u:r:untrusted_app:s0:c126,c256,c512,c768` | — |
| `runcon u:r:untrusted_app:s0 /debug_ramdisk/su -c id` | ❌ `exec /debug_ramdisk/su: Permission denied` |
| `ls -laZ /debug_ramdisk/magisk` | **`u:object_r:system_file:s0`**（su symlink 指向它） |
| Magisk policy | 放行规则是 `allow domain magisk_file file {… execute …}` —— **只对 magisk_file 类型生效** |
| **`chcon u:object_r:magisk_file:s0 /debug_ramdisk/magisk`** 后再 runcon untrusted_app exec su | ✅ **`uid=0(root)` 提权成功** |

### 结论
- Magisk 30.7 在本机把 su 二进制放在 `/debug_ramdisk/magisk`（`/debug_ramdisk/su` symlink→它），
  **但 label 是 `system_file`**；`allow appdomain system_file file { execute }` 虽在策略里，
  **untrusted_app 域实际 exec 仍被拒**（Android app 域的 system_file execute 有路径/neverallow 约束）
- Magisk 自己的放行规则针对 **magisk_file** → label 不对 = **所有 app 的 su 调用都失败**
  （不止 launcher；adbshell 域能 exec 所以此前只用 adb 测试时未暴露）
- **修复：`chcon u:object_r:magisk_file:s0 /debug_ramdisk/magisk`**（实测 launcher 域即可提权）
- ⚠️ **临时性**：重启后 Magisk 重新挂载 /debug_ramdisk，label 会变回 system_file → 需持久化
  （Magisk 模块 post-fs-data.sh 执行该 chcon，或修复 Magisk 安装本身）
- 回滚：`chcon u:object_r:system_file:s0 /debug_ramdisk/magisk`

### 附带（SuHelper 诊断能力）
- `SuHelper` 写 `files/su_debug.log`（logcat tag `SuHelper`）：probe 各候选结果、命令 rc/stderr、execRead 输出长度
- 探测带 8s 超时（避免 Magisk 授权弹窗挂死 UI）
- 注：日志文件属 app 私有（`app_data_file`，MLS c126…），**magisk 域直读被拒**；读取办法：
  `chcon u:object_r:magisk_file:s0 <logfile> && cat`，之后 `restorecon` 还原
- 待改进：probe 阶段日志应写入文件（现 init 在 load() 内，probe 早于 init 只进 logcat）；
  probe 应记录 `id` 的 stdout（区分"exec 失败"与"exec 成功但未提权"）

---

## ★ 第三层根因：launcher UI 编辑状态丢失（2026-09-11，故障机 6C7F0E64 实测）

### 现象（故障机）
「手势设置」**能读到**现有手势，"改不了"——选完动作后 UI 仍显示旧值。

### 日志证据（故障机 `files/su_debug.log`，一次保存）
```
execRead len=776   cat gestures_config      ← 读到现有配置
execOk  rc=0       cp 备份                  ← 成功
execOk  rc=0       echo '<b64>' | base64 -d > gestures_config   ← 写入成功
execOk  rc=0       kill $(pidof com.android.systemui)           ← 重启成功
execRead len=777   cat gestures_config      ← 文件确实变了
```
→ **su / 权限 / 写入全部正常**（故障机上 app 域能 exec su）；文件 776→777 但**内容未按用户选择变化**。

### 真根因（UI 逻辑）
`SettingFragment.showGestureActionDialog()` 选完动作后 `map.put(...)` → 调 `showGestureSettingsDialog()`
→ 后者**重新 `load()` 并新建 `final Map`**（丢弃刚选的 map）→
- UI 显示回到文件旧值（"改不了"）
- 保存时 `save(map)` 写的是**旧值**（所以文件大小微变、内容不变）

### 修复（commit a1b0d8e）
- 新增字段 `gestureEditMap`（跨对话框重建保持）
- `showGestureSettingsDialog()`：`gestureEditMap != null` 时复用；labels 从 map 构建
- 保存成功 / 取消 / 点外部关闭 → `gestureEditMap = null`（下次打开重新从文件读）

### 两台设备差异小结（重要）
| | 正常机 6C1BF7D9 | 故障机 6C7F0E64 |
|---|---|---|
| `/debug_ramdisk/magisk` label | `system_file` | `system_file` |
| app 域(untrusted_app) 能否 exec su | ❌ `Permission denied`（实测） | ✅ 能（日志显示读/写都成功） |
| 症状 | 读全空 + 保存报错（su 起不来） | 能读、写入成功但"改不了"（UI bug） |
| 处置 | 设备侧：`chcon … magisk_file`（临时，重启失效；持久化待定） | 代码侧：a1b0d8e 已修 |

→ 同一 label 下两机行为不同，说明**故障机的 SELinux policy 与正常机不同**（故障机 app 域被允许 exec su）。
正常机问题属设备/Magisk 侧；故障机问题是 launcher 代码 bug（本次已修）。

---

## ★★ 第四层：Magisk 重装后的两个变化（2026-09-11 凌晨，正常机 6C1BF7D9）

用户"彻底卸载 Magisk → 重装并刷入新 boot"后，正常机手势依旧全无。实测发现**两个叠加变化**：

### 变化 1：su 位置从 `/debug_ramdisk/su` 变为 `/product/bin/su`
```
/product/bin/su -> ./magisk            （/product/bin 由 magisk 以 tmpfs 覆盖挂载）
/product/bin/magisk  label = system_file
mount: magisk on /product/bin type tmpfs (ro,seclabel)
```
- **`/product/bin` 在 app 进程 PATH 中**，但 **`Runtime.exec` 不查 PATH** → 必须绝对路径探测
- 修复（commit 07e9241）：`SuHelper.CANDIDATES` 增加 `/product/bin/su`（置顶）、`/vendor/bin/su`、`/odm/bin/su`
- 旧版 `/debug_ramdisk/su` 仍保留候选（兼容旧布局）

### 变化 2：`彻底卸载 Magisk` 清空 su 授权表（关键）
- 卸载会删 `/data/adb`（含 `magisk.db` 的 `policies` 授权表）→ 重装后**所有 su 请求需重新授权**
- 实测证据：
  | 命令 | 结果 | 说明 |
  |---|---|---|
  | `/product/bin/magisk -v` | `30.7:MAGISK:R` rc=0 | 同一二进制**可执行**（不需授权） |
  | `/product/bin/su -c id` | `Permission denied` rc=13 | 被 **magiskd 拒绝**（不是 exec/label 问题） |
  | `/product/bin/magisk --sqlite …` | `Root is required for this operation` | 明确：未获 root |
  | `ps -A` | `magiskd`(root) + `com.topjohnwu.magisk:root:0` | magiskd 与 Magisk app 自身正常 |
- **结论**：`Permission denied` 来自 magisk su 客户端被 magiskd 拒绝，根因是**授权表为空**
- **修复（用户侧）**：Magisk app → 设置 → **超级用户访问 = 仅允许**（或"提示"+弹窗确认）；也可在"超级用户"列表里给 `cn.modificator.launcher` 与 `Shell` 放行

### 排查要点（避免误判）
- 不要把 su 的 `Permission denied` 一律归因于 SELinux label：**先对比同一二进制的 `-v` 是否可执行**——
  可执行 ⇒ exec/标签没问题 ⇒ 是 **magiskd 授权**问题；不可执行 ⇒ 才是 exec/label 问题
- `adb shell su` 在重装后也可能消失（shell PATH 不含新位置时），需用绝对路径 `/product/bin/su`

---

## ★★★ 最终根因（2026-09-11 确认，问题已解决）：Magisk 30.7 新 su 布局 × 「命名空间=继承」不兼容

**用户把 Magisk 设置里的命名空间从「继承」改为「全局」后，正常机 launcher 手势设置立即恢复正常。**
⚠️ 但**「继承」本身不是 root 因**（它是 Magisk 默认值）：**故障机 6C7F0E64 同样设置"继承"却一直正常**。
→ 真正的组合条件是 **"Magisk 30.7 重装后的新 su 布局（/product/bin，tmpfs）× 继承 ns"**。

### 两机对照（关键）
| | 故障机 6C7F0E64 | 正常机 6C1BF7D9 |
|---|---|---|
| su 位置 | `/debug_ramdisk/su`（旧布局） | `/product/bin/su`（重装 Magisk 30.7 后的新布局，tmpfs） |
| 命名空间 | 继承 | 继承 → **app 调 su 提权失败**；**改全局后正常** |
| app 调 su | ✅ 正常 | ❌（读 len=0 / cp 无权限） |

### 根因链（正常机）
```
Magisk 30.7 新布局(su=/product/bin tmpfs) + 命名空间=继承(Inherit)
  → su 会话继承 app 的 mount namespace，该视图下 magisk 的 su/mount 组合无法
    与 magiskd 完成提权（命令以非 root 执行）
  → launcher root 功能全失效：cat gestures_config 无输出(len=0)、cp 报无权限
     → “手势全无 / 保存失败”
命名空间=全局(Global) → su 会话用 init 全局 ns → 提权成功 → 一切正常
```
（注：Magisk 30 的 daemon socket 为抽象 socket，不落文件系统，故"文件不可见"不是解释；
实际差异来自 **Magisk 版本/布局与 ns 模式的兼容性**——旧布局配"继承"OK，新布局配"继承"不 OK。）

### 关键设置项（Magisk app → 设置）
| 设置项 | 取值 | 说明 |
|---|---|---|
| **命名空间** | 全局 / 独立 / 继承 | **新布局（/product/bin）下必须「全局」**；旧布局「继承」可用（故障机） |
| 超级用户访问 | 仅 adb / **用户和 adb** | 「用户和 adb」= 允许 app 请求（本次设置正确） |
| 超级用户列表 | — | 逐 uid 授权（本机 `10126 policy=2` 已 allow，**不是**根因） |

### 排查方法论（本次踩坑总结）
1. **先分清三层**：① su 路径（能否找到）→ ② su 授权（magiskd 是否放行）→ ③ **运行环境（命名空间 / SELinux）**
2. **同一二进制的 `-v` 可执行但 `-c id` 不提权** ⇒ 不是 exec/label 问题 ⇒ 查授权与**命名空间**
3. **runcon 测 app 域会误导**：avc 报 `entrypoint denied`（域转换语义），而真实 app 进程 exec 检查的是 `execute`
   ⇒ 不要用 runcon 结论否定"app 能否执行 su"
4. **`/product/bin`、`/debug_ramdisk` 是 Magisk 的 read-only tmpfs** ⇒ `chcon` 改 label 的旧修复在此布局下**不可用**（Read-only file system）
5. 日志取证方式：launcher `files/su_debug.log`（app 私有，需 `chcon … magisk_file` 后读，或用 `SuHelper` 的 logcat 输出）；
   `SuHelper` 探测日志现在会记录 `su -c id` 的 stdout（`out=uid=…`）——**直接判定有没有提权**

### 结论
launcher 侧三处代码修复（均已完成并入库）：
- `SuHelper`：绝对路径探测 su（含 `/product/bin/su` 新布局）+ 诊断日志 + 探测超时（`c2fa7c5` / `07e9241` / `443816e`）
- `SettingFragment`：手势编辑状态跨对话框保持（`a1b0d8e`）
- UI 文案：`手势设置` / `手势已启用（点击切换底部按键）` / `按键已启用（点击切换手势）`（`e0e4635`）

**但本次故障的直接原因在设备侧 Magisk 设置**（命名空间=继承），代码修复只是让 launcher 能适配各种 su 布局并把失败原因记录下来。
