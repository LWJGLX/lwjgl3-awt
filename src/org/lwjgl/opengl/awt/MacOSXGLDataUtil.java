package org.lwjgl.opengl.awt;

import java.awt.AWTException;
import java.util.ArrayList;
import java.util.List;

final class MacOSXGLDataUtil {
    static final int NS_OPENGL_PFA_DOUBLE_BUFFER = 5;
    static final int NS_OPENGL_PFA_STEREO = 6;
    static final int NS_OPENGL_PFA_COLOR_SIZE = 8;
    static final int NS_OPENGL_PFA_ALPHA_SIZE = 11;
    static final int NS_OPENGL_PFA_DEPTH_SIZE = 12;
    static final int NS_OPENGL_PFA_STENCIL_SIZE = 13;
    static final int NS_OPENGL_PFA_ACCUM_SIZE = 14;
    static final int NS_OPENGL_PFA_SAMPLE_BUFFERS = 55;
    static final int NS_OPENGL_PFA_SAMPLES = 56;
    static final int NS_OPENGL_PFA_COLOR_FLOAT = 58;
    static final int NS_OPENGL_PFA_ACCELERATED = 73;
    static final int NS_OPENGL_PFA_CLOSEST_POLICY = 74;
    static final int NS_OPENGL_PFA_OPENGL_PROFILE = 99;

    static final int NS_OPENGL_PROFILE_LEGACY = 0x1000;
    static final int NS_OPENGL_PROFILE_3_2_CORE = 0x3200;
    static final int NS_OPENGL_PROFILE_4_1_CORE = 0x4100;

    private MacOSXGLDataUtil() {
    }

    static void validateAttributes(GLData data) throws AWTException {
        GLUtil.validateAttributes(data);
        if (data.api != GLData.API.GL) {
            throw new AWTException("macOS NSOpenGL does not support OpenGL ES contexts");
        }
        if (data.shareContext != null) {
            throw new AWTException("macOS NSOpenGL context sharing is not supported");
        }
        if (data.debug) {
            throw new AWTException("macOS NSOpenGL debug contexts are not supported");
        }
        if (data.sRGB) {
            throw new AWTException("macOS NSOpenGL sRGB pixel formats are not supported");
        }
        if (data.contextReleaseBehavior != null) {
            throw new AWTException("macOS NSOpenGL context release behavior is not supported");
        }
        if (data.colorSamplesNV > 0) {
            throw new AWTException("macOS NSOpenGL coverage sampling is not supported");
        }
        if (data.swapGroupNV > 0 || data.swapBarrierNV > 0) {
            throw new AWTException("macOS NSOpenGL swap groups and barriers are not supported");
        }
        if (data.robustness) {
            throw new AWTException("macOS NSOpenGL robustness contexts are not supported");
        }
        if (data.profile == GLData.Profile.COMPATIBILITY) {
            throw new AWTException("macOS NSOpenGL does not support compatibility profiles newer than OpenGL 2.1");
        }
        if (data.majorVersion > 4 || (data.majorVersion == 4 && data.minorVersion > 1)) {
            throw new AWTException("macOS NSOpenGL supports OpenGL versions up to 4.1");
        }
    }

    static PixelFormatSelection choosePixelFormat(GLData data, PixelFormatFactory factory) throws AWTException {
        List<int[]> candidates = createPixelFormatCandidates(data);
        for (int[] candidate : candidates) {
            long pixelFormat = factory.create(candidate);
            if (pixelFormat != 0L) {
                return new PixelFormatSelection(pixelFormat, candidate);
            }
        }
        throw new AWTException("No compatible macOS OpenGL pixel format found after "
                + candidates.size() + " attempts");
    }

    static List<int[]> createPixelFormatCandidates(GLData data) {
        List<int[]> candidates = new ArrayList<>();
        boolean includeSamples = data.samples > 0;
        boolean includeAccumulation = accumulatorSize(data) > 0;
        boolean includeStereo = data.stereo;
        boolean includeFloat = data.pixelFormatFloat;
        candidates.add(createPixelFormatAttributes(data, includeSamples, includeAccumulation,
                includeStereo, includeFloat, true));

        // Relax cumulatively from the most to the least exotic attribute. Dropping stereo and floating-point color
        // first preserves commonly supported requests such as multisampling whenever possible.
        if (includeStereo) {
            includeStereo = false;
            candidates.add(createPixelFormatAttributes(data, includeSamples, includeAccumulation,
                    false, includeFloat, true));
        }
        if (includeFloat) {
            includeFloat = false;
            candidates.add(createPixelFormatAttributes(data, includeSamples, includeAccumulation,
                    includeStereo, false, true));
        }
        if (includeAccumulation) {
            includeAccumulation = false;
            candidates.add(createPixelFormatAttributes(data, includeSamples, false,
                    includeStereo, includeFloat, true));
        }
        if (includeSamples) {
            includeSamples = false;
            candidates.add(createPixelFormatAttributes(data, false, includeAccumulation,
                    includeStereo, includeFloat, true));
        }
        candidates.add(createPixelFormatAttributes(data, false, false, false, false, false));
        return candidates;
    }

    static int[] createPixelFormatAttributes(GLData data, boolean includeOptional, boolean accelerated) {
        return createPixelFormatAttributes(data, includeOptional && data.samples > 0,
                includeOptional && accumulatorSize(data) > 0,
                includeOptional && data.stereo,
                includeOptional && data.pixelFormatFloat,
                accelerated);
    }

    private static int[] createPixelFormatAttributes(GLData data, boolean includeSamples,
            boolean includeAccumulation, boolean includeStereo, boolean includeFloat, boolean accelerated) {
        List<Integer> attributes = new ArrayList<>();
        if (accelerated) {
            attributes.add(NS_OPENGL_PFA_ACCELERATED);
        }
        attributes.add(NS_OPENGL_PFA_CLOSEST_POLICY);
        if (data.doubleBuffer) {
            attributes.add(NS_OPENGL_PFA_DOUBLE_BUFFER);
        }
        if (includeStereo) {
            attributes.add(NS_OPENGL_PFA_STEREO);
        }
        if (includeFloat) {
            attributes.add(NS_OPENGL_PFA_COLOR_FLOAT);
        }

        int accumSize = accumulatorSize(data);
        if (includeAccumulation) {
            addValue(attributes, NS_OPENGL_PFA_ACCUM_SIZE, accumSize);
        }

        int colorSize = data.redSize + data.greenSize + data.blueSize + data.alphaSize;
        if (colorSize == 0) {
            colorSize = 32;
        } else if (colorSize < 15) {
            colorSize = 15;
        }
        if (includeFloat && colorSize < 64) {
            colorSize = 64;
        }
        addValue(attributes, NS_OPENGL_PFA_COLOR_SIZE, colorSize);
        addValue(attributes, NS_OPENGL_PFA_ALPHA_SIZE, data.alphaSize);
        addValue(attributes, NS_OPENGL_PFA_DEPTH_SIZE, data.depthSize);
        addValue(attributes, NS_OPENGL_PFA_STENCIL_SIZE, data.stencilSize);

        if (includeSamples) {
            addValue(attributes, NS_OPENGL_PFA_SAMPLE_BUFFERS, 1);
            addValue(attributes, NS_OPENGL_PFA_SAMPLES, data.samples);
        }

        addValue(attributes, NS_OPENGL_PFA_OPENGL_PROFILE, profileAttribute(data));
        attributes.add(0);

        int[] result = new int[attributes.size()];
        for (int i = 0; i < attributes.size(); i++) {
            result[i] = attributes.get(i);
        }
        return result;
    }

    static int profileAttribute(GLData data) {
        if (data.profile == GLData.Profile.COMPATIBILITY) {
            return NS_OPENGL_PROFILE_LEGACY;
        }
        if (data.majorVersion >= 4 || (data.majorVersion == 3 && data.minorVersion > 2)) {
            return NS_OPENGL_PROFILE_4_1_CORE;
        }
        if (data.profile == GLData.Profile.CORE || data.majorVersion >= 3) {
            return NS_OPENGL_PROFILE_3_2_CORE;
        }
        return NS_OPENGL_PROFILE_LEGACY;
    }

    private static int accumulatorSize(GLData data) {
        return data.accumRedSize + data.accumGreenSize + data.accumBlueSize + data.accumAlphaSize;
    }

    private static void addValue(List<Integer> attributes, int attribute, int value) {
        attributes.add(attribute);
        attributes.add(value);
    }

    interface PixelFormatFactory {
        long create(int[] attributes);
    }

    static final class PixelFormatSelection {
        final long pixelFormat;
        final int[] attributes;

        PixelFormatSelection(long pixelFormat, int[] attributes) {
            this.pixelFormat = pixelFormat;
            this.attributes = attributes;
        }
    }
}
