package org.lwjgl.opengl.awt;

import java.awt.BorderLayout;
import java.awt.Dimension;

import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL11.*;

import javax.swing.JFrame;

public class AWTTest {
	public static void main(String[] args) {
		JFrame frame = new JFrame("AWT test");
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.setLayout(new BorderLayout());
		frame.setPreferredSize(new Dimension(600, 600));
		GLData data = new GLData();
		data.samples = 4;
		frame.add(new AWTGLCanvas(data) {
			public void initGL() {
			    createCapabilities();
			    glClearColor(0.3f, 0.4f, 0.5f, 1);
			}
			public void paintGL() {
			    int w = getWidth();
			    int h = getHeight();
			    float aspect = (float)w/h;
			    glClear(GL_COLOR_BUFFER_BIT);
			    glViewport(0, 0, w, h);
			    glBegin(GL_QUADS);
			    glColor3f(0.4f, 0.6f, 0.8f);
			    glVertex2f(-0.5f / aspect, 0.0f);
			    glVertex2f(0, -0.5f);
			    glVertex2f(+0.5f / aspect, 0);
			    glVertex2f(0, +0.5f);
			    glEnd();
			    swapBuffers();
			    this.repaint();
			}
		}, BorderLayout.CENTER);
		frame.pack();
		frame.setVisible(true);
	}
}
