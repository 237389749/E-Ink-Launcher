package io.onyx;

public class CheckMode {
    public static void main(String[] args) throws Exception {
        try {
            Class<?> c = Class.forName("android.onyx.optimization.EInkHelper");
            Object mode = c.getMethod("getAppScopeRefreshMode").invoke(null);
            System.out.println("appScopeRefreshMode=" + mode);
        } catch (Throwable t) {
            System.out.println("mode_err=" + t);
        }
        try {
            Class<?> v = Class.forName("android.onyx.ViewUpdateHelper");
            Object fast = v.getMethod("getFastModeIndex").invoke(null);
            System.out.println("fastModeIndex=" + fast);
        } catch (Throwable t) {
            System.out.println("fast_err=" + t);
        }
    }
}
