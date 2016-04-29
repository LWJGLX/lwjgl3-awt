package org.lwjgl.opengl.awt;

import java.awt.AWTException;
import java.awt.Canvas;

public interface PlatformGLCanvas {
    long create(Canvas canvas, GLData data, GLData effective) throws AWTException;
    boolean deleteContext(long context);
    boolean makeCurrent(long context);
    boolean isCurrent(long context);
    boolean swapBuffers();
    boolean delayBeforeSwapNV(float seconds);
}
