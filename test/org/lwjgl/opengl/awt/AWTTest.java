package org.lwjgl.opengl.awt;

import java.awt.BorderLayout;

import javax.swing.JFrame;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

public class AWTTest {

	public static void main(String[] args) {
		JFrame frame = new JFrame() {
		};
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.setLayout(new BorderLayout());
		frame.add(new AWTGLCanvas() {
			private static final long serialVersionUID = 1L;

			public void initGL() {
			    GL.createCapabilities();
			    GL11.glClearColor(0.3f, 0.4f, 0.5f, 1);
			}

			public void paintGL() {
			    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
			    swapBuffers();
			    this.repaint();
			}
		}, BorderLayout.CENTER);
		frame.setVisible(true);
	}

}
