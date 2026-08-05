package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

class JAWTDrawingSurfaceThreadLifetimeTest {
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
            AWTGLCanvas canvas = new AWTGLCanvas(new GLData()) {
                @Override
                public void initGL() {
                    GL.createCapabilities();
                }

                @Override
                public void paintGL() {
                    swapBuffers();
                }
            };
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
            }
        }, "short-lived-renderer-" + cycle);

        try {
            renderer.start();
            renderer.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(renderer.isAlive(), "Rendering thread did not exit in cycle " + cycle);
        } finally {
            SwingUtilities.invokeAndWait(() -> lifecycle.frame.getContentPane().remove(lifecycle.canvas));
            SwingUtilities.invokeAndWait(lifecycle.frame::dispose);
        }

        if (renderFailure.get() != null) {
            fail("Rendering failed in cycle " + cycle, renderFailure.get());
        }
    }

    private static class Lifecycle {
        final JFrame frame;
        final AWTGLCanvas canvas;

        Lifecycle(JFrame frame, AWTGLCanvas canvas) {
            this.frame = frame;
            this.canvas = canvas;
        }
    }
}
