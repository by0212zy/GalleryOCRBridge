package com.miui.gallery.ocrbridge;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.util.DisplayMetrics;
import android.content.res.Resources;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 内存截屏：把当前屏幕变成 Bitmap 直接交给 OCR，全程不产生临时截图文件。
 *
 * 三级回退：
 *   1. 隐藏 API SurfaceControl.captureDisplay / screenshot —— 拿到 Bitmap 或 HardwareBuffer，
 *      不需要任何外部进程，最干净；
 *   2. su -c "screencap -p" —— PNG 输出到 stdout，管道读进内存解码（LSPosed 环境一般有 root）；
 *   3. screencap -p —— 同上但不提权，给个别放宽了截屏权限的 ROM 用。
 *
 * 三级都只在内存里流转：byte[] -> Bitmap -> native OCR -> recycle，用完即弃。
 */
final class ScreenCapture {

    private ScreenCapture() {
    }

    static Bitmap capture() throws Exception {
        Throwable last = null;

        try {
            Bitmap bm = viaSurfaceControl();
            if (bm != null) {
                return software(bm);
            }
        } catch (Throwable t) {
            last = t;
        }

        // root 提权的 screencap 优先于不提权的，成功率高得多
        String[][] commands = {
                {"su", "-c", "screencap -p"},
                {"screencap", "-p"},
        };
        for (String[] cmd : commands) {
            try {
                Bitmap bm = viaScreencap(cmd);
                if (bm != null) {
                    return software(bm);
                }
            } catch (Throwable t) {
                last = t;
            }
        }

        throw new IllegalStateException(
                "截屏失败（SurfaceControl / screencap / su 均不可用）。"
                        + "若设备无 root，请改用 extract_image_text 传入已有图片，"
                        + "或在 shell 端 screencap -p 到文件后传路径。最后错误："
                        + (last == null ? "unknown" : last));
    }

    /** 反射隐藏 API；LSPosed 注入的进程通常已绕过 hidden API 限制。 */
    private static Bitmap viaSurfaceControl() throws Throwable {
        Class<?> sc = Class.forName("android.view.SurfaceControl", false, null);

        // Android 12+ (API 31+)：captureDisplay(int displayId, Rect sourceCrop)
        // 空 Rect 表示整屏。返回 ScreenshotHardwareBuffer，取其 HardwareBuffer 包成 Bitmap。
        try {
            Method m = sc.getDeclaredMethod("captureDisplay", int.class, Rect.class);
            m.setAccessible(true);
            Object buffer = m.invoke(null, 0, new Rect());
            if (buffer != null) {
                Method ghb = buffer.getClass().getMethod("getHardwareBuffer");
                Object hb = ghb.invoke(buffer);
                if (hb instanceof HardwareBuffer) {
                    return Bitmap.wrapHardwareBuffer((HardwareBuffer) hb, null);
                }
            }
        } catch (Throwable ignored) {
            // 继续试旧签名
        }

        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);

        // 旧签名：screenshot(Rect, int width, int height, int rotation)
        try {
            Method m = sc.getDeclaredMethod("screenshot",
                    Rect.class, int.class, int.class, int.class);
            m.setAccessible(true);
            Object o = m.invoke(null, new Rect(), w, h, 0);
            if (o instanceof Bitmap) {
                return (Bitmap) o;
            }
        } catch (Throwable ignored) {
            // 继续
        }

        // 更旧：screenshot(int width, int height, int rotation)
        try {
            Method m = sc.getDeclaredMethod("screenshot", int.class, int.class, int.class);
            m.setAccessible(true);
            Object o = m.invoke(null, w, h, 0);
            if (o instanceof Bitmap) {
                return (Bitmap) o;
            }
        } catch (Throwable ignored) {
            // 继续
        }

        // 两参数版
        try {
            Method m = sc.getDeclaredMethod("screenshot", int.class, int.class);
            m.setAccessible(true);
            Object o = m.invoke(null, w, h);
            if (o instanceof Bitmap) {
                return (Bitmap) o;
            }
        } catch (Throwable ignored) {
            // 结束
        }
        return null;
    }

    /** 执行 screencap，把 stdout 的 PNG 字节读进内存解码。不写任何文件。 */
    private static Bitmap viaScreencap(String[] cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(cmd);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4 << 20);
        byte[] buf = new byte[64 * 1024];
        try (InputStream is = p.getInputStream()) {
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        }
        // 读掉 stderr，避免缓冲区写满导致子进程阻塞
        try (InputStream es = p.getErrorStream()) {
            while (es.read(buf) > 0) {
                // discard
            }
        }
        if (!p.waitFor(12, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        byte[] data = bos.toByteArray();
        if (data.length < 128) {
            throw new IllegalStateException("screencap 无有效输出 (" + cmd[0] + ")");
        }
        Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bm == null) {
            throw new IllegalStateException("screencap 输出无法解码为 PNG");
        }
        return bm;
    }

    /** HARDWARE Bitmap 的像素不可被 native 直接读取，转成普通 ARGB_8888。 */
    private static Bitmap software(Bitmap bm) {
        if (bm.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap copy = bm.copy(Bitmap.Config.ARGB_8888, false);
            bm.recycle();
            return copy;
        }
        return bm;
    }
}
