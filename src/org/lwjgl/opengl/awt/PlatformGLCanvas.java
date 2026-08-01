package org.lwjgl.opengl.awt;

import java.awt.AWTException;
import java.awt.Canvas;

/**
 * Interface for platform-specific implementations of {@link AWTGLCanvas}.
 *
 * @author Kai Burjack
 */
public interface PlatformGLCanvas {
    long create(Canvas canvas, GLData data, GLData effective) throws AWTException;
    boolean deleteContext(long context);
    boolean makeCurrent(long context);
    boolean isCurrent(long context);
    boolean swapBuffers();
    boolean delayBeforeSwapNV(float seconds);

    /**
     * Acquires and locks a JAWT drawing surface for the current render operation.
     */
    void lock() throws AWTException;

    /**
     * Unlocks and frees the JAWT drawing surface acquired by {@link #lock()}.
     * This must be called on the same thread as {@code lock()}.
     */
    void unlock() throws AWTException;

    /**
     * Releases platform resources that outlive a drawing-surface lock cycle.
     */
    void dispose();
}
