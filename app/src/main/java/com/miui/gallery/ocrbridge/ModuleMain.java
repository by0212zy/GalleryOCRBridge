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
 * v1.2.0 起一个模块管两个作用域，按包名分流：
 *   com.miui.gallery            （服务端）ProviderCallHook + BroadcastTrigger
 *                                把相册自带本地 OCR 暴露成 content call / 广播。
 *   com.fooview.android.fooview（客户端）FvOcrHook
 *                                把 FV 悬浮球「自动文字识别」的 Bitmap 转发给相册，
 *                                取回文本作为 recognize() 的返回值。
 *
 * 不修改系统属性、不改 Settings 数据库、不 Hook 其它 App。
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
            if (Config.FV_PKG.equals(pkg)) {
                // FV 悬浮球（含 :circle 子进程）：只装客户端 Hook，不碰它的 Provider
                FvOcrHook.install(this, loader, pkg);
            } else if (Config.TARGET_PKG.equals(pkg)) {
                // 小米相册（含 :remote 等子进程）：装服务端 Hook
                ProviderCallHook.install(this, loader, pkg);
                // 广播接收器只在主进程注册，避免一条广播被多个子进程重复触发
                if (!proc.contains(":")) {
                    BroadcastTrigger.install(this, pkg);
                }
            } else {
                Diag.i(this, "非目标包，跳过: " + pkg);
            }
        } catch (Throwable t) {
            Diag.e(this, "安装 Hook 失败: " + pkg + "/" + proc, t);
        }
    }
}
