package cn.modificator.launcher.model;

import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 应用图标、标签的内存缓存，以及自定义图标替换映射管理。
 * <ul>
 *   <li>{@link #getIcon} / {@link #getLabel} —— 带缓存（内存 + 磁盘）的图标 / 标签加载</li>
 *   <li>{@link #getCachedIcon} / {@link #getCachedLabel} —— 仅查内存缓存（主线程快速路径）</li>
 *   <li>{@link #refreshCustomIcons} —— 扫描外部存储中的自定义图标文件</li>
 *   <li>{@link #markDirty()} —— 标记需要重新扫描文件系统</li>
 *   <li>{@link #clearAppCache()} —— 应用安装/卸载后清除缓存</li>
 * </ul>
 */
public class IconCache {

  private static final String ICON_DIR = "E-Ink Launcher" + File.separator + "icon";
  private static final String DISK_CACHE_DIR = "icons";
  private static final int DEFAULT_ICON_SIZE = 128;

  private final Map<String, Drawable> drawableCache = new HashMap<>();
  private final Map<String, CharSequence> labelCache = new HashMap<>();
  private final Map<String, File> customIconMap = new HashMap<>();
  private File diskCacheDir;
  private boolean dirty = true;

  /** 设置磁盘缓存目录（传 context.getCacheDir()），可避免每次冷启动重新解析图标 */
  public void setDiskCacheDir(File cacheDir) {
    diskCacheDir = new File(cacheDir, DISK_CACHE_DIR);
  }

  // =========================================================================
  // 自定义图标
  // =========================================================================

  /** 标记自定义图标映射为脏，下次 {@link #refreshCustomIcons} 时重新扫描 */
  public void markDirty() {
    dirty = true;
  }

  /**
   * 如有必要，重新扫描外部存储中的自定义图标目录。
   *
   * @param hasExternalStorage 外部存储是否可用
   * @param showCustomIcon     用户是否启用"显示自定义图标"（true 表示禁用替换）
   * @return true 表示执行了实际扫描
   */
  public boolean refreshCustomIcons(boolean hasExternalStorage, boolean showCustomIcon) {
    if (!dirty) return false;
    customIconMap.clear();

    if (hasExternalStorage && showCustomIcon) {
      File root = getIconDirectory();
      if (!root.exists()) {
        try {
          root.mkdirs();
        } catch (Exception ignored) {
        }
      }
      File[] files = root.listFiles();
      if (files != null) {
        for (File file : files) {
          String name = file.getName();
          int dot = name.lastIndexOf('.');
          customIconMap.put(dot > 0 ? name.substring(0, dot) : name, file);
        }
      }
    }
    dirty = false;
    return true;
  }

  private static File getIconDirectory() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
      return new File(
          Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ICON_DIR);
    }
    return new File(Environment.getExternalStorageDirectory(), ICON_DIR);
  }

  /** 获取指定包名的自定义图标文件，不存在时返回 null */
  public File getCustomIcon(String packageName) {
    return customIconMap.get(packageName);
  }

  /** 获取完整的自定义图标映射（包名 → 文件），供 WifiControl 使用 */
  public Map<String, File> getCustomIconMap() {
    return Collections.unmodifiableMap(customIconMap);
  }

  // =========================================================================
  // 应用图标 & 标签缓存
  // =========================================================================

  /** 仅查内存缓存（主线程快速路径，未命中返回 null 时调用方应异步加载） */
  public synchronized Drawable getCachedIcon(String packageName) {
    return drawableCache.get(packageName);
  }

  /** 仅查内存缓存（主线程快速路径，未命中返回 null 时调用方应异步加载） */
  public synchronized CharSequence getCachedLabel(String packageName) {
    return labelCache.get(packageName);
  }

  /** 带缓存（内存 → 磁盘 → PackageManager）的图标加载，可在后台线程调用 */
  public synchronized Drawable getIcon(String packageName, ResolveInfo info, PackageManager pm) {
    Drawable cached = drawableCache.get(packageName);
    if (cached == null) {
      cached = loadIconFromDisk(packageName);
      if (cached == null) {
        cached = info.loadIcon(pm);
        if (cached != null) {
          drawableCache.put(packageName, cached);
          saveIconToDisk(packageName, cached);
        }
      } else {
        drawableCache.put(packageName, cached);
      }
    }
    return cached;
  }

  /** 带缓存的标签加载 */
  public synchronized CharSequence getLabel(String packageName, ResolveInfo info, PackageManager pm) {
    CharSequence cached = labelCache.get(packageName);
    if (cached == null) {
      cached = info.loadLabel(pm);
      labelCache.put(packageName, cached);
    }
    return cached;
  }

  // =========================================================================
  // 磁盘缓存
  // =========================================================================

  private File iconFile(String packageName) {
    return diskCacheDir == null ? null : new File(diskCacheDir, packageName + ".png");
  }

  private Drawable loadIconFromDisk(String packageName) {
    File file = iconFile(packageName);
    if (file == null || !file.exists()) return null;
    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
    if (bitmap == null) return null;
    return new BitmapDrawable(null, bitmap);
  }

  private void saveIconToDisk(String packageName, Drawable icon) {
    File file = iconFile(packageName);
    if (file == null) return;
    try {
      if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) return;
      Bitmap bitmap = drawableToBitmap(icon);
      try (FileOutputStream fos = new FileOutputStream(file)) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
      }
      bitmap.recycle();
    } catch (IOException | RuntimeException ignored) {
      // 磁盘缓存失败不影响功能
    }
  }

  private static Bitmap drawableToBitmap(Drawable drawable) {
    int w = drawable.getIntrinsicWidth();
    int h = drawable.getIntrinsicHeight();
    if (w <= 0 || h <= 0) {
      w = DEFAULT_ICON_SIZE;
      h = DEFAULT_ICON_SIZE;
    }
    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    drawable.setBounds(0, 0, w, h);
    drawable.draw(canvas);
    return bitmap;
  }

  /** 清除图标和标签缓存（应用安装/卸载时调用） */
  public void clearAppCache() {
    drawableCache.clear();
    labelCache.clear();
  }
}
