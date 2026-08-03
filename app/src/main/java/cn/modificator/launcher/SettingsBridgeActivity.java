package cn.modificator.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * 设置桥接 Activity：接收 Onyx 自定义 action，转发到原生 Android 设置。
 * <p>
 * 当内置桌面（eink-home）被卸载后，SystemUI 中依赖 onyx.settings.action.* 的
 * 磁贴/入口会找不到目标 Activity。此 Activity 注册了这些自定义 action，
 * 收到后跳转到对应的原生设置页面，然后立即 finish 自身。
 * <p>
 * 主题设为透明，用户看不到任何界面切换。
 */
public class SettingsBridgeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            finish();
            return;
        }

        String action = intent.getAction();
        Intent target = null;

        switch (action) {
            // ── 设置主页 ──────────────────────────────────────────
            case "com.onyx.action.SETTING":
            case "com.setting.action.CHILD_APP_MANAGEMENT":
            case "com.setting.action.CHILD_LIBRARY_MANAGEMENT":
                target = new Intent(Settings.ACTION_SETTINGS);
                break;

            // ── 声音设置 ──────────────────────────────────────────
            case "onyx.settings.action.SoundSettings":
                target = new Intent(Settings.ACTION_SOUND_SETTINGS);
                break;

            // ── 通知管理 ──────────────────────────────────────────
            case "onyx.settings.action.APP_NOTIFICATION_MANAGER":
                target = new Intent(Settings.ACTION_NOTIFICATION_SETTINGS);
                break;

            // ── 电池/电源管理 ──────────────────────────────────────
            case "onyx.settings.action.powermanager":
                target = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
                break;

            // ── 语言设置 ──────────────────────────────────────────
            case "onyx.settings.action.language":
                target = new Intent(Settings.ACTION_LOCALE_SETTINGS);
                break;

            // ── 日期时间 ──────────────────────────────────────────
            case "onyx.settings.action.datetime":
                target = new Intent(Settings.ACTION_DATE_SETTINGS);
                break;

            // ── 网络（无线和网络） ─────────────────────────────────
            case "onyx.settings.action.network":
                target = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                break;

            // ── WiFi ──────────────────────────────────────────────
            case "onyx.settings.action.wifi":
            case "android.intent.action.WIFI_ENABLE":
                target = new Intent(Settings.ACTION_WIFI_SETTINGS);
                break;

            // ── 蓝牙 ──────────────────────────────────────────────
            case "onyx.settings.action.bluetooth":
                target = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                break;

            // ── 应用管理 ──────────────────────────────────────────
            case "onyx.settings.action.app.management":
                target = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                break;

            // ── 安全/密码 ─────────────────────────────────────────
            case "onyx.settings.action.SYSTEM_PASSWORD_SETTING":
                target = new Intent(Settings.ACTION_SECURITY_SETTINGS);
                break;

            case "onyx.settings.action.password_manager":
                target = new Intent(Settings.ACTION_SECURITY_SETTINGS);
                break;

            // ── 反馈 ──────────────────────────────────────────────
            case "onyx.settings.action.feedback":
            case "onyx.settings.action.SUBMIT_FEEDBACK":
                // 原生没有直接反馈页，跳到关于手机
                target = new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS);
                break;

            // ── 工作模式 / WLAN 共享 ──────────────────────────────
            case "com.android.systemui.CONFIGURE_WORK_PROFILE_ACTION":
            case "com.android.systemui.CONFIGURE_TABLET_WORK_PROFILE_ACTION":
                target = new Intent(Settings.ACTION_SETTINGS);
                break;

            case "com.android.systemui.CONFIGURE_WLAN_NETWORK_SHARE_ACTION":
            case "com.android.systemui.CONFIGURE_TABLET_WLAN_NETWORK_SHARE_ACTION":
                // 跳转到 WiFi 热点设置
                target = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                break;

            // ── OTA / 固件升级 ────────────────────────────────────
            case "onyx.settings.action.firmware":
            case "onyx.settings.action.ACTIVE_PEN_FIRMWARE":
                target = new Intent(Settings.ACTION_SYSTEM_UPDATE_SETTINGS);
                break;

            // ── 辅助功能 ──────────────────────────────────────────
            case "onyx.settings.action.accessibility":
                target = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                break;

            // ── 账号同步 ──────────────────────────────────────────
            case "onyx.settings.action.STATISTICS":
            case "onyx.settings.action.MY_ANNOTATION":
                // 这些是 Onyx 独有功能，跳到设置主页让用户自行找
                target = new Intent(Settings.ACTION_SETTINGS);
                break;

            // ── 快捷启动 / 邮件 / 侧键 / 翻页器 / 滚动按钮 ──────
            case "onyx.settings.action.QUICK_LAUNCHER":
            case "onyx.settings.action.MAIL_SETTING":
            case "onyx.settings.action.CUSTOM_SIDE_KEY":
            case "onyx.settings.action.SCROLL_BUTTON_SETTING":
                // Onyx 独有功能，无原生对应，跳到设置主页
                target = new Intent(Settings.ACTION_SETTINGS);
                break;

            // ── 刷新模式帮助/设置 ─────────────────────────────────
            case "com.onyx.reader.settings.action.REFRESH_HELP":
            case "com.onyx.reader.settings.action.REFRESH_SETTINGS":
                // E-Ink 专属，无原生对应
                target = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
                break;

            // ── 屏保 / 关机画面 ───────────────────────────────────
            case "onyx.settings.action.SHUTDOWN_IMAGE_SETTING":
            case "onyx.settings.action.DREAM_STYLE_SETTING":
            case "onyx.settings.action.OTHER_DREAM_SETTING_GUIDE":
                target = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
                break;

            // ── 库/书架管理 ───────────────────────────────────────
            case "onyx.settings.action.library":
                target = new Intent(Settings.ACTION_SETTINGS);
                break;

            default:
                // 未知 action，尝试跳到设置主页
                target = new Intent(Settings.ACTION_SETTINGS);
                break;
        }

        if (target != null) {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(target);
        }
        finish();
    }
}
