package com.miui.gallery.ocrbridge;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * FV 悬浮球侧（客户端）：把它的「自动文字识别」无感转发给小米相册的本地 OCR。
 *
 * 反编译 FV 悬浮球 1.6.4 确认：
 *   z1/e                                     抽象引擎基类
 *     .recognize(Bitmap) -> String           统一入口，返回合并后的纯文本
 *   com.baidu.paddle.lite.demo.ocr.BaiduOCREngine   百度 PaddleLite 本地实现（要下载语言包）
 *   z1/c                                    语言包未下载时的空壳（直接返回 null）
 *   com.fooview...ocrservice.CircleService$c$a      在 :circle 进程调用 recognize，
 *                                                  用 ConditionVariable 同步等结果
 *
 * 小米相册的 libmiocr.so / OCREngine 是相册私有，FV 进程无法直接加载；但相册进程里
 * 本模块已经把 OCR 暴露成 content://com.miui.gallery.widget 的 call()。于是这里把
 * FV 已经截好的 Bitmap 跨进程送过去，取回 total_text 作为 recognize() 的返回值。
 *
 * 关键：全程用 FV 自己截好的 Bitmap，不需要 screencap 权限、不需要 root、不落盘，
 * 也不依赖 FV 下载百度语言包（连 z1/c 空壳一起接管）。
 *
 * 三级保险，任何一步失败都不让 FV 的识别功能坏掉：
 *   通道 A：Bundle 直接带 Bitmap（一次 Binder，ashmem）
 *   通道 B：ocr_input 内存管道 + extract_screen_text（v1.1.0 通道）
 *   兜底  ：chain.proceed() 回退到 FV 自带 PaddleOCR
 */
final class FvOcrHook {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "GalleryOCR-FV");
        t.setDaemon(true);
        return t;
    });

    private FvOcrHook() {
    }

    static void install(XposedModule module, ClassLoader loader, String pkg) {
        int n = 0;
        // 目标是所有「参数 (Bitmap)、返回 String、非抽象」的 recognize 方法。
        // 只按签名匹配，不写死方法所在的类，兼容 FV 不同版本的混淆差异。
        for (String cn : Config.FV_ENGINE_CLASSES) {
            Class<?> c = Host.find(loader, cn);
            if (c == null) {
                Diag.s("FV: 跳过未找到的引擎类 " + cn);
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!"recognize".equals(m.getName())) {
                    continue;
                }
                if (Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || ps[0] != Bitmap.class
                        || !String.class.equals(m.getReturnType())) {
                    continue;
                }
                if (hookOne(module, m)) {
                    n++;
                }
            }
        }
        Diag.i(module, pkg + ": FV 本地识别已接管 " + n + " 处（0 处则检查 FV 版本）");
    }

    private static boolean hookOne(XposedModule module, Method m) {
        try {
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Bitmap bm = null;
                        try {
                            for (Object o : chain.getArgs()) {
                                if (o instanceof Bitmap) {
                                    bm = (Bitmap) o;
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {
                            // 取参失败就回退
                        }
                        if (bm == null || bm.isRecycled()) {
                            return chain.proceed();
                        }
                        String text = bridge(bm);
                        if (text != null && !text.isEmpty()) {
                            Diag.s("FV 接管成功，小米相册 OCR 文本长度=" + text.length());
                            return text;
                        }
                        // 相册不可用 / 未勾选作用域 / 超时，一律回退 FV 自带识别
                        return chain.proceed();
                    });
            try {
                module.deoptimize(m);
            } catch (Throwable ignored) {
                // 个别方法不支持反优化
            }
            return true;
        } catch (Throwable t) {
            Diag.w(module, "hook " + m.getDeclaringClass().getName() + ".recognize 失败", t);
            return false;
        }
    }

    /** 用独立线程做同步 Binder 调用并限时，避免相册进程冷启动拖死 FV 的工作线程。 */
    private static String bridge(Bitmap bm) {
        Future<String> f;
        try {
            f = POOL.submit(new Bridge(bm));
        } catch (Throwable t) {
            Diag.s("FV 桥接提交失败: " + t);
            return null;
        }
        try {
            return f.get(Config.BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            f.cancel(true);
            Diag.s("FV 桥接超时/异常（回退本地 OCR）: " + t);
            return null;
        }
    }

    private static final class Bridge implements Callable<String> {
        private final Bitmap src;

        Bridge(Bitmap b) {
            src = b;
        }

        @Override
        public String call() {
            Context ctx = Host.application();
            if (ctx == null) {
                Diag.s("FV: 拿不到 Application");
                return null;
            }
            ContentResolver cr = ctx.getContentResolver();

            // 通道 A：Bitmap 直接放进 Bundle，一次 content call 完成。
            // 相册进程冷启动时首次可能还没注入，重试 2 次。
            Bitmap soft = softwareCopy(src);
            for (int i = 0; i < 2; i++) {
                try {
                    Bundle args = new Bundle();
                    args.putParcelable(Config.EXTRA_BITMAP, soft == null ? src : soft);
                    Bundle r = cr.call(Uri.parse(Config.CALL_URI),
                            Config.METHOD_BITMAP, null, args);
                    String t = textOf(r);
                    if (t != null) {
                        Diag.s("FV 通道A(extract_bitmap) 成功");
                        return t;
                    }
                } catch (Throwable t) {
                    Diag.s("FV 通道A 第" + (i + 1) + "次失败: " + t);
                }
                sleep(350);
            }

            // 通道 B：内存管道写 PNG，再 extract_screen_text 取走。
            try {
                try (OutputStream os = cr.openOutputStream(Uri.parse(Config.INPUT_URI), "w")) {
                    if (os == null) {
                        throw new IllegalStateException("openOutputStream 返回空");
                    }
                    src.compress(Bitmap.CompressFormat.PNG, 100, os);
                    os.flush();
                }
                Bundle r = cr.call(Uri.parse(Config.CALL_URI),
                        Config.METHOD_SCREEN, null, new Bundle());
                String t = textOf(r);
                if (t != null) {
                    Diag.s("FV 通道B(ocr_input) 成功");
                    return t;
                }
            } catch (Throwable t) {
                Diag.s("FV 通道B 失败: " + t);
            }

            // 备用 URI 再试一次 A
            try {
                Bundle args = new Bundle();
                args.putParcelable(Config.EXTRA_BITMAP, soft == null ? src : soft);
                Bundle r = cr.call(Uri.parse(Config.CALL_URI_BACKUP),
                        Config.METHOD_BITMAP, null, args);
                return textOf(r);
            } catch (Throwable t) {
                Diag.s("FV 备用 URI 失败: " + t);
                return null;
            } finally {
                if (soft != null && !soft.isRecycled()) {
                    soft.recycle();
                }
            }
        }
    }

    /** HARDWARE / mutable Bitmap 不适合跨 Binder 用 ashmem 传，复制成 ARGB_8888 只读副本。 */
    private static Bitmap softwareCopy(Bitmap b) {
        try {
            boolean hardware = Build.VERSION.SDK_INT >= 26
                    && b.getConfig() == Bitmap.Config.HARDWARE;
            if (!hardware && !b.isMutable()) {
                return null;
            }
            return b.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String textOf(Bundle r) {
        if (r == null) {
            return null;
        }
        if (!r.getBoolean(Config.EXTRA_SUCCESS, false)) {
            Diag.s("FV: 相册返回失败 " + r.getString(Config.EXTRA_ERROR));
            return null;
        }
        String t = r.getString(Config.EXTRA_TEXT, "");
        return t == null || t.isEmpty() ? null : t;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
