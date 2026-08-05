package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.linux.X11;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GLX11.glXQueryExtensionsString;
import static org.lwjgl.opengl.GLX13.glXQueryDrawable;
import static org.lwjgl.opengl.GLXEXTSwapControl.GLX_SWAP_INTERVAL_EXT;

@EnabledOnOs(OS.LINUX)
class LinuxSwapIntervalIntegrationTest {
    @Test
    void appliesRequestedSwapIntervals() throws Exception {
        assumeTrue(withCanvas(null, LinuxSwapIntervalIntegrationTest::supportsSwapControl),
                "GLX_EXT_swap_control is unavailable");

        assertEquals(0, queryConfiguredSwapInterval(0));
        assertEquals(1, queryConfiguredSwapInterval(1));
    }

    private static int queryConfiguredSwapInterval(int swapInterval) throws Exception {
        return withCanvas(swapInterval, platformCanvas -> glXQueryDrawable(
                platformCanvas.display, platformCanvas.drawable, GLX_SWAP_INTERVAL_EXT));
    }

    private static boolean supportsSwapControl(PlatformLinuxGLCanvas platformCanvas) {
        int screen = X11.XDefaultScreen(platformCanvas.display);
        String extensionString = glXQueryExtensionsString(platformCanvas.display, screen);
        return extensionString != null
                && Arrays.asList(extensionString.split(" ")).contains("GLX_EXT_swap_control");
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
                canvas.render();
                result.set(query.apply((PlatformLinuxGLCanvas) canvas.platformCanvas));
            } finally {
                GL.setCapabilities(null);
                frame.dispose();
            }
        });
        return result.get();
    }

    private interface CanvasQuery<T> {
        T apply(PlatformLinuxGLCanvas platformCanvas);
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
        }
    }
}
