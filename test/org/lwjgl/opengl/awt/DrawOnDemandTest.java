package org.lwjgl.opengl.awt;

import static org.lwjgl.opengl.GL11.glClearColor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;

import javax.swing.JColorChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

public class DrawOnDemandTest {
	
	static Color quadColor = new Color(0x77aadd);

	public static void main(String[] args) {
		
		AWTGLCanvas canvas = new AWTGLCanvas() {
			private static final long serialVersionUID = 1L;

			@Override
			public void initGL() {
				GL.createCapabilities();
				glClearColor(0.3f, 0.4f, 0.5f, 1);
			}
			
			@Override
			public void paintGL() {
				int w = getWidth();
				int h = getHeight();
				if (w == 0 || h == 0) {
					return;
				}
				float aspect = (float) w / h;
				GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
				GL11.glViewport(0, 0, w, h);
				GL11.glBegin(GL11.GL_QUADS);
				GL11.glColor3f(quadColor.getRed()/255f, quadColor.getGreen()/255f, quadColor.getBlue()/255f);
				GL11.glVertex2f(-0.75f / aspect, 0.0f);
				GL11.glVertex2f(0, -0.75f);
				GL11.glVertex2f(+0.75f / aspect, 0);
				GL11.glVertex2f(0, +0.75f);
				GL11.glEnd();
				swapBuffers();
			}

			@Override
			public void repaint() {
				if (SwingUtilities.isEventDispatchThread()) {
					render();
				} else {
					SwingUtilities.invokeLater(() -> render());
				}
			}

		};

		JFrame frame = new JFrame();
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.getContentPane().setLayout(new BorderLayout());
		frame.getContentPane().add(canvas, BorderLayout.CENTER);
		canvas.setPreferredSize(new Dimension(200, 200));
		canvas.addComponentListener(new ComponentAdapter() {
			public void componentResized(java.awt.event.ComponentEvent e) {
				canvas.repaint();
			};
		});
		JColorChooser colorChooser = new JColorChooser(quadColor);
		frame.getContentPane().add(colorChooser, BorderLayout.SOUTH);
		colorChooser.getSelectionModel().addChangeListener((e)->{
			quadColor = colorChooser.getColor();
			canvas.repaint();
		});
		

		SwingUtilities.invokeLater(() -> {
			frame.pack();
			frame.setVisible(true);
		});
	}

}
