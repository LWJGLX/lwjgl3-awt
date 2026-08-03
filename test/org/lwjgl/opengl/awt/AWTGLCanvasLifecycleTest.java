package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;

import java.awt.AWTException;
import java.awt.Canvas;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AWTGLCanvasLifecycleTest {

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

    private static class RecordingPlatformCanvas implements PlatformGLCanvas {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch deleteCalled = new CountDownLatch(1);
        RuntimeException deleteFailure;

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
            return true;
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
