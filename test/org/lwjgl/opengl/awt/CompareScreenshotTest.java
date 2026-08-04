package org.lwjgl.opengl.awt;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.github.romankh3.image.comparison.ImageComparison;
import com.github.romankh3.image.comparison.model.ImageComparisonState;
import org.lwjgl.BufferUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.lwjgl.system.Platform;

import static org.lwjgl.opengl.GL.createCapabilities;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DRAW_BUFFER;
import static org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL11.GL_READ_BUFFER;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glColor3f;
import static org.lwjgl.opengl.GL11.glDrawBuffer;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glVertex2f;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.GL_RENDERBUFFER_BINDING_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.GL_RENDERBUFFER_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glBindRenderbufferEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glCheckFramebufferStatusEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glDeleteFramebuffersEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glDeleteRenderbuffersEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glFramebufferRenderbufferEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glGenFramebuffersEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glGenRenderbuffersEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glRenderbufferStorageEXT;

public class CompareScreenshotTest {

    private static final long SCREEN_STABILITY_TIMEOUT_MILLIS = 5_000L;

    static {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (ClassNotFoundException | IllegalAccessException | UnsupportedLookAndFeelException | InstantiationException e) {
            throw new RuntimeException(e);
        }
    }

    private final Map<TestInfo, Integer> screenShotIndexMap = new HashMap<>();

    private JFrame frame;

    @BeforeEach
    void setup(TestInfo testInfo) throws InvocationTargetException, InterruptedException {
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame(testInfo.getDisplayName());
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        });
    }

    @AfterEach
    void tearDown() throws InvocationTargetException, InterruptedException {
        if (frame != null) {
            JFrame frameToDispose = frame;
            frame = null;
            SwingUtilities.invokeAndWait(frameToDispose::dispose);
        }
    }

    @Test
    void canvasInContentPane(TestInfo testInfo) throws IOException, InvocationTargetException, InterruptedException {
        DemoCanvas canvas = showSingleCanvas();
        compareWithScreenshot(testInfo, frame, canvas);
    }

    @Test
    void canvasInSplitPane(TestInfo testInfo) throws IOException, InvocationTargetException, InterruptedException {
        DemoCanvas[] canvases = showSplitCanvases();
        compareWithScreenshot(testInfo, frame, canvases);
    }

    @Test
    void reAddCanvas(TestInfo testInfo) throws IOException, InvocationTargetException, InterruptedException {
        DemoCanvas canvas = showSingleCanvas();

        // make sure the underlying OpenGL Context is created
        SwingUtilities.invokeAndWait(canvas::render);

        // remove and re-add
        SwingUtilities.invokeAndWait(() -> {
            frame.remove(canvas);
            frame.add(canvas, BorderLayout.CENTER);
            frame.pack();
        });

        compareWithScreenshot(testInfo, frame, canvas);
    }

    @Test
    void hideAndShowCanvas(TestInfo testInfo) throws IOException, InvocationTargetException, InterruptedException {
        DemoCanvas canvas = showSingleCanvas();

        // make sure the underlying OpenGL Context is created
        SwingUtilities.invokeAndWait(canvas::render);
        SwingUtilities.invokeAndWait(frame::pack);

        compareWithScreenshot(testInfo, frame, canvas);

        // hide
        SwingUtilities.invokeAndWait(() -> canvas.setVisible(false));
        compareWithScreenshot(testInfo, frame);

        // show
        SwingUtilities.invokeAndWait(() -> canvas.setVisible(true));
        compareWithScreenshot(testInfo, frame, canvas);
    }

    @Test
    @EnabledForRobotScreenCapture
    void robotCapturesCanvasInContentPane(TestInfo testInfo)
            throws IOException, InvocationTargetException, InterruptedException {
        DemoCanvas canvas = showSingleCanvas();
        compareDisplayedWithScreenshot(testInfo, "canvasInContentPane", frame, canvas);
    }

    @Test
    @EnabledForRobotScreenCapture
    void robotCapturesCanvasInSplitPane(TestInfo testInfo)
            throws IOException, InvocationTargetException, InterruptedException {
        DemoCanvas[] canvases = showSplitCanvases();
        compareDisplayedWithScreenshot(testInfo, "canvasInSplitPane", frame, canvases);
    }

    @Test
    @EnabledForRobotScreenCapture
    void robotCapturesReAddedCanvas(TestInfo testInfo)
            throws IOException, InvocationTargetException, InterruptedException {
        DemoCanvas canvas = showSingleCanvas();

        SwingUtilities.invokeAndWait(canvas::render);
        SwingUtilities.invokeAndWait(() -> {
            frame.remove(canvas);
            frame.add(canvas, BorderLayout.CENTER);
            frame.pack();
        });

        compareDisplayedWithScreenshot(testInfo, "reAddCanvas", frame, canvas);
    }

    @Test
    @EnabledForRobotScreenCapture
    void robotCapturesHiddenAndShownCanvas(TestInfo testInfo)
            throws IOException, InvocationTargetException, InterruptedException {
        DemoCanvas canvas = showSingleCanvas();

        SwingUtilities.invokeAndWait(canvas::render);
        SwingUtilities.invokeAndWait(frame::pack);

        compareDisplayedWithScreenshot(testInfo, "hideAndShowCanvas", frame, canvas);

        SwingUtilities.invokeAndWait(() -> canvas.setVisible(false));
        compareDisplayedWithScreenshot(testInfo, "hideAndShowCanvas", frame);

        SwingUtilities.invokeAndWait(() -> canvas.setVisible(true));
        compareDisplayedWithScreenshot(testInfo, "hideAndShowCanvas", frame, canvas);
    }

    private DemoCanvas showSingleCanvas() throws InvocationTargetException, InterruptedException {
        DemoCanvas[] result = new DemoCanvas[1];
        SwingUtilities.invokeAndWait(() -> {
            frame.setLayout(new BorderLayout());
            DemoCanvas canvas = new DemoCanvas(createGLData());
            canvas.setPreferredSize(new Dimension(600, 600));
            frame.add(canvas, BorderLayout.CENTER);
            addBorderPanels();
            showFrame();
            result[0] = canvas;
        });
        return result[0];
    }

    private DemoCanvas[] showSplitCanvases() throws InvocationTargetException, InterruptedException {
        DemoCanvas[] canvases = new DemoCanvas[4];
        SwingUtilities.invokeAndWait(() -> {
            frame.setLayout(new BorderLayout());
            JSplitPane topAndBottomLeft = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
            JSplitPane topAndBottomRight = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
            JSplitPane leftAndRight = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
            leftAndRight.setLeftComponent(topAndBottomLeft);
            leftAndRight.setRightComponent(topAndBottomRight);

            GLData data = createGLData();
            for (int i = 0; i < canvases.length; i++) {
                canvases[i] = new DemoCanvas(data);
                canvases[i].setPreferredSize(new Dimension(200, 200));
            }
            topAndBottomLeft.setTopComponent(canvases[0]);
            topAndBottomLeft.setBottomComponent(canvases[1]);
            topAndBottomRight.setTopComponent(canvases[2]);
            topAndBottomRight.setBottomComponent(canvases[3]);

            frame.add(leftAndRight, BorderLayout.CENTER);
            addBorderPanels();
            showFrame();
        });
        return canvases;
    }

    private static GLData createGLData() {
        GLData data = new GLData();
        data.samples = 0;
        data.swapInterval = 0;
        return data;
    }

    private void addBorderPanels() {
        frame.add(createPanel(Color.BLUE), BorderLayout.NORTH);
        frame.add(createPanel(Color.RED), BorderLayout.SOUTH);
        frame.add(createPanel(Color.GREEN), BorderLayout.EAST);
        frame.add(createPanel(Color.YELLOW), BorderLayout.WEST);
    }

    private static JPanel createPanel(Color color) {
        JPanel panel = new JPanel();
        panel.setBackground(color);
        return panel;
    }

    private void showFrame() {
        frame.pack();
        frame.setVisible(true);
        frame.transferFocus();
    }

    private void compareWithScreenshot(TestInfo testInfo, JFrame frame, AWTGLCanvas... canvases) throws IOException {
        String expectedMethodName = testInfo.getTestMethod().map(Method::getName).orElse("unknown");
        compareWithExpectedScreenshot(testInfo, expectedMethodName, captureContentPane(frame, canvases), false);
    }

    private void compareDisplayedWithScreenshot(
            TestInfo testInfo,
            String expectedMethodName,
            JFrame frame,
            AWTGLCanvas... canvases) throws IOException {
        compareWithExpectedScreenshot(
                testInfo,
                expectedMethodName,
                captureDisplayedContentPane(frame, canvases),
                true);
    }

    private void compareWithExpectedScreenshot(
            TestInfo testInfo,
            String expectedMethodName,
            BufferedImage background,
            boolean capturedFromDisplay) throws IOException {
        String actualMethodName = testInfo.getTestMethod().map(Method::getName).orElse("unknown");

        String screenShotSuffix = "";
        int screenShotIndex = screenShotIndexMap.compute(testInfo, (info, index) -> index == null ? 1 : index + 1);
        if (screenShotIndex > 1) {
            screenShotSuffix = "_" + screenShotIndex;
        }

        ImageIO.write(background, "png", new File(
                new File("target"),
                System.getProperty("os.name") + "_" +
                        testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_" +
                        actualMethodName + screenShotSuffix + ".png"));

        BufferedImage expectedImage = ImageIO.read(getClass().getResource("/"
                + testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_"
                + expectedMethodName + screenShotSuffix + ".png"));

        File resultDestination = new File(
                new File("target"),
                System.getProperty("os.name") + "_" +
                        testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_" +
                        actualMethodName + screenShotSuffix + "_diff.png");

        //Create ImageComparison object for it.
        ImageComparison imageComparison = new ImageComparison(expectedImage, background, resultDestination);
        if (capturedFromDisplay && Platform.get() == Platform.MACOSX) {
            // Robot observes color-managed and Retina-rasterized pixels, as well as the rounded pixels at the
            // bottom of a macOS window. Keep the permitted area small enough that misplaced or missing canvas
            // content still fails while tolerating those compositor-only differences.
            imageComparison.setPixelToleranceLevel(0.20d);
            imageComparison.setAllowingPercentOfDifferentPixels(0.10d);
        } else {
            // Ignore a small number of platform-dependent pixels at component boundaries.
            imageComparison.setAllowingPercentOfDifferentPixels(0.02d);
        }
        Assertions.assertEquals(
                ImageComparisonState.MATCH,
                imageComparison.compareImages().getImageComparisonState());
    }

    /**
     * Captures the pixels presented by the window system. This retains end-to-end
     * coverage of native canvas placement, visibility and buffer presentation on
     * platforms where Robot screen capture is enabled by {@link EnabledForRobotScreenCapture}.
     */
    private BufferedImage captureDisplayedContentPane(JFrame frame, AWTGLCanvas... canvases) throws IOException {
        final Rectangle[] captureBounds = new Rectangle[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                if (frame.isAlwaysOnTopSupported()) {
                    frame.setAlwaysOnTop(true);
                }
                frame.toFront();
                frame.requestFocus();
                for (AWTGLCanvas canvas : canvases) {
                    if (!canvas.isValid()) {
                        throw new IllegalStateException("Cannot capture an invalid OpenGL canvas");
                    }
                }

                Container contentPane = frame.getContentPane();
                Point location = contentPane.getLocationOnScreen();
                captureBounds[0] = new Rectangle(
                        location.x,
                        location.y,
                        contentPane.getWidth(),
                        contentPane.getHeight());
            });

            Robot robot = new Robot();
            robot.waitForIdle();
            robot.delay(100);
            BufferedImage previous = null;
            long deadline = System.currentTimeMillis() + SCREEN_STABILITY_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                // macOS applies native layer bounds asynchronously on AppKit's main thread. Rendering each pass
                // prevents a stable but blank first capture from winning before those bounds have taken effect.
                SwingUtilities.invokeAndWait(() -> {
                    for (AWTGLCanvas canvas : canvases) {
                        if (!canvas.isValid()) {
                            throw new IllegalStateException("Cannot capture an invalid OpenGL canvas");
                        }
                        canvas.render();
                    }
                });
                robot.waitForIdle();
                robot.delay(50);
                BufferedImage current = robot.createScreenCapture(captureBounds[0]);
                if (previous != null
                        && new ImageComparison(previous, current).compareImages().getDifferencePercent() == 0.0f) {
                    return current;
                }
                previous = current;
            }
            throw new IOException("The displayed test window did not become stable within "
                    + SCREEN_STABILITY_TIMEOUT_MILLIS + " ms");
        } catch (AWTException e) {
            throw new IOException("Failed to capture the displayed test window", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while capturing the displayed test window", e);
        } catch (InvocationTargetException e) {
            throw new IOException("Failed to prepare the displayed test window", e.getCause());
        }
    }

    private BufferedImage captureContentPane(JFrame frame, AWTGLCanvas... canvases) throws IOException {
        BufferedImage[] result = new BufferedImage[1];
        Runnable capture = () -> {
            Map<AWTGLCanvas, BufferedImage> canvasImages = new HashMap<>();
            for (AWTGLCanvas canvas : canvases) {
                if (!canvas.isValid()) {
                    throw new IllegalStateException("Cannot capture an invalid OpenGL canvas");
                }
                canvasImages.put(canvas, ((DemoCanvas) canvas).renderAndCaptureFramebuffer());
            }

            Container contentPane = frame.getContentPane();
            int width = contentPane.getWidth();
            int height = contentPane.getHeight();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                contentPane.printAll(graphics);

                for (Map.Entry<AWTGLCanvas, BufferedImage> entry : canvasImages.entrySet()) {
                    AWTGLCanvas canvas = entry.getKey();
                    Point location = SwingUtilities.convertPoint(canvas, 0, 0, contentPane);
                    graphics.drawImage(entry.getValue(),
                            location.x,
                            location.y,
                            canvas.getWidth(),
                            canvas.getHeight(),
                            null);
                }
            } finally {
                graphics.dispose();
            }
            result[0] = image;
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                capture.run();
            } else {
                SwingUtilities.invokeAndWait(capture);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while capturing the test window", e);
        } catch (InvocationTargetException e) {
            throw new IOException("Failed to capture the test window", e.getCause());
        }
        return result[0];
    }

    private static BufferedImage readFramebuffer(int width, int height) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glFinish();
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            int sourceY = height - y - 1;
            for (int x = 0; x < width; x++) {
                int offset = (sourceY * width + x) * 4;
                int red = pixels.get(offset) & 0xFF;
                int green = pixels.get(offset + 1) & 0xFF;
                int blue = pixels.get(offset + 2) & 0xFF;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private static class DemoCanvas extends AWTGLCanvas {
        private boolean captureRequested;
        private BufferedImage framebufferImage;

        public DemoCanvas(GLData data) {
            super(data);
        }

        BufferedImage renderAndCaptureFramebuffer() {
            captureRequested = true;
            framebufferImage = null;
            try {
                render();
                return getFramebufferImage();
            } finally {
                captureRequested = false;
            }
        }

        private BufferedImage getFramebufferImage() {
            if (framebufferImage == null) {
                throw new IllegalStateException("The OpenGL canvas has not been rendered");
            }
            return framebufferImage;
        }

        public void initGL() {
            System.out.println("OpenGL version: " + effective.majorVersion + "." + effective.minorVersion + " (Profile: " + effective.profile + ")");
            createCapabilities();
            glClearColor(0.3f, 0.4f, 0.5f, 1);
        }

        public void paintGL() {
            int w = getFramebufferWidth();
            int h = getFramebufferHeight();
            drawScene(w, h);
            if (captureRequested) {
                framebufferImage = captureOffscreen(getWidth(), getHeight());
            }
            swapBuffers();
        }

        private void drawScene(int w, int h) {
            float aspect = (float) w / h;
            double now = 100;
            float width = (float) Math.abs(Math.sin(now * 0.3));
            glClear(GL_COLOR_BUFFER_BIT);
            glViewport(0, 0, w, h);
            glBegin(GL_QUADS);
            glColor3f(0.4f, 0.6f, 0.8f);
            glVertex2f(-0.75f * width / aspect, 0.0f);
            glVertex2f(0, -0.75f);
            glVertex2f(+0.75f * width / aspect, 0);
            glVertex2f(0, +0.75f);
            glEnd();
        }

        private BufferedImage captureOffscreen(int width, int height) {
            int previousFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING_EXT);
            int previousRenderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING_EXT);
            int previousDrawBuffer = glGetInteger(GL_DRAW_BUFFER);
            int previousReadBuffer = glGetInteger(GL_READ_BUFFER);
            int framebuffer = glGenFramebuffersEXT();
            int colorBuffer = glGenRenderbuffersEXT();
            try {
                glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, framebuffer);
                glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, colorBuffer);
                glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT, GL_RGBA8, width, height);
                glFramebufferRenderbufferEXT(
                        GL_FRAMEBUFFER_EXT,
                        GL_COLOR_ATTACHMENT0_EXT,
                        GL_RENDERBUFFER_EXT,
                        colorBuffer);
                int status = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
                if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
                    throw new IllegalStateException("Incomplete screenshot framebuffer: 0x" + Integer.toHexString(status));
                }

                glDrawBuffer(GL_COLOR_ATTACHMENT0_EXT);
                glReadBuffer(GL_COLOR_ATTACHMENT0_EXT);
                drawScene(width, height);
                return readFramebuffer(width, height);
            } finally {
                glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, previousFramebuffer);
                glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, previousRenderbuffer);
                glDrawBuffer(previousDrawBuffer);
                glReadBuffer(previousReadBuffer);
                glDeleteRenderbuffersEXT(colorBuffer);
                glDeleteFramebuffersEXT(framebuffer);
            }
        }
    }
}
