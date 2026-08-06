package org.lwjgl.opengl.awt;

import org.lwjgl.opengl.GL;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.JNI;

import java.awt.AWTException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.system.MemoryUtil.NULL;

final class GLXSwapInterval {
    static final String EXT_SWAP_CONTROL = "GLX_EXT_swap_control";
    static final String EXT_SWAP_CONTROL_TEAR = "GLX_EXT_swap_control_tear";
    static final String MESA_SWAP_CONTROL = "GLX_MESA_swap_control";
    static final String SGI_SWAP_CONTROL = "GLX_SGI_swap_control";
    static final String NO_COMPATIBLE_EXTENSION =
            "Swap interval requested but no compatible GLX swap-control extension is available";

    enum Mechanism {
        EXT(EXT_SWAP_CONTROL, "glXSwapIntervalEXT"),
        MESA(MESA_SWAP_CONTROL, "glXSwapIntervalMESA"),
        SGI(SGI_SWAP_CONTROL, "glXSwapIntervalSGI");

        final String extension;
        final String function;

        Mechanism(String extension, String function) {
            this.extension = extension;
            this.function = function;
        }
    }

    private final int interval;
    private final Mechanism mechanism;
    private final long functionAddress;

    private GLXSwapInterval(int interval, Mechanism mechanism, long functionAddress) {
        this.interval = interval;
        this.mechanism = mechanism;
        this.functionAddress = functionAddress;
    }

    static GLXSwapInterval create(Integer interval, Collection<String> extensions)
            throws AWTException {
        Mechanism mechanism = select(interval, extensions);
        if (mechanism == null) {
            return null;
        }

        FunctionProvider functionProvider = GL.getFunctionProvider();
        long functionAddress = functionProvider == null
                ? NULL
                : functionProvider.getFunctionAddress(mechanism.function);
        if (functionAddress == NULL) {
            throw new AWTException(mechanism.extension + " is advertised but "
                    + mechanism.function + " is unavailable");
        }
        return new GLXSwapInterval(interval, mechanism, functionAddress);
    }

    static Mechanism select(Integer interval, Collection<String> extensions)
            throws AWTException {
        if (interval == null) {
            return null;
        }
        if (interval < 0) {
            if (extensions.contains(EXT_SWAP_CONTROL)
                    && extensions.contains(EXT_SWAP_CONTROL_TEAR)) {
                return Mechanism.EXT;
            }
            throw new AWTException("Negative swap interval requested but "
                    + EXT_SWAP_CONTROL_TEAR + " is unavailable");
        }
        // EXT is per-drawable and supports every non-negative interval. MESA is the closest
        // fallback; SGI comes last because it cannot disable synchronization with interval 0.
        if (extensions.contains(EXT_SWAP_CONTROL)) {
            return Mechanism.EXT;
        }
        if (extensions.contains(MESA_SWAP_CONTROL)) {
            return Mechanism.MESA;
        }
        if (interval > 0 && extensions.contains(SGI_SWAP_CONTROL)) {
            return Mechanism.SGI;
        }
        throw new AWTException(NO_COMPATIBLE_EXTENSION);
    }

    static Set<String> parseExtensions(String extensionString) {
        if (extensionString == null || extensionString.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(Arrays.asList(extensionString.trim().split("\\s+")));
    }

    void apply(long display, long drawable) throws AWTException {
        if (mechanism == Mechanism.EXT) {
            JNI.callPPV(display, drawable, interval, functionAddress);
            return;
        }

        int error = JNI.callI(interval, functionAddress);
        if (error != 0) {
            throw new AWTException("Failed to set the swap interval with "
                    + mechanism.extension + " (GLX error " + error + ")");
        }
    }
}
