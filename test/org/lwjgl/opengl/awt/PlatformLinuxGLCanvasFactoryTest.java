package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformLinuxGLCanvasFactoryTest {
    @Test
    void followsExplicitContextAPI() {
        assertTrue(PlatformLinuxGLCanvasFactory.shouldUseEGL("EGL", "x11", null));
        assertFalse(PlatformLinuxGLCanvasFactory.shouldUseEGL("native", "wayland", "wayland-0"));
    }

    @Test
    void defaultsToEGLInWaylandSessions() {
        assertTrue(PlatformLinuxGLCanvasFactory.shouldUseEGL(null, "wayland", "wayland-0"));
        assertFalse(PlatformLinuxGLCanvasFactory.shouldUseEGL(null, "wayland", null));
        assertFalse(PlatformLinuxGLCanvasFactory.shouldUseEGL(null, "x11", "wayland-0"));
    }
}
