package org.lwjgl.opengl.awt;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL11.*;

import javax.swing.JFrame;

public class AWTThreadTest {
    abstract static class AWTGLCanvasExplicitDispose extends AWTGLCanvas {
        public AWTGLCanvasExplicitDispose(GLData data) {
            super(data);
        }

        @Override
        public void disposeCanvas() {
        }

        public void doDisposeCanvas() {
            super.disposeCanvas();
        }
    }
    public static void main(String[] args) {
        Semaphore signalTerminate = new Semaphore(0);
        Semaphore signalTerminated = new Semaphore(0);
        JFrame frame = new JFrame("AWT test") {
            @Override
            public void dispose() {
                // request the cleanup
                signalTerminate.release();
                try {
                    // wait until the thread is done with the cleanup
                    signalTerminated.acquire();
                } catch (InterruptedException ignored) {
                }
                super.dispose();
            }
        };
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setPreferredSize(new Dimension(600, 600));
        GLData data = new GLData();
        data.samples = 4;
        data.swapInterval = 0;
        AWTGLCanvasExplicitDispose canvas;
        frame.add(canvas = new AWTGLCanvasExplicitDispose(data) {
            private static final long serialVersionUID = 1L;

            public void initGL() {
                System.out.println("OpenGL version: " + effective.majorVersion + "." + effective.minorVersion + " (Profile: " + effective.profile + ")");
                createCapabilities();
                glClearColor(0.3f, 0.4f, 0.5f, 1);
            }
            public void paintGL() {
                int w = getWidth();
                int h = getHeight();
                float aspect = (float) w / h;
                double now = System.currentTimeMillis() * 0.001;
                float width = (float) Math.abs(Math.sin(now * 0.3));
                glClear(GL_COLOR_BUFFER_BIT);
                glViewport(0, 0, w, h);
                glBegin(GL_QUADS);
                glColor3f(0.4f, 0.6f, 0.8f);
                glVertex2f(-0.75f * width / aspect, 0.0f);
                glVertex2f(0, -0.75f);
                glVertex2f(+0.75f * width/ aspect, 0);
                glVertex2f(0, +0.75f);
                glEnd();
                swapBuffers();
            }

        }, BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);
        frame.transferFocus();

        Runnable renderLoop = new Runnable() {
            public void run() {
                while (true) {
                    canvas.render();
                    try {
                        if (signalTerminate.tryAcquire(10, TimeUnit.MILLISECONDS)) {
                            canvas.doDisposeCanvas();
                            signalTerminated.release();
                            return;
                        }
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        };
        Thread renderThread = new Thread(renderLoop);
        renderThread.start();
    }
}
