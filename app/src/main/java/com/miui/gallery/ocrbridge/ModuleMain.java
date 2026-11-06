package com.miui.gallery.ocrbridge;

import android.os.Build;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 模块入口（META-INF/xposed/java_init.list），按包名严格分流：
 *   com.miui.gallery            （服务端）ProviderCallHook + BroadcastTrigger
 *   com.fooview.android.fooview（客户端）FvOcrHook
 * 其它包一律跳过，不给无关进程装任何 Hook。
 *
 * 不修改系统属性、不改 Settings 数据库、不联网、不打包第三方 OCR。
 */
public class ModuleMain extends XposedModule {

    private static final Set<String> INSTALLED =
            Collections.synchronizedSet(new HashSet<String>());

    private volatile String processName = "?";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Diag.bind(this);
        processName = param.getProcessName();
        Diag.i(this, "════ 模块载入 ════ 进程=" + processName
                + " 框架=" + getFrameworkName() + " API=" + getApiVersion()
                + " 设备=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " SDK=" + Build.VERSION.SDK_INT);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        final String pkg = param.getPackageName();
        // 只处理两个目标包，其它包（同进程内被 LSPosed 回调的 gms/webview 等）直接跳过
        if (!Config.TARGET_PKG.equals(pkg) && !Config.FV_PKG.equals(pkg)) {
            return;
        }
        final String proc = processName;
        final ClassLoader loader = param.getClassLoader();
        if (loader == null) {
            return;
        }
        if (!INSTALLED.add(proc + "|" + pkg + "|" + System.identityHashCode(loader))) {
            return;
        }

        Diag.i(this, "onPackageReady 进程=" + proc + " 包=" + pkg);
        try {
            if (Config.FV_PKG.equals(pkg)) {
                FvOcrHook.install(this, loader, pkg);
            } else {
                ProviderCallHook.install(this, loader, pkg);
                // 广播接收器只在主进程注册，避免一条广播被多个子进程重复触发
                if (!proc.contains(":")) {
                    BroadcastTrigger.install(this, pkg);
                }
            }
        } catch (Throwable t) {
            Diag.e(this, "安装 Hook 失败: " + pkg + "/" + proc, t);
        }
    }
}
