package com.miui.gallery.ocrbridge;

import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 把相册的 ContentProvider.call 变成同步的 OCR 入口。
 *
 * 为什么用 content call：
 *   - Provider 是静态注册组件，content call 会自动把相册进程拉起来，不需要常驻服务；
 *   - call() 的返回值会同步打印到 shell，正好用来回传识别出的文字；
 *   - com.miui.gallery.widget 这个 authority 是 exported 且无权限保护，任何 App 都能调。
 *
 * Hook 基类 android.content.ContentProvider.call（两个重载）已覆盖主 URI
 * （GalleryWidgetOpenProvider 未覆写 call，必走基类）；额外 hook 覆写了 call 的
 * 具体 Provider，让备用 URI 也能命中。method 名通过扫描所有 String 参数定位，
 * 因此不受 3 参数 / 4 参数签名差异影响。
 */
final class ProviderCallHook {

    private ProviderCallHook() {
    }

    static void install(XposedModule module, ClassLoader loader, String pkg) {
        int n = 0;

        Class<?> base = Host.find(loader, "android.content.ContentProvider");
        if (base != null) {
            for (Method m : base.getDeclaredMethods()) {
                if (isCall(m) && hook(module, m)) {
                    n++;
                }
            }
        } else {
            Diag.w(module, "找不到 android.content.ContentProvider", null);
        }

        for (String cn : Config.PROVIDER_CLASSES) {
            Class<?> c = Host.find(loader, cn);
            if (c == null) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (isCall(m) && hook(module, m)) {
                    n++;
                }
            }
        }
        Diag.i(module, pkg + ": ContentProvider.call 入口已装 " + n + " 处");
    }

    private static boolean isCall(Method m) {
        return "call".equals(m.getName())
                && !Modifier.isAbstract(m.getModifiers())
                && m.getParameterCount() >= 3
                && Bundle.class.equals(m.getReturnType());
    }

    private static boolean hook(XposedModule module, Method m) {
        try {
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        String method = null;
                        Bundle extras = null;
                        try {
                            List<Object> args = chain.getArgs();
                            for (Object o : args) {
                                if (o instanceof String && Config.isOurMethod((String) o)) {
                                    method = (String) o;
                                }
                                if (o instanceof Bundle) {
                                    extras = (Bundle) o;
                                }
                            }
                        } catch (Throwable ignored) {
                            // 取参失败就放行
                        }
                        if (method == null) {
                            return chain.proceed();
                        }

                        Context ctx = Host.application();
                        ClassLoader loader = ctx != null ? ctx.getClassLoader() : null;
                        Diag.i(module, "★ content call 命中 method=" + method);
                        if (ctx == null || loader == null) {
                            Bundle err = new Bundle();
                            err.putBoolean(Config.EXTRA_SUCCESS, false);
                            err.putString(Config.EXTRA_ERROR, "相册 Application 未就绪，请稍后重试");
                            return err;
                        }
                        return OcrService.handle(loader, ctx, method, extras);
                    });
            // 只反优化实际 Hook 的方法，防止 ART 内联绕过
            try {
                module.deoptimize(m);
            } catch (Throwable ignored) {
                // 个别方法不支持反优化
            }
            return true;
        } catch (Throwable t) {
            Diag.w(module, "hook " + m.getDeclaringClass().getName() + ".call 失败", t);
            return false;
        }
    }
}
