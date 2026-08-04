package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.macosx.MacOSXLibrary;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;

/**
 * Enables Robot screen-capture tests where they are safe to run.
 *
 * <p>Linux and Windows retain their normal coverage. macOS defaults to {@code off}, because direct capture can
 * display privacy UI on unattended machines. On macOS 14 and earlier, set {@value #MODE_PROPERTY} to {@code auto}
 * to run only when the Core Graphics permission preflight succeeds. Set it to {@code required} on an explicitly
 * authorized machine to make a missing permission a test error. The preflight never requests the normal
 * screen-capture permission; required mode assumes any macOS 15+ private-picker allowance is already granted.</p>
 */
public final class RobotScreenCaptureCondition implements ExecutionCondition {

    static final String MODE_PROPERTY = "lwjgl3.awt.robotScreenshots";

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Robot screen capture is available");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Platform platform = Platform.get();
        if (platform == Platform.LINUX || platform == Platform.WINDOWS) {
            return ENABLED;
        }
        if (platform != Platform.MACOSX) {
            return ConditionEvaluationResult.disabled("Robot screenshot tests support Linux, macOS and Windows");
        }

        Mode mode = parseMode(System.getProperty(MODE_PROPERTY, Mode.OFF.name()));
        if (mode == Mode.OFF) {
            return ConditionEvaluationResult.disabled(
                    "macOS Robot screenshots are disabled; use -Pmacos-robot-screenshots on an authorized Mac");
        }
        if (mode == Mode.AUTO && hasPrivateWindowPicker()) {
            // macOS 15 added a separate, monthly authorization for bypassing its content picker. There is no
            // public preflight for that authorization, so automatic capture would risk opening privacy UI.
            return ConditionEvaluationResult.disabled(
                    "macOS 15+ Robot screenshots require explicit opt-in with -Pmacos-robot-screenshots");
        }

        PermissionCheck permission = MacOSPermissionHolder.PERMISSION;
        if (permission.granted) {
            return ENABLED;
        }

        String message = "macOS screen-capture permission is not available to this process"
                + (permission.failure == null ? "" : ": " + permission.failure);
        if (mode == Mode.REQUIRED) {
            throw new ExtensionConfigurationException(message
                    + ". Grant Screen & System Audio Recording access, restart the process, and retry.");
        }
        return ConditionEvaluationResult.disabled(message);
    }

    static Mode parseMode(String value) {
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ExtensionConfigurationException(
                    "Invalid " + MODE_PROPERTY + " value '" + value + "'; expected off, auto or required",
                    exception);
        }
    }

    enum Mode {
        OFF,
        AUTO,
        REQUIRED
    }

    private static boolean hasPrivateWindowPicker() {
        return hasPrivateWindowPicker(System.getProperty("os.version", ""));
    }

    static boolean hasPrivateWindowPicker(String version) {
        String[] components = version.split("\\.", 3);
        try {
            int major = Integer.parseInt(components[0]);
            if (major >= 15) {
                return true;
            }
            // Older runtimes may report 10.16 for any post-Catalina macOS release. Treat it conservatively,
            // because the real system version cannot be determined safely from this process.
            return major == 10 && components.length > 1 && Integer.parseInt(components[1]) >= 16;
        } catch (NumberFormatException exception) {
            // Be conservative if a future runtime changes the version format.
            return true;
        }
    }

    private static PermissionCheck preflightMacOSPermission() {
        try (SharedLibrary coreGraphics =
                     MacOSXLibrary.create("/System/Library/Frameworks/CoreGraphics.framework")) {
            long preflight = coreGraphics.getFunctionAddress("CGPreflightScreenCaptureAccess");
            if (preflight == MemoryUtil.NULL) {
                // The preflight API was introduced together with screen-capture permission on macOS 10.15.
                return new PermissionCheck(true, null);
            }
            return new PermissionCheck(JNI.invokeI(preflight) != 0, null);
        } catch (RuntimeException | UnsatisfiedLinkError exception) {
            return new PermissionCheck(false, exception.getMessage());
        }
    }

    private static final class MacOSPermissionHolder {
        private static final PermissionCheck PERMISSION = preflightMacOSPermission();
    }

    private static final class PermissionCheck {
        private final boolean granted;
        private final String failure;

        private PermissionCheck(boolean granted, String failure) {
            this.granted = granted;
            this.failure = failure;
        }
    }
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RobotScreenCaptureCondition.class)
@interface EnabledForRobotScreenCapture {
}
