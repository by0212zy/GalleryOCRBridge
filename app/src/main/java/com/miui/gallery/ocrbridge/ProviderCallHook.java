package com.miui.gallery.ocrbridge;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 把相册的 ContentProvider 变成两个入口：
 *   1) call()                       —— content call 同步返回识别文本
 *   2) openFile()/openAssetFile()   —— content write 把 shell 端截屏写进内存管道
 *
 * 为什么用 content call：Provider 是静态组件，content call 会自动拉起相册进程；
 * call() 的返回值同步打印到 shell，正好回传文字；com.miui.gallery.widget 是
 * exported 且无权限保护的 authority，任何 App 都能调。
 *
 * 正式版只 Hook 基类 android.content.ContentProvider：反编译确认相册的
 * GalleryWidgetOpenProvider 并未覆写 call()，走的就是基类；真机日志里全部命中
 * 也都发生在基类。旧版额外 Hook 3 个 Provider 子类属于从未命中的冗余，已移除。
 */
final class ProviderCallHook {

    private ProviderCallHook() {
    }

    static void install(XposedModule module, ClassLoader loader, String pkg) {
        int calls = 0;
        int pipes = 0;

        Class<?> base = Host.find(loader, "android.content.ContentProvider");
        if (base == null) {
            Diag.w(module, "找不到 android.content.ContentProvider", null);
        } else {
            for (Method m : base.getDeclaredMethods()) {
                if (isCall(m) && hookCall(module, m)) {
                    calls++;
                }
            }
            pipes = hookInput(module, base);
        }
        Diag.i(module, pkg + ": call 入口 " + calls + " 处，截图管道 " + pipes + " 处");
    }

    private static boolean isCall(Method m) {
        return "call".equals(m.getName())
                && !Modifier.isAbstract(m.getModifiers())
                && m.getParameterCount() >= 3
                && Bundle.class.equals(m.getReturnType());
    }

    private static boolean hookCall(XposedModule module, Method m) {
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

                        Diag.s("★ content call 命中 method=" + method);
                        Context ctx = Host.application();
                        if (ctx == null) {
                            Bundle err = new Bundle();
                            err.putBoolean(Config.EXTRA_SUCCESS, false);
                            err.putString(Config.EXTRA_ERROR, "相册 Application 未就绪，请稍后重试");
                            return err;
                        }
                        return OcrService.handle(ctx.getClassLoader(), ctx, method, extras);
                    });
            try {
                module.deoptimize(m);
            } catch (Throwable ignored) {
                // 个别方法不支持反优化
            }
            return true;
        } catch (Throwable t) {
            Diag.w(module, "hook ContentProvider.call 失败", t);
            return false;
        }
    }

    /** Hook openFile/openAssetFile：匹配 ocr_input 时返回内存管道的写端。 */
    private static int hookInput(XposedModule module, Class<?> c) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            if (!"openFile".equals(name) && !"openAssetFile".equals(name)) {
                continue;
            }
            Class<?> ret = m.getReturnType();
            final boolean asset = ret == AssetFileDescriptor.class;
            if (!asset && ret != ParcelFileDescriptor.class) {
                continue;
            }
            try {
                module.hook(m)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Uri uri = null;
                            try {
                                for (Object o : chain.getArgs()) {
                                    if (o instanceof Uri) {
                                        uri = (Uri) o;
                                    }
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                            if (uri == null || !uri.toString().contains(Config.INPUT_PATH)) {
                                return chain.proceed();
                            }
                            try {
                                ParcelFileDescriptor pfd = ScreenInput.accept();
                                return asset ? new AssetFileDescriptor(pfd, 0, -1) : pfd;
                            } catch (Throwable t) {
                                Diag.s("截图管道建立失败: " + t);
                                return chain.proceed();
                            }
                        });
                try {
                    module.deoptimize(m);
                } catch (Throwable ignored) {
                    // ignore
                }
                n++;
            } catch (Throwable t) {
                Diag.w(module, "hook ContentProvider." + name + " 失败", t);
            }
        }
        return n;
    }
}
