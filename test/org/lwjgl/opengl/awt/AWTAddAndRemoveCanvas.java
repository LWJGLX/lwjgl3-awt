package org.lwjgl.opengl.awt;

import java.awt.AWTEventMulticaster;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.GroupLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.LayoutStyle;
import javax.swing.SwingUtilities;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL.createCapabilities;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glColor3f;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glVertex2f;
import static org.lwjgl.opengl.GL11.glViewport;

/**
 * @author wil
 */
public class AWTAddAndRemoveCanvas {
    public static void main(String[] args) {
        Semaphore signalTerminate = new Semaphore(0);
        Semaphore signalTerminated = new Semaphore(0);
        
        AtomicBoolean showCanvas = new AtomicBoolean(false);
        
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
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);        
        frame.setLayout(new BorderLayout());
        frame.setPreferredSize(new Dimension(600, 600));
        
         final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BorderLayout());
        
        GLData data = new GLData();
        data.profile = GLData.Profile.COMPATIBILITY;
        
        AWTGLCanvas canvas;
        mainPanel.add(canvas = new AWTGLCanvas(data) {
            @Override
            public void removeNotify() {
                showCanvas.set(false);
                super.removeNotify();
            }

            @Override
            public void addNotify() {
                super.addNotify();
                showCanvas.set(true);
            }
            
            @Override
            public void initGL() {
                System.out.println("OpenGL version: " + effective.majorVersion + "." + effective.minorVersion + " (Profile: " + effective.profile + ")");
                createCapabilities();
                glClearColor(0.3f, 0.4f, 0.5f, 1);
            }
            
            @Override
            public void paintGL() {
                int w = getFramebufferWidth();
                int h = getFramebufferHeight();
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
        
        JPanel controlPanel = new JPanel();
        
        JButton btnRmove = new JButton("Remove");
        btnRmove.addActionListener((ActionEvent ae) -> {
            mainPanel.remove(canvas);
            System.out.println("remove()");
        });
        
        JButton btnAdd   = new JButton("Add");
        btnAdd.addActionListener((ActionEvent ae) -> {
            mainPanel.add(canvas, BorderLayout.CENTER);
            System.out.println("add()");
        });
        
        GroupLayout layout = new GroupLayout(controlPanel);
        controlPanel.setLayout(layout);
        
        layout.setHorizontalGroup(
            layout.createParallelGroup(GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(GroupLayout.Alignment.LEADING)
                    .addComponent(btnRmove)
                    .addComponent(btnAdd))
                .addContainerGap(9, Short.MAX_VALUE))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(btnRmove)
                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnAdd)
                .addContainerGap(438, Short.MAX_VALUE))
        );
        
        mainPanel.add(controlPanel, BorderLayout.LINE_START);
        
        frame.getContentPane().add(mainPanel, BorderLayout.CENTER);
        frame.pack();
        
        /* Create and display the form */
        EventQueue.invokeLater(() -> {
            frame.setVisible(true);
        });
        
        Runnable renderLoop = () -> {
            while (true) {
                if (showCanvas.get()) {
                    canvas.render();
                    try {
                        if (signalTerminate.tryAcquire(10, TimeUnit.MILLISECONDS)) {
                            GL.setCapabilities(null);
                            canvas.disposeCanvas();
                            signalTerminated.release();
                            return;
                        }
                    } catch (InterruptedException ignored) { }
                }
            }
        };
        
        Thread renderThread = new Thread(renderLoop);
        renderThread.start();
    }
}
