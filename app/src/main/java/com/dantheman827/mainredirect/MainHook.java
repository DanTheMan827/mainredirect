package com.dantheman827.mainredirect;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.BaseDexClassLoader;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Legacy Xposed API entry point for LSPosed with legacy API support. */
public final class MainHook implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static String moduleApk;
    private static boolean installed;

    @Override public void initZygote(StartupParam param) {
        moduleApk = param.modulePath;
    }

    @Override public synchronized void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        // LSPosed's selected scope controls where this module runs.
        if (installed) return;
        if (moduleApk == null || p.appInfo == null || p.appInfo.dataDir == null) {
            XposedBridge.log("MainRedirect: missing module APK or target data directory");
            return;
        }
        final ReplacementStore store = new ReplacementStore(moduleApk, p.appInfo.dataDir);
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            // Resolve before Runtime can reject a missing original library. This also
            // handles normal PathClassLoader / DelegateLastClassLoader lookups.
            hooks.add(XposedHelpers.findAndHookMethod(BaseDexClassLoader.class,
                    "findLibrary", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!"main".equals(param.args[0])) return;
                    try {
                        param.setResult(store.path());
                    } catch (Exception e) {
                        fail(param, e);
                    }
                }
            }));

            // Hook the Java absolute-path entry, not nativeLoad. ART recognizes
            // nativeLoad's method identity when choosing the JNI_OnLoad loader;
            // invoking a hooked backup can break FindClass for unrelated libs.
            int pathHooks = 0;
            for (Method method : Runtime.class.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (!"load0".equals(method.getName()) || args.length != 2 ||
                        args[1] != String.class ||
                        (args[0] != Class.class && args[0] != ClassLoader.class) ||
                        method.getReturnType() != void.class ||
                        Modifier.isNative(method.getModifiers())) continue;
                hooks.add(XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        String requested = (String) param.args[1];
                        if (requested == null || !new File(requested).isAbsolute() ||
                                !"libmain.so".equals(new File(requested).getName())) return;
                        try {
                            String replacement = store.path();
                            if (!replacement.equals(requested)) {
                                XposedBridge.log("MainRedirect: " + requested + " -> " + replacement);
                                param.args[1] = replacement;
                            }
                        } catch (Exception e) {
                            fail(param, e);
                        }
                    }
                }));
                pathHooks++;
            }
            if (pathHooks == 0) throw new IllegalStateException("Unsupported Runtime.load0 API");
            installed = true;
            XposedBridge.log("MainRedirect v1.1: hooks installed in " + p.processName +
                    " (findLibrary + Java load0)");
        } catch (Throwable e) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            XposedBridge.log("MainRedirect: installation failed; no redirection active");
            XposedBridge.log(e);
        }
    }

    private static void fail(XC_MethodHook.MethodHookParam param, Exception cause) {
        XposedBridge.log(cause);
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(
                "MainRedirect could not prepare bundled libmain.so: " + cause.getMessage());
        error.initCause(cause);
        // Xposed normally catches exceptions from callbacks; setThrowable is needed
        // to actually stop the original load rather than silently falling back.
        param.setThrowable(error);
    }
}
