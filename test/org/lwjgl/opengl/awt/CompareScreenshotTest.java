package org.lwjgl.opengl.awt;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

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
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.github.romankh3.image.comparison.ImageComparison;
import org.junit.jupiter.api.Assertions;
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
    @Test
    void canvasInContentPane(TestInfo testInfo) throws AWTException, IOException {
        JFrame frame = new JFrame("AWT test");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        GLData data = new GLData();
        data.samples = 0;
        data.swapInterval = 0;
        AWTGLCanvas canvas;
        frame.add(canvas = new AWTGLCanvas(data) {
            private static final long serialVersionUID = 1L;

            public void initGL() {
                System.out.println("OpenGL version: " + effective.majorVersion + "." + effective.minorVersion + " (Profile: " + effective.profile + ")");
                createCapabilities();
                glClearColor(0.3f, 0.4f, 0.5f, 1);
            }

            public void paintGL() {
                int w = getWidth();
                int h = getHeight();
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
        }, BorderLayout.CENTER);

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

    private void compareWithScreenshot(TestInfo testInfo, Window window, AWTGLCanvas canvas) throws AWTException, IOException {
        AtomicInteger renderCount = new AtomicInteger(0);

        AtomicReference<Exception> renderException = new AtomicReference<>();

        Runnable renderLoop = new Runnable() {
            public void run() {
                if (!canvas.isValid())
                    return;
                if (renderException.get() != null) {
                    return;
                }
                renderCount.incrementAndGet();
                try {
                    if (renderCount.get() < 10) {
                        canvas.render();
                    }
                } catch (Exception e) {
                    renderException.set(e);
                }
                SwingUtilities.invokeLater(this);
            }
        };
        SwingUtilities.invokeLater(renderLoop);

        // Wait until we definitely have been rendered
        while (renderException.get() == null && renderCount.get() < 10) {
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

        ImageIO.write(background, "png", new File(
                System.getProperty("os.name") + "_" +
                        testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_" +
                        testInfo.getTestMethod().map(Method::getName).orElse("unknown") + ".png"));

        if (renderException.get() != null) {
            renderException.get().printStackTrace();
            throw new RuntimeException(renderException.get());
        }

        BufferedImage expectedImage = ImageIO.read(getClass().getResource("/" + testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_" +
                testInfo.getTestMethod().map(Method::getName).orElse("unknown") + ".png"));

        File resultDestination = new File(
                System.getProperty("os.name") + "_" +
                        testInfo.getTestClass().map(Class::getSimpleName).orElse("unknown") + "_" +
                        testInfo.getTestMethod().map(Method::getName).orElse("unknown") + "_diff.png");

        //Create ImageComparison object for it.
        ImageComparison imageComparison = new ImageComparison(expectedImage, background, resultDestination);
        //Mac OS has round corners in the bottom, so we need to ignore a few pixels
        imageComparison.setAllowingPercentOfDifferentPixels(0.01d);
        Assertions.assertTrue(imageComparison.compareImages().getDifferencePercent() < 0.1f);
    }


}
