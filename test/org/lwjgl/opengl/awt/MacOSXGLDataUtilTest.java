package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;

import java.awt.AWTException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_ACCELERATED;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_ACCUM_SIZE;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_ALPHA_SIZE;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_COLOR_FLOAT;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_COLOR_SIZE;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_DEPTH_SIZE;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_DOUBLE_BUFFER;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_OPENGL_PROFILE;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_SAMPLE_BUFFERS;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_SAMPLES;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_STENCIL_SIZE;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PFA_STEREO;
import static org.lwjgl.opengl.awt.MacOSXGLDataUtil.NS_OPENGL_PROFILE_4_1_CORE;

class MacOSXGLDataUtilTest {

    @Test
    void encodesDoubleBufferAndCoreProfileExactlyOnce() {
        GLData data = new GLData();
        data.majorVersion = 4;
        data.minorVersion = 1;
        data.profile = GLData.Profile.CORE;
        data.doubleBuffer = true;

        int[] attributes = MacOSXGLDataUtil.createPixelFormatAttributes(data, true, true);

        assertTrue(contains(attributes, NS_OPENGL_PFA_DOUBLE_BUFFER));
        assertTrue(containsPair(attributes, NS_OPENGL_PFA_COLOR_SIZE, 32));
        assertEquals(1, count(attributes, NS_OPENGL_PFA_OPENGL_PROFILE));
        assertTrue(containsPair(attributes, NS_OPENGL_PFA_OPENGL_PROFILE, NS_OPENGL_PROFILE_4_1_CORE));
    }

    @Test
    void omitsDoubleBufferWhenSingleBufferingIsRequested() {
        GLData data = new GLData();
        data.doubleBuffer = false;

        int[] attributes = MacOSXGLDataUtil.createPixelFormatAttributes(data, true, true);

        assertFalse(contains(attributes, NS_OPENGL_PFA_DOUBLE_BUFFER));
    }

    @Test
    void retriesWithRelaxedOptionalAttributes() throws Exception {
        GLData data = new GLData();
        data.stereo = true;
        data.samples = 16;
        List<int[]> attempts = new ArrayList<>();

        MacOSXGLDataUtil.PixelFormatSelection selection = MacOSXGLDataUtil.choosePixelFormat(data, attributes -> {
            attempts.add(attributes);
            return attempts.size() == 2 ? 42L : 0L;
        });

        assertEquals(42L, selection.pixelFormat);
        assertEquals(2, attempts.size());
        assertTrue(contains(attempts.get(0), NS_OPENGL_PFA_STEREO));
        assertTrue(contains(attempts.get(0), NS_OPENGL_PFA_SAMPLES));
        assertFalse(contains(attempts.get(1), NS_OPENGL_PFA_STEREO));
        assertTrue(contains(attempts.get(1), NS_OPENGL_PFA_SAMPLES));
        assertSame(attempts.get(1), selection.attributes);
    }

    @Test
    void relaxesMostExoticAttributesFirst() {
        GLData data = new GLData();
        data.stereo = true;
        data.pixelFormatFloat = true;
        data.accumRedSize = 8;
        data.samples = 4;

        List<int[]> candidates = MacOSXGLDataUtil.createPixelFormatCandidates(data);

        assertEquals(6, candidates.size());
        assertTrue(contains(candidates.get(0), NS_OPENGL_PFA_STEREO));
        assertTrue(contains(candidates.get(0), NS_OPENGL_PFA_COLOR_FLOAT));
        assertTrue(contains(candidates.get(0), NS_OPENGL_PFA_ACCUM_SIZE));
        assertTrue(contains(candidates.get(0), NS_OPENGL_PFA_SAMPLES));

        assertFalse(contains(candidates.get(1), NS_OPENGL_PFA_STEREO));
        assertTrue(contains(candidates.get(1), NS_OPENGL_PFA_COLOR_FLOAT));
        assertFalse(contains(candidates.get(2), NS_OPENGL_PFA_COLOR_FLOAT));
        assertTrue(contains(candidates.get(2), NS_OPENGL_PFA_ACCUM_SIZE));
        assertFalse(contains(candidates.get(3), NS_OPENGL_PFA_ACCUM_SIZE));
        assertTrue(contains(candidates.get(3), NS_OPENGL_PFA_SAMPLES));
        assertFalse(contains(candidates.get(4), NS_OPENGL_PFA_SAMPLES));
        assertTrue(contains(candidates.get(4), NS_OPENGL_PFA_ACCELERATED));
        assertFalse(contains(candidates.get(5), NS_OPENGL_PFA_ACCELERATED));
    }

    @Test
    void reportsPixelFormatFailureAsAwtException() {
        AWTException exception = assertThrows(AWTException.class,
                () -> MacOSXGLDataUtil.choosePixelFormat(new GLData(), attributes -> 0L));

        assertTrue(exception.getMessage().contains("2 attempts"));
    }

    private static boolean contains(int[] values, int expected) {
        return count(values, expected) > 0;
    }

    private static int count(int[] values, int expected) {
        int count = 0;
        for (int i = 0; i < values.length && values[i] != 0;) {
            int attribute = values[i++];
            if (attribute == expected) {
                count++;
            }
            if (hasValue(attribute)) {
                i++;
            }
        }
        return count;
    }

    private static boolean containsPair(int[] values, int first, int second) {
        for (int i = 0; i < values.length && values[i] != 0;) {
            int attribute = values[i++];
            if (!hasValue(attribute)) {
                continue;
            }
            int value = values[i++];
            if (attribute == first && value == second) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasValue(int attribute) {
        return attribute == NS_OPENGL_PFA_COLOR_SIZE
                || attribute == NS_OPENGL_PFA_ALPHA_SIZE
                || attribute == NS_OPENGL_PFA_DEPTH_SIZE
                || attribute == NS_OPENGL_PFA_STENCIL_SIZE
                || attribute == NS_OPENGL_PFA_ACCUM_SIZE
                || attribute == NS_OPENGL_PFA_SAMPLE_BUFFERS
                || attribute == NS_OPENGL_PFA_SAMPLES
                || attribute == NS_OPENGL_PFA_OPENGL_PROFILE;
    }
}
