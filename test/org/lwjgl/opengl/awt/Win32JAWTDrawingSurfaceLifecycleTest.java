package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.opengl.GL;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;

@EnabledOnOs(OS.WINDOWS)
class Win32JAWTDrawingSurfaceLifecycleTest {

    @Test
    void rendersAcrossRepeatedDrawingSurfaceAndResizeCycles() throws Exception {
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<AWTGLCanvas> canvasRef = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            GLData data = new GLData();
            data.majorVersion = 3;
            data.minorVersion = 2;
            data.profile = GLData.Profile.CORE;
            data.samples = 0;
            data.swapInterval = 0;

            AWTGLCanvas canvas = new AWTGLCanvas(data) {
                @Override
                public void initGL() {
                    GL.createCapabilities();
                }

                @Override
                public void paintGL() {
                    glClear(GL_COLOR_BUFFER_BIT);
                    swapBuffers();
                }
            };
            canvas.setPreferredSize(new Dimension(320, 240));

            JFrame frame = new JFrame("Win32 JAWT lifecycle test");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(canvas);
            frame.pack();
            frame.setVisible(true);
            frameRef.set(frame);
            canvasRef.set(canvas);
        });

        try {
            for (int i = 0; i < 50; i++) {
                int width = 320 + i;
                int height = 240 + i;
                SwingUtilities.invokeAndWait(() -> {
                    frameRef.get().setSize(width, height);
                    canvasRef.get().render();
                });
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                GL.setCapabilities(null);
                frameRef.get().dispose();
            });
        }
    }
}
