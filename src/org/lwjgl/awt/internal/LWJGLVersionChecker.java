package org.lwjgl.awt.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Internal runtime dependency diagnostic. This class is public only so lwjgl3-awt entry points in sibling packages
 * can trigger the check; it is not part of the supported API.
 */
public final class LWJGLVersionChecker {
    private static final String METADATA_RESOURCE = "/META-INF/lwjgl3-awt.properties";
    private static final Module[] MODULES = {
            new Module("lwjgl", "org.lwjgl.Version"),
            new Module("lwjgl-jawt", "org.lwjgl.system.jawt.JAWT"),
            new Module("lwjgl-egl", "org.lwjgl.egl.EGL"),
            new Module("lwjgl-opengl", "org.lwjgl.opengl.GL"),
            new Module("lwjgl-vulkan", "org.lwjgl.vulkan.VK")
    };
    private static final boolean INITIALIZED = initialize();

    private LWJGLVersionChecker() {
    }

    /**
     * Triggers the one-time runtime dependency check.
     */
    public static void check() {
        if (!INITIALIZED) {
            throw new AssertionError("LWJGL version check did not initialize");
        }
    }

    private static boolean initialize() {
        try {
            BuildMetadata metadata = loadBuildMetadata();
            if (metadata != null && isResolvedVersion(metadata.lwjglVersion)) {
                warnIfIncompatible(metadata, resolveModules(LWJGLVersionChecker.class.getClassLoader()), System.err);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never prevent the library from starting.
        }
        return true;
    }

    static BuildMetadata loadBuildMetadata() {
        try (InputStream input = LWJGLVersionChecker.class.getResourceAsStream(METADATA_RESOURCE)) {
            if (input == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(input);
            return new BuildMetadata(
                    properties.getProperty("lwjgl3-awt.version"),
                    properties.getProperty("lwjgl.version"));
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    static List<ModuleVersion> resolveModules(ClassLoader classLoader) {
        List<ModuleVersion> versions = new ArrayList<>();
        for (Module module : MODULES) {
            ModuleVersion version = resolveModule(classLoader, module);
            if (version != null) {
                versions.add(version);
            }
        }
        return versions;
    }

    private static ModuleVersion resolveModule(ClassLoader classLoader, Module module) {
        try {
            Class<?> moduleClass = Class.forName(module.className, false, classLoader);
            Package modulePackage = moduleClass.getPackage();
            if (modulePackage == null) {
                return null;
            }
            String version = modulePackage.getSpecificationVersion();
            return isResolvedVersion(version) ? new ModuleVersion(module.name, version) : null;
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    static boolean warnIfIncompatible(BuildMetadata metadata, List<ModuleVersion> modules, PrintStream output) {
        List<ModuleVersion> mismatches = new ArrayList<>();
        for (ModuleVersion module : modules) {
            if (!metadata.lwjglVersion.equals(module.version)) {
                mismatches.add(module);
            }
        }
        if (mismatches.isEmpty()) {
            return false;
        }

        String libraryVersion = isResolvedVersion(metadata.libraryVersion)
                ? metadata.libraryVersion
                : "unknown";
        StringBuilder warning = new StringBuilder(384);
        warning.append("[LWJGLX] [WARN] Incompatible LWJGL module versions detected.\n")
                .append("lwjgl3-awt ").append(libraryVersion)
                .append(" was compiled against LWJGL ").append(metadata.lwjglVersion)
                .append(", but the runtime provides:\n");
        for (ModuleVersion mismatch : mismatches) {
            warning.append("  ").append(mismatch.name).append(": ").append(mismatch.version).append('\n');
        }
        warning.append("Use one LWJGL version for all org.lwjgl modules and a lwjgl3-awt build compiled against that ")
                .append("version (the LWJGL BOM can align Maven or Gradle dependencies). Otherwise binary linkage ")
                .append("errors such as NoSuchMethodError may occur.\n");
        output.print(warning);
        return true;
    }

    private static boolean isResolvedVersion(String version) {
        return version != null && !version.isEmpty() && version.indexOf("${") < 0;
    }

    static final class BuildMetadata {
        final String libraryVersion;
        final String lwjglVersion;

        BuildMetadata(String libraryVersion, String lwjglVersion) {
            this.libraryVersion = libraryVersion;
            this.lwjglVersion = lwjglVersion;
        }
    }

    static final class ModuleVersion {
        final String name;
        final String version;

        ModuleVersion(String name, String version) {
            this.name = name;
            this.version = version;
        }
    }

    private static final class Module {
        final String name;
        final String className;

        Module(String name, String className) {
            this.name = name;
            this.className = className;
        }
    }
}
