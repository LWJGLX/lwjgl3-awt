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
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.github.romankh3.image.comparison.ImageComparison;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.lwjgl.opengl.GL.createCapabilities;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glColor3f;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glVertex2f;
import static org.lwjgl.opengl.GL11.glViewport;

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
    void setup(TestInfo testInfo) {
        frame = new JFrame(testInfo.getDisplayName());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }

    @AfterEach
    void tearDown() {
        if (frame != null) {
            frame.dispose();
            frame = null;
        }
    }

    @Test
    void canvasInContentPane(TestInfo testInfo) throws AWTException, IOException {
        frame.setLayout(new BorderLayout());
        GLData data = new GLData();
        data.samples = 0;
        data.swapInterval = 0;
        AWTGLCanvas canvas = new DemoCanvas(data);
        frame.add(canvas, BorderLayout.CENTER);
        canvas.setPreferredSize(new Dimension(600, 600));

        frame.add(new JPanel() {{
            setBackground(Color.BLUE);
        }}, BorderLayout.NORTH);
        frame.add(new JPanel() {{
            setBackground(Color.RED);
        }}, BorderLayout.SOUTH);
        frame.add(new JPanel() {{
            setBackground(Color.GREEN);
        }}, BorderLayout.EAST);
        frame.add(new JPanel() {{
            setBackground(Color.YELLOW);
        }}, BorderLayout.WEST);

        frame.pack();
        frame.setVisible(true);
        frame.transferFocus();

        compareWithScreenshot(testInfo, frame, canvas);
    }

    @Test
    void canvasInSplitPane(TestInfo testInfo) throws AWTException, IOException {
        frame.setLayout(new BorderLayout());
        GLData data = new GLData();
        data.samples = 0;
        data.swapInterval = 0;

        JSplitPane vert1 = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        JSplitPane vert2 = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        JSplitPane horiz = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        horiz.setLeftComponent(vert1);
        horiz.setRightComponent(vert2);

        DemoCanvas canvas1 = new DemoCanvas(data);
        canvas1.setPreferredSize(new Dimension(200, 200));
        vert1.setTopComponent(canvas1);

        DemoCanvas canvas2 = new DemoCanvas(data);
        canvas2.setPreferredSize(new Dimension(200, 200));
        vert1.setBottomComponent(canvas2);

        DemoCanvas canvas3 = new DemoCanvas(data);
        canvas3.setPreferredSize(new Dimension(200, 200));
        vert2.setTopComponent(canvas3);

        DemoCanvas canvas4 = new DemoCanvas(data);
        canvas4.setPreferredSize(new Dimension(200, 200));
        vert2.setBottomComponent(canvas4);

        frame.add(horiz, BorderLayout.CENTER);

        frame.add(new JPanel() {{
            setBackground(Color.BLUE);
        }}, BorderLayout.NORTH);
        frame.add(new JPanel() {{
            setBackground(Color.RED);
        }}, BorderLayout.SOUTH);
        frame.add(new JPanel() {{
            setBackground(Color.GREEN);
        }}, BorderLayout.EAST);
        frame.add(new JPanel() {{
            setBackground(Color.YELLOW);
        }}, BorderLayout.WEST);

        frame.pack();
        frame.setVisible(true);
        frame.transferFocus();

        compareWithScreenshot(testInfo, frame, canvas1, canvas2, canvas3, canvas4);
    }

    @Test
    void reAddCanvas(TestInfo testInfo) throws AWTException, IOException, InvocationTargetException, InterruptedException {
        frame.setLayout(new BorderLayout());
        GLData data = new GLData();
        data.samples = 0;
        data.swapInterval = 0;
        AWTGLCanvas canvas = new DemoCanvas(data);
        frame.add(canvas, BorderLayout.CENTER);
        canvas.setPreferredSize(new Dimension(600, 600));

        frame.add(new JPanel() {{
            setBackground(Color.BLUE);
        }}, BorderLayout.NORTH);
        frame.add(new JPanel() {{
            setBackground(Color.RED);
        }}, BorderLayout.SOUTH);
        frame.add(new JPanel() {{
            setBackground(Color.GREEN);
        }}, BorderLayout.EAST);
        frame.add(new JPanel() {{
            setBackground(Color.YELLOW);
        }}, BorderLayout.WEST);

        frame.pack();
        frame.setVisible(true);
        frame.transferFocus();

        // make sure the underlying OpenGL Context is created
        SwingUtilities.invokeAndWait(canvas::render);

        frame.pack();

        compareWithScreenshot(testInfo, frame, canvas);

        // remove
        frame.remove(canvas);
        compareWithScreenshot(testInfo, frame);

        // re-add
        frame.add(canvas, BorderLayout.CENTER);
        compareWithScreenshot(testInfo, frame, canvas);
    }

    @Test
    void hideAndShowCanvas(TestInfo testInfo) throws AWTException, IOException, InvocationTargetException, InterruptedException {
        JFrame frame = new JFrame(testInfo.getDisplayName());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        GLData data = new GLData();
        data.samples = 0;
        data.swapInterval = 0;
        AWTGLCanvas canvas = new DemoCanvas(data);
        frame.add(canvas, BorderLayout.CENTER);
        canvas.setPreferredSize(new Dimension(600, 600));

        frame.add(new JPanel() {{
            setBackground(Color.BLUE);
        }}, BorderLayout.NORTH);
        frame.add(new JPanel() {{
            setBackground(Color.RED);
        }}, BorderLayout.SOUTH);
        frame.add(new JPanel() {{
            setBackground(Color.GREEN);
        }}, BorderLayout.EAST);
        frame.add(new JPanel() {{
            setBackground(Color.YELLOW);
        }}, BorderLayout.WEST);

        frame.pack();
        frame.setVisible(true);
        frame.transferFocus();

        // make sure the underlying OpenGL Context is created
        SwingUtilities.invokeAndWait(canvas::render);

        frame.pack();

        compareWithScreenshot(testInfo, frame, canvas);

        // hide
        canvas.setVisible(false);
        compareWithScreenshot(testInfo, frame);

        // show
        canvas.setVisible(true);
        compareWithScreenshot(testInfo, frame, canvas);
    }

    private void compareWithScreenshot(TestInfo testInfo, Window window, AWTGLCanvas... canvases) throws AWTException, IOException {
        AtomicBoolean finished = new AtomicBoolean(false);

        AtomicReference<Exception> renderException = new AtomicReference<>();

        Runnable renderLoop = new Runnable() {
            private int renderCount = 0;

            long startTime = System.currentTimeMillis();

            public void run() {
                for (AWTGLCanvas canvas : canvases) {
                    if (!canvas.isValid())
                        continue;
                    renderCount++;
                    try {
                        if (renderCount < 10) {
                            canvas.render();
                        }
                    } catch (Exception e) {
                        renderException.set(e);
                    }
                }

                // Wait until we definitely have been rendered, or a timeout of 20 seconds occurred
                if (!(canvases.length > 0 && renderException.get() == null && renderCount < 10 && System.currentTimeMillis() - startTime < 20_000)) {
                    finished.set(true);
                }


                if (!finished.get()) {
                    SwingUtilities.invokeLater(this);
                }
            }
        };
        SwingUtilities.invokeLater(renderLoop);


        while (!finished.get()) {
            Thread.yield();
        }
        Robot rbt = new Robot();

        // Calculate inner window area
        Rectangle frameBounds = window.getBounds();
        Insets insets = window.getInsets();
        frameBounds.y += insets.top;
        frameBounds.height -= (insets.top + insets.bottom);
        frameBounds.x += insets.left;
        frameBounds.width -= (insets.left + insets.right);

        window.toFront();
        BufferedImage background = rbt.createScreenCapture(frameBounds);
        // Some Operating Systems have a window open/close animation, so we wait until the screenshot is stable
        while (true) {
            BufferedImage s = rbt.createScreenCapture(frameBounds);
            if (new ImageComparison(background, s).compareImages().getDifferencePercent() == 0) {
                break;
            } else {
                background = s;
            }
        }

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

        if (renderException.get() != null) {
            renderException.get().printStackTrace();
            throw new RuntimeException(renderException.get());
        }

        BufferedImage expectedImage = ImageIO.read(getClass().getResource("/" + testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_" +
                testInfo.getTestMethod().map(Method::getName).orElse("unknown") + screenShotSuffix + ".png"));

        File resultDestination = new File(
                new File("target"),
                System.getProperty("os.name") + "_" +
                        testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_" +
                        testInfo.getTestMethod().map(Method::getName).orElse("unknown") + screenShotSuffix + "_diff.png");

        //Create ImageComparison object for it.
        ImageComparison imageComparison = new ImageComparison(expectedImage, background, resultDestination);
        //Mac OS has round corners in the bottom, so we need to ignore a few pixels
        imageComparison.setAllowingPercentOfDifferentPixels(0.02d);
        Assertions.assertTrue(imageComparison.compareImages().getDifferencePercent() < 0.1f);
    }

    private static class DemoCanvas extends AWTGLCanvas {
        public DemoCanvas(GLData data) {
            super(data);
        }

        public void initGL() {
            System.out.println("OpenGL version: " + effective.majorVersion + "." + effective.minorVersion + " (Profile: " + effective.profile + ")");
            createCapabilities();
            glClearColor(0.3f, 0.4f, 0.5f, 1);
        }

        public void paintGL() {
            int w = getFramebufferWidth();
            int h = getFramebufferHeight();
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
            swapBuffers();
        }
    }
}
