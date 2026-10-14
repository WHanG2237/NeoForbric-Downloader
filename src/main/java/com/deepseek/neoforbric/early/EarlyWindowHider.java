package com.deepseek.neoforbric.early;

import com.deepseek.neoforbric.core.ForbricLog;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;


public final class EarlyWindowHider {

    private static final int SW_HIDE = 0;

    private static final String GLFW_WINDOW_CLASS_PREFIX = "GLFW";

    private EarlyWindowHider() {}


    public static boolean hide() {
        String providerInfo = "n/a";
        try {
            Object provider = providerInstance(GameRelauncher.class.getClassLoader());
            if (provider == null) {


                provider = providerInstance(discovererLoader());
            }
            if (provider != null) {
                providerInfo = provider.getClass().getName();
                Long handle = glfwHandle(provider);
                if (handle != null && handle != 0L) {
                    Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
                    glfw.getMethod("glfwHideWindow", long.class).invoke(null, handle);
                    ForbricLog.info("hid the early window (glfw path: handle=%d, provider=%s)",
                            handle, providerInfo);
                    return true;
                }
                ForbricLog.debug("no usable GLFW handle on provider=%s, falling back to win32", providerInfo);
            } else {
                ForbricLog.debug("ImmediateWindowHandler.provider unavailable, falling back to win32");
            }
        } catch (Throwable t) {
            ForbricLog.debug("glfw hide failed (provider=%s): %s, falling back to win32", providerInfo, t);
        }

        try {
            if (hideByWin32()) {
                ForbricLog.info("hid the early window (win32 ShowWindow; provider=%s)", providerInfo);
            } else {
                ForbricLog.debug("no visible GLFW window to hide (provider=%s)", providerInfo);
            }
            return true;
        } catch (Throwable t) {
            ForbricLog.warn("hide early window failed (a frozen loading window may remain): %s", t);
            return false;
        }
    }


    private static boolean hideByWin32() {
        long selfPid = ProcessHandle.current().pid();
        WinDef.HWND[] target = new WinDef.HWND[1];
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                return true;
            }
            if (!isGlfwWindow(hwnd)) {

                return true;
            }
            IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            if (pidRef.getValue() != (int) selfPid) {
                return true;
            }
            target[0] = hwnd;
            return false;
        }, null);

        if (target[0] == null) {
            return false;
        }
        User32.INSTANCE.ShowWindow(target[0], SW_HIDE);
        return !User32.INSTANCE.IsWindowVisible(target[0]);
    }

    private static boolean isGlfwWindow(WinDef.HWND hwnd) {
        char[] buffer = new char[64];
        int length = User32.INSTANCE.GetClassName(hwnd, buffer, buffer.length);
        return length > 0 && Native.toString(buffer).startsWith(GLFW_WINDOW_CLASS_PREFIX);
    }

    private static Object providerInstance(ClassLoader loader) throws Exception {
        if (loader == null) {
            return null;
        }
        Class<?> handler = Class.forName("net.neoforged.fml.loading.ImmediateWindowHandler", false, loader);
        java.lang.reflect.Field field = handler.getDeclaredField("provider");
        field.setAccessible(true);
        return field.get(null);
    }

    private static ClassLoader discovererLoader() {
        try {
            return Class.forName("net.neoforged.fml.loading.ModDirTransformerDiscoverer").getClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Long glfwHandle(Object provider) {
        try {
            java.lang.reflect.Field field = provider.getClass().getDeclaredField("window");
            field.setAccessible(true);
            return field.getLong(provider);
        } catch (Throwable t) {
            return null;
        }
    }
}
