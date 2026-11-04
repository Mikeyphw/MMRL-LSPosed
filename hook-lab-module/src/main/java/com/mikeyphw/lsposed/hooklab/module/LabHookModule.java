package com.mikeyphw.lsposed.hooklab.module;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import java.lang.reflect.Method;

/** Scoped to the disposable S08 target only; adapted from MMRL Phase05 (GPL-3.0). */
public final class LabHookModule extends XposedModule {
    private static final String TARGET = "com.mikeyphw.lsposed.hooklab.target";
    @Override public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        try {
            Class<?> klass = param.getClassLoader().loadClass(TARGET + ".ProbeValue");
            Method method = klass.getDeclaredMethod("readValue");
            hook(method).intercept(chain -> "HOOKED_LSPOSED_S08");
        } catch (ReflectiveOperationException error) {
            log(android.util.Log.ERROR, "LSPOSED_S08_HOOKLAB",
                "HOOK_REGISTRATION_FAILED: " + error.getClass().getSimpleName());
        }
    }
}
