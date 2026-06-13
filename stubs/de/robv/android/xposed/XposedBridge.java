package de.robv.android.xposed;

import java.util.Set;

public final class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
}
