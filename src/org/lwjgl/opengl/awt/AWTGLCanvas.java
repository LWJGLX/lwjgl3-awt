package org.lwjgl.opengl.awt;

import java.awt.AWTException;
import java.awt.Canvas;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.concurrent.*;

import org.lwjgl.awthacks.NonClearGraphics;
import org.lwjgl.awthacks.NonClearGraphics2D;
import org.lwjgl.system.Platform;

/**
 * An AWT {@link Canvas} that supports to be drawn on using OpenGL.
 * 
 * @author Kai Burjack
 */
public abstract class AWTGLCanvas extends Canvas {
    private static final long serialVersionUID = 1L;
    protected PlatformGLCanvas platformCanvas = createPlatformCanvas();

    private static PlatformGLCanvas createPlatformCanvas() {
        switch (Platform.get()) {
        case WINDOWS:
            return new PlatformWin32GLCanvas();
        case LINUX:
            return new PlatformLinuxGLCanvas();
        default:
            throw new UnsupportedOperationException("Platform " + Platform.get() + " not yet supported");
        }
    }

    protected long context;
    protected final GLData data;
    protected final GLData effective = new GLData();
    protected boolean initCalled;

    @Override
    public void removeNotify() {
        super.removeNotify();
        disposeCanvas();
    }

    public void disposeCanvas() {
        this.platformCanvas.dispose();
    }
    protected AWTGLCanvas(GLData data) {
        this.data = data;
    }

    protected AWTGLCanvas() {
        this(new GLData());
    }

    protected void beforeRender() {
        if (context == 0L) {
            try {
                context = platformCanvas.create(this, data, effective);
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
    }

    protected void afterRender() {
        platformCanvas.makeCurrent(0L);
        try {
            platformCanvas.unlock(); // <- MUST unlock on Linux
        } catch (AWTException e) {
            throw new RuntimeException("Failed to unlock Canvas", e);
        }
    }

    public <T> T executeInContext(Callable<T> callable) throws Exception {
        beforeRender();
        try {
            return callable.call();
        } finally {
            afterRender();
        }
    }

    public void runInContext(Runnable runnable) {
        beforeRender();
        try {
            runnable.run();
        } finally {
            afterRender();
        }
    }

    public void render() {
        beforeRender();
        try {
            if (!initCalled) {
                initGL();
                initCalled = true;
            }
            paintGL();
        } finally {
            afterRender();
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
    
    /**
     * Returns Graphics object that ignores {@link Graphics#clearRect(int, int, int, int)}
     * calls.
     * This is done so that the frame buffer will not be cleared by AWT/Swing internals.
     */
    @Override
    public Graphics getGraphics() {
    	Graphics graphics = super.getGraphics();
    	return (graphics instanceof Graphics2D) ? 
    			new NonClearGraphics2D((Graphics2D) graphics) : new NonClearGraphics(graphics);
    }

}
