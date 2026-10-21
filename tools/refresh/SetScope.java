package io.onyx;

public class SetScope {
    public static void main(String[] args) throws Exception {
        int value = Integer.parseInt(args[0]);
        Class<?> v = Class.forName("android.onyx.ViewUpdateHelper");
        Object r = v.getMethod("applyAppScopeUpdate", String.class, boolean.class, int.class, int.class, int.class)
                .invoke(null, null, true, 0, value, Integer.MAX_VALUE);
        System.out.println("setScope value=" + value + " ret=" + r);
    }
}
