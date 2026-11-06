package com.miui.gallery.ocrbridge;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.api.XposedModule;

/**
 * 异步备用入口：动态注册一个 exported 广播接收器，供 Tasker / MacroDroid / 其它 App 触发。
 *
 * 广播本身无法同步回传文本，所以结果按 extras 分发：
 *   --es callback <action>  把结果 Bundle 作为广播发回（其它 App 监听该 action）
 *   --es out <路径>          把文本写入文件
 *   --ez clip true          写入系统剪贴板
 *   都不给时只写 logcat（tag GalleryOCR）。
 *
 * 注册刻意延后到应用就绪之后：过早注册会让外部广播在 Application 初始化完成前
 * 触发宿主类的 <clinit>，这是上一个项目里导致桌面闪退的根因。
 */
final class BroadcastTrigger {

    private BroadcastTrigger() {
    }

    static void install(XposedModule module, String pkg) {
        registerWithRetry(module, pkg, 0);
    }

    private static void registerWithRetry(XposedModule module, String pkg, int attempt) {
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> {
            Context ctx = Host.application();
            if (ctx == null) {
                if (attempt < 5) {
                    registerWithRetry(module, pkg, attempt + 1);
                } else {
                    Diag.w(module, "广播注册放弃：Application 一直未就绪", null);
                }
                return;
            }
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(Config.ACTION_SCREEN);
                f.addAction(Config.ACTION_IMAGE);
                if (Build.VERSION.SDK_INT >= 33) {
                    // Android 13+ 必须显式声明可被外部发送
                    ctx.registerReceiver(new Receiver(), f, Context.RECEIVER_EXPORTED);
                } else {
                    ctx.registerReceiver(new Receiver(), f);
                }
                Diag.i(module, pkg + ": 广播入口已注册（主进程）");
            } catch (Throwable t) {
                Diag.e(module, "注册广播失败", t);
            }
        }, 1500L + attempt * 1000L);
    }

    private static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            String method = Config.ACTION_IMAGE.equals(action)
                    ? Config.METHOD_IMAGE : Config.METHOD_SCREEN;
            Bundle src = intent.getExtras();
            final Bundle extras = src == null ? new Bundle() : new Bundle(src);
            final Context app = ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx;
            final ClassLoader loader = app.getClassLoader();

            // OCR 可能耗时，放到工作线程，避免阻塞广播主线程导致 ANR
            new Thread(() -> {
                Bundle result = OcrService.handle(loader, app, method, extras);
                deliver(app, extras, result);
            }, "GalleryOCR-Worker").start();
        }
    }

    private static void deliver(Context ctx, Bundle extras, Bundle result) {
        String text = result.getString(Config.EXTRA_TEXT, "");
        boolean ok = result.getBoolean(Config.EXTRA_SUCCESS, false);
        Log.i(Config.TAG, "OCR " + (ok ? "成功" : "失败: " + result.getString(Config.EXTRA_ERROR))
                + " | 文本=" + text);

        String callback = extras.getString(Config.EXTRA_CALLBACK);
        if (callback != null && !callback.isEmpty()) {
            try {
                Intent i = new Intent(callback);
                i.putExtras(result);
                ctx.sendBroadcast(i);
            } catch (Throwable t) {
                Log.w(Config.TAG, "回调广播发送失败", t);
            }
        }

        String out = extras.getString(Config.EXTRA_OUT);
        if (out != null && !out.isEmpty()) {
            try {
                File f = new File(out);
                // 不主动 mkdirs，避免越权创建目录；写失败只记日志
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable t) {
                Log.w(Config.TAG, "写结果文件失败: " + out, t);
            }
        }

        if (extras.getBoolean(Config.EXTRA_CLIP, false)) {
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("GalleryOCR", text));
                }
            } catch (Throwable t) {
                Log.w(Config.TAG, "写剪贴板失败（后台应用可能受限）", t);
            }
        }
    }
}
