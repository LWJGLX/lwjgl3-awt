package org.lwjgl.opengl.awt;

import org.lwjgl.awthacks.NonClearGraphics;
import org.lwjgl.awthacks.NonClearGraphics2D;
import org.lwjgl.system.Platform;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An AWT {@link Canvas} that supports to be drawn on using OpenGL.
 *
 * <p>Rendering and context callbacks execute while this canvas's lifecycle lock is held. They must not synchronously
 * wait for AWT's event-dispatch thread, because that thread may be waiting to remove or dispose this canvas.</p>
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

    protected long context;
    protected final GLData data;
    protected final GLData effective = new GLData();
    protected boolean initCalled;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
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
        lifecycleLock.lock();
        try {
            super.removeNotify();
            disposeCanvas();
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public synchronized void addComponentListener(ComponentListener l) {
        super.addComponentListener(l);
    }

    /**
     * Deletes the OpenGL context and releases platform-specific canvas resources.
     *
     * <p>If rendering is in progress on another thread, this method waits for that operation to finish before deleting
     * the context. Applications remain responsible for stopping any render loop before disposing the canvas.</p>
     */
    public void disposeCanvas() {
        lifecycleLock.lock();
        try {
            try {
                if (context != 0L) {
                    platformCanvas.deleteContext(context);
                }
            } finally {
                // prepare for a possible re-adding
                context = 0L;
                initCalled = false;
                platformCanvas.dispose();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }
    protected AWTGLCanvas(GLData data) {
        this.data = data;
        this.addComponentListener(listener);
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
        lifecycleLock.lock();
        try {
            beforeRender();
            try {
                return callable.call();
            } finally {
                afterRender();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void runInContext(Runnable runnable) {
        lifecycleLock.lock();
        try {
            beforeRender();
            try {
                runnable.run();
            } finally {
                afterRender();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Makes this canvas's context current and invokes {@link #initGL()} when necessary, followed by {@link #paintGL()}.
     *
     * <p>The callbacks run while the lifecycle lock is held and must not call {@link EventQueue#invokeAndWait(Runnable)}
     * or otherwise wait synchronously for the event-dispatch thread.</p>
     */
    public void render() {
        lifecycleLock.lock();
        try {
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
        } finally {
            lifecycleLock.unlock();
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

    /**
     * Swaps this canvas's buffers without making its context current.
     *
     * <p>Call this only while the context is current, normally from {@link #paintGL()} or a callback passed to
     * {@link #runInContext(Runnable)} or {@link #executeInContext(Callable)}.</p>
     */
    public final void swapBuffers() {
        lifecycleLock.lock();
        try {
            platformCanvas.swapBuffers();
        } finally {
            lifecycleLock.unlock();
        }
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
