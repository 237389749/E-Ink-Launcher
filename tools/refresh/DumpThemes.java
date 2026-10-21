package io.onyx;

import java.util.List;

/** Dump all theme JSON of a package (reads eac_theme_* keys) via EInkHelper reflection. */
public class DumpThemes {
  public static void main(String[] a) {
    String pkg = a.length > 0 ? a[0] : "com.legado.app.release";
    try {
      Class<?> c = Class.forName("android.onyx.optimization.EInkHelper");
      Object res = c.getMethod("loadThemes", String.class).invoke(null, pkg);
      @SuppressWarnings("unchecked")
      List<String> ts = (List<String>) res;
      System.out.println("themeCount=" + (ts == null ? 0 : ts.size()) + " pkg=" + pkg);
      if (ts != null) {
        for (int i = 0; i < ts.size(); i++) {
          System.out.println("--- theme[" + i + "] ---");
          System.out.println(ts.get(i));
        }
      }
    } catch (Throwable t) {
      t.printStackTrace();
    }
  }
}
