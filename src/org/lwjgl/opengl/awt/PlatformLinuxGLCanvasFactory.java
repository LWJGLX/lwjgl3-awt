package org.lwjgl.opengl.awt;

import org.lwjgl.system.Configuration;

final class PlatformLinuxGLCanvasFactory {
    private PlatformLinuxGLCanvasFactory() {
    }

    static PlatformGLCanvas create() {
        return shouldUseEGL(
                Configuration.OPENGL_CONTEXT_API.get(),
                System.getenv("XDG_SESSION_TYPE"),
                System.getenv("WAYLAND_DISPLAY"))
                ? new PlatformLinuxEGLCanvas()
                : new PlatformLinuxGLCanvas();
    }

    static boolean shouldUseEGL(String contextAPI, String sessionType, String waylandDisplay) {
        return "EGL".equals(contextAPI)
                || contextAPI == null && "wayland".equals(sessionType) && waylandDisplay != null;
    }
}
