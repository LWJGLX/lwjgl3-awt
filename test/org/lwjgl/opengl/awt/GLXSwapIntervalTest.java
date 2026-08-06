package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;

import java.awt.AWTException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GLXSwapIntervalTest {
    @Test
    void doesNotSelectSwapControlWhenNoIntervalIsRequested() throws AWTException {
        assertNull(GLXSwapInterval.select(null, Collections.<String>emptySet()));
    }

    @Test
    void prefersExtSwapControl() throws AWTException {
        assertEquals(GLXSwapInterval.Mechanism.EXT, GLXSwapInterval.select(0,
                extensions(GLXSwapInterval.EXT_SWAP_CONTROL,
                        GLXSwapInterval.MESA_SWAP_CONTROL,
                        GLXSwapInterval.SGI_SWAP_CONTROL)));
    }

    @Test
    void fallsBackToMesaForNonNegativeIntervals() throws AWTException {
        Set<String> extensions = extensions(
                GLXSwapInterval.MESA_SWAP_CONTROL, GLXSwapInterval.SGI_SWAP_CONTROL);

        assertEquals(GLXSwapInterval.Mechanism.MESA, GLXSwapInterval.select(0, extensions));
        assertEquals(GLXSwapInterval.Mechanism.MESA, GLXSwapInterval.select(1, extensions));
    }

    @Test
    void fallsBackToSgiForPositiveIntervals() throws AWTException {
        assertEquals(GLXSwapInterval.Mechanism.SGI, GLXSwapInterval.select(1,
                extensions(GLXSwapInterval.SGI_SWAP_CONTROL)));
    }

    @Test
    void doesNotUseSgiToDisableSynchronization() {
        AWTException failure = assertThrows(AWTException.class,
                () -> GLXSwapInterval.select(0,
                        extensions(GLXSwapInterval.SGI_SWAP_CONTROL)));

        assertEquals(GLXSwapInterval.NO_COMPATIBLE_EXTENSION, failure.getMessage());
    }

    @Test
    void rejectsIntervalsWhenNoCompatibleExtensionExists() {
        AWTException failure = assertThrows(AWTException.class,
                () -> GLXSwapInterval.select(1, Collections.<String>emptySet()));

        assertEquals(GLXSwapInterval.NO_COMPATIBLE_EXTENSION, failure.getMessage());
    }

    @Test
    void requiresExtSwapControlTearForNegativeIntervals() {
        AWTException failure = assertThrows(AWTException.class,
                () -> GLXSwapInterval.select(-1,
                        extensions(GLXSwapInterval.EXT_SWAP_CONTROL,
                                GLXSwapInterval.MESA_SWAP_CONTROL,
                                GLXSwapInterval.SGI_SWAP_CONTROL)));

        assertTrue(failure.getMessage().contains(GLXSwapInterval.EXT_SWAP_CONTROL_TEAR));
    }

    @Test
    void acceptsNegativeIntervalsWithExtSwapControlTear() throws AWTException {
        assertEquals(GLXSwapInterval.Mechanism.EXT, GLXSwapInterval.select(-1,
                extensions(GLXSwapInterval.EXT_SWAP_CONTROL,
                        GLXSwapInterval.EXT_SWAP_CONTROL_TEAR)));
    }

    @Test
    void parsesNullEmptyAndWhitespaceSeparatedExtensionStrings() {
        assertTrue(GLXSwapInterval.parseExtensions(null).isEmpty());
        assertTrue(GLXSwapInterval.parseExtensions("   ").isEmpty());
        assertEquals(extensions("GLX_one", "GLX_two"),
                GLXSwapInterval.parseExtensions("  GLX_one\tGLX_two  "));
    }

    private static Set<String> extensions(String... extensions) {
        return new HashSet<>(Arrays.asList(extensions));
    }
}
