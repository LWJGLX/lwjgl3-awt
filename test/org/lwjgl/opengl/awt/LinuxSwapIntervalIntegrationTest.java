package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.JNI;
import org.lwjgl.system.linux.X11;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Dimension;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GLX11.glXQueryExtensionsString;
import static org.lwjgl.opengl.GLX13.glXQueryDrawable;
import static org.lwjgl.opengl.GLXEXTSwapControl.GLX_SWAP_INTERVAL_EXT;

@EnabledOnOs(OS.LINUX)
class LinuxSwapIntervalIntegrationTest {
    private static final int RENDER_CYCLES = 4;

    @Test
    void appliesZeroSwapIntervalAcrossRenderCycles() throws Exception {
        assumeGLXIsSelected();
        assumeDriverPolicyAllows(0);
        GLXSwapInterval.Mechanism mechanism = assumeSupported(0, queryExtensions());

        assertEquals(Integer.valueOf(0), queryConfiguredSwapInterval(0, mechanism));
    }

    @Test
    void appliesPositiveSwapIntervalAcrossRenderCycles() throws Exception {
        assumeGLXIsSelected();
        assumeDriverPolicyAllows(1);
        GLXSwapInterval.Mechanism mechanism = assumeSupported(1, queryExtensions());

        Integer configured = queryConfiguredSwapInterval(1, mechanism);
        if (mechanism != GLXSwapInterval.Mechanism.SGI) {
            assertEquals(Integer.valueOf(1), configured);
        }
        // GLX_SGI_swap_control has no query operation. Successfully rendering above proves that
        // glXSwapIntervalSGI accepted the positive interval; the production path checks its result.
    }

    @Test
    void rejectsSwapIntervalWhenNoCompatibleExtensionExists() throws Exception {
        assumeGLXIsSelected();
        Set<String> extensions = queryExtensions();
        assumeTrue(!supports(0, extensions), "A compatible GLX swap-control extension is available");

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> withCanvas(0, (canvas, platformCanvas) -> null));
        Throwable creationFailure = failure.getCause();
        assertTrue(creationFailure instanceof RuntimeException);
        assertTrue(creationFailure.getCause() instanceof AWTException);
        assertEquals(GLXSwapInterval.NO_COMPATIBLE_EXTENSION,
                creationFailure.getCause().getMessage());
    }

    private static Set<String> queryExtensions() throws Exception {
        return withCanvas(null, (canvas, platformCanvas) -> {
            int screen = X11.XDefaultScreen(platformCanvas.display);
            return GLXSwapInterval.parseExtensions(
                    glXQueryExtensionsString(platformCanvas.display, screen));
        });
    }

    private static Integer queryConfiguredSwapInterval(int swapInterval,
            GLXSwapInterval.Mechanism mechanism) throws Exception {
        return withCanvas(swapInterval, (canvas, platformCanvas) -> {
            if (mechanism == GLXSwapInterval.Mechanism.EXT) {
                return glXQueryDrawable(
                        platformCanvas.display, platformCanvas.drawable, GLX_SWAP_INTERVAL_EXT);
            }
            if (mechanism == GLXSwapInterval.Mechanism.MESA) {
                return queryMesaSwapInterval(canvas);
            }
            return null;
        });
    }

    private static int queryMesaSwapInterval(TestCanvas canvas) {
        AtomicReference<Integer> result = new AtomicReference<>();
        canvas.runInContext(() -> {
            FunctionProvider functionProvider = GL.getFunctionProvider();
            long functionAddress = functionProvider == null
                    ? 0L
                    : functionProvider.getFunctionAddress("glXGetSwapIntervalMESA");
            assertNotEquals(0L, functionAddress,
                    "GLX_MESA_swap_control is advertised but glXGetSwapIntervalMESA is unavailable");
            result.set(JNI.callI(functionAddress));
        });
        return result.get();
    }

    private static GLXSwapInterval.Mechanism assumeSupported(
            int interval, Set<String> extensions) {
        try {
            return GLXSwapInterval.select(interval, extensions);
        } catch (AWTException unsupported) {
            assumeTrue(false, unsupported.getMessage());
            throw new AssertionError(unsupported);
        }
    }

    private static boolean supports(int interval, Set<String> extensions) {
        try {
            GLXSwapInterval.select(interval, extensions);
            return true;
        } catch (AWTException unsupported) {
            return false;
        }
    }

    private static void assumeGLXIsSelected() {
        assumeTrue(!PlatformLinuxGLCanvasFactory.shouldUseEGL(
                        Configuration.OPENGL_CONTEXT_API.get(),
                        System.getenv("XDG_SESSION_TYPE"),
                        System.getenv("WAYLAND_DISPLAY")),
                "The EGL backend is selected");
    }

    private static void assumeDriverPolicyAllows(int interval) {
        // Mesa can force synchronization on or off independently of the application. Do not
        // turn that explicit developer/CI policy into a failure of the swap-control test.
        String vblankMode = System.getenv("vblank_mode");
        boolean overridden = interval == 0 && "3".equals(vblankMode)
                || interval > 0 && "0".equals(vblankMode);
        assumeTrue(!overridden,
                "Mesa vblank_mode=" + vblankMode + " overrides the requested swap interval");
    }

    private static <T> T withCanvas(Integer swapInterval, CanvasQuery<T> query)
            throws InvocationTargetException, InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            GLData data = new GLData();
            data.swapInterval = swapInterval;

            TestCanvas canvas = new TestCanvas(data);
            canvas.setPreferredSize(new Dimension(320, 240));

            JFrame frame = new JFrame("Linux swap interval test");
            try {
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.getContentPane().add(canvas);
                frame.pack();
                frame.setVisible(true);
                for (int i = 0; i < RENDER_CYCLES; i++) {
                    canvas.render();
                }
                result.set(query.apply(canvas, (PlatformLinuxGLCanvas) canvas.platformCanvas));
            } finally {
                GL.setCapabilities(null);
                frame.dispose();
            }
        });
        return result.get();
    }

    private interface CanvasQuery<T> {
        T apply(TestCanvas canvas, PlatformLinuxGLCanvas platformCanvas);
    }

    private static final class TestCanvas extends AWTGLCanvas {
        private static final long serialVersionUID = 1L;

        TestCanvas(GLData data) {
            super(data);
        }

        @Override
        public void initGL() {
            GL.createCapabilities();
        }

        @Override
        public void paintGL() {
            swapBuffers();
        }
    }
}
