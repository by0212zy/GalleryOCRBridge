package com.miui.gallery.ocrbridge;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/** 反射工具：Class.forName(name, false, loader) 一律只加载不初始化，避免早期触发宿主 &lt;clinit&gt;。 */
final class Host {
    private Host() {
    }

    static Class<?> find(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    static Method method(Class<?> c, String name, Class<?>... params) {
        if (c == null) {
            return null;
        }
        try {
            return c.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 Hook 链上的参数；部分框架版本没有 getArg()，退回 getArgs()。 */
    static Object arg(XposedInterface.Chain chain, int index) {
        try {
            return chain.getArg(index);
        } catch (Throwable t) {
            try {
                List<Object> args = chain.getArgs();
                return args.size() > index ? args.get(index) : null;
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    static String argString(XposedInterface.Chain chain, int index) {
        Object o = arg(chain, index);
        return o instanceof String ? (String) o : null;
    }

    /** 当前进程的 Application；ContentProvider.call 到达时必然已就绪。 */
    static Context application() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread", false, null);
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
