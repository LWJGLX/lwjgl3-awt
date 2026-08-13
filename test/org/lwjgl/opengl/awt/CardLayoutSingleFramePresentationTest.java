package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.lwjgl.system.Platform;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Robot;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL.createCapabilities;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;

class CardLayoutSingleFramePresentationTest {

    private static final String PANEL_CARD = "panel";
    private static final String CANVAS_CARD = "canvas";
    private static final int TRANSITIONS = 30;
    private static final long PIXEL_TIMEOUT_MILLIS = 5_000L;

    @Test
    @Timeout(60)
    @EnabledForRobotScreenCapture
    void exposeRequestsOneFrameWithoutTimedRetries() throws Exception {
        GLData data = new GLData();
        data.samples = 0;
        if (Platform.get() != Platform.LINUX) {
            data.swapInterval = 0;
        }

        ExecutorService renderThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "card-layout-gl-renderer");
            thread.setDaemon(true);
            return thread;
        });
        try {
            UiState ui = createUi(data, renderThread);
            try {
                SingleFrameCanvas canvas = ui.canvas;
                Robot robot = new Robot();
                robot.setAutoDelay(5);
                robot.waitForIdle();
                robot.delay(100);
                robot.mouseMove(0, 0);
                Point sample = contentCenter(ui.content);
                awaitColor(robot, sample, Color.BLUE, "initial panel");

                for (int transition = 0; transition < TRANSITIONS; transition++) {
                    final boolean red = (transition & 1) == 0;
                    CompletableFuture<Void> render = canvas.armFrame(red);

                    SwingUtilities.invokeAndWait(() -> ui.cards.show(ui.content, CANVAS_CARD));
                    render.get(10, TimeUnit.SECONDS);
                    assertEquals(transition + 1, canvas.getPaintCount(),
                            "Each showing transition must render exactly once");

                    Color expected = red ? Color.RED : Color.GREEN;
                    awaitColor(robot, sample, expected, "canvas transition " + transition);
                    assertColorRemains(robot, sample, expected, "canvas transition " + transition);

                    SwingUtilities.invokeAndWait(() -> ui.cards.show(ui.content, PANEL_CARD));
                    awaitColor(robot, sample, Color.BLUE, "panel transition " + transition);
                }
                assertEquals(TRANSITIONS, canvas.getPaintCount(),
                        "Additional AWT paint events must not trigger unrequested OpenGL renders");
            } finally {
                dispose(ui.frame);
            }
        } finally {
            renderThread.shutdownNow();
            renderThread.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static UiState createUi(GLData data, ExecutorService renderThread)
            throws InvocationTargetException, InterruptedException {
        UiState[] result = new UiState[1];
        SwingUtilities.invokeAndWait(() -> {
            SingleFrameCanvas canvas = new SingleFrameCanvas(data, renderThread);
            CardLayout cards = new CardLayout();
            JPanel content = new JPanel(cards);
            JPanel panel = new JPanel();
            panel.setBackground(Color.BLUE);
            JPanel canvasCard = new JPanel(new BorderLayout());
            canvasCard.add(canvas, BorderLayout.CENTER);
            content.add(panel, PANEL_CARD);
            content.add(canvasCard, CANVAS_CARD);

            JFrame frame = new JFrame("CardLayout single-frame presentation");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(content);
            content.setPreferredSize(new Dimension(320, 240));
            cards.show(content, PANEL_CARD);
            frame.pack();
            if (frame.isAlwaysOnTopSupported()) {
                frame.setAlwaysOnTop(true);
            }
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
            result[0] = new UiState(frame, content, cards, canvas);
        });
        return result[0];
    }

    private static Point contentCenter(JPanel content)
            throws InvocationTargetException, InterruptedException {
        Point[] result = new Point[1];
        SwingUtilities.invokeAndWait(() -> {
            Point location = content.getLocationOnScreen();
            result[0] = new Point(
                    location.x + content.getWidth() / 2,
                    location.y + content.getHeight() / 2);
        });
        return result[0];
    }

    private static void awaitColor(Robot robot, Point sample, Color expected, String phase)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PIXEL_TIMEOUT_MILLIS);
        Color actual = robot.getPixelColor(sample.x, sample.y);
        while (!matches(actual, expected) && System.nanoTime() < deadline) {
            Thread.sleep(20L);
            actual = robot.getPixelColor(sample.x, sample.y);
        }
        Color lastActual = actual;
        assertTrue(matches(lastActual, expected), () -> phase + " never presented " + colorName(expected)
                + "; last sampled pixel was " + colorName(lastActual));
    }

    private static void assertColorRemains(Robot robot, Point sample, Color expected, String phase)
            throws InterruptedException {
        for (int sampleIndex = 0; sampleIndex < 5; sampleIndex++) {
            Thread.sleep(30L);
            Color actual = robot.getPixelColor(sample.x, sample.y);
            assertTrue(matches(actual, expected), () -> phase + " did not remain visible; expected "
                    + colorName(expected) + " but sampled " + colorName(actual));
        }
    }

    private static boolean matches(Color actual, Color expected) {
        if (expected == Color.RED) {
            return actual.getRed() > 128
                    && actual.getRed() > actual.getGreen() * 2
                    && actual.getRed() > actual.getBlue() * 2;
        }
        if (expected == Color.GREEN) {
            return actual.getGreen() > 128
                    && actual.getGreen() > actual.getRed() * 2
                    && actual.getGreen() > actual.getBlue() * 2;
        }
        return actual.getBlue() > 128
                && actual.getBlue() > actual.getRed() * 2
                && actual.getBlue() > actual.getGreen() * 2;
    }

    private static String colorName(Color color) {
        return "rgb(" + color.getRed() + ", " + color.getGreen() + ", " + color.getBlue() + ")";
    }

    private static void dispose(JFrame frame) throws InvocationTargetException, InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            frame.dispose();
        } else {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    private static final class UiState {
        private final JFrame frame;
        private final JPanel content;
        private final CardLayout cards;
        private final SingleFrameCanvas canvas;

        private UiState(JFrame frame, JPanel content, CardLayout cards, SingleFrameCanvas canvas) {
            this.frame = frame;
            this.content = content;
            this.cards = cards;
            this.canvas = canvas;
        }
    }

    private static final class SingleFrameCanvas extends AWTGLCanvas {
        private final ExecutorService renderThread;
        private final AtomicInteger paintCount = new AtomicInteger();
        private final AtomicReference<CompletableFuture<Void>> pendingFrame = new AtomicReference<>();
        private volatile boolean red;

        private SingleFrameCanvas(GLData data, ExecutorService renderThread) {
            super(data);
            this.renderThread = renderThread;
            setBackground(Color.BLACK);
        }

        private CompletableFuture<Void> armFrame(boolean red) {
            this.red = red;
            CompletableFuture<Void> frame = new CompletableFuture<>();
            if (!pendingFrame.compareAndSet(null, frame)) {
                throw new IllegalStateException("A frame is already pending");
            }
            return frame;
        }

        private int getPaintCount() {
            return paintCount.get();
        }

        @Override
        public void initGL() {
            createCapabilities();
        }

        @Override
        protected void requestRender() {
            CompletableFuture<Void> frame = pendingFrame.getAndSet(null);
            if (frame == null) {
                return;
            }
            try {
                renderThread.execute(() -> {
                    try {
                        render();
                        frame.complete(null);
                    } catch (Throwable failure) {
                        frame.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException failure) {
                frame.completeExceptionally(failure);
            }
        }

        @Override
        public void paintGL() {
            if (red) {
                glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
            } else {
                glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
            }
            glClear(GL_COLOR_BUFFER_BIT);
            swapBuffers();
            paintCount.incrementAndGet();
        }
    }
}
