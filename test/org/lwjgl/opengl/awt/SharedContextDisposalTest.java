package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glIsTexture;

@DisabledOnOs(value = OS.MAC, disabledReason = "The macOS backend does not support GLData.shareContext")
class SharedContextDisposalTest {
    @Test
    void deletesCanvasOwnedSharedObjectWhileAnotherContextSurvives() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());

        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                OwnerCanvas owner = new OwnerCanvas();
                owner.setPreferredSize(new Dimension(160, 120));

                GLData sharedData = new GLData();
                sharedData.shareContext = owner;
                TransientCanvas transientCanvas = new TransientCanvas(sharedData);
                transientCanvas.setPreferredSize(new Dimension(160, 120));

                JPanel canvases = new JPanel(new GridLayout(1, 2));
                canvases.add(owner);
                canvases.add(transientCanvas);

                JFrame frame = new JFrame("shared-context-disposal");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.setContentPane(canvases);
                frame.pack();
                frame.setVisible(true);
                frameRef.set(frame);

                owner.render();
                transientCanvas.render();

                int deletedTexture = transientCanvas.deletedTexture;
                int survivingTexture = transientCanvas.survivingTexture;
                assertTrue(owner.isTexture(deletedTexture));
                assertTrue(owner.isTexture(survivingTexture));

                canvases.remove(transientCanvas);

                assertTrue(transientCanvas.disposeGLCalled);
                assertFalse(owner.isTexture(deletedTexture),
                        "disposeGL did not delete the canvas-owned object from the surviving share group");
                assertTrue(owner.isTexture(survivingTexture),
                        "Destroying one context unexpectedly deleted an undeleted shared object");

                owner.deleteTexture(survivingTexture);
                assertFalse(owner.isTexture(survivingTexture));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                GL.setCapabilities(null);
                JFrame frame = frameRef.get();
                if (frame != null) {
                    frame.dispose();
                }
            });
        }
    }

    private abstract static class SharedCanvas extends AWTGLCanvas {
        GLCapabilities capabilities;

        SharedCanvas() {
        }

        SharedCanvas(GLData data) {
            super(data);
        }

        @Override
        public void initGL() {
            capabilities = GL.createCapabilities();
        }

        @Override
        public void paintGL() {
            swapBuffers();
        }

        final boolean isTexture(int texture) {
            final boolean[] result = new boolean[1];
            runInContext(() -> withCapabilities(() -> result[0] = glIsTexture(texture)));
            return result[0];
        }

        final void deleteTexture(int texture) {
            runInContext(() -> withCapabilities(() -> glDeleteTextures(texture)));
        }

        final void withCapabilities(Runnable action) {
            GL.setCapabilities(capabilities);
            try {
                action.run();
            } finally {
                GL.setCapabilities(null);
            }
        }
    }

    private static final class OwnerCanvas extends SharedCanvas {
    }

    private static final class TransientCanvas extends SharedCanvas {
        int deletedTexture;
        int survivingTexture;
        boolean disposeGLCalled;

        TransientCanvas(GLData data) {
            super(data);
        }

        @Override
        public void initGL() {
            super.initGL();
            deletedTexture = createTexture();
            survivingTexture = createTexture();
        }

        @Override
        protected void disposeGL() {
            assertTrue(platformCanvas.isCurrent(context));
            withCapabilities(() -> glDeleteTextures(deletedTexture));
            disposeGLCalled = true;
        }

        private static int createTexture() {
            int texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindTexture(GL_TEXTURE_2D, 0);
            return texture;
        }
    }
}
