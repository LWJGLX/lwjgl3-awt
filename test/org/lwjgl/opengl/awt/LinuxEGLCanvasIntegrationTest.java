package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Configuration;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.egl.EGL10.EGL_NO_CONTEXT;
import static org.lwjgl.egl.EGL10.EGL_NO_DISPLAY;
import static org.lwjgl.egl.EGL10.EGL_VERSION;
import static org.lwjgl.egl.EGL10.eglQueryString;
import static org.lwjgl.egl.EGL14.eglGetCurrentDisplay;
import static org.lwjgl.egl.EGL14.eglGetCurrentContext;

@EnabledOnOs(OS.LINUX)
class LinuxEGLCanvasIntegrationTest {
    @Test
    void rendersWithAnEGLContextWhenEGLIsSelected() throws Exception {
        assumeEGLIsSelected();

        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<TestCanvas> canvasRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                createFrame("Linux EGL integration test", frameRef, canvasRef));

        try {
            SwingUtilities.invokeAndWait(() -> {
                TestCanvas canvas = canvasRef.get();
                canvas.render();
                assertTrue(canvas.platformCanvas instanceof PlatformLinuxEGLCanvas);
                assertTrue(canvas.getFramebufferWidth() > 0);
                assertTrue(canvas.getFramebufferHeight() > 0);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                GL.setCapabilities(null);
                dispose(frameRef.get());
            });
        }
    }

    @Test
    void keepsTheDisplayAliveWhileAnotherCanvasUsesIt() throws Exception {
        assumeEGLIsSelected();

        AtomicReference<JFrame> firstFrameRef = new AtomicReference<>();
        AtomicReference<JFrame> secondFrameRef = new AtomicReference<>();
        AtomicReference<TestCanvas> firstCanvasRef = new AtomicReference<>();
        AtomicReference<TestCanvas> secondCanvasRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            createFrame("First Linux EGL canvas", firstFrameRef, firstCanvasRef);
            createFrame("Second Linux EGL canvas", secondFrameRef, secondCanvasRef);
        });

        try {
            SwingUtilities.invokeAndWait(() -> {
                firstCanvasRef.get().render();
                secondCanvasRef.get().render();

                firstFrameRef.get().dispose();
                secondCanvasRef.get().render();
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                GL.setCapabilities(null);
                dispose(firstFrameRef.get());
                dispose(secondFrameRef.get());
            });
        }
    }

    @Test
    void leavesTheSharedDisplayInitializedAfterTheLastCanvasIsDisposed() throws Exception {
        assumeEGLIsSelected();

        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<TestCanvas> canvasRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                createFrame("Shared Linux EGL display", frameRef, canvasRef));

        try {
            SwingUtilities.invokeAndWait(() -> {
                TestCanvas canvas = canvasRef.get();
                canvas.render();
                long display = canvas.lastCurrentDisplay;
                assertNotEquals(EGL_NO_DISPLAY, display);

                GL.setCapabilities(null);
                frameRef.get().dispose();

                assertNotNull(eglQueryString(display, EGL_VERSION));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                GL.setCapabilities(null);
                dispose(frameRef.get());
            });
        }
    }

    @Test
    void selectsAnAtLeastCoreProfileContext() throws Exception {
        assumeEGLIsSelected();

        GLData data = new GLData();
        data.majorVersion = 3;
        data.minorVersion = 2;
        data.profile = GLData.Profile.CORE;
        data.versionPolicy = GLData.VersionPolicy.AT_LEAST;
        data.swapInterval = 0;

        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<TestCanvas> canvasRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> createFrame(
                "Linux EGL core profile test", new TestCanvas(data), frameRef, canvasRef));

        try {
            SwingUtilities.invokeAndWait(() -> {
                TestCanvas canvas = canvasRef.get();
                canvas.render();
                assertEquals(GLData.API.GL, canvas.effective.api);
                assertTrue(GLUtil.atLeast32(
                        canvas.effective.majorVersion, canvas.effective.minorVersion));
                assertEquals(GLData.Profile.CORE, canvas.effective.profile);
                assertEquals(GLData.VersionPolicy.AT_LEAST, canvas.effective.versionPolicy);
                assertEquals(Integer.valueOf(0), canvas.effective.swapInterval);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                GL.setCapabilities(null);
                dispose(frameRef.get());
            });
        }
    }

    private static void assumeEGLIsSelected() {
        assumeTrue(PlatformLinuxGLCanvasFactory.shouldUseEGL(
                        Configuration.OPENGL_CONTEXT_API.get(),
                        System.getenv("XDG_SESSION_TYPE"),
                        System.getenv("WAYLAND_DISPLAY")),
                "EGL is not selected");
    }

    private static void createFrame(String title, AtomicReference<JFrame> frameRef,
            AtomicReference<TestCanvas> canvasRef) {
        createFrame(title, new TestCanvas(), frameRef, canvasRef);
    }

    private static void createFrame(String title, TestCanvas canvas,
            AtomicReference<JFrame> frameRef, AtomicReference<TestCanvas> canvasRef) {
        canvas.setPreferredSize(new Dimension(320, 240));

        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(canvas);
        frame.pack();
        frame.setVisible(true);
        frameRef.set(frame);
        canvasRef.set(canvas);
    }

    private static void dispose(JFrame frame) {
        if (frame != null) {
            frame.dispose();
        }
    }

    private static final class TestCanvas extends AWTGLCanvas {
        private static final long serialVersionUID = 1L;
        private long lastCurrentDisplay = EGL_NO_DISPLAY;

        private TestCanvas() {
        }

        private TestCanvas(GLData data) {
            super(data);
        }

        @Override
        public void initGL() {
            GL.createCapabilities();
        }

        @Override
        public void paintGL() {
            assertTrue(context != EGL_NO_CONTEXT);
            assertEquals(context, eglGetCurrentContext());
            lastCurrentDisplay = eglGetCurrentDisplay();
            swapBuffers();
        }
    }
}
