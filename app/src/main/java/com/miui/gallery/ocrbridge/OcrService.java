package com.miui.gallery.ocrbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

/** 统一处理入口：取图 -> OCR -> 结果 Bundle。content call 与广播共用。 */
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
            } else if (Config.METHOD_BITMAP.equals(method)) {
                // FV 悬浮球桥接：对方进程把已经截好的 Bitmap 放进 Bundle 直接送来。
                Object o = extras == null ? null : extras.getParcelable(Config.EXTRA_BITMAP);
                if (!(o instanceof Bitmap)) {
                    throw new IllegalArgumentException(
                            "Bundle 里没有 Bitmap（key=" + Config.EXTRA_BITMAP + "）");
                }
                bm = (Bitmap) o;
                if (bm.isRecycled()) {
                    throw new IllegalStateException("传入的 Bitmap 已被回收");
                }
                if (Build.VERSION.SDK_INT >= 26 && bm.getConfig() == Bitmap.Config.HARDWARE) {
                    // HARDWARE Bitmap 像素不可读，native OCR 会失败，先转软件副本
                    Bitmap soft = bm.copy(Bitmap.Config.ARGB_8888, false);
                    if (soft == null) {
                        throw new IllegalStateException("HARDWARE Bitmap 转换失败");
                    }
                    bm = soft;
                }
                source = "bitmap:app";
                Diag.s("收到 App 传入的 Bitmap " + bm.getWidth() + "x" + bm.getHeight()
                        + " config=" + bm.getConfig());
            } else {
                // 截屏由 shell 进程完成，PNG 通过内存管道送进来（零文件、无需 root）。
                // 正式版已删除进程内截屏回退：真机日志显示它从未命中，且 v1.0.0
                // 正是它因权限被拒而让命令挂 20 秒。
                bm = ScreenInput.take(Config.INPUT_WAIT_MS);
                if (bm == null) {
                    throw new IllegalStateException("没有收到截图。请先执行 "
                            + "screencap -p | content write --uri " + Config.INPUT_URI
                            + "，或改用 extract_image_text 传已有图片");
                }
                source = "screen:pipe";
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
