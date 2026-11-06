package com.miui.gallery.ocrbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 反射调用相册自带的小米本地 OCR 引擎。全部按字符串查找，不 import 宿主类。 */
final class OcrEngine {

    private OcrEngine() {
    }

    /**
     * 完全照抄相册自己的调用序列（OCRLocalRequestHelper$OCRLocalAsyncTask）：
     * <pre>
     *   OCREngine e = OCREngine.getInstance();
     *   e.init(GalleryApp.sGetAndroidContext().getCacheDir().getPath(), null);
     *   OCRResult r = e.doOCR(bitmap);
     *   String text = r.total_text;
     * </pre>
     * getInstance() 内部会 System.loadLibrary 加载 libc++_shared / libmiocr /
     * libmiocr_wrapper。本模块就运行在相册进程，宿主的 nativeLibraryDir 天然可用，
     * 所以 libDir 传 null 即可（与相册一致）。
     */
    static String recognize(ClassLoader loader, Context ctx, Bitmap bitmap) throws Throwable {
        Class<?> engineClass = Class.forName(Config.ENGINE_CLASS, false, loader);
        Diag.s("找到引擎类 " + Config.ENGINE_CLASS);

        Method getInstance = engineClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object engine = getInstance.invoke(null);
        if (engine == null) {
            throw new IllegalStateException("OCREngine.getInstance() 返回空");
        }
        Diag.s("getInstance() 成功，本地 OCR so 已加载");

        Method init = engineClass.getDeclaredMethod("init", String.class, String.class);
        init.setAccessible(true);
        Object ok = init.invoke(engine, ctx.getCacheDir().getPath(), null);
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("OCREngine.init 失败：本地 OCR so 未成功加载"
                    + "（请先在相册里成功用过一次「提取文字」）");
        }
        Diag.s("init(" + ctx.getCacheDir().getPath() + ", null) 返回 true");

        Method doOCR = engineClass.getDeclaredMethod("doOCR", Bitmap.class);
        doOCR.setAccessible(true);
        Object result = doOCR.invoke(engine, bitmap);
        if (result == null) {
            throw new IllegalStateException("doOCR 返回空（引擎未就绪或 Bitmap 无效）");
        }
        Diag.s("doOCR 返回结果类 " + result.getClass().getName());

        Field f = result.getClass().getField(Config.RESULT_FIELD);
        Object text = f.get(result);
        return text == null ? "" : text.toString();
    }

    /** 引擎状态探测：只加载引擎类并触发 so 加载，不截屏、不 OCR。 */
    static String probe(ClassLoader loader) {
        try {
            Class<?> c = Class.forName(Config.ENGINE_CLASS, false, loader);
            Method gi = c.getDeclaredMethod("getInstance");
            gi.setAccessible(true);
            Object engine = gi.invoke(null);
            Field f = c.getDeclaredField("sIsLoadSuccess");
            f.setAccessible(true);
            boolean loaded = f.getBoolean(null);
            return "engine=" + (engine != null ? "OK" : "null")
                    + ", ocrSoLoaded=" + loaded
                    + ", sdk=" + Build.VERSION.SDK_INT
                    + ", abi=" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
        } catch (Throwable t) {
            return "probe 失败: " + t;
        }
    }
}
