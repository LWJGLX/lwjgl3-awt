package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;

import java.awt.AWTException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxGLDataUtilTest {
    @Test
    void doesNotRequireSwapControlWhenNoIntervalIsRequested() {
        assertDoesNotThrow(() -> LinuxGLDataUtil.validateSwapInterval(null, Collections.emptySet()));
    }

    @Test
    void requiresSwapControlWhenAnIntervalIsRequested() {
        AWTException failure = assertThrows(AWTException.class,
                () -> LinuxGLDataUtil.validateSwapInterval(0, Collections.emptySet()));

        assertTrue(failure.getMessage().contains("GLX_EXT_swap_control"));
    }

    @Test
    void acceptsNonNegativeIntervalsWithSwapControl() {
        assertDoesNotThrow(() -> LinuxGLDataUtil.validateSwapInterval(
                0, Collections.singleton("GLX_EXT_swap_control")));
        assertDoesNotThrow(() -> LinuxGLDataUtil.validateSwapInterval(
                1, Collections.singleton("GLX_EXT_swap_control")));
    }

    @Test
    void requiresSwapControlTearForNegativeIntervals() {
        AWTException failure = assertThrows(AWTException.class,
                () -> LinuxGLDataUtil.validateSwapInterval(
                        -1, Collections.singleton("GLX_EXT_swap_control")));

        assertTrue(failure.getMessage().contains("GLX_EXT_swap_control_tear"));
    }

    @Test
    void acceptsNegativeIntervalsWithSwapControlTear() {
        assertDoesNotThrow(() -> LinuxGLDataUtil.validateSwapInterval(-1,
                Arrays.asList("GLX_EXT_swap_control", "GLX_EXT_swap_control_tear")));
    }
}
