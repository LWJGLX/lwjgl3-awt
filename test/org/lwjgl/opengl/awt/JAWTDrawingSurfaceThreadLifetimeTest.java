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

    @Test
    void disposesCanvasAfterRenderingThreadExits() throws Exception {
        AtomicReference<AWTGLCanvas> canvasRef = new AtomicReference<>();
        AtomicReference<JFrame> frameRef = new AtomicReference<>();

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

            JFrame frame = new JFrame("dispose-after-render-thread-exits");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(canvas);
            frame.pack();
            frame.setVisible(true);

            canvasRef.set(canvas);
            frameRef.set(frame);
        });

        AtomicReference<Throwable> renderFailure = new AtomicReference<>();
        Thread renderer = new Thread(() -> {
            try {
                canvasRef.get().render();
            } catch (Throwable t) {
                renderFailure.set(t);
            }
        }, "short-lived-renderer");
        renderer.start();
        renderer.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(renderer.isAlive(), "Rendering thread did not exit");

        SwingUtilities.invokeAndWait(() -> frameRef.get().getContentPane().remove(canvasRef.get()));
        SwingUtilities.invokeAndWait(frameRef.get()::dispose);

        if (renderFailure.get() != null) {
            fail("Rendering failed", renderFailure.get());
        }
    }
}
