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
    void lock() throws AWTException;
    void unlock() throws AWTException;
    void dispose();
}
