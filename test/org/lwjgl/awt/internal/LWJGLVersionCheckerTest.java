package org.lwjgl.awt.internal;

import org.junit.jupiter.api.Test;
import org.lwjgl.Version;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LWJGLVersionCheckerTest {
    @Test
    void buildMetadataContainsExpectedLwjglVersion() {
        LWJGLVersionChecker.BuildMetadata metadata = LWJGLVersionChecker.loadBuildMetadata();

        assertNotNull(metadata);
        assertNotNull(metadata.libraryVersion);
        assertFalse(metadata.libraryVersion.contains("${"));
        assertEquals(Version.class.getPackage().getSpecificationVersion(), metadata.lwjglVersion);
    }

    @Test
    void resolvedLwjglModulesMatchBuildMetadata() {
        LWJGLVersionChecker.BuildMetadata metadata = LWJGLVersionChecker.loadBuildMetadata();
        List<LWJGLVersionChecker.ModuleVersion> modules =
                LWJGLVersionChecker.resolveModules(LWJGLVersionChecker.class.getClassLoader());

        assertNotNull(metadata);
        assertFalse(modules.isEmpty());
        for (LWJGLVersionChecker.ModuleVersion module : modules) {
            assertEquals(metadata.lwjglVersion, module.version, module.name);
        }
    }

    @Test
    void warningIdentifiesMismatchedModulesAndLikelyFailure() throws Exception {
        LWJGLVersionChecker.BuildMetadata metadata =
                new LWJGLVersionChecker.BuildMetadata("0.1.8", "3.3.3");
        List<LWJGLVersionChecker.ModuleVersion> modules = Arrays.asList(
                new LWJGLVersionChecker.ModuleVersion("lwjgl", "3.3.6"),
                new LWJGLVersionChecker.ModuleVersion("lwjgl-jawt", "3.3.6"),
                new LWJGLVersionChecker.ModuleVersion("lwjgl-opengl", "3.3.3"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        boolean warned;
        try (PrintStream output = new PrintStream(bytes, true, "UTF-8")) {
            warned = LWJGLVersionChecker.warnIfIncompatible(metadata, modules, output);
        }
        String warning = bytes.toString("UTF-8");

        assertTrue(warned);
        assertTrue(warning.contains("lwjgl3-awt 0.1.8 was compiled against LWJGL 3.3.3"));
        assertTrue(warning.contains("lwjgl: 3.3.6"));
        assertTrue(warning.contains("lwjgl-jawt: 3.3.6"));
        assertFalse(warning.contains("lwjgl-opengl: 3.3.3"));
        assertTrue(warning.contains("LWJGL BOM"));
        assertTrue(warning.contains("NoSuchMethodError"));
    }

    @Test
    void compatibleModulesDoNotProduceAWarning() throws Exception {
        LWJGLVersionChecker.BuildMetadata metadata =
                new LWJGLVersionChecker.BuildMetadata("0.2.5", "3.4.2");
        List<LWJGLVersionChecker.ModuleVersion> modules = Arrays.asList(
                new LWJGLVersionChecker.ModuleVersion("lwjgl", "3.4.2"),
                new LWJGLVersionChecker.ModuleVersion("lwjgl-opengl", "3.4.2"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        boolean warned;
        try (PrintStream output = new PrintStream(bytes, true, "UTF-8")) {
            warned = LWJGLVersionChecker.warnIfIncompatible(metadata, modules, output);
        }

        assertFalse(warned);
        assertEquals("", bytes.toString("UTF-8"));
    }
}
