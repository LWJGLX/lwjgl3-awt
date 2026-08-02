package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;

import java.awt.AWTException;
import java.awt.Canvas;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        final List<String> calls = new ArrayList<>();
        RuntimeException deleteFailure;

        @Override
        public long create(Canvas canvas, GLData data, GLData effective) {
            calls.add("create");
            return 42L;
        }

        @Override
        public boolean deleteContext(long context) {
            calls.add("delete:" + context);
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
