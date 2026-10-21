package io.onyx;
public class ClearScope {
  public static void main(String[] a) throws Exception {
    Class<?> v = Class.forName("android.onyx.ViewUpdateHelper");
    Object r = v.getMethod("clearAppScopeUpdate", boolean.class).invoke(null, true);
    System.out.println("clearScope ret=" + r);
  }
}
