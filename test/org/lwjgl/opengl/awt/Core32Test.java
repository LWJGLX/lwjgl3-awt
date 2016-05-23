package org.lwjgl.opengl.awt;

import java.awt.BorderLayout;
import java.awt.Dimension;

import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class Core32Test {
    public static void main(String[] args) {
        JFrame frame = new JFrame("AWT test");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setPreferredSize(new Dimension(600, 600));
        GLData data = new GLData();
        data.samples = 4;
        data.swapInterval = 0;
        data.majorVersion = 3;
        data.minorVersion = 2;
        data.profile = GLData.Profile.CORE;
        AWTGLCanvas canvas;
        frame.add(canvas = new AWTGLCanvas(data) {
            private static final long serialVersionUID = 1L;
            int aspectUniform;
            public void initGL() {
                System.out.println("OpenGL version: " + effective.majorVersion + "." + effective.minorVersion + " (Profile: " + effective.profile + ")");
                createCapabilities();
                glClearColor(0.3f, 0.4f, 0.5f, 1);
                glBindVertexArray(glGenVertexArrays());
                int vbo = glGenBuffers();
                glBindBuffer(GL_ARRAY_BUFFER, vbo);
                glBufferData(GL_ARRAY_BUFFER, new float[] { -0.5f, 0, 0, -0.5f, 0.5f, 0, 0.5f, 0, 0, 0.5f, -0.5f, 0 }, GL_STATIC_DRAW);
                glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
                glEnableVertexAttribArray(0);
                int vs = glCreateShader(GL_VERTEX_SHADER);
                glShaderSource(vs, "#version 150 core\nuniform float aspect;in vec2 vertex;void main(void){gl_Position=vec4(vertex/vec2(aspect, 1.0), 0.0, 1.0);}");
                glCompileShader(vs);
                if (glGetShaderi(vs, GL_COMPILE_STATUS) == 0)
                    throw new AssertionError("Could not compile vertex shader: " + glGetShaderInfoLog(vs));
                int fs = glCreateShader(GL_FRAGMENT_SHADER);
                glShaderSource(fs, "#version 150 core\nout vec4 color;void main(void){color=vec4(0.4, 0.6, 0.8, 1.0);}");
                glCompileShader(fs);
                if (glGetShaderi(fs, GL_COMPILE_STATUS) == 0)
                    throw new AssertionError("Could not compile fragment shader: " + glGetShaderInfoLog(fs));
                int prog = glCreateProgram();
                glAttachShader(prog, vs);
                glAttachShader(prog, fs);
                glLinkProgram(prog);
                if (glGetProgrami(prog, GL_LINK_STATUS) == 0)
                    throw new AssertionError("Could not link program: " + glGetProgramInfoLog(prog));
                glUseProgram(prog);
                aspectUniform = glGetUniformLocation(prog, "aspect");
            }
            public void paintGL() {
                int w = getWidth();
                int h = getHeight();
                float aspect = (float) w / h;
                glClear(GL_COLOR_BUFFER_BIT);
                glViewport(0, 0, w, h);
                glUniform1f(aspectUniform, aspect);
                glDrawArrays(GL_TRIANGLES, 0, 6);
                this.swapBuffers();
                this.repaint();
            }
        }, BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);
        frame.transferFocus();

        Runnable renderLoop = new Runnable() {
			public void run() {
				if (!canvas.isValid())
					return;
				canvas.render();
				SwingUtilities.invokeLater(this);
			}
		};
		SwingUtilities.invokeLater(renderLoop);
    }
}
