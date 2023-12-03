package org.lwjgl.opengl.awt;

import org.lwjgl.awthacks.NonClearGraphics;
import org.lwjgl.awthacks.NonClearGraphics2D;
import org.lwjgl.system.Platform;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.util.concurrent.Callable;

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
        case MACOSX:
            return new PlatformMacOSXGLCanvas();
        default:
            throw new UnsupportedOperationException("Platform " + Platform.get() + " not yet supported");
        }
    }

    protected ContextData context;
    protected final GLData data;
    protected GLData effective = new GLData();
    protected boolean initCalled;
    private int framebufferWidth, framebufferHeight;
    private final ComponentListener listener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            java.awt.geom.AffineTransform t = AWTGLCanvas.this.getGraphicsConfiguration().getDefaultTransform();
            float sx = (float) t.getScaleX(), sy = (float) t.getScaleY();
            AWTGLCanvas.this.framebufferWidth = (int) (getWidth() * sx);
            AWTGLCanvas.this.framebufferHeight = (int) (getHeight() * sy);
        }
    };

    @Override
    public void removeNotify() {
        super.removeNotify();
        // prepare for a possible re-adding
        context = null;
        initCalled = false;
        disposeCanvas();
    }

    @Override
    public synchronized void addComponentListener(ComponentListener l) {
        super.addComponentListener(l);
    }

    public void disposeCanvas() {
        this.platformCanvas.dispose();
    }
    protected AWTGLCanvas(GLData data) {
        this.data = data;
        this.addComponentListener(listener);
    }

    protected AWTGLCanvas() {
        this(new GLData());
    }

    protected ContextData createContext(GLData data) throws AWTException {
	return platformCanvas.create(this, data);
    }

    protected ContextData createContext() throws AWTException {
	return(createContext(data));
    }

    protected void beforeRender() {
        if (context == null) {
            try {
                context = createContext();
		effective = context.caps;
            } catch (AWTException e) {
                throw new RuntimeException("Exception while creating the OpenGL context", e);
            }
        }
        try {
            platformCanvas.lock(); // <- MUST lock on Linux
        } catch (AWTException e) {
            throw new RuntimeException("Failed to lock Canvas", e);
        }
        platformCanvas.makeCurrent(context.ctx);
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

    public int getFramebufferWidth() {
        return framebufferWidth;
    }

    public int getFramebufferHeight() {
        return framebufferHeight;
    }

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
