package org.lwjgl.opengl.awt;

import org.lwjgl.awt.internal.LWJGLVersionChecker;
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
 * wait for AWT's event-dispatch thread or acquire AWT's tree lock, because that thread may already hold the tree lock
 * while waiting to remove or dispose this canvas. Post AWT work asynchronously instead.</p>
 * 
 * @author Kai Burjack
 */
public abstract class AWTGLCanvas extends Canvas {
    private static final long serialVersionUID = 1L;
    static {
        LWJGLVersionChecker.check();
    }

    protected PlatformGLCanvas platformCanvas = createPlatformCanvas();

    private static PlatformGLCanvas createPlatformCanvas() {
        switch (Platform.get()) {
        case WINDOWS:
            return new PlatformWin32GLCanvas();
        case LINUX:
            return PlatformLinuxGLCanvasFactory.create();
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
    /** Whether context teardown is in progress while {@link #lifecycleLock} is held. */
    private boolean disposing;
    private volatile int framebufferWidth;
    private volatile int framebufferHeight;
    private final ComponentListener listener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            updateFramebufferSizeFromComponent();
        }

        @Override
        public void componentMoved(ComponentEvent e) {
            updateFramebufferSizeFromComponent();
        }
    };

    @Override
    public void removeNotify() {
        lifecycleLock.lock();
        try {
            Throwable failure = null;
            try {
                // The context-current cleanup hook needs the native peer to still exist.
                disposeCanvas();
            } catch (RuntimeException | Error e) {
                failure = e;
            }
            try {
                super.removeNotify();
            } catch (RuntimeException | Error e) {
                failure = appendFailure(failure, e);
            }
            if (failure != null) {
                rethrow(failure);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public synchronized void addComponentListener(ComponentListener l) {
        super.addComponentListener(l);
    }

    /**
     * Invokes the optional {@link #disposeGL()} context-current cleanup hook, deletes the OpenGL context, and releases
     * platform-specific canvas resources.
     *
     * <p>If rendering is in progress on another thread, this method waits for that operation to finish before deleting
     * the context. Applications remain responsible for stopping any render loop before disposing the canvas. The
     * callback runs on the thread that calls this method, which may differ from the rendering thread. If the callback
     * fails, context deletion and platform cleanup are still attempted before the failure is rethrown. A reentrant call
     * from {@link #disposeGL()} returns without doing anything.</p>
     */
    public void disposeCanvas() {
        lifecycleLock.lock();
        try {
            if (disposing) {
                return;
            }
            disposing = true;
            try {
                Throwable failure = null;
                long contextToDelete = context;
                if (contextToDelete != 0L) {
                    try {
                        disposeGLInContext(contextToDelete);
                    } catch (RuntimeException | Error e) {
                        failure = e;
                    }
                    try {
                        platformCanvas.deleteContext(contextToDelete);
                    } catch (RuntimeException | Error e) {
                        failure = appendFailure(failure, e);
                    }
                }
                // prepare for a possible re-adding
                context = 0L;
                initCalled = false;
                try {
                    platformCanvas.dispose();
                } catch (RuntimeException | Error e) {
                    failure = appendFailure(failure, e);
                }
                if (failure != null) {
                    rethrow(failure);
                }
            } finally {
                disposing = false;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void disposeGLInContext(long contextToDelete) {
        lockAndMakeCurrent(contextToDelete, false);
        Throwable callbackFailure = null;
        try {
            disposeGL();
        } catch (RuntimeException | Error failure) {
            callbackFailure = failure;
            throw failure;
        } finally {
            afterDisposeGL(callbackFailure);
        }
    }

    protected AWTGLCanvas(GLData data) {
        this.data = data;
        this.addComponentListener(listener);
        this.addPropertyChangeListener("graphicsConfiguration", e -> updateFramebufferSizeFromComponent());
    }

    protected AWTGLCanvas() {
        this(new GLData());
    }

    protected void beforeRender() {
        if (disposing) {
            throw new IllegalStateException("Canvas is being disposed");
        }
        if (context == 0L) {
            try {
                context = platformCanvas.create(this, data, effective);
            } catch (AWTException e) {
                throw new RuntimeException("Exception while creating the OpenGL context", e);
            }
        }
        lockAndMakeCurrent(context, true);
    }

    private void lockAndMakeCurrent(long contextToMakeCurrent, boolean updateFramebuffer) {
        try {
            platformCanvas.lock(); // <- MUST lock on Linux
        } catch (AWTException e) {
            throw new RuntimeException("Failed to lock Canvas", e);
        }
        try {
            if (!platformCanvas.makeCurrent(contextToMakeCurrent)) {
                throw new IllegalStateException("Failed to make the OpenGL context current");
            }
            if (updateFramebuffer) {
                updateFramebufferSize();
            }
        } catch (RuntimeException | Error failure) {
            releaseDrawingSurfaceAfterFailure(failure);
            throw failure;
        }
    }

    protected void afterRender() {
        clearCurrentAndUnlock();
    }

    private void clearCurrentAndUnlock() {
        Throwable failure = null;
        try {
            if (!platformCanvas.makeCurrent(0L)) {
                failure = new IllegalStateException("Failed to clear the current OpenGL context");
            }
        } catch (RuntimeException | Error e) {
            failure = e;
        }
        try {
            platformCanvas.unlock(); // <- MUST unlock on Linux
        } catch (AWTException e) {
            RuntimeException unlockFailure = new RuntimeException("Failed to unlock Canvas", e);
            if (failure == null) {
                failure = unlockFailure;
            } else {
                failure.addSuppressed(unlockFailure);
            }
        } catch (RuntimeException | Error e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private void releaseDrawingSurfaceAfterFailure(Throwable failure) {
        try {
            if (!platformCanvas.makeCurrent(0L)) {
                failure.addSuppressed(new IllegalStateException("Failed to clear the current OpenGL context"));
            }
        } catch (RuntimeException | Error e) {
            failure.addSuppressed(e);
        }
        try {
            platformCanvas.unlock();
        } catch (AWTException e) {
            failure.addSuppressed(new RuntimeException("Failed to unlock Canvas", e));
        } catch (RuntimeException | Error e) {
            failure.addSuppressed(e);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw (RuntimeException) failure;
    }

    private static Throwable appendFailure(Throwable failure, Throwable additionalFailure) {
        if (failure == null) {
            return additionalFailure;
        }
        if (failure != additionalFailure) {
            failure.addSuppressed(additionalFailure);
        }
        return failure;
    }

    public <T> T executeInContext(Callable<T> callable) throws Exception {
        lifecycleLock.lock();
        try {
            beforeRender();
            Throwable callbackFailure = null;
            try {
                return callable.call();
            } catch (Exception | Error failure) {
                callbackFailure = failure;
                throw failure;
            } finally {
                afterRender(callbackFailure);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void runInContext(Runnable runnable) {
        lifecycleLock.lock();
        try {
            beforeRender();
            Throwable callbackFailure = null;
            try {
                runnable.run();
            } catch (RuntimeException | Error failure) {
                callbackFailure = failure;
                throw failure;
            } finally {
                afterRender(callbackFailure);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Makes this canvas's context current and invokes {@link #initGL()} when necessary, followed by {@link #paintGL()}.
     *
     * <p>The callbacks run while the lifecycle lock is held. They must not call
     * {@link EventQueue#invokeAndWait(Runnable)}, synchronize on {@link Component#getTreeLock()}, or invoke AWT/Swing
     * operations that acquire the tree lock. Such calls can deadlock with canvas removal on the event-dispatch thread.
     * Post AWT work asynchronously instead.</p>
     */
    public void render() {
        lifecycleLock.lock();
        try {
            beforeRender();
            Throwable callbackFailure = null;
            try {
                if (!initCalled) {
                    initGL();
                    initCalled = true;
                }
                paintGL();
            } catch (RuntimeException | Error failure) {
                callbackFailure = failure;
                throw failure;
            } finally {
                afterRender(callbackFailure);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void afterRender(Throwable callbackFailure) {
        try {
            afterRender();
        } catch (RuntimeException | Error cleanupFailure) {
            handleCleanupFailure(callbackFailure, cleanupFailure);
        }
    }

    private void afterDisposeGL(Throwable callbackFailure) {
        try {
            clearCurrentAndUnlock();
        } catch (RuntimeException | Error cleanupFailure) {
            handleCleanupFailure(callbackFailure, cleanupFailure);
        }
    }

    private static void handleCleanupFailure(Throwable callbackFailure, Throwable cleanupFailure) {
        if (callbackFailure == null) {
            rethrow(cleanupFailure);
        } else if (callbackFailure != cleanupFailure) {
            callbackFailure.addSuppressed(cleanupFailure);
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

    /**
     * Optional context-current cleanup hook called before this canvas's OpenGL context is deleted.
     *
     * <p>Destroying the last context in an OpenGL share group automatically reclaims the group's OpenGL resources.
     * Applications therefore do not need to override this method merely to delete ordinary OpenGL objects owned by a
     * context that is the last member of its share group.</p>
     *
     * <p>Destroying one context has no effect on shared objects while another context in the share group remains alive.
     * If this canvas belongs to a share group created through {@link GLData#shareContext}, subclasses can override this
     * method to delete buffers, programs and shaders, renderbuffers, samplers, sync objects, or textures logically owned
     * by this canvas. Normal OpenGL deletion semantics still apply, so the implementation may retain an object's storage
     * while another context continues to reference it. This hook can also perform other cleanup that requires a current
     * context.</p>
     *
     * <p>The drawing surface is locked and the context is current while this callback runs. It is invoked at most once
     * for each created context, including when {@link #initGL()} was never called or did not complete. It is not invoked
     * when there is no context or when the context cannot be made current.</p>
     *
     * <p>This callback runs on the thread that disposes the canvas, which is normally the AWT event-dispatch thread for
     * automatic removal and may differ from the rendering thread. LWJGL's OpenGL and OpenGL ES capabilities are
     * thread-local; applications rendering on another thread must install the corresponding capabilities before issuing
     * GL calls here and clear or restore them before returning.</p>
     *
     * <p>Any {@link RuntimeException} or {@link Error} thrown by this callback is rethrown from the disposing operation
     * after context and platform cleanup have been attempted. Automatic AWT removal also destroys the native peer before
     * rethrowing the failure.</p>
     *
     * <p>During automatic AWT removal, this callback also runs while AWT's tree lock is held. It must not invoke AWT or
     * Swing operations that acquire the tree lock or synchronously wait for the event-dispatch thread. Post such work
     * asynchronously instead.</p>
     *
     * <p>This callback must not make the context non-current or invoke {@link #render()},
     * {@link #runInContext(Runnable)}, or {@link #executeInContext(Callable)}. Such method calls fail with an
     * {@link IllegalStateException}; a reentrant {@link #disposeCanvas()} call is ignored.</p>
     */
    protected void disposeGL() {
    }

    public int getFramebufferWidth() {
        return framebufferWidth;
    }

    public int getFramebufferHeight() {
        return framebufferHeight;
    }

    private void updateFramebufferSize() {
        int[] platformFramebufferSize = new int[2];
        if (platformCanvas.getFramebufferSize(platformFramebufferSize)) {
            framebufferWidth = Math.max(0, platformFramebufferSize[0]);
            framebufferHeight = Math.max(0, platformFramebufferSize[1]);
        } else {
            updateFramebufferSizeFromComponent();
        }
    }

    private void updateFramebufferSizeFromComponent() {
        int[] size = new int[2];
        FramebufferSizeUtil.getScaledSize(this, getWidth(), getHeight(), size);
        framebufferWidth = size[0];
        framebufferHeight = size[1];
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
