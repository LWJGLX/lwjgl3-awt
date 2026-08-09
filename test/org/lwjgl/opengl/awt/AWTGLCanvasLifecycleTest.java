package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;

import java.awt.AWTException;
import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AWTGLCanvasLifecycleTest {

    @Test
    void paintAndUpdateRequestRenderingWithoutUsingTheOpenGLContext() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        AtomicInteger requests = new AtomicInteger();
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            protected void requestRender() {
                requests.incrementAndGet();
            }
        };

        canvas.paint(null);
        canvas.update(null);

        assertEquals(2, requests.get());
        assertTrue(platform.calls.isEmpty());
    }

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
    void disposeCanvasInvokesDisposeGLWhileContextIsCurrentBeforeDeletingIt() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            protected void disposeGL() {
                assertTrue(platform.isCurrent(42L));
                platform.calls.add("disposeGL");
            }
        };
        canvas.context = 42L;
        canvas.initCalled = true;

        canvas.disposeCanvas();

        assertEquals(Arrays.asList("lock", "makeCurrent:42", "disposeGL", "makeCurrent:0", "unlock",
                "delete:42", "dispose"), platform.calls);
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

        assertEquals(Arrays.asList("lock", "makeCurrent:42", "makeCurrent:0", "unlock", "delete:42",
                "dispose"), platform.calls);
        assertEquals(0L, canvas.context);
        assertFalse(canvas.initCalled);
    }

    @Test
    void disposeCanvasStillDeletesContextWhenDisposeGLFails() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        IllegalStateException disposeGLFailure = new IllegalStateException("disposeGL failed");
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            protected void disposeGL() {
                platform.calls.add("disposeGL");
                throw disposeGLFailure;
            }
        };
        canvas.context = 42L;
        canvas.initCalled = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class, canvas::disposeCanvas);

        assertSame(disposeGLFailure, failure);
        assertEquals(Arrays.asList("lock", "makeCurrent:42", "disposeGL", "makeCurrent:0", "unlock",
                "delete:42", "dispose"), platform.calls);
        assertEquals(0L, canvas.context);
        assertFalse(canvas.initCalled);
    }

    @Test
    void disposeCanvasDoesNotInvokeDisposeGLWhenMakingContextCurrentFails() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.makeCurrentFailureContext = 42L;
        AtomicBoolean callbackCalled = new AtomicBoolean();
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            protected void disposeGL() {
                callbackCalled.set(true);
            }
        };
        canvas.context = 42L;

        IllegalStateException failure = assertThrows(IllegalStateException.class, canvas::disposeCanvas);

        assertEquals("Failed to make the OpenGL context current", failure.getMessage());
        assertFalse(callbackCalled.get());
        assertEquals(Arrays.asList("lock", "makeCurrent:42", "makeCurrent:0", "unlock", "delete:42",
                "dispose"), platform.calls);
        assertEquals(0L, canvas.context);
    }

    @Test
    void disposeCanvasDoesNotQueryFramebufferSizeBeforeDisposeGL() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.framebufferSizeFailure = new IllegalStateException("size query failed");
        AtomicBoolean callbackCalled = new AtomicBoolean();
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            protected void disposeGL() {
                callbackCalled.set(true);
            }
        };
        canvas.context = 42L;

        canvas.disposeCanvas();

        assertTrue(callbackCalled.get());
        assertEquals(Arrays.asList("lock", "makeCurrent:42", "makeCurrent:0", "unlock", "delete:42",
                "dispose"), platform.calls);
    }

    @Test
    void disposeCanvasPreservesCallbackFailureWhenLaterCleanupAlsoFails() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        platform.makeCurrentFailureContext = 0L;
        platform.deleteFailure = new IllegalStateException("delete failed");
        platform.disposeFailure = new IllegalStateException("platform dispose failed");
        IllegalStateException disposeGLFailure = new IllegalStateException("disposeGL failed");
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            protected void disposeGL() {
                platform.calls.add("disposeGL");
                throw disposeGLFailure;
            }
        };
        canvas.context = 42L;

        IllegalStateException failure = assertThrows(IllegalStateException.class, canvas::disposeCanvas);

        assertSame(disposeGLFailure, failure);
        assertEquals(3, failure.getSuppressed().length);
        assertEquals("Failed to clear the current OpenGL context", failure.getSuppressed()[0].getMessage());
        assertSame(platform.deleteFailure, failure.getSuppressed()[1]);
        assertSame(platform.disposeFailure, failure.getSuppressed()[2]);
        assertEquals(Arrays.asList("lock", "makeCurrent:42", "disposeGL", "makeCurrent:0", "unlock",
                "delete:42", "dispose"), platform.calls);
        assertEquals(0L, canvas.context);
    }

    @Test
    void disposeGLIsInvokedOnlyOncePerContext() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        AtomicInteger callbackCalls = new AtomicInteger();
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            protected void disposeGL() {
                callbackCalls.incrementAndGet();
            }
        };
        canvas.context = 42L;

        canvas.disposeCanvas();
        canvas.disposeCanvas();

        assertEquals(1, callbackCalls.get());
        assertEquals(Arrays.asList("lock", "makeCurrent:42", "makeCurrent:0", "unlock", "delete:42",
                "dispose", "dispose"), platform.calls);
    }

    @Test
    void contextCreatedByRunInContextIsDisposedWithoutCallingInitGL() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        AtomicBoolean initGLCalled = new AtomicBoolean();
        AtomicBoolean disposeGLCalled = new AtomicBoolean();
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            public void initGL() {
                initGLCalled.set(true);
            }

            @Override
            protected void disposeGL() {
                assertTrue(platform.isCurrent(42L));
                disposeGLCalled.set(true);
                platform.calls.add("disposeGL");
            }
        };

        canvas.runInContext(() -> platform.calls.add("work"));
        assertFalse(initGLCalled.get());
        assertFalse(canvas.initCalled);

        canvas.disposeCanvas();

        assertTrue(disposeGLCalled.get());
        assertEquals(Arrays.asList("create", "lock", "makeCurrent:42", "work", "makeCurrent:0", "unlock",
                "lock", "makeCurrent:42", "disposeGL", "makeCurrent:0", "unlock", "delete:42", "dispose"),
                platform.calls);
    }

    @Test
    void disposeGLPreventsReentrantLifecycleOperationsWithoutRelockingDrawingSurface() {
        RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
        AtomicInteger callbackCalls = new AtomicInteger();
        TestCanvas canvas = new TestCanvas(platform) {
            @Override
            protected void disposeGL() {
                callbackCalls.incrementAndGet();

                disposeCanvas();

                IllegalStateException renderFailure = assertThrows(IllegalStateException.class, this::render);
                assertEquals("Canvas is being disposed", renderFailure.getMessage());
                IllegalStateException runFailure = assertThrows(IllegalStateException.class,
                        () -> runInContext(() -> {}));
                assertEquals("Canvas is being disposed", runFailure.getMessage());
                IllegalStateException executeFailure = assertThrows(IllegalStateException.class,
                        () -> executeInContext(() -> null));
                assertEquals("Canvas is being disposed", executeFailure.getMessage());

                platform.calls.add("disposeGL");
            }
        };
        canvas.context = 42L;

        canvas.disposeCanvas();

        assertEquals(1, callbackCalls.get());
        assertEquals(Arrays.asList("lock", "makeCurrent:42", "disposeGL", "makeCurrent:0", "unlock",
                "delete:42", "dispose"), platform.calls);
    }

    @Test
    void removeNotifyInvokesDisposeGLBeforeDestroyingNativePeer() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicBoolean callbackCalled = new AtomicBoolean();
        AtomicBoolean callbackSawDisplayableCanvas = new AtomicBoolean();

        EventQueue.invokeAndWait(() -> {
            Frame frame = new Frame();
            try {
                RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
                TestCanvas canvas = new TestCanvas(platform) {
                    @Override
                    protected void disposeGL() {
                        callbackCalled.set(true);
                        callbackSawDisplayableCanvas.set(isDisplayable());
                    }
                };
                frame.add(canvas);
                frame.pack();
                assertTrue(canvas.isDisplayable());
                canvas.context = 42L;

                frame.remove(canvas);

                assertTrue(callbackCalled.get());
                assertTrue(callbackSawDisplayableCanvas.get());
                assertFalse(canvas.isDisplayable());
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void windowDisposeInvokesDisposeGLBeforeDestroyingNativePeer() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicBoolean callbackCalled = new AtomicBoolean();
        AtomicBoolean callbackSawDisplayableCanvas = new AtomicBoolean();

        EventQueue.invokeAndWait(() -> {
            Frame frame = new Frame();
            try {
                RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
                TestCanvas canvas = new TestCanvas(platform) {
                    @Override
                    protected void disposeGL() {
                        callbackCalled.set(true);
                        callbackSawDisplayableCanvas.set(isDisplayable());
                    }
                };
                frame.add(canvas);
                frame.pack();
                assertTrue(canvas.isDisplayable());
                canvas.context = 42L;

                frame.dispose();

                assertTrue(callbackCalled.get());
                assertTrue(callbackSawDisplayableCanvas.get());
                assertFalse(canvas.isDisplayable());
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void awtRemovalDisposesOncePerContextAcrossCanvasReAdd() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicInteger callbackCalls = new AtomicInteger();

        EventQueue.invokeAndWait(() -> {
            Frame frame = new Frame();
            try {
                RecordingPlatformCanvas platform = new RecordingPlatformCanvas();
                TestCanvas canvas = new TestCanvas(platform) {
                    @Override
                    protected void disposeGL() {
                        assertTrue(isDisplayable());
                        callbackCalls.incrementAndGet();
                    }
                };
                frame.add(canvas);
                frame.pack();
                canvas.context = 42L;

                frame.remove(canvas);
                assertFalse(canvas.isDisplayable());

                frame.add(canvas);
                frame.pack();
                assertTrue(canvas.isDisplayable());
                canvas.context = 84L;

                frame.remove(canvas);
                assertFalse(canvas.isDisplayable());
                assertEquals(2, callbackCalls.get());
            } finally {
                frame.dispose();
            }
        });
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
                "makeCurrent:0", "unlock", "lock", "makeCurrent:42", "makeCurrent:0", "unlock",
                "delete:42", "dispose"), platform.calls);
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
        RuntimeException disposeFailure;
        RuntimeException framebufferSizeFailure;
        long makeCurrentFailureContext = Long.MIN_VALUE;
        long makeCurrentExceptionContext = Long.MIN_VALUE;
        long currentContext;
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
            if (context == makeCurrentFailureContext) {
                return false;
            }
            currentContext = context;
            return true;
        }

        @Override
        public boolean isCurrent(long context) {
            return currentContext == context;
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
            if (disposeFailure != null) {
                throw disposeFailure;
            }
        }
    }
}
