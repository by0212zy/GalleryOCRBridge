package com.miui.gallery.ocrbridge;

import android.os.Build;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 模块入口（META-INF/xposed/java_init.list）。
 *
 * 作用域只有 com.miui.gallery：模块只在相册进程里生效，不碰系统属性、
 * 不改 Settings 数据库、不 Hook 其它 App。两条 shell 入口都注册在相册进程内：
 *   - ProviderCallHook：content call 同步返回识别文本（主入口）
 *   - BroadcastTrigger：am broadcast 异步触发（备用 / 其它 App 集成）
 */
public class ModuleMain extends XposedModule {

    private static final Set<String> INSTALLED =
            Collections.synchronizedSet(new HashSet<String>());

    private volatile String processName = "?";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        Diag.i(this, "════ 模块载入 ════ 进程=" + processName
                + " 框架=" + getFrameworkName() + " API=" + getApiVersion()
                + " 设备=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " SDK=" + Build.VERSION.SDK_INT);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        final String pkg = param.getPackageName();
        final String proc = processName;
        final ClassLoader loader = param.getClassLoader();
        if (loader == null) {
            return;
        }

        String key = proc + "|" + pkg + "|" + System.identityHashCode(loader);
        if (!INSTALLED.add(key)) {
            return;
        }

        Diag.i(this, "onPackageReady 进程=" + proc + " 包=" + pkg);
        try {
            // 主入口：所有进程都装，Provider 可能落在任意进程
            ProviderCallHook.install(this, loader, pkg);
            // 备用入口：只在主进程注册，避免一条广播被多个子进程重复触发
            if (!proc.contains(":")) {
                BroadcastTrigger.install(this, pkg);
            }
        } catch (Throwable t) {
            Diag.e(this, "安装 Hook 失败: " + pkg + "/" + proc, t);
        }
    }
}
