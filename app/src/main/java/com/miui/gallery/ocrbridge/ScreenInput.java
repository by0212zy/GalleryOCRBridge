package com.miui.gallery.ocrbridge;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * shell 端截屏的内存管道。
 *
 * 命令：{@code screencap -p | content write --uri content://com.miui.gallery.widget/ocr_input}
 *
 * 我们 Hook ContentProvider.openFile / openAssetFile，返回一个 pipe 的写端给
 * ContentResolver.openOutputStream；同时开线程把读端的 PNG 字节解码成 Bitmap
 * 存在内存里。随后 extract_screen_text 直接取走这个 Bitmap 送进 OCR。
 *
 * 全程零临时文件：字节只在 pipe -> byte[] -> Bitmap -> native OCR 之间流转。
 * 截屏由 shell 进程完成（shell uid 有截屏权限），不需要相册 app 进程有
 * READ_FRAME_BUFFER，也不需要 su 提权（那会卡在 Magisk 授权弹窗）。
 */
final class ScreenInput {

    private static volatile Bitmap pending;
    private static volatile CountDownLatch latch;

    private ScreenInput() {
    }

    /** 建立管道，返回写端（交给 ContentResolver.openOutputStream / content write）。 */
    static ParcelFileDescriptor accept() throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        final CountDownLatch signal = new CountDownLatch(1);
        latch = signal;
        pending = null;

        final ParcelFileDescriptor readSide = pipe[0];
        Thread reader = new Thread(() -> {
            Bitmap bm = null;
            int bytes = 0;
            try (InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(readSide)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(4 << 20);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                byte[] data = bos.toByteArray();
                bytes = data.length;
                if (data.length > 128) {
                    bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                }
                Diag.s("管道收到截图 " + bytes + " 字节，解码 " + (bm != null ? "成功" : "失败"));
            } catch (Throwable t) {
                Diag.s("读管道截图异常: " + t);
            } finally {
                pending = bm;
                signal.countDown();
            }
        }, "GalleryOCR-Input");
        reader.setDaemon(true);
        reader.start();

        Diag.s("截图管道已建立，等待 shell 写入");
        return pipe[1];
    }

    /** 取走 shell 送进来的内存截图；没有管道输入时立即返回 null。 */
    static Bitmap take(long timeoutMs) {
        CountDownLatch signal = latch;
        if (signal == null) {
            return null;
        }
        try {
            signal.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // 中断也继续尝试取
        }
        Bitmap bm = pending;
        pending = null;
        latch = null;
        if (bm != null) {
            Diag.s("取用管道截图 " + bm.getWidth() + "x" + bm.getHeight());
        }
        return bm;
    }
}
