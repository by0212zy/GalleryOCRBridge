package com.miui.gallery.ocrbridge;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 进程内截屏（回退通道）：把当前屏幕变成 Bitmap 直接交给 OCR，不产生临时截图文件。
 *
 * 注意：相册是普通 app 进程，通常没有 READ_FRAME_BUFFER，这一级大概率会被拒。
 * 主通道是 {@link ScreenInput}（shell 端截屏 + 内存管道）。这里保留三级回退，
 * 且每级都打日志、超时只有 3 秒，避免像 v1.0.0 那样一条命令卡 20 秒。
 *
 *   1. 隐藏 API SurfaceControl.captureDisplay / screenshot
 *   2. su -c "screencap -p"   （PNG 走 stdout 管道读进内存）
 *   3. screencap -p
 */
final class ScreenCapture {

    private ScreenCapture() {
    }

    static Bitmap capture() throws Exception {
        Throwable last = null;

        try {
            Diag.s("尝试隐藏 API SurfaceControl 截屏");
            Bitmap bm = viaSurfaceControl();
            if (bm != null) {
                Diag.s("SurfaceControl 截屏成功");
                return software(bm);
            }
            Diag.s("SurfaceControl 未返回 Bitmap");
        } catch (Throwable t) {
            Diag.s("SurfaceControl 失败: " + t);
            last = t;
        }

        String[][] commands = {
                {"su", "-c", "screencap -p"},
                {"screencap", "-p"},
        };
        for (String[] cmd : commands) {
            try {
                Diag.s("尝试 " + String.join(" ", cmd));
                Bitmap bm = viaScreencap(cmd);
                if (bm != null) {
                    Diag.s("screencap 截屏成功");
                    return software(bm);
                }
            } catch (Throwable t) {
                Diag.s("screencap 失败: " + t);
                last = t;
            }
        }

        throw new IllegalStateException(
                "进程内截屏失败。请改用主命令（shell 端 screencap -p | content write ...），"
                        + "或用 extract_image_text 传已有图片。最后错误："
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
        } catch (Throwable t) {
            Diag.s("captureDisplay 不可用: " + t);
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
            // 继续试其它签名
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
        try (InputStream es = p.getErrorStream()) {
            while (es.read(buf) > 0) {
                // 丢弃 stderr，避免缓冲区写满阻塞子进程
            }
        }
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("screencap 超时（3s）");
        }
        byte[] data = bos.toByteArray();
        if (data.length < 128) {
            throw new IllegalStateException("screencap 无有效输出 (" + cmd[0]
                    + ")，多半是权限被拒或 su 未授权");
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
