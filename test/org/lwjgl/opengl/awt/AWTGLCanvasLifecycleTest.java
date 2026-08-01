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
            return true;
        }

        @Override
        public boolean isCurrent(long context) {
            return false;
        }

        @Override
        public boolean swapBuffers() {
            return true;
        }

        @Override
        public boolean delayBeforeSwapNV(float seconds) {
            return false;
        }

        @Override
        public void lock() throws AWTException {
        }

        @Override
        public void unlock() throws AWTException {
        }

        @Override
        public void dispose() {
            calls.add("dispose");
        }
    }
}
