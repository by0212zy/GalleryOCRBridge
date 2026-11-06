package com.miui.gallery.ocrbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;

/** 统一处理入口：截屏 / 读图 -> OCR -> 结果 Bundle。content call 与广播共用。 */
final class OcrService {

    private OcrService() {
    }

    static Bundle handle(ClassLoader loader, Context ctx, String method, Bundle extras) {
        Bundle out = new Bundle();
        Bitmap bm = null;
        try {
            if (ctx == null) {
                throw new IllegalStateException("相册 Application 尚未就绪，请稍后重试");
            }
            if (Config.METHOD_PING.equals(method)) {
                String info = OcrEngine.probe(loader);
                Diag.s("ping: " + info);
                out.putBoolean(Config.EXTRA_SUCCESS, true);
                out.putString(Config.EXTRA_TEXT, info);
                out.putString(Config.EXTRA_SOURCE, "ping");
                return out;
            }

            String source;
            if (Config.METHOD_IMAGE.equals(method)) {
                String path = extras == null ? null : extras.getString(Config.EXTRA_PATH);
                if (TextUtils.isEmpty(path)) {
                    throw new IllegalArgumentException(
                            "缺少图片路径：加 --extra path:s:/sdcard/xxx.png");
                }
                bm = BitmapFactory.decodeFile(path);
                if (bm == null) {
                    throw new IllegalStateException("无法读取图片：" + path);
                }
                source = "image:" + path;
                Diag.s("已解码图片 " + path + " -> " + bm.getWidth() + "x" + bm.getHeight());
            } else {
                // 优先用 shell 端通过内存管道送进来的截图（零文件、无需 root）
                bm = ScreenInput.take(Config.INPUT_WAIT_MS);
                if (bm != null) {
                    source = "screen:pipe";
                } else {
                    Diag.s("无管道截图，回退到进程内截屏（可能被权限拒绝）");
                    bm = ScreenCapture.capture();
                    source = "screen:app";
                }
            }

            Diag.s("送入 OCR，Bitmap=" + bm.getWidth() + "x" + bm.getHeight()
                    + " config=" + bm.getConfig());
            String text = OcrEngine.recognize(loader, ctx, bm);
            Diag.s("OCR 完成，文本长度=" + (text == null ? 0 : text.length()));
            out.putBoolean(Config.EXTRA_SUCCESS, true);
            out.putString(Config.EXTRA_TEXT, text == null ? "" : text);
            out.putString(Config.EXTRA_SOURCE, source);
        } catch (Throwable t) {
            String err = describe(t);
            Diag.s("✗ 提取失败: " + err);
            out.putBoolean(Config.EXTRA_SUCCESS, false);
            out.putString(Config.EXTRA_TEXT, "");
            out.putString(Config.EXTRA_ERROR, err);
        } finally {
            if (bm != null && !bm.isRecycled()) {
                bm.recycle();
            }
        }
        return out;
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        for (int i = 0; i < 3 && cur != null; i++, cur = cur.getCause()) {
            if (i > 0) {
                sb.append(" <- ");
            }
            sb.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null) {
                sb.append('(').append(cur.getMessage()).append(')');
            }
        }
        return sb.toString();
    }
}
