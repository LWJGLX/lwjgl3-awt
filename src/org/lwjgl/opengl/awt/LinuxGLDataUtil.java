package org.lwjgl.opengl.awt;

import java.awt.AWTException;
import java.util.Collection;

final class LinuxGLDataUtil {
    private LinuxGLDataUtil() {
    }

    static void validateSwapInterval(Integer swapInterval, Collection<String> extensions)
            throws AWTException {
        if (swapInterval == null) {
            return;
        }
        if (!extensions.contains("GLX_EXT_swap_control")) {
            throw new AWTException("Swap interval requested but GLX_EXT_swap_control is unavailable");
        }
        if (swapInterval < 0 && !extensions.contains("GLX_EXT_swap_control_tear")) {
            throw new AWTException(
                    "Negative swap interval requested but GLX_EXT_swap_control_tear is unavailable");
        }
    }
}
