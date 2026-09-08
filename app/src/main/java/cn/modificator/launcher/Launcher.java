package cn.modificator.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Set;

import cn.modificator.launcher.ftpservice.FTPReceiver;
import cn.modificator.launcher.ftpservice.FTPService;
import cn.modificator.launcher.model.AdminReceiver;
import cn.modificator.launcher.model.AppDataCenter;
import cn.modificator.launcher.model.HomeEntranceService;
import cn.modificator.launcher.model.IconCache;
import cn.modificator.launcher.model.WifiControl;
import cn.modificator.launcher.widgets.AppItemBinder;
import cn.modificator.launcher.widgets.BatteryView;
import cn.modificator.launcher.widgets.EInkLauncherView;
import cn.modificator.launcher.widgets.LauncherAdapter;

/**
 * 主界面 Activity - E-Ink 墨水屏桌面启动器。
 */
public class Launcher extends Activity
    implements AppItemBinder.Callback, EInkLauncherView.OnPageChangeListener,
    SettingFragment.OnSettingChangeListener {

  private static final int REQUEST_DEVICE_ADMIN = 10001;

  // ---- Views ----
  private EInkLauncherView launcherView;
  private TextView pageStatus;
  private BatteryView batteryProgress;
  private TextView batteryStatus;
  private TextView textClock;

  // ---- Data ----
  private AppDataCenter dataCenter;
  private Config config;
  private Calendar calendar;
  private boolean isChina = true;
  private IconCache iconCache;
  private LauncherAdapter adapter;
  private AppItemBinder binder;
  private boolean isSystemApp = false;

  // ---- Device Admin ----
  private DevicePolicyManager policyManager;

  // ---- Receivers ----
  private FTPReceiver ftpReceiver = new FTPReceiver();
  private boolean batteryRegistered;
  private boolean timeRegistered;
  private boolean usbRegistered;
  private boolean ftpRegistered;

  private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      updateTimeShow();
    }
  };

  private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      handleBatteryChanged(intent);
    }
  };

  private final BroadcastReceiver appChangeReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      iconCache.clearAppCache();
      dataCenter.refreshAppList(binder.isDelete());
    }
  };

  private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
        iconCache.markDirty();
        refreshIcons();
      }
    }
  };

  // =========================================================================
  // Lifecycle
  // =========================================================================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.launcher_activity);

    config = new Config(this);
    WifiControl.init(this);
    applyStatusBarVisibility();

    isChina = getResources().getConfiguration().locale.getCountry().equals("CN");

    initViews();
    registerStaticReceivers();
    checkLaunchHomeNotification();

    // 恢复上次设置的全局刷新模式（若 Onyx SDK 可用）
    RefreshModeHelper.init(this);
    int refreshMode = config.getRefreshMode();
    if (refreshMode >= 0 && RefreshModeHelper.isAvailable()) {
      RefreshModeHelper.apply(refreshMode);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    registerDynamicReceivers();
    refreshIcons();
  }

  @Override
  protected void onPause() {
    super.onPause();
    unregisterDynamicReceivers();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    unregisterDynamicReceivers();
    unregisterReceiver(appChangeReceiver);
  }

  // =========================================================================
  // View 初始化
  // =========================================================================

  private void initViews() {
    policyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

    launcherView = findViewById(R.id.mList);
    pageStatus = findViewById(R.id.pageStatus);
    batteryProgress = findViewById(R.id.batteryProgress);
    batteryStatus = findViewById(R.id.batteryStatus);
    textClock = findViewById(R.id.textClock);

    ImageView settingIcon = findViewById(R.id.toSetting);
    settingIcon.setImageDrawable(
        Utils.tintDrawable(getResources().getDrawable(R.drawable.navibar_icon_settings_highlight),
            ColorStateList.valueOf(0xff000000)));

    // 配置 Binder、Adapter、View
    iconCache = new IconCache();
    iconCache.setDiskCacheDir(getCacheDir());
    binder = new AppItemBinder(getPackageManager());
    binder.setCallback(this);
    binder.setIconCache(iconCache);
    binder.setHideAppPkg(config.getHideApps());
    adapter = new LauncherAdapter();
    adapter.setBinder(binder);
    adapter.setFontSize(config.getFontSize());
    adapter.setAppNameLines(config.getAppNameLines());
    launcherView.setAdapter(adapter);
    launcherView.setOnPageChangeListener(this);

    // 初始化数据中心
    dataCenter = new AppDataCenter(this);
    dataCenter.setSortMode(config.getSortMode());
    dataCenter.setPageStatus(pageStatus);
    dataCenter.setAdapter(adapter);
    dataCenter.setHideApps(config.getHideApps());

    // 一次性配置网格参数，避免多次重建
    launcherView.configure(config.getColNum(), config.getRowNum(), config.isHideDivider());
    dataCenter.setGridSize(config.getColNum(), config.getRowNum());

    // 翻页按钮
    findViewById(R.id.lastPage).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        dataCenter.showLastPage();
      }
    });
    findViewById(R.id.nextPage).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        dataCenter.showNextPage();
      }
    });

    // 设置按钮
    findViewById(R.id.toSetting).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        getFragmentManager().beginTransaction()
            .replace(android.R.id.content, new SettingFragment())
            .addToBackStack(null)
            .commit();
      }
    });

    // 管理完成按钮
    findViewById(R.id.deleteFinish).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        binder.setDelete(false);
        dataCenter.refreshAppList();
        config.setHideApps(dataCenter.getHideApps());
        v.setVisibility(View.GONE);
      }
    });

    // 时间显示
    calendar = Calendar.getInstance();
    updateTimeShow();

    // 检测系统应用
    try {
      isSystemApp = !isUserApp(getPackageManager().getPackageInfo(getPackageName(), 0));
    } catch (PackageManager.NameNotFoundException e) {
      e.printStackTrace();
    }
  }

  // =========================================================================
  // SettingFragment.OnSettingChangeListener 实现
  // =========================================================================

  @Override
  public void onRowNumChanged(int rowNum) {
    launcherView.setRowNum(rowNum);
    dataCenter.setRowNum(rowNum);
  }

  @Override
  public void onColNumChanged(int colNum) {
    launcherView.setColNum(colNum);
    dataCenter.setColNum(colNum);
  }

  @Override
  public void onFontSizeChanged(float size) {
    adapter.setFontSize(size);
  }

  @Override
  public void onAppNameLinesChanged(int lines) {
    adapter.setAppNameLines(lines);
  }

  @Override
  public void onHideDividerChanged(boolean hide) {
    launcherView.setHideDivider(hide);
  }

  @Override
  public void onShowStatusBarChanged(boolean show) {
    applyStatusBarVisibility();
  }

  @Override
  public void onShowCustomIconChanged(boolean show) {
    iconCache.markDirty();
    refreshIcons();
  }

  @Override
  public void onEnterManageMode() {
    binder.setDelete(true);
    dataCenter.refreshAppList(true);
    findViewById(R.id.deleteFinish).setVisibility(View.VISIBLE);
  }

  @Override
  public void onSortModeChanged(int mode) {
    dataCenter.setSortMode(mode);
    dataCenter.refreshAppList(binder.isDelete());
  }

  @Override
  public void onToggleGestureNav() {
    if (GestureNavHelper.isGestureMode()) {
      GestureNavHelper.switchToVirtualKey();
    } else {
      GestureNavHelper.switchToGesture();
    }
  }

  // =========================================================================
  // 布局更新
  // =========================================================================

  private void refreshIcons() {
    if (adapter == null || iconCache == null) return;
    iconCache.refreshCustomIcons(getExternalCacheDir() != null, config.isShowCustomIcon());
    adapter.refreshDisplay();
  }

  // =========================================================================
  // AppItemBinder.Callback 实现
  // =========================================================================

  @Override
  public void onItemClick(ResolveInfo info) {
    String pkgName = info.activityInfo.packageName;

    if (AppDataCenter.LOCK_PACKAGE_NAME.equals(pkgName)) {
      lockScreen();
    } else if (AppDataCenter.WIFI_PACKAGE_NAME.equals(pkgName)) {
      WifiControl.onClickWifiItem();
    } else {
      ComponentName comp = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
      Intent intent = new Intent(Intent.ACTION_MAIN);
      intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
      intent.addCategory(Intent.CATEGORY_LAUNCHER);
      intent.setComponent(comp);
      try {
        startActivity(intent);
      } catch (ActivityNotFoundException | SecurityException e) {
        // 应用被冻结/卸载后图标仍残留时，避免崩溃
        e.printStackTrace();
      }
    }
  }

  @Override
  public void onItemLongClick(View anchor, ResolveInfo info) {
    String packageName = info.activityInfo.packageName;

    if (AppDataCenter.LOCK_PACKAGE_NAME.equals(packageName)) {
      showPowerMenu();
    } else if (AppDataCenter.WIFI_PACKAGE_NAME.equals(packageName)) {
      WifiControl.onLongClickWifiItem();
    } else {
      showAppInfoDialog(info, packageName);
    }
  }

  @Override
  public void onItemDeleteClick(ResolveInfo info) {
    Intent deleteIntent = new Intent(Intent.ACTION_DELETE,
        Uri.parse("package:" + info.activityInfo.packageName));
    startActivity(deleteIntent);
  }

  @Override
  public void onItemHideToggle(String packageName, boolean hidden) {
    // 管理模式下的隐藏切换仅更新 UI 状态，"完成" 按钮处理持久化
  }

  // =========================================================================
  // EInkLauncherView.OnPageChangeListener 实现
  // =========================================================================

  @Override
  public void onPageNext() {
    dataCenter.showNextPage();
  }

  @Override
  public void onPagePrev() {
    dataCenter.showLastPage();
  }

  private void showPowerMenu() {
    if (!isSystemApp) return;
    new AlertDialog.Builder(this)
        .setTitle(R.string.power_title)
        .setItems(R.array.power_menu, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            if (which == 0) {
              Intent intent = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
              intent.putExtra("android.intent.extra.KEY_CONFIRM", false);
              intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              startActivity(intent);
            } else {
              PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
              pm.reboot("重启");
            }
          }
        })
        .setPositiveButton(R.string.dialog_cancel, null)
        .show();
  }

  private void showAppInfoDialog(ResolveInfo info, final String packageName) {
    new AlertDialog.Builder(this)
        .setIcon(iconCache.getIcon(packageName, info, getPackageManager()))
        .setTitle(iconCache.getLabel(packageName, info, getPackageManager()))
        .setMessage(getString(R.string.dialog_pkg_name, packageName))
        .setItems(new String[]{
            getString(R.string.dialog_hide),
            getString(R.string.dialog_uninstall),
            getString(R.string.dialog_refresh_mode),
        }, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            if (which == 0) {
              Set<String> hideApps = binder.getHideAppPkg();
              if (!hideApps.add(packageName)) {
                hideApps.remove(packageName);
              }
              dataCenter.refreshAppList();
            } else if (which == 1) {
              Intent deleteIntent = new Intent(Intent.ACTION_DELETE,
                  Uri.parse("package:" + packageName));
              startActivity(deleteIntent);
            } else {
              showPerAppRefreshModeDialog(packageName);
            }
          }
        })
        .show();
  }

  /** 长按菜单：为该应用配置系统 per-app 刷新模式（写入系统 EACAppTheme，与通知栏 EInk Center 同源） */
  private void showPerAppRefreshModeDialog(final String packageName) {
    new AlertDialog.Builder(this)
        .setTitle(getString(R.string.dialog_refresh_mode) + " — " + packageName)
        .setItems(RefreshModeHelper.LABELS, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            boolean ok = applyPerAppRefreshMode(packageName, which);
            Toast.makeText(Launcher.this,
                ok ? "刷新模式已应用（root）" : "配置失败（root 授权或系统服务不可用）",
                Toast.LENGTH_SHORT).show();
          }
        })
        .show();
  }

  /** 构造 EACAppTheme JSON（fastjson 反序列化所需字段，fastjson 缺失字段用构造器默认） */
  private String buildPerAppThemeJson(String pkg, int modeValue) {
    try {
      org.json.JSONObject refresh = new org.json.JSONObject();
      refresh.put("updateMode", modeValue);
      refresh.put("enable", true);
      refresh.put("gcInterval", 20);
      org.json.JSONObject gac = new org.json.JSONObject();
      gac.put("refreshConfig", refresh);
      org.json.JSONObject appConfig = new org.json.JSONObject();
      appConfig.put("pkgName", pkg);
      appConfig.put("enable", true);
      appConfig.put("globalActivityConfig", gac);
      org.json.JSONObject theme = new org.json.JSONObject();
      theme.put("pkg", pkg);
      theme.put("name", pkg);
      theme.put("alias", "refresh");
      theme.put("themeType", 3);
      theme.put("changed", true);
      theme.put("appConfig", appConfig);
      return theme.toString();
    } catch (Exception e) {
      return null;
    }
  }

  /** 经 su + app_process 以 root 身份调 EInkHelper.applyEACAppTheme 写入系统 per-app 配置 */
  private boolean applyPerAppRefreshMode(String pkg, int modeIndex) {
    int modeValue = RefreshModeHelper.getModeValue(modeIndex);
    if (modeValue < 0) return false;
    String json = buildPerAppThemeJson(pkg, modeValue);
    if (json == null) return false;
    String b64 = android.util.Base64.encodeToString(json.getBytes(),
        android.util.Base64.NO_WRAP);
    String cmd = "CLASSPATH=" + getApplicationInfo().sourceDir
        + " app_process /system/bin cn.modificator.launcher.PerAppRefreshHelper "
        + pkg + " " + b64;
    try {
      Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
      int code = p.waitFor();
      return code == 0;
    } catch (Exception e) {
      return false;
    }
  }

  // =========================================================================
  // 时间显示
  // =========================================================================

  private void updateTimeShow() {
    if (textClock == null || calendar == null) return;

    boolean is24Hour = DateFormat.is24HourFormat(this);
    calendar.setTimeInMillis(System.currentTimeMillis());

    StringBuilder sb = new StringBuilder("yyyy-MM-dd ");
    if (!is24Hour && isChina) {
      sb.append(Utils.getAMPMCNString(calendar.get(Calendar.HOUR), calendar.get(Calendar.AM_PM)));
    }
    sb.append(is24Hour ? "HH:mm" : "hh:mm");
    if (!is24Hour && !isChina) {
      sb.append(" a");
    }
    sb.append(" EEEE");

    textClock.setText(new SimpleDateFormat(sb.toString(), Locale.getDefault()).format(calendar.getTime()));
  }

  // =========================================================================
  // 电池信息
  // =========================================================================

  private void handleBatteryChanged(Intent intent) {
    int rawLevel = intent.getIntExtra("level", -1);
    int scale = intent.getIntExtra("scale", -1);
    int status = intent.getIntExtra("status", -1);
    int health = intent.getIntExtra("health", -1);

    int level = (rawLevel >= 0 && scale > 0) ? (rawLevel * 100) / scale : -1;
    batteryProgress.setProgress(level);
    batteryStatus.setVisibility(View.VISIBLE);

    if (BatteryManager.BATTERY_HEALTH_OVERHEAT == health) {
      batteryStatus.setText(R.string.battery_heat);
      return;
    }

    switch (status) {
      case BatteryManager.BATTERY_STATUS_UNKNOWN:
        batteryStatus.setText(R.string.battery_unknown);
        break;
      case BatteryManager.BATTERY_STATUS_CHARGING:
        batteryStatus.setText(R.string.battery_charging);
        break;
      case BatteryManager.BATTERY_STATUS_DISCHARGING:
      case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
        if (level < 15) {
          batteryStatus.setText(R.string.battery_low);
        } else {
          batteryStatus.setVisibility(View.GONE);
        }
        break;
      case BatteryManager.BATTERY_STATUS_FULL:
        batteryStatus.setText(R.string.battery_full);
        break;
      default:
        batteryStatus.setText(R.string.battery_wtf);
        break;
    }
  }

  // =========================================================================
  // 广播注册/注销
  // =========================================================================

  /** 注册生命周期不变的静态广播 */
  private void registerStaticReceivers() {
    // 应用安装/卸载/冻结（启用状态变化）广播
    IntentFilter appChangeFilter = new IntentFilter();
    appChangeFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
    appChangeFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
    appChangeFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
    appChangeFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
    appChangeFilter.addDataScheme("package");
    registerCompatReceiver(appChangeReceiver, appChangeFilter);
  }

  /** 注册跟随 onResume/onPause 的动态广播 */
  private void registerDynamicReceivers() {
    if (!batteryRegistered) {
      registerCompatReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      batteryRegistered = true;
    }
    if (!timeRegistered) {
      registerCompatReceiver(timeReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
      timeRegistered = true;
    }
    updateTimeShow();
    if (!usbRegistered) {
      registerUsbReceiver();
    }
    if (!ftpRegistered) {
      IntentFilter ftpFilter = new IntentFilter(FTPService.ACTION_START_FTPSERVER);
      ftpFilter.addAction(FTPService.ACTION_STOP_FTPSERVER);
      registerCompatReceiver(ftpReceiver, ftpFilter);
      ftpRegistered = true;
    }
  }

  private void unregisterDynamicReceivers() {
    if (batteryRegistered) {
      unregisterReceiver(batteryReceiver);
      batteryRegistered = false;
    }
    if (timeRegistered) {
      unregisterReceiver(timeReceiver);
      timeRegistered = false;
    }
    if (usbRegistered) {
      unregisterReceiver(usbReceiver);
      usbRegistered = false;
    }
    if (ftpRegistered) {
      unregisterReceiver(ftpReceiver);
      ftpRegistered = false;
    }
  }

  private void registerUsbReceiver() {
    IntentFilter usbFilter = new IntentFilter();
    usbFilter.addAction(Intent.ACTION_UMS_DISCONNECTED);
    usbFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
    usbFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
    usbFilter.addAction(Intent.ACTION_MEDIA_REMOVED);
    usbFilter.addDataScheme("file");
    registerCompatReceiver(usbReceiver, usbFilter);
    usbRegistered = true;
  }

  private void registerCompatReceiver(BroadcastReceiver receiver, IntentFilter filter) {
    Utils.registerReceiverCompat(this, receiver, filter);
  }

  // =========================================================================
  // 按键处理
  // =========================================================================

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
      dataCenter.showLastPage();
      return true;
    } else if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
      dataCenter.showNextPage();
      return true;
    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
      // 作为 HOME 桌面，拦截返回键防止退出
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  // =========================================================================
  // 锁屏
  // =========================================================================

  public void lockScreen() {
    try {
      if (policyManager.isAdminActive(new ComponentName(this, AdminReceiver.class))) {
        policyManager.lockNow();
      } else {
        requestDeviceAdmin();
      }
    } catch (Exception e) {
      showDeviceAdminDialog();
    }
  }

  private void requestDeviceAdmin() {
    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, AdminReceiver.class));
    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "E-Ink Launcher 获取锁屏权限");
    startActivity(intent);
  }

  private void showDeviceAdminDialog() {
    new AlertDialog.Builder(this)
        .setTitle(R.string.launch_failed)
        .setMessage(R.string.launch_devicemanager_failed)
        .setPositiveButton(R.string.launch_devicemanager, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            try {
              Intent intent = Intent.parseUri(
                  "intent:#Intent;component=com.android.settings/.DeviceAdminSettings;end",
                  Intent.URI_INTENT_SCHEME);
              intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              startActivity(intent);
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        })
        .setNegativeButton(R.string.dialog_cancel, null)
        .show();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode == RESULT_OK && requestCode == REQUEST_DEVICE_ADMIN) {
      policyManager.lockNow();
    }
  }

  // =========================================================================
  // 状态栏/系统应用判断/通知栏
  // =========================================================================

  public void applyStatusBarVisibility() {
    int flags = WindowManager.LayoutParams.FLAG_FULLSCREEN;
    if (config.isShowStatusBar()) {
      getWindow().clearFlags(flags);
    } else {
      getWindow().setFlags(flags, flags);
    }
  }

  public boolean isUserApp(PackageInfo pInfo) {
    return (pInfo.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
  }

  private void checkLaunchHomeNotification() {
    if (!TextUtils.equals(Build.DEVICE, "virgo-perf1")) return;
    Intent service = new Intent(this, HomeEntranceService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(service);
    } else {
      startService(service);
    }
  }
}
