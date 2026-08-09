package org.lwjgl.opengl.awt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.opengl.awt.GLData.API;
import org.lwjgl.opengl.awt.GLData.VersionPolicy;

class GLUtil {
    private static final ContextVersion[] GL_VERSIONS = {
            version(4, 6), version(4, 5), version(4, 4), version(4, 3), version(4, 2), version(4, 1), version(4, 0),
            version(3, 3), version(3, 2), version(3, 1), version(3, 0),
            version(2, 1), version(2, 0),
            version(1, 5), version(1, 4), version(1, 3), version(1, 2), version(1, 1), version(1, 0)
    };
    private static final ContextVersion[] GLES_VERSIONS = {
            version(3, 2), version(3, 1), version(3, 0),
            version(2, 0), version(1, 1), version(1, 0)
    };
    
    static boolean atLeast32(int major, int minor) {
        return major == 3 && minor >= 2 || major > 3;
    }

    static boolean atLeast30(int major, int minor) {
        return major == 3 && minor >= 0 || major > 3;
    }

    static boolean validVersionGL(int major, int minor) {
        return major == 0 && minor == 0 || contains(GL_VERSIONS, major, minor);
    }

    public static boolean validVersionGLES(int major, int minor) {
        return major == 0 && minor == 0 || contains(GLES_VERSIONS, major, minor);
    }

    static List<ContextVersion> contextVersionCandidates(GLData data, int maximumMajor, int maximumMinor) {
        if (data.versionPolicy == VersionPolicy.EXACT) {
            return Collections.singletonList(version(data.majorVersion, data.minorVersion));
        }

        ContextVersion[] versions = data.api == API.GLES ? GLES_VERSIONS : GL_VERSIONS;
        List<ContextVersion> candidates = new ArrayList<>();
        for (ContextVersion version : versions) {
            if (compare(version.major, version.minor, maximumMajor, maximumMinor) > 0) {
                continue;
            }
            if (data.versionPolicy == VersionPolicy.AT_LEAST
                    && compare(version.major, version.minor, data.majorVersion, data.minorVersion) < 0) {
                continue;
            }
            if (data.api == API.GL && data.profile != null && !atLeast32(version.major, version.minor)) {
                continue;
            }
            if (data.forwardCompatible && !atLeast30(version.major, version.minor)) {
                continue;
            }
            candidates.add(version);
        }
        return candidates;
    }

    static String describeVersionRequest(GLData data) {
        if (data.versionPolicy == VersionPolicy.HIGHEST) {
            return "the highest supported " + apiName(data.api) + " version";
        }
        return (data.api == API.GLES ? "OpenGL ES " : "OpenGL ")
                + data.majorVersion + "." + data.minorVersion + " or later";
    }

    private static String apiName(API api) {
        return api == API.GLES ? "OpenGL ES" : "OpenGL";
    }

    private static int compare(int major, int minor, int otherMajor, int otherMinor) {
        int majorComparison = Integer.compare(major, otherMajor);
        return majorComparison != 0 ? majorComparison : Integer.compare(minor, otherMinor);
    }

    private static boolean contains(ContextVersion[] versions, int major, int minor) {
        for (ContextVersion version : versions) {
            if (version.major == major && version.minor == minor) {
                return true;
            }
        }
        return false;
    }

    private static ContextVersion version(int major, int minor) {
        return new ContextVersion(major, minor);
    }

    /**
     * Validate the given {@link GLData} and throw an exception on validation error.
     * 
     * @param attribs
     *            the {@link GLData} to validate
     */
    static void validateAttributes(GLData attribs) {
        if (attribs.alphaSize < 0) {
            throw new IllegalArgumentException("Alpha bits cannot be less than 0");
        }
        if (attribs.redSize < 0) {
            throw new IllegalArgumentException("Red bits cannot be less than 0");
        }
        if (attribs.greenSize < 0) {
            throw new IllegalArgumentException("Green bits cannot be less than 0");
        }
        if (attribs.blueSize < 0) {
            throw new IllegalArgumentException("Blue bits cannot be less than 0");
        }
        if (attribs.stencilSize < 0) {
            throw new IllegalArgumentException("Stencil bits cannot be less than 0");
        }
        if (attribs.depthSize < 0) {
            throw new IllegalArgumentException("Depth bits cannot be less than 0");
        }
        validateVersionAttributes(attribs);
        if (attribs.samples < 0) {
            throw new IllegalArgumentException("Invalid samples count");
        }
        if (!attribs.doubleBuffer && attribs.swapInterval != null) {
            throw new IllegalArgumentException("Swap interval set but not using double buffering");
        }
        if (attribs.colorSamplesNV < 0) {
            throw new IllegalArgumentException("Invalid color samples count");
        }
        if (attribs.colorSamplesNV > attribs.samples) {
            throw new IllegalArgumentException("Color samples greater than number of (coverage) samples");
        }
        if (attribs.swapGroupNV < 0) {
            throw new IllegalArgumentException("Invalid swap group");
        }
        if (attribs.swapBarrierNV < 0) {
            throw new IllegalArgumentException("Invalid swap barrier");
        }
        if ((attribs.swapGroupNV > 0 || attribs.swapBarrierNV > 0) && !attribs.doubleBuffer) {
            throw new IllegalArgumentException("Swap group or barrier requested but not using double buffering");
        }
        if (attribs.swapBarrierNV > 0 && attribs.swapGroupNV == 0) {
            throw new IllegalArgumentException("Swap barrier requested but no valid swap group set");
        }
        if (attribs.loseContextOnReset && !attribs.robustness) {
            throw new IllegalArgumentException("Lose context notification requested but not using robustness");
        }
        if (attribs.contextResetIsolation && !attribs.robustness) {
            throw new IllegalArgumentException("Context reset isolation requested but not using robustness");
        }
    }

    static void validateVersionAttributes(GLData attribs) {
        if (attribs.versionPolicy == null) {
            throw new IllegalArgumentException("Unspecified context version policy");
        }
        if (attribs.api == null) {
            throw new IllegalArgumentException("Unspecified client API");
        }
        if (attribs.api == API.GLES && attribs.profile != null) {
            throw new IllegalArgumentException("OpenGL ES does not support desktop OpenGL context profiles");
        }
        if (attribs.forwardCompatible && attribs.versionPolicy != VersionPolicy.HIGHEST
                && !atLeast30(attribs.majorVersion, attribs.minorVersion)) {
            throw new IllegalArgumentException("Forward-compatibility is only defined for OpenGL version 3.0 and above");
        }
        if (attribs.profile != null && attribs.versionPolicy != VersionPolicy.HIGHEST
                && !atLeast32(attribs.majorVersion, attribs.minorVersion)) {
            throw new IllegalArgumentException("Context profiles are only defined for OpenGL version 3.2 and above");
        }
        if (attribs.versionPolicy == VersionPolicy.HIGHEST) {
            return;
        }
        if (attribs.versionPolicy == VersionPolicy.AT_LEAST && attribs.majorVersion == 0) {
            throw new IllegalArgumentException("AT_LEAST context version policy requires a minimum version");
        }
        if (attribs.api == API.GL && !validVersionGL(attribs.majorVersion, attribs.minorVersion)) {
            throw new IllegalArgumentException("Invalid OpenGL version");
        }
        if (attribs.api == API.GLES && !validVersionGLES(attribs.majorVersion, attribs.minorVersion)) {
            throw new IllegalArgumentException("Invalid OpenGL ES version");
        }
    }

    static final class ContextVersion {
        final int major;
        final int minor;

        private ContextVersion(int major, int minor) {
            this.major = major;
            this.minor = minor;
        }

        @Override
        public String toString() {
            return major + "." + minor;
        }
    }

}
