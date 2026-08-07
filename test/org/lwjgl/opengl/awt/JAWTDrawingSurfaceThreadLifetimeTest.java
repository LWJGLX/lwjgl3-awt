package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JAWTDrawingSurfaceThreadLifetimeTest {
    // Repeating the complete peer and context teardown catches native lifecycle failures that a one-shot smoke misses.
    private static final int LIFECYCLE_CYCLES = 10;

    @Test
    void repeatedlyCreatesRendersRemovesAndDisposesCanvasAfterRenderingThreadExits() throws Exception {
        for (int cycle = 0; cycle < LIFECYCLE_CYCLES; cycle++) {
            runLifecycle(cycle);
        }
    }

    private static void runLifecycle(int cycle) throws Exception {
        AtomicReference<Lifecycle> lifecycleRef = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            LifecycleCanvas canvas = new LifecycleCanvas(cycle);
            canvas.setPreferredSize(new Dimension(320, 240));

            JFrame frame = new JFrame("dispose-after-render-thread-exits-" + cycle);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(canvas);
            frame.pack();
            frame.setVisible(true);

            lifecycleRef.set(new Lifecycle(frame, canvas));
        });

        Lifecycle lifecycle = lifecycleRef.get();
        AtomicReference<Throwable> renderFailure = new AtomicReference<>();
        Thread renderer = new Thread(() -> {
            try {
                lifecycle.canvas.render();
            } catch (Throwable t) {
                renderFailure.set(t);
            } finally {
                GL.setCapabilities(null);
            }
        }, "short-lived-renderer-" + cycle);
        renderer.setDaemon(true);

        renderer.start();
        renderer.join(TimeUnit.SECONDS.toMillis(10));
        if (renderer.isAlive()) {
            // A stuck renderer may still hold the lifecycle lock. Do not block the EDT trying to remove the canvas,
            // because that would hide this failure behind Surefire's fork timeout.
            fail("Rendering thread did not exit in cycle " + cycle);
        }

        Throwable renderingFailure = renderFailure.get();
        Throwable cleanupFailure = cleanupLifecycle(lifecycle, cycle);
        if (renderingFailure != null) {
            if (cleanupFailure != null && renderingFailure != cleanupFailure) {
                renderingFailure.addSuppressed(cleanupFailure);
            }
            fail("Rendering failed in cycle " + cycle, renderingFailure);
        }
        if (cleanupFailure != null) {
            fail("Lifecycle cleanup failed in cycle " + cycle, cleanupFailure);
        }
    }

    private static Throwable cleanupLifecycle(Lifecycle lifecycle, int cycle) {
        Throwable failure = null;
        try {
            SwingUtilities.invokeAndWait(() -> {
                lifecycle.frame.getContentPane().remove(lifecycle.canvas);
                assertEquals(0L, lifecycle.canvas.context,
                        "OpenGL context was not cleared in cycle " + cycle);
                assertFalse(lifecycle.canvas.initCalled,
                        "Canvas remained initialized after removal in cycle " + cycle);
                assertTrue(lifecycle.canvas.disposeGLCalled,
                        "disposeGL was not called in cycle " + cycle);
            });
        } catch (Throwable cleanupFailure) {
            failure = unwrapInvocationFailure(cleanupFailure);
        }

        try {
            SwingUtilities.invokeAndWait(lifecycle.frame::dispose);
        } catch (Throwable disposeFailure) {
            failure = appendFailure(failure, unwrapInvocationFailure(disposeFailure));
        }
        return failure;
    }

    private static Throwable appendFailure(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (primary != additional) {
            primary.addSuppressed(additional);
        }
        return primary;
    }

    private static Throwable unwrapInvocationFailure(Throwable failure) {
        if (failure instanceof InvocationTargetException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static class Lifecycle {
        final JFrame frame;
        final LifecycleCanvas canvas;

        Lifecycle(JFrame frame, LifecycleCanvas canvas) {
            this.frame = frame;
            this.canvas = canvas;
        }
    }

    private static final class LifecycleCanvas extends AWTGLCanvas {
        private final int cycle;
        private boolean disposeGLCalled;

        LifecycleCanvas(int cycle) {
            this.cycle = cycle;
        }

        @Override
        public void initGL() {
            GL.createCapabilities();
        }

        @Override
        public void paintGL() {
            swapBuffers();
        }

        @Override
        protected void disposeGL() {
            assertTrue(platformCanvas.isCurrent(context),
                    "OpenGL context was not current during disposal in cycle " + cycle);
            disposeGLCalled = true;
        }
    }
}
