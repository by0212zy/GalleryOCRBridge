package com.miui.gallery.ocrbridge;

import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/** 运行时诊断：LSPosed 日志筛 GalleryOCR。 */
final class Diag {

    private static final Set<String> ONCE = Collections.synchronizedSet(new HashSet<String>());

    private Diag() {
    }

    static void i(XposedModule m, String msg) {
        try {
            m.log(Log.INFO, Config.TAG, msg);
        } catch (Throwable ignored) {
            // 日志异常不能影响 Hook
        }
    }

    static void w(XposedModule m, String msg, Throwable t) {
        try {
            m.log(Log.WARN, Config.TAG, msg, t);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    static void e(XposedModule m, String msg, Throwable t) {
        try {
            m.log(Log.ERROR, Config.TAG, msg, t);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 同一 key 只打一次，避免高频方法刷屏。 */
    static void once(XposedModule m, String key, String msg) {
        if (ONCE.add(key)) {
            i(m, msg);
        }
    }
}
