package cn.modificator.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import cn.modificator.launcher.ftpservice.FTPService;
import cn.modificator.launcher.model.AppSortComparator;
import cn.modificator.launcher.model.WifiControl;

/**
 * 设置页面 Fragment。
 */
public class SettingFragment extends Fragment implements View.OnClickListener {

  /** 设置变更回调接口：宿主 Activity 应实现此接口以响应设置变更。 */
  public interface OnSettingChangeListener {
    void onRowNumChanged(int rowNum);
    void onColNumChanged(int colNum);
    void onFontSizeChanged(float size);
    void onAppNameLinesChanged(int lines);
    void onHideDividerChanged(boolean hide);
    void onShowStatusBarChanged(boolean show);
    void onShowCustomIconChanged(boolean show);
    void onSortModeChanged(int mode);
    void onEnterManageMode();
    void onToggleGestureNav();
  }

  private OnSettingChangeListener listener;

  private Spinner colNumSpinner;
  private Spinner rowNumSpinner;
  private Spinner appNameLinesSpinner;
  private Spinner sortModeSpinner;
  private SeekBar fontControl;
  private View rootView;
  private TextView hideDivider;
  private TextView ftpAddr;
  private TextView ftpStatus;
  private TextView showStatusBar;
  private TextView showCustomIcon;
  private Config config;

  @SuppressWarnings("deprecation")
  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);
    if (activity instanceof OnSettingChangeListener) {
      listener = (OnSettingChangeListener) activity;
    } else {
      throw new ClassCastException(activity.toString() + " must implement OnSettingChangeListener");
    }
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    return inflater.inflate(R.layout.activity_setting, null);
  }

  @Override
  public void onActivityCreated(Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    rootView = getView();
    config = new Config(getActivity());
    initViews();
    initSpinners();
    initFontControl();
    updateFtpStatus();
  }

  // =========================================================================
  // 初始化
  // =========================================================================

  private void initViews() {
    rootView.findViewById(R.id.toBack).setOnClickListener(this);
    rootView.findViewById(R.id.rootView).setOnClickListener(this);
    rootView.findViewById(R.id.deleteApp).setOnClickListener(this);
    rootView.findViewById(R.id.showWifiName).setOnClickListener(this);
    rootView.findViewById(R.id.btnHideFontControl).setOnClickListener(this);
    rootView.findViewById(R.id.changeFontSize).setOnClickListener(this);
    rootView.findViewById(R.id.helpAbout).setOnClickListener(this);
    rootView.findViewById(R.id.menu_ftp).setOnClickListener(this);
    rootView.findViewById(R.id.openDeviceManager).setOnClickListener(this);
    rootView.findViewById(R.id.toggleGestureNav).setOnClickListener(this);
    rootView.findViewById(R.id.gestureSettings).setOnClickListener(this);
    rootView.findViewById(R.id.refreshMode).setOnClickListener(this);
    if (!RefreshModeHelper.isAvailable()) {
      rootView.findViewById(R.id.refreshMode).setVisibility(View.GONE);
    }

    showStatusBar = rootView.findViewById(R.id.showStatusBar);
    showCustomIcon = rootView.findViewById(R.id.showCustomIcon);
    ftpStatus = rootView.findViewById(R.id.ftp_status);
    ftpAddr = rootView.findViewById(R.id.ftp_addr);
    hideDivider = rootView.findViewById(R.id.hideDivider);
    fontControl = rootView.findViewById(R.id.font_control);
    colNumSpinner = rootView.findViewById(R.id.col_num_spinner);
    rowNumSpinner = rootView.findViewById(R.id.row_num_spinner);
    appNameLinesSpinner = rootView.findViewById(R.id.appNameLine);
    sortModeSpinner = rootView.findViewById(R.id.sortModeSpinner);

    showStatusBar.setOnClickListener(this);
    hideDivider.setOnClickListener(this);
    showCustomIcon.setOnClickListener(this);

    // 初始化 UI 状态
    showStatusBar.getPaint().setStrikeThruText(config.isShowStatusBar());
    hideDivider.getPaint().setStrikeThruText(config.isHideDivider());
    hideDivider.setText(config.isHideDivider() ? "显示分隔线" : "隐藏分隔线");
    showCustomIcon.getPaint().setStrikeThruText(config.isShowCustomIcon());
    fontControl.setProgress((int) ((config.getFontSize() - 10) * 10));

    updateGestureNavLabel();
  }

  private void updateGestureNavLabel() {
    TextView tv = rootView.findViewById(R.id.toggleGestureNav);
    boolean isGesture = GestureNavHelper.isGestureMode();
    tv.setText(isGesture
        ? getString(R.string.setting_gesture_nav) + " (ON)"
        : getString(R.string.setting_virtual_key_nav) + " (OFF)");
  }

  /** 全局刷新模式选择弹窗（模式集与系统引擎 4 模式互补） */
  private void showRefreshModeDialog() {
    if (!RefreshModeHelper.isAvailable()) {
      Toast.makeText(getActivity(), "此设备不支持 EPD 模式切换", Toast.LENGTH_SHORT).show();
      return;
    }
    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.setting_refresh_mode)
        .setItems(RefreshModeHelper.LABELS, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            if (RefreshModeHelper.applyWithEac(which)) {
              config.setRefreshMode(which);
              Toast.makeText(getActivity(),
                  "刷新模式已切换（EAC 兜底配置后台执行中…）", Toast.LENGTH_SHORT).show();
            }
            getActivity().onBackPressed();
          }
        })
        .show();
  }

  /** 手势编辑中的映射（跨对话框重建保持；保存/取消后清空并重新从文件读） */
  private java.util.Map<String, String> gestureEditMap;

  /** 手势配置设置（root 写 systemui gestures_config + 重启 SystemUI；不含侧滑音量/亮度） */
  private void showGestureSettingsDialog() {
    if (!GestureConfigHelper.isRootAvailable()) {
      Toast.makeText(getActivity(), "需要 root 权限", Toast.LENGTH_SHORT).show();
      return;
    }
    final java.util.Map<String, String> map;
    if (gestureEditMap != null) {
      // 复用编辑中的状态：否则每次选完动作都会重新读文件，把刚做的选择覆盖掉（“改不了”）
      map = gestureEditMap;
    } else {
      map = new java.util.LinkedHashMap<>();
      String cur = GestureConfigHelper.load();
      for (int i = 0; i < GestureConfigHelper.POSITIONS.length; i++) {
        String pos = GestureConfigHelper.POSITIONS[i];
        String action = "NONE";
        if (cur != null) {
          String key = "\"" + pos + "\":\"";
          int idx = cur.indexOf(key);
          if (idx >= 0) {
            int end = cur.indexOf('"', idx + key.length());
            if (end > idx) action = cur.substring(idx + key.length(), end);
          }
        }
        map.put(pos, action);
      }
      gestureEditMap = map;
    }
    final String[] labels = new String[GestureConfigHelper.POSITIONS.length];
    for (int i = 0; i < GestureConfigHelper.POSITIONS.length; i++) {
      String action = map.get(GestureConfigHelper.POSITIONS[i]);
      labels[i] = GestureConfigHelper.POSITION_LABELS[i] + "  [" + actionLabel(action) + "]";
    }
    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.setting_gesture_config)
        .setItems(labels, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            showGestureActionDialog(map, which);
          }
        })
        .setPositiveButton("保存并应用", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface d, int w) {
            if (GestureConfigHelper.save(map)) {
              gestureEditMap = null;
              Toast.makeText(getActivity(), "手势已应用（SystemUI 重启中）", Toast.LENGTH_SHORT).show();
            } else {
              Toast.makeText(getActivity(), "保存失败（root/写入错误）", Toast.LENGTH_SHORT).show();
            }
          }
        })
        .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface d, int w) {
            gestureEditMap = null;
          }
        })
        .setOnCancelListener(new DialogInterface.OnCancelListener() {
          @Override
          public void onCancel(DialogInterface d) {
            gestureEditMap = null;
          }
        })
        .show();
  }

  /** 单个手势位置的动作选择弹窗 */
  private void showGestureActionDialog(final java.util.Map<String, String> map, final int pos) {
    new AlertDialog.Builder(getActivity())
        .setTitle(GestureConfigHelper.POSITION_LABELS[pos])
        .setItems(GestureConfigHelper.ACTION_LABELS, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface d, int which) {
            map.put(GestureConfigHelper.POSITIONS[pos], GestureConfigHelper.ACTIONS[which]);
            showGestureSettingsDialog();
          }
        })
        .show();
  }

  private String actionLabel(String action) {
    for (int i = 0; i < GestureConfigHelper.ACTIONS.length; i++) {
      if (GestureConfigHelper.ACTIONS[i].equals(action)) return GestureConfigHelper.ACTION_LABELS[i];
    }
    return action;
  }

  private void initSpinners() {
    rowNumSpinner.setSelection(config.getRowNum() - 2, false);
    rowNumSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        int rowNum = position + 2;
        config.setRowNum(rowNum);
        listener.onRowNumChanged(rowNum);
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
      }
    });

    colNumSpinner.setSelection(config.getColNum() - 2, false);
    colNumSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        int colNum = position + 2;
        config.setColNum(colNum);
        listener.onColNumChanged(colNum);
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
      }
    });

    appNameLinesSpinner.setSelection(getAppLineSpinnerSelectPosition(), false);
    appNameLinesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        int lines = (position == 3) ? Integer.MAX_VALUE : position;
        config.setAppNameLines(lines);
        listener.onAppNameLinesChanged(lines);
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
      }
    });

    sortModeSpinner.setSelection(config.getSortMode(), false);
    sortModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (AppSortComparator.modeNeedsUsageStats(position)
            && !AppSortComparator.hasUsageStatsPermission(getActivity())) {
          Toast.makeText(getActivity(), R.string.sort_need_usage_permission, Toast.LENGTH_LONG).show();
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
          }
          sortModeSpinner.setSelection(config.getSortMode(), false);
          return;
        }
        config.setSortMode(position);
        listener.onSortModeChanged(position);
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
      }
    });
  }

  private void initFontControl() {
    fontControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
          float newSize = 10 + progress / 10f;
          config.setFontSize(newSize);
          listener.onFontSizeChanged(newSize);
        }
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {
      }

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
      }
    });
  }

  private int getAppLineSpinnerSelectPosition() {
    int lines = config.getAppNameLines();
    return (lines <= 2) ? lines : 3;
  }

  // =========================================================================
  // 点击处理
  // =========================================================================

  @Override
  public void onClick(View v) {
    int id = v.getId();
    if (id == R.id.toBack || id == R.id.rootView) {
      getActivity().onBackPressed();
    } else if (id == R.id.deleteApp) {
      handleDeleteApp();
    } else if (id == R.id.showStatusBar) {
      handleToggleStatusBar();
    } else if (id == R.id.helpAbout) {
      AboutDialog.getInstance(getActivity()).show();
    } else if (id == R.id.btnHideFontControl) {
      rootView.findViewById(R.id.menuList).setVisibility(View.VISIBLE);
      rootView.findViewById(R.id.font_control_p).setVisibility(View.GONE);
    } else if (id == R.id.changeFontSize) {
      rootView.findViewById(R.id.menuList).setVisibility(View.GONE);
      rootView.findViewById(R.id.font_control_p).setVisibility(View.VISIBLE);
    } else if (id == R.id.hideDivider) {
      handleToggleDivider();
    } else if (id == R.id.menu_ftp) {
      handleFtp();
    } else if (id == R.id.showWifiName) {
      handleShowWifiName();
    } else if (id == R.id.showCustomIcon) {
      handleToggleCustomIcon();
    } else if (id == R.id.openDeviceManager) {
      startActivity(new Intent().setComponent(
          new ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings")));
    } else if (id == R.id.toggleGestureNav) {
      listener.onToggleGestureNav();
      updateGestureNavLabel();
    } else if (id == R.id.gestureSettings) {
      showGestureSettingsDialog();
    } else if (id == R.id.refreshMode) {
      showRefreshModeDialog();
    }
  }

  private void handleDeleteApp() {
    listener.onEnterManageMode();
    getActivity().onBackPressed();
  }

  private void handleToggleStatusBar() {
    boolean newValue = !config.isShowStatusBar();
    config.setShowStatusBar(newValue);
    listener.onShowStatusBarChanged(newValue);
    getActivity().onBackPressed();
  }

  private void handleToggleDivider() {
    boolean newValue = !config.isHideDivider();
    config.setHideDivider(newValue);
    hideDivider.setText(newValue ? "显示分隔线" : "隐藏分隔线");
    listener.onHideDividerChanged(newValue);
    getActivity().onBackPressed();
  }

  private void handleFtp() {
    Utils.checkStoragePermission(getActivity(), new Runnable() {
      @Override
      public void run() {
        if (!FTPService.isRunning()) {
          if (FTPService.isConnectedToWifi(getActivity())) {
            startFtpServer();
          } else {
            Toast.makeText(getActivity(), "大哥诶，麻烦先把WIFI连上吧", Toast.LENGTH_SHORT).show();
          }
        } else {
          stopFtpServer();
        }
      }
    });
  }

  private void handleShowWifiName() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 10002);
    }
  }

  private void handleToggleCustomIcon() {
    Utils.checkStoragePermission(getActivity(), new Runnable() {
      @Override
      public void run() {
        boolean newValue = !config.isShowCustomIcon();
        config.setShowCustomIcon(newValue);
        listener.onShowCustomIconChanged(newValue);
        getActivity().onBackPressed();
      }
    });
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == 10002) {
      WifiControl.reloadWifiName();
      getActivity().onBackPressed();
    }
  }

  // =========================================================================
  // 生命周期
  // =========================================================================

  @Override
  public void onResume() {
    super.onResume();
    updateFtpStatus();

    IntentFilter wifiFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
    Utils.registerReceiverCompat(getActivity(), wifiReceiver, wifiFilter);

    IntentFilter ftpFilter = new IntentFilter();
    ftpFilter.addAction(FTPService.ACTION_STARTED);
    ftpFilter.addAction(FTPService.ACTION_STOPPED);
    ftpFilter.addAction(FTPService.ACTION_FAILEDTOSTART);
    Utils.registerReceiverCompat(getActivity(), ftpReceiver, ftpFilter);
  }

  @Override
  public void onPause() {
    super.onPause();
    getActivity().unregisterReceiver(wifiReceiver);
    getActivity().unregisterReceiver(ftpReceiver);
  }

  // =========================================================================
  // FTP 控制
  // =========================================================================

  private void startFtpServer() {
    getActivity().sendBroadcast(new Intent(FTPService.ACTION_START_FTPSERVER));
  }

  private void stopFtpServer() {
    getActivity().sendBroadcast(new Intent(FTPService.ACTION_STOP_FTPSERVER));
  }

  private void updateFtpStatus() {
    if (FTPService.isConnectedToWifi(getActivity())) {
      if (FTPService.isRunning()) {
        ftpStatus.setText(R.string.setting_cloud_manager_on);
        ftpAddr.setVisibility(View.VISIBLE);
        String address = getFTPAddressString();
        if (address != null) {
          ftpAddr.setText(address);
        } else {
          ftpAddr.setVisibility(View.GONE);
        }
      } else {
        ftpStatus.setText(R.string.setting_cloud_manager_off);
        ftpAddr.setVisibility(View.GONE);
      }
    } else {
      ftpStatus.setText(R.string.setting_cloud_manager_wifi_off);
      ftpAddr.setVisibility(View.GONE);
    }
  }

  private String getFTPAddressString() {
    if (FTPService.getLocalInetAddress(getActivity()) == null) {
      return null;
    }
    return "ftp://" + FTPService.getLocalInetAddress(getActivity()).getHostAddress()
        + ":" + FTPService.getPort();
  }

  // =========================================================================
  // 广播接收器
  // =========================================================================

  private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      ConnectivityManager conMan = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo netInfo = conMan.getActiveNetworkInfo();
      if (netInfo == null || netInfo.getType() != ConnectivityManager.TYPE_WIFI) {
        stopFtpServer();
      }
      updateFtpStatus();
    }
  };

  private final BroadcastReceiver ftpReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      updateFtpStatus();
    }
  };
}
