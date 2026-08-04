package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;

import java.awt.AWTException;
import java.awt.Canvas;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AWTGLCanvasLifecycleTest {

    @Test
    void initializesFramebufferSizeFromDrawingSurfaceBeforeInitGL() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.framebufferWidth = 640;
        platform.framebufferHeight = 480;
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            public void initGL() {
                assertEquals(640, getFramebufferWidth());
                assertEquals(480, getFramebufferHeight());
            }
        };

        canvas.render();
    }

    @Test
    void refreshesFramebufferSizeFromDrawingSurfaceBeforeEveryRender() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.framebufferWidth = 320;
        platform.framebufferHeight = 240;
        TestCanvas canvas = new TestCanvas(platform);

        canvas.render();
        assertEquals(320, canvas.getFramebufferWidth());
        assertEquals(240, canvas.getFramebufferHeight());

        platform.framebufferWidth = 900;
        platform.framebufferHeight = 600;
        canvas.render();
        assertEquals(900, canvas.getFramebufferWidth());
        assertEquals(600, canvas.getFramebufferHeight());
    }

    @Test
    void refreshesFallbackFramebufferSizeWhenGraphicsScaleChanges() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.reportsFramebufferSize = false;
        ScaledTestCanvas canvas = new ScaledTestCanvas(platform, 201, 101, 1.5, 1.5);

        canvas.render();
        assertEquals(302, canvas.getFramebufferWidth());
        assertEquals(152, canvas.getFramebufferHeight());

        canvas.graphicsConfiguration = new TestGraphicsConfiguration(2.0, 2.0);
        canvas.render();
        assertEquals(402, canvas.getFramebufferWidth());
        assertEquals(202, canvas.getFramebufferHeight());
    }

    @Test
    void disposeCanvasDeletesContextBeforeDrawingSurface() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        TestCanvas canvas = new TestCanvas(platform);
        canvas.context = 42L;
        canvas.initCalled = true;

        canvas.disposeCanvas();

        assertEquals(Arrays.asList("delete:42", "dispose"), platform.calls);
        assertEquals(0L, canvas.context);
        assertFalse(canvas.initCalled);
    }

    @Test
    void disposeCanvasStillResetsStateWhenContextDeletionFails() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.deleteFailure = new IllegalStateException("delete failed");
        TestCanvas canvas = new TestCanvas(platform);
        canvas.context = 42L;
        canvas.initCalled = true;

        assertThrows(IllegalStateException.class, canvas::disposeCanvas);

        assertEquals(Arrays.asList("delete:42", "dispose"), platform.calls);
        assertEquals(0L, canvas.context);
        assertFalse(canvas.initCalled);
    }

    @Test
    void disposeCanvasSkipsDeletionWithoutAContext() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        TestCanvas canvas = new TestCanvas(platform);

        canvas.disposeCanvas();

        assertEquals(Arrays.asList("dispose"), platform.calls);
    }

    @Test
    void renderKeepsContextOperationsInsideDrawingSurfaceLock() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            public void initGL() {
                platform.calls.add("init");
            }

            @Override
            public void paintGL() {
                platform.calls.add("paint");
                swapBuffers();
            }
        };

        canvas.render();

        assertEquals(Arrays.asList("create", "lock", "makeCurrent:42", "init", "paint",
                "swapBuffers", "makeCurrent:0", "unlock"), platform.calls);
    }

    @Test
    void renderUnlocksDrawingSurfaceWhenPaintingFails() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            public void paintGL() {
                throw new IllegalStateException("paint failed");
            }
        };

        assertThrows(IllegalStateException.class, canvas::render);

        assertEquals(Arrays.asList("create", "lock", "makeCurrent:42", "makeCurrent:0", "unlock"),
                platform.calls);
    }

    @Test
    void renderDoesNotInvokeCallbacksWhenMakingContextCurrentFails() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.makeCurrentFailureContext = 42L;
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            public void initGL() {
                platform.calls.add("init");
            }

            @Override
            public void paintGL() {
                platform.calls.add("paint");
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class, canvas::render);

        assertEquals("Failed to make the OpenGL context current", failure.getMessage());
        assertEquals(Arrays.asList("create", "lock", "makeCurrent:42", "makeCurrent:0", "unlock"),
                platform.calls);
    }

    @Test
    void renderUnlocksDrawingSurfaceWhenClearingCurrentFails() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.makeCurrentFailureContext = 0L;
        TestCanvas canvas = new TestCanvas(platform);

        IllegalStateException failure = assertThrows(IllegalStateException.class, canvas::render);

        assertEquals("Failed to clear the current OpenGL context", failure.getMessage());
        assertEquals(Arrays.asList("create", "lock", "makeCurrent:42", "makeCurrent:0", "unlock"),
                platform.calls);
    }

    @Test
    void renderPreservesCallbackFailureWhenCleanupAlsoFails() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.makeCurrentFailureContext = 0L;
        IllegalStateException paintFailure = new IllegalStateException("paint failed");
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            public void paintGL() {
                throw paintFailure;
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class, canvas::render);

        assertSame(paintFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("Failed to clear the current OpenGL context", failure.getSuppressed()[0].getMessage());
        assertEquals(Arrays.asList("create", "lock", "makeCurrent:42", "makeCurrent:0", "unlock"),
                platform.calls);
    }

    @Test
    void renderUnlocksDrawingSurfaceWhenMakingContextCurrentThrows() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.makeCurrentExceptionContext = 42L;
        TestCanvas canvas = new TestCanvas(platform);

        IllegalStateException failure = assertThrows(IllegalStateException.class, canvas::render);

        assertEquals("make current failed", failure.getMessage());
        assertEquals(Arrays.asList("create", "lock", "makeCurrent:42", "makeCurrent:0", "unlock"),
                platform.calls);
    }

    @Test
    void renderUnlocksDrawingSurfaceWhenFramebufferSizeQueryThrows() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.framebufferSizeFailure = new IllegalStateException("size query failed");
        TestCanvas canvas = new TestCanvas(platform);

        IllegalStateException failure = assertThrows(IllegalStateException.class, canvas::render);

        assertEquals("size query failed", failure.getMessage());
        assertEquals(Arrays.asList("create", "lock", "makeCurrent:42", "makeCurrent:0", "unlock"),
                platform.calls);
    }

    @Test
    void disposeCanvasWaitsForInFlightRender() throws Exception {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        CountDownLatch paintStarted = new CountDownLatch(1);
        CountDownLatch finishPaint = new CountDownLatch(1);
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            public void paintGL() {
                platform.calls.add("paint:start");
                paintStarted.countDown();
                try {
                    if (!finishPaint.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to finish paintGL");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while painting", e);
                }
                platform.calls.add("paint:end");
            }
        };
        AtomicReference<Throwable> renderFailure = new AtomicReference<>();
        AtomicReference<Throwable> disposeFailure = new AtomicReference<>();

        Thread renderer = new Thread(() -> runAndRecordFailure(canvas::render, renderFailure), "lifecycle-renderer");
        renderer.start();
        assertTrue(paintStarted.await(5, TimeUnit.SECONDS), "Rendering did not reach paintGL");

        CountDownLatch disposeStarted = new CountDownLatch(1);
        Thread disposer = new Thread(() -> {
            disposeStarted.countDown();
            runAndRecordFailure(canvas::disposeCanvas, disposeFailure);
        }, "lifecycle-disposer");
        disposer.start();
        assertTrue(disposeStarted.await(5, TimeUnit.SECONDS), "Disposal did not start");

        boolean deletedDuringRender;
        try {
            deletedDuringRender = platform.deleteCalled.await(250, TimeUnit.MILLISECONDS);
        } finally {
            finishPaint.countDown();
            renderer.join(TimeUnit.SECONDS.toMillis(5));
            disposer.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(deletedDuringRender, "Context was deleted while paintGL was still running");
        assertFalse(renderer.isAlive(), "Rendering thread did not exit");
        assertFalse(disposer.isAlive(), "Disposal thread did not exit");
        assertNull(renderFailure.get(), "Rendering failed");
        assertNull(disposeFailure.get(), "Disposal failed");
        assertEquals(Arrays.asList("create", "lock", "makeCurrent:42", "paint:start", "paint:end",
                "makeCurrent:0", "unlock", "delete:42", "dispose"), platform.calls);
    }

    @Test
    void lifecycleLocksDoNotSerializeDifferentCanvases() throws Exception {
        CountDownLatch firstPaintStarted = new CountDownLatch(1);
        CountDownLatch finishFirstPaint = new CountDownLatch(1);
        CountDownLatch secondPaintFinished = new CountDownLatch(1);
        TestCanvas firstCanvas = new TestCanvas(new RecordingPlatformCanvas()) {
            @Override
            public void paintGL() {
                firstPaintStarted.countDown();
                try {
                    if (!finishFirstPaint.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to finish first canvas");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while painting first canvas", e);
                }
            }
        };
        TestCanvas secondCanvas = new TestCanvas(new RecordingPlatformCanvas()) {
            @Override
            public void paintGL() {
                secondPaintFinished.countDown();
            }
        };
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread firstRenderer = new Thread(
                () -> runAndRecordFailure(firstCanvas::render, firstFailure), "first-canvas-renderer");
        Thread secondRenderer = new Thread(
                () -> runAndRecordFailure(secondCanvas::render, secondFailure), "second-canvas-renderer");
        firstRenderer.start();
        assertTrue(firstPaintStarted.await(5, TimeUnit.SECONDS), "First canvas did not reach paintGL");

        secondRenderer.start();
        boolean renderedConcurrently;
        try {
            renderedConcurrently = secondPaintFinished.await(1, TimeUnit.SECONDS);
        } finally {
            finishFirstPaint.countDown();
            firstRenderer.join(TimeUnit.SECONDS.toMillis(5));
            secondRenderer.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertTrue(renderedConcurrently, "A different canvas was blocked by the first canvas's lifecycle lock");
        assertFalse(firstRenderer.isAlive(), "First rendering thread did not exit");
        assertFalse(secondRenderer.isAlive(), "Second rendering thread did not exit");
        assertNull(firstFailure.get(), "First canvas rendering failed");
        assertNull(secondFailure.get(), "Second canvas rendering failed");
    }

    private static void runAndRecordFailure(Runnable action, AtomicReference<Throwable> failure) {
        try {
            action.run();
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    private static class TestCanvas extends AWTGLCanvas {
        TestCanvas(PlatformGLCanvas platformCanvas) {
            this.platformCanvas = platformCanvas;
        }

        @Override
        public void initGL() {
        }

        @Override
        public void paintGL() {
        }
    }

    private static final class ScaledTestCanvas extends TestCanvas {
        private final int width;
        private final int height;
        private GraphicsConfiguration graphicsConfiguration;

        ScaledTestCanvas(PlatformGLCanvas platformCanvas, int width, int height, double scaleX, double scaleY) {
            super(platformCanvas);
            this.width = width;
            this.height = height;
            this.graphicsConfiguration = new TestGraphicsConfiguration(scaleX, scaleY);
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public GraphicsConfiguration getGraphicsConfiguration() {
            return graphicsConfiguration;
        }
    }

    private static final class TestGraphicsConfiguration extends GraphicsConfiguration {
        private final AffineTransform transform;

        TestGraphicsConfiguration(double scaleX, double scaleY) {
            transform = AffineTransform.getScaleInstance(scaleX, scaleY);
        }

        @Override
        public GraphicsDevice getDevice() {
            return null;
        }

        @Override
        public ColorModel getColorModel() {
            return ColorModel.getRGBdefault();
        }

        @Override
        public ColorModel getColorModel(int transparency) {
            return transparency == Transparency.OPAQUE ? ColorModel.getRGBdefault() : null;
        }

        @Override
        public AffineTransform getDefaultTransform() {
            return new AffineTransform(transform);
        }

        @Override
        public AffineTransform getNormalizingTransform() {
            return new AffineTransform();
        }

        @Override
        public Rectangle getBounds() {
            return new Rectangle();
        }
    }

    private static class RecordingPlatformCanvas implements PlatformGLCanvas {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch deleteCalled = new CountDownLatch(1);
        RuntimeException deleteFailure;
        RuntimeException framebufferSizeFailure;
        long makeCurrentFailureContext = Long.MIN_VALUE;
        long makeCurrentExceptionContext = Long.MIN_VALUE;
        boolean reportsFramebufferSize = true;
        int framebufferWidth;
        int framebufferHeight;

        @Override
        public long create(Canvas canvas, GLData data, GLData effective) {
            calls.add("create");
            return 42L;
        }

        @Override
        public boolean deleteContext(long context) {
            calls.add("delete:" + context);
            deleteCalled.countDown();
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            return true;
        }

        @Override
        public boolean makeCurrent(long context) {
            calls.add("makeCurrent:" + context);
            if (context == makeCurrentExceptionContext) {
                throw new IllegalStateException("make current failed");
            }
            return context != makeCurrentFailureContext;
        }

        @Override
        public boolean isCurrent(long context) {
            return false;
        }

        @Override
        public boolean swapBuffers() {
            calls.add("swapBuffers");
            return true;
        }

        @Override
        public boolean delayBeforeSwapNV(float seconds) {
            return false;
        }

        @Override
        public boolean getFramebufferSize(int[] size) {
            if (framebufferSizeFailure != null) {
                throw framebufferSizeFailure;
            }
            if (!reportsFramebufferSize) {
                return false;
            }
            size[0] = framebufferWidth;
            size[1] = framebufferHeight;
            return true;
        }

        @Override
        public void lock() throws AWTException {
            calls.add("lock");
        }

        @Override
        public void unlock() throws AWTException {
            calls.add("unlock");
        }

        @Override
        public void dispose() {
            calls.add("dispose");
        }
    }
}
