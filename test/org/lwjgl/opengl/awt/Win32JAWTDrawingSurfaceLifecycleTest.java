package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.system.windows.User32;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.WGL.wglGetCurrentDC;
import static org.lwjgl.system.JNI.callPI;

@EnabledOnOs(OS.WINDOWS)
class Win32JAWTDrawingSurfaceLifecycleTest {
    private static final int GR_GDIOBJECTS = 0;
    private static final int WARMUP_CYCLES = 32;
    private static final int MEASURED_CYCLES = 256;
    private static final int MAX_GDI_OBJECT_GROWTH = 32;

    @Test
    void singleSampledRenderingUsesAndReleasesTheJAWTHdc() throws Exception {
        assertRenderingUsesAndReleasesTheJAWTHdc(0);
    }

    @Test
    void nativeMultisampledRenderingUsesAndReleasesTheJAWTHdc() throws Exception {
        assertRenderingUsesAndReleasesTheJAWTHdc(4);
    }

    private static void assertRenderingUsesAndReleasesTheJAWTHdc(int samples) throws Exception {
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<AWTGLCanvas> canvasRef = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            GLData data = new GLData();
            data.majorVersion = 3;
            data.minorVersion = 2;
            data.profile = GLData.Profile.CORE;
            data.samples = samples;
            data.swapInterval = 0;

            AWTGLCanvas canvas = new AWTGLCanvas(data) {
                @Override
                public void initGL() {
                    GL.createCapabilities();
                }

                @Override
                public void paintGL() {
                    long jawtHdc = getLockedJAWTHdc(this);
                    assertNotEquals(0L, jawtHdc, "JAWT did not provide a DC for rendering");
                    assertEquals(jawtHdc, wglGetCurrentDC(),
                            "The OpenGL context is not current on the JAWT-owned DC");
                    glClear(GL_COLOR_BUFFER_BIT);
                    swapBuffers();
                }
            };
            canvas.setPreferredSize(new Dimension(320, 240));

            JFrame frame = new JFrame("Win32 JAWT lifecycle test (samples=" + samples + ")");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(canvas);
            frame.pack();
            frame.setVisible(true);
            frameRef.set(frame);
            canvasRef.set(canvas);
        });

        try {
            try {
                renderResizeCycles(frameRef.get(), canvasRef.get(), WARMUP_CYCLES);
            } catch (Exception e) {
                String unsupportedReason = findUnsupportedMultisamplingReason(e);
                if (samples > 0 && unsupportedReason != null) {
                    assumeTrue(false, "Native 4x MSAA is unavailable: " + unsupportedReason);
                }
                throw e;
            }
            assertEquals(samples, canvasRef.get().effective.samples,
                    "The created context does not use the requested sample count");
            assertEquals(samples == 0 ? 0 : 1, canvasRef.get().effective.sampleBuffers,
                    "The created context reports an unexpected sample-buffer count");
            int gdiObjectsBefore = getGdiObjectCount();

            renderResizeCycles(frameRef.get(), canvasRef.get(), MEASURED_CYCLES);
            int gdiObjectsAfter = getGdiObjectCount();
            int growth = gdiObjectsAfter - gdiObjectsBefore;

            assertTrue(growth <= MAX_GDI_OBJECT_GROWTH,
                    () -> "GDI object count grew by " + growth + " across " + MEASURED_CYCLES
                            + " render cycles (before=" + gdiObjectsBefore
                            + ", after=" + gdiObjectsAfter + ")");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                GL.setCapabilities(null);
                JFrame frame = frameRef.get();
                if (frame != null) {
                    frame.dispose();
                }
            });
        }
    }

    private static String findUnsupportedMultisamplingReason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null
                    && (message.contains("neither WGL_ARB_multisample nor WGL_EXT_multisample")
                    || message.contains("No support for wglChoosePixelFormatARB/EXT")
                    || message.contains("No supported pixel format found"))) {
                return message;
            }
        }
        return null;
    }

    private static void renderResizeCycles(JFrame frame, AWTGLCanvas canvas, int cycles)
            throws Exception {
        for (int i = 0; i < cycles; i++) {
            int width = 320 + (i & 1) * 32;
            int height = 240 + (i & 1) * 24;
            SwingUtilities.invokeAndWait(() -> {
                frame.setSize(width, height);
                canvas.render();
                assertEquals(0L, getLockedJAWTHdc(canvas),
                        "The JAWT-owned DC was retained after rendering");
            });
        }
    }

    private static int getGdiObjectCount() {
        long getGuiResources = User32.getLibrary().getFunctionAddress("GetGuiResources");
        assertNotEquals(0L, getGuiResources, "GetGuiResources is unavailable");
        int count = callPI(Kernel32.GetCurrentProcess(), GR_GDIOBJECTS, getGuiResources);
        assertTrue(count > 0, "GetGuiResources failed to return the GDI object count");
        return count;
    }

    private static long getLockedJAWTHdc(AWTGLCanvas canvas) {
        try {
            Field hdc = PlatformWin32GLCanvas.class.getDeclaredField("hdc");
            hdc.setAccessible(true);
            return hdc.getLong(canvas.platformCanvas);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to inspect the locked JAWT HDC", e);
        }
    }
}
