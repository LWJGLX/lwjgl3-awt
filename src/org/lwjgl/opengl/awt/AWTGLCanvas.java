package org.lwjgl.opengl.awt;

import java.awt.AWTException;
import java.awt.Canvas;
import java.awt.Graphics;

public abstract class AWTGLCanvas extends Canvas {
    private static final long serialVersionUID = 1L;

    private PlatformGLCanvas platformGLCanvas = new PlatformWin32GLCanvas();
    protected long context;
    private final GLData data;
    private final GLData effective = new GLData();

    protected AWTGLCanvas(GLData data) {
        this.data = data;
    }

    protected AWTGLCanvas() {
        this(new GLData());
    }

    @Override
    public void paint(Graphics g) {
        boolean created = false;
        if (context == 0L) {
            try {
                context = platformGLCanvas.create(this, data, effective);
                created = true;
            } catch (AWTException e) {
                e.printStackTrace();
                throw new RuntimeException();
            }
        }
        platformGLCanvas.makeCurrent(this, context);
        try {
            if (created)
                initGL();
            paintGL();
        } finally {
            platformGLCanvas.makeCurrent(null, 0L);
        }
    }

    /**
     * Will be called once after the OpenGL has been created.
     */
    public abstract void initGL();

    /**
     * Will be called whenever the {@link Canvas} needs to paint itself.
     */
    public abstract void paintGL();

    public final void swapBuffers() {
        platformGLCanvas.swapBuffers(this);
    }

}
