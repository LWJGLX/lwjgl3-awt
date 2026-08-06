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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledOnOs(OS.LINUX)
class LinuxGLXCanvasIntegrationTest {
    @Test
    void honorsMultisampleFramebufferAttributes() throws Exception {
        assumeGLXIsSelected();

        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<TestCanvas> canvasRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                GLData data = new GLData();
                data.samples = 4;
                data.swapInterval = 0;

                TestCanvas canvas = new TestCanvas(data);
                canvas.setPreferredSize(new Dimension(320, 240));

                JFrame frame = new JFrame("Linux GLX multisample integration test");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.getContentPane().add(canvas);
                frame.pack();
                frame.setVisible(true);
                frameRef.set(frame);
                canvasRef.set(canvas);
            });

            SwingUtilities.invokeAndWait(() -> {
                TestCanvas canvas = canvasRef.get();
                canvas.render();
                assertTrue(canvas.platformCanvas instanceof PlatformLinuxGLCanvas);
                assertTrue(canvas.effective.sampleBuffers > 0);
                assertTrue(canvas.effective.samples >= 4);
            });
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

    private static void assumeGLXIsSelected() {
        assumeFalse(PlatformLinuxGLCanvasFactory.shouldUseEGL(
                        Configuration.OPENGL_CONTEXT_API.get(),
                        System.getenv("XDG_SESSION_TYPE"),
                        System.getenv("WAYLAND_DISPLAY")),
                "GLX is not selected");
    }

    private static final class TestCanvas extends AWTGLCanvas {
        private static final long serialVersionUID = 1L;

        private TestCanvas(GLData data) {
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
