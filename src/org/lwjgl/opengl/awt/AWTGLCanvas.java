package org.lwjgl.opengl.awt;

import java.awt.AWTException;
import java.awt.Canvas;

import org.lwjgl.system.Platform;

/**
 * An AWT {@link Canvas} that supports to be drawn on using OpenGL.
 * 
 * @author Kai Burjack
 */
public abstract class AWTGLCanvas extends Canvas {
    private static final long serialVersionUID = 1L;
    private PlatformGLCanvas platformCanvas;
    {
        String platformClassName;
        switch (Platform.get()) {
        case WINDOWS:
            platformClassName = "org.lwjgl.opengl.awt.PlatformWin32GLCanvas";
            break;
        case LINUX:
            platformClassName = "org.lwjgl.opengl.awt.PlatformLinuxGLCanvas";
            break;
        default:
            throw new AssertionError("NYI");
        }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends PlatformGLCanvas> clazz = (Class<? extends PlatformGLCanvas>) AWTGLCanvas.class.getClassLoader().loadClass(platformClassName);
            platformCanvas = clazz.newInstance();
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Platform-specific GLCanvas class not found: " + platformClassName);
        } catch (InstantiationException e) {
            throw new AssertionError("Could not instantiate platform-specific GLCanvas class: " + platformClassName);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Could not instantiate platform-specific GLCanvas class: " + platformClassName);
        }
    }

    protected long context;
    private final GLData data;
    protected final GLData effective = new GLData();

    protected AWTGLCanvas(GLData data) {
        this.data = data;
    }

    protected AWTGLCanvas() {
        this(new GLData());
    }

    public void render() {
    	boolean created = false;
        if (context == 0L) {
            try {
                context = platformCanvas.create(this, data, effective);
                created = true;
            } catch (AWTException e) {
                throw new RuntimeException("Exception while creating the OpenGL context", e);
            }
        }
        try {
            platformCanvas.lock(); // <- MUST lock on Linux
        } catch (AWTException e) {
            throw new RuntimeException("Failed to lock Canvas", e);
        }
        platformCanvas.makeCurrent(context);
        try {
            if (created)
                initGL();
            paintGL();
        } finally {
            platformCanvas.makeCurrent(0L);
            try {
                platformCanvas.unlock(); // <- MUST unlock on Linux
            } catch (AWTException e) {
                throw new RuntimeException("Failed to unlock Canvas", e);
            }
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
        platformCanvas.swapBuffers();
    }

}
