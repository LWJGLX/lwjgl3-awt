package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GLUtilTest {

    @Test
    void exactPreservesTheConfiguredSingleRequest() {
        GLData data = new GLData();
        data.majorVersion = 3;
        data.minorVersion = 3;

        assertEquals(Arrays.asList("3.3"), candidates(data, 4, 6));
    }

    @Test
    void atLeastTriesCoreVersionsFromThePlatformMaximumToTheMinimum() {
        GLData data = new GLData();
        data.majorVersion = 3;
        data.minorVersion = 3;
        data.profile = GLData.Profile.CORE;
        data.versionPolicy = GLData.VersionPolicy.AT_LEAST;

        assertEquals(Arrays.asList("4.6", "4.5", "4.4", "4.3", "4.2", "4.1", "4.0", "3.3"),
                candidates(data, 4, 6));
    }

    @Test
    void highestHonorsThePlatformMaximumAndProfile() {
        GLData data = new GLData();
        data.profile = GLData.Profile.CORE;
        data.versionPolicy = GLData.VersionPolicy.HIGHEST;

        assertEquals(Arrays.asList("4.1", "4.0", "3.3", "3.2"), candidates(data, 4, 1));
    }

    @Test
    void atLeastEnumeratesOpenGLESVersionsSeparately() {
        GLData data = new GLData();
        data.api = GLData.API.GLES;
        data.majorVersion = 2;
        data.minorVersion = 0;
        data.versionPolicy = GLData.VersionPolicy.AT_LEAST;

        assertEquals(Arrays.asList("3.2", "3.1", "3.0", "2.0"), candidates(data, 3, 2));
    }

    @Test
    void highestAllowsAProfileWithoutAConfiguredVersion() {
        GLData data = new GLData();
        data.profile = GLData.Profile.CORE;
        data.versionPolicy = GLData.VersionPolicy.HIGHEST;

        GLUtil.validateAttributes(data);
    }

    @Test
    void atLeastRequiresAValidMinimum() {
        GLData data = new GLData();
        data.versionPolicy = GLData.VersionPolicy.AT_LEAST;

        assertThrows(IllegalArgumentException.class, () -> GLUtil.validateAttributes(data));
    }

    @Test
    void highestIgnoresAConfiguredVersion() {
        GLData data = new GLData();
        data.majorVersion = 3;
        data.minorVersion = 3;
        data.versionPolicy = GLData.VersionPolicy.HIGHEST;

        GLUtil.validateAttributes(data);
        assertEquals("4.6", GLUtil.contextVersionCandidates(data, 4, 6).get(0).toString());
    }

    @Test
    void recognizesTheCurrentDesktopAndEmbeddedVersionCeilings() {
        assertTrue(GLUtil.validVersionGL(4, 6));
        assertFalse(GLUtil.validVersionGL(5, 0));
        assertTrue(GLUtil.validVersionGLES(3, 2));
        assertFalse(GLUtil.validVersionGLES(3, 3));
    }

    private static List<String> candidates(GLData data, int maximumMajor, int maximumMinor) {
        List<String> versions = new ArrayList<>();
        for (GLUtil.ContextVersion version :
                GLUtil.contextVersionCandidates(data, maximumMajor, maximumMinor)) {
            versions.add(version.toString());
        }
        return versions;
    }
}
