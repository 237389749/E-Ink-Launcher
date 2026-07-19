package cn.modificator.launcher.model;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.widget.TextView;

import cn.modificator.launcher.FileLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import cn.modificator.launcher.R;
import cn.modificator.launcher.widgets.AppItemBinder;
import cn.modificator.launcher.widgets.LauncherAdapter;

/**
 * 应用数据管理中心，负责加载应用列表和分页逻辑。
 */
public class AppDataCenter {

  private static final String TAG = "EInkLauncher";

  /** 虚拟包名：Wifi 控制入口 */
  public static final String WIFI_PACKAGE_NAME = "E-ink_Launcher.WiFi";
  /** 虚拟包名：一键锁屏入口 */
  public static final String LOCK_PACKAGE_NAME = "E-ink_Launcher.Lock";

  private final Context mContext;
  private final List<ResolveInfo> mApps = new ArrayList<>();
  private int pageIndex = 0;
  private int pageCount = 0;
  private int colNum = 5;
  private int rowNum = 5;
  private LauncherAdapter adapter;
  private AppItemBinder binder;
  private TextView pageStatus;
  private final Set<String> hideApps = new HashSet<>();
  private int sortMode = AppSortComparator.SORT_NAME_ASC;

  public AppDataCenter(Context context) {
    this.mContext = context;
  }

  // =========================================================================
  // Adapter / Binder 绑定
  // =========================================================================

  public void setAdapter(LauncherAdapter adapter) {
    this.adapter = adapter;
    this.binder = adapter.getBinder();
    if (binder != null) {
      binder.setHideAppPkg(hideApps);
    }
    setPageShow();
  }

  public void setPageStatus(TextView pageStatus) {
    this.pageStatus = pageStatus;
    pageStatus.setText((pageIndex + 1) + "/" + (pageCount + 1));
  }

  // =========================================================================
  // 隐藏应用管理
  // =========================================================================

  public void setHideApps(Set<String> hideApps) {
    this.hideApps.clear();
    this.hideApps.addAll(hideApps);
    loadApps();
  }

  public Set<String> getHideApps() {
    return hideApps;
  }

  // =========================================================================
  // 列数/行数
  // =========================================================================

  public void setColNum(int colNum) {
    this.colNum = colNum;
    updatePageCount();
    setPageShow();
  }

  public void setRowNum(int rowNum) {
    this.rowNum = rowNum;
    updatePageCount();
    setPageShow();
  }

  /** 批量设置行列数，只触发一次分页更新 */
  public void setGridSize(int colNum, int rowNum) {
    this.colNum = colNum;
    this.rowNum = rowNum;
    updatePageCount();
    setPageShow();
  }

  // =========================================================================
  // 排序
  // =========================================================================

  public void setSortMode(int sortMode) {
    this.sortMode = sortMode;
  }

  /** 返回当前加载的应用总数（含虚拟图标）。 */
  public int getAppCount() {
    return mApps.size();
  }

  public int getSortMode() {
    return sortMode;
  }

  // =========================================================================
  // 翻页
  // =========================================================================

  public void showNextPage() {
    if (pageIndex >= pageCount) return;
    pageIndex++;
    setPageShow();
  }

  public void showLastPage() {
    if (pageIndex <= 0) return;
    pageIndex--;
    setPageShow();
  }

  // =========================================================================
  // 刷新
  // =========================================================================

  public void refreshAppList() {
    refreshAppList(false);
  }

  public void refreshAppList(boolean showAll) {
    FileLog.log(TAG, "refreshAppList showAll=" + showAll + " currentCount=" + mApps.size());
    if (showAll) {
      loadAllApps();
    } else {
      loadApps();
    }
    FileLog.log(TAG, "refreshAppList done — newCount=" + mApps.size()
        + " pageIndex=" + pageIndex + " pageCount=" + pageCount);
    setPageShow();
  }

  // =========================================================================
  // 内部加载
  // =========================================================================

  private static int getQueryFlags() {
    if (android.os.Build.VERSION.SDK_INT >= 30) {
      return android.content.pm.PackageManager.MATCH_ALL;
    } else if (android.os.Build.VERSION.SDK_INT >= 24) {
      return android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
          | android.content.pm.PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
    }
    return 0; // API < 24 默认包含已停用组件
  }

  private void loadApps() {
    Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
    mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

    if (binder != null) {
      hideApps.clear();
      hideApps.addAll(binder.getHideAppPkg());
    }

    mApps.clear();

    // —— 诊断：全量列出所有已安装包 ——
    dumpAllPackages(mainIntent);

    int flags = getQueryFlags();
    java.util.List<ResolveInfo> results = mContext.getPackageManager().queryIntentActivities(mainIntent, flags);
    FileLog.log(TAG, "loadApps: queryIntentActivities(flags=0x" + Integer.toHexString(flags) + ") returned " + results.size()
        + " activities, hideApps=" + hideApps.size());
    for (int i = 0; i < results.size(); i++) {
      ResolveInfo ri = results.get(i);
      FileLog.log(TAG, "  [" + i + "] pkg=" + ri.activityInfo.packageName
          + " name=" + ri.activityInfo.name
          + " label=" + ri.loadLabel(mContext.getPackageManager()));
    }

    for (ResolveInfo resolveInfo : results) {
      if ("cn.modificator.launcher.Launcher".equals(resolveInfo.activityInfo.name)) {
        FileLog.log(TAG, "loadApps: skip self: " + resolveInfo.activityInfo.name);
        continue;
      }
      if (!hideApps.contains(resolveInfo.activityInfo.packageName)) {
        mApps.add(resolveInfo);
      } else {
        FileLog.log(TAG, "loadApps: skip hidden: " + resolveInfo.activityInfo.packageName);
      }
    }

    FileLog.log(TAG, "loadApps: after filter — " + mApps.size() + " apps (hidden=" + hideApps.size() + ")");
    for (int i = 0; i < mApps.size(); i++) {
      FileLog.log(TAG, "  app[" + i + "] " + mApps.get(i).activityInfo.packageName);
    }

    if (!hideApps.contains(LOCK_PACKAGE_NAME)) {
      mApps.add(createPowerIcon());
    }
    if (!hideApps.contains(WIFI_PACKAGE_NAME)) {
      mApps.add(createWifiIcon());
    }
    FileLog.log(TAG, "loadApps: after virtual icons — " + mApps.size() + " total");
    sortApps();
    updatePageCount();
  }

  private void loadAllApps() {
    Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
    mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

    mApps.clear();
    mApps.addAll(mContext.getPackageManager().queryIntentActivities(mainIntent, getQueryFlags()));
    mApps.add(createPowerIcon());
    mApps.add(createWifiIcon());
    if (binder != null) {
      binder.setHideAppPkg(hideApps);
    }
    sortApps();
    updatePageCount();
  }

  private void setPageShow() {
    int itemCount = colNum * rowNum;
    int pageStart = pageIndex * itemCount;
    int pageEnd = Math.min(pageStart + itemCount, mApps.size());
    adapter.setAppList(mApps.subList(pageStart, pageEnd));
    pageStatus.setText((pageIndex + 1) + "/" + (pageCount + 1));
  }

  private void updatePageCount() {
    int itemCount = colNum * rowNum;
    pageCount = mApps.size() / itemCount - (mApps.size() % itemCount == 0 ? 1 : 0);
    pageCount = Math.max(pageCount, 0);
    pageIndex = Math.min(pageIndex, pageCount);
  }

  private void sortApps() {
    Collections.sort(mApps, new AppSortComparator(mContext, mContext.getPackageManager(), sortMode));
  }

  // =========================================================================
  // 诊断：全量包扫描
  // =========================================================================

  /**
   * 列出设备上所有已安装包，与 Launcher 查询结果对比。
   * 用于诊断"应用装上了但桌面不显示"的问题。
   */
  private void dumpAllPackages(Intent launcherIntent) {
    android.content.pm.PackageManager pm = mContext.getPackageManager();

    // 1. 列出所有已安装的包
    java.util.List<android.content.pm.PackageInfo> allPkgs =
        pm.getInstalledPackages(0);
    FileLog.log(TAG, "=== DIAGNOSTIC: getInstalledPackages returned "
        + allPkgs.size() + " total packages ===");
    java.util.Set<String> allPkgNames = new java.util.HashSet<>();
    for (android.content.pm.PackageInfo pi : allPkgs) {
      allPkgNames.add(pi.packageName);
      // 检查是否有 LAUNCHER activity
      boolean hasLauncher = false;
      if (pi.activities != null) {
        for (android.content.pm.ActivityInfo ai : pi.activities) {
          for (android.content.IntentFilter filter : ai.filterIntents()) {
            if (filter.hasAction(Intent.ACTION_MAIN)
                && filter.hasCategory(Intent.CATEGORY_LAUNCHER)) {
              hasLauncher = true;
              break;
            }
          }
          if (hasLauncher) break;
        }
      }
      FileLog.log(TAG, "  [" + pi.packageName + "] hasLauncher=" + hasLauncher
          + " enabled=" + pi.applicationInfo.enabled
          + " flags=" + Integer.toHexString(pi.applicationInfo.flags));
    }

    // 2. 用不同 flag 查 queryIntentActivities
    try {
      java.util.List<ResolveInfo> matchAll = pm.queryIntentActivities(launcherIntent,
          android.content.pm.PackageManager.MATCH_ALL);
      FileLog.log(TAG, "=== queryIntentActivities(MATCH_ALL) returned "
          + matchAll.size() + " activities ===");
      for (ResolveInfo ri : matchAll) {
        FileLog.log(TAG, "  pkg=" + ri.activityInfo.packageName
            + " name=" + ri.activityInfo.name);
      }
    } catch (Exception e) {
      FileLog.log(TAG, "queryIntentActivities(MATCH_ALL) failed: " + e.getMessage(), e);
    }

    // 3. 列出未出现在 launcher 查询中但可能有 launcher activity 的包
    java.util.List<ResolveInfo> launcherResults = pm.queryIntentActivities(launcherIntent, getQueryFlags());
    java.util.Set<String> inLauncher = new java.util.HashSet<>();
    for (ResolveInfo ri : launcherResults) {
      inLauncher.add(ri.activityInfo.packageName);
    }
    java.util.List<String> missing = new java.util.ArrayList<>();
    for (String pkg : allPkgNames) {
      if (!inLauncher.contains(pkg)
          && !pkg.startsWith("com.android.")
          && !pkg.startsWith("com.google.")
          && !pkg.startsWith("android.")) {
        missing.add(pkg);
      }
    }
    if (!missing.isEmpty()) {
      FileLog.log(TAG, "=== MISSING from launcher ("
          + missing.size() + " third-party packages) ===");
      for (String pkg : missing) {
        FileLog.log(TAG, "  MISSING: " + pkg);
      }
    }
    FileLog.log(TAG, "=== DIAGNOSTIC END ===");
  }

  // =========================================================================
  // 虚拟图标创建
  // =========================================================================

  private ResolveInfo createWifiIcon() {
    ResolveInfo resolveInfo = new ResolveInfo();
    resolveInfo.icon = R.drawable.wifi_on;
    resolveInfo.activityInfo = new ActivityInfo();
    resolveInfo.activityInfo.packageName = WIFI_PACKAGE_NAME;
    return resolveInfo;
  }

  private ResolveInfo createPowerIcon() {
    ResolveInfo resolveInfo = new ResolveInfo();
    resolveInfo.icon = R.drawable.ic_onekeylock;
    resolveInfo.activityInfo = new ActivityInfo();
    resolveInfo.activityInfo.packageName = LOCK_PACKAGE_NAME;
    return resolveInfo;
  }
}
