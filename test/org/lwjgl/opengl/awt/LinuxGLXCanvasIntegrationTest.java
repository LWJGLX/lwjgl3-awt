package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.JNI;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;

@EnabledOnOs(OS.LINUX)
class LinuxGLXCanvasIntegrationTest {
    @Test
    void leavesOpenGL31ContextErrorStateClean() throws Exception {
        assumeGLXIsSelected();

        GLData data = new GLData();
        data.majorVersion = 3;
        data.minorVersion = 1;

        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<ErrorCheckingCanvas> canvasRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                ErrorCheckingCanvas canvas = new ErrorCheckingCanvas(data);
                canvas.setPreferredSize(new Dimension(320, 240));

                JFrame frame = new JFrame("Linux GLX 3.1 context query test");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.getContentPane().add(canvas);
                frame.pack();
                frame.setVisible(true);
                frameRef.set(frame);
                canvasRef.set(canvas);
            });

            SwingUtilities.invokeAndWait(() -> canvasRef.get().render());

            ErrorCheckingCanvas canvas = canvasRef.get();
            assumeTrue(canvas.effective.majorVersion == 3 && canvas.effective.minorVersion == 1,
                    "The GLX implementation returned OpenGL "
                            + canvas.effective.majorVersion + "." + canvas.effective.minorVersion);
            assertEquals(GL_NO_ERROR, canvas.errorBeforeCapabilities);
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

    @Test
    void validatesFramebufferAndSwapAttributes() throws Exception {
        assumeGLXIsSelected();

        GLData data = new GLData();
        data.depthSize = -1;

        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<TestCanvas> canvasRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                TestCanvas canvas = new TestCanvas(data);
                canvas.setPreferredSize(new Dimension(320, 240));

                JFrame frame = new JFrame("Linux GLX attribute validation test");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.getContentPane().add(canvas);
                frame.pack();
                frame.setVisible(true);
                frameRef.set(frame);
                canvasRef.set(canvas);
            });

            SwingUtilities.invokeAndWait(() -> {
                TestCanvas canvas = canvasRef.get();
                IllegalArgumentException negativeDepth = assertThrows(
                        IllegalArgumentException.class, canvas::render);
                assertTrue(negativeDepth.getMessage().contains("Depth bits"));

                data.depthSize = 24;
                data.samples = 1;
                data.colorSamplesNV = 2;
                IllegalArgumentException excessiveColorSamples = assertThrows(
                        IllegalArgumentException.class, canvas::render);
                assertTrue(excessiveColorSamples.getMessage().contains("Color samples greater"));

                data.samples = 0;
                data.colorSamplesNV = 0;
                data.doubleBuffer = false;
                data.swapInterval = 1;
                IllegalArgumentException singleBufferedSwapInterval = assertThrows(
                        IllegalArgumentException.class, canvas::render);
                assertTrue(singleBufferedSwapInterval.getMessage().contains("Swap interval"));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = frameRef.get();
                if (frame != null) {
                    frame.dispose();
                }
            });
        }
    }

    @Test
    void selectsAnAtLeastCoreProfileContext() throws Exception {
        assumeGLXIsSelected();

        GLData data = new GLData();
        data.majorVersion = 3;
        data.minorVersion = 2;
        data.profile = GLData.Profile.CORE;
        data.versionPolicy = GLData.VersionPolicy.AT_LEAST;

        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<TestCanvas> canvasRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                TestCanvas canvas = new TestCanvas(data);
                canvas.setPreferredSize(new Dimension(320, 240));

                JFrame frame = new JFrame("Linux GLX context version policy test");
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
                assertTrue(GLUtil.atLeast32(
                        canvas.effective.majorVersion, canvas.effective.minorVersion));
                assertEquals(GLData.Profile.CORE, canvas.effective.profile);
                assertEquals(GLData.VersionPolicy.AT_LEAST, canvas.effective.versionPolicy);
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

            try {
                SwingUtilities.invokeAndWait(() -> {
                    TestCanvas canvas = canvasRef.get();
                    canvas.render();
                    assertTrue(canvas.platformCanvas instanceof PlatformLinuxGLCanvas);
                    assertTrue(canvas.effective.sampleBuffers > 0);
                    assertTrue(canvas.effective.samples >= 4);
                });
            } catch (Exception e) {
                String unsupportedReason = findUnsupportedMultisamplingReason(e);
                if (unsupportedReason != null) {
                    assumeTrue(false, "Native 4x MSAA is unavailable: " + unsupportedReason);
                }
                throw e;
            }
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

    private static String findUnsupportedMultisamplingReason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("No supported framebuffer configurations found")) {
                return message;
            }
        }
        return null;
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

    private static final class ErrorCheckingCanvas extends AWTGLCanvas {
        private static final long serialVersionUID = 1L;

        private int errorBeforeCapabilities;

        private ErrorCheckingCanvas(GLData data) {
            super(data);
        }

        @Override
        public void initGL() {
            long glGetError = GL.getFunctionProvider().getFunctionAddress("glGetError");
            errorBeforeCapabilities = JNI.callI(glGetError);
            GL.createCapabilities();
        }

        @Override
        public void paintGL() {
            swapBuffers();
        }
    }
}
