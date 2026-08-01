package org.lwjgl.opengl.awt;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Window;
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

    private void compareWithScreenshot(TestInfo testInfo, Window window, AWTGLCanvas... canvases) throws IOException {
        BufferedImage background = captureWindow(window, canvases);

        String screenShotSuffix = "";
        int screenShotIndex = screenShotIndexMap.compute(testInfo, (info, index) -> index == null ? 1 : index + 1);
        if (screenShotIndex > 1) {
            screenShotSuffix = "_" + screenShotIndex;
        }

        ImageIO.write(background, "png", new File(
                new File("target"),
                System.getProperty("os.name") + "_" +
                        testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_" +
                        testInfo.getTestMethod().map(Method::getName).orElse("unknown") + screenShotSuffix + ".png"));

        BufferedImage expectedImage = ImageIO.read(getClass().getResource("/" + testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_" +
                testInfo.getTestMethod().map(Method::getName).orElse("unknown") + screenShotSuffix + ".png"));

        File resultDestination = new File(
                new File("target"),
                System.getProperty("os.name") + "_" +
                        testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_" +
                        testInfo.getTestMethod().map(Method::getName).orElse("unknown") + screenShotSuffix + "_diff.png");

        //Create ImageComparison object for it.
        ImageComparison imageComparison = new ImageComparison(expectedImage, background, resultDestination);
        // Mac OS has rounded window corners, so ignore a few pixels at the edges.
        imageComparison.setAllowingPercentOfDifferentPixels(0.02d);
        Assertions.assertEquals(
                ImageComparisonState.MATCH,
                imageComparison.compareImages().getImageComparisonState());
    }

    private BufferedImage captureWindow(Window window, AWTGLCanvas... canvases) throws IOException {
        BufferedImage[] result = new BufferedImage[1];
        Runnable capture = () -> {
            Map<AWTGLCanvas, BufferedImage> canvasImages = new HashMap<>();
            for (AWTGLCanvas canvas : canvases) {
                if (!canvas.isValid()) {
                    throw new IllegalStateException("Cannot capture an invalid OpenGL canvas");
                }
                canvasImages.put(canvas, ((DemoCanvas) canvas).renderAndCaptureFramebuffer());
            }

            Insets insets = window.getInsets();
            int width = window.getWidth() - insets.left - insets.right;
            int height = window.getHeight() - insets.top - insets.bottom;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.translate(-insets.left, -insets.top);
                window.printAll(graphics);
                graphics.translate(insets.left, insets.top);

                for (Map.Entry<AWTGLCanvas, BufferedImage> entry : canvasImages.entrySet()) {
                    AWTGLCanvas canvas = entry.getKey();
                    Point location = SwingUtilities.convertPoint(canvas, 0, 0, window);
                    graphics.drawImage(entry.getValue(),
                            location.x - insets.left,
                            location.y - insets.top,
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
