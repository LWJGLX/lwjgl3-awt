package org.lwjgl.opengl.awt;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGLCapabilities;
import org.lwjgl.opengl.ARBRobustness;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.APIUtil.APIVersion;
import org.lwjgl.system.Checks;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;

import java.awt.AWTException;
import java.awt.Canvas;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.egl.EGL10.*;
import static org.lwjgl.egl.EGL11.*;
import static org.lwjgl.egl.EGL12.*;
import static org.lwjgl.egl.EGL14.*;
import static org.lwjgl.egl.EGL15.eglCreatePlatformWindowSurface;
import static org.lwjgl.egl.EGL15.eglGetPlatformDisplay;
import static org.lwjgl.egl.EXTCreateContextRobustness.*;
import static org.lwjgl.egl.EXTPixelFormatFloat.*;
import static org.lwjgl.egl.EXTPlatformBase.eglCreatePlatformWindowSurfaceEXT;
import static org.lwjgl.egl.EXTPlatformBase.eglGetPlatformDisplayEXT;
import static org.lwjgl.egl.EXTPlatformX11.*;
import static org.lwjgl.egl.KHRContextFlushControl.*;
import static org.lwjgl.egl.KHRCreateContext.*;
import static org.lwjgl.egl.KHRGLColorspace.*;
import static org.lwjgl.egl.KHRPlatformX11.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.jawt.JAWTFunctions.*;

/**
 * EGL implementation backed by the X11 window exposed by AWT under XWayland.
 */
public class PlatformLinuxEGLCanvas implements PlatformGLCanvas {
    private static final JAWT AWT = createAWT();
    private static final Map<Long, DisplayRef> DISPLAY_REFS = new HashMap<>();

    private Canvas canvas;
    private JAWTDrawingSurface ds;
    private Thread drawingSurfaceThread;
    private DisplayRef displayRef;
    private long eglDisplay;
    private long eglSurface;
    private long eglContext;

    private static JAWT createAWT() {
        JAWT awt = JAWT.create(MemoryUtil.getAllocator().calloc(1, JAWT.SIZEOF)); // untracked allocation
        awt.version(JAWT_VERSION_1_4);
        if (!JAWT_GetAWT(awt)) {
            throw new AssertionError("GetAWT failed");
        }
        return awt;
    }

    @Override
    public long create(Canvas canvas, GLData attribs, GLData effective) throws AWTException {
        GLUtil.validateAttributes(attribs);
        validateUnsupportedAttributes(attribs);
        this.canvas = canvas;

        JAWTDrawingSurface drawingSurface = JAWT_GetDrawingSurface(canvas, AWT.GetDrawingSurface());
        if (drawingSurface == null) {
            throw new AWTException("Failed to get JAWT drawing surface");
        }
        try {
            int lock = JAWT_DrawingSurface_Lock(drawingSurface, drawingSurface.Lock());
            if ((lock & JAWT_LOCK_ERROR) != 0) {
                throw new AWTException("JAWT_DrawingSurface_Lock() failed");
            }
            try {
                JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(
                        drawingSurface, drawingSurface.GetDrawingSurfaceInfo());
                if (dsi == null) {
                    throw new AWTException("Failed to get JAWT drawing surface information");
                }
                try {
                    JAWTX11DrawingSurfaceInfo x11 = JAWTX11DrawingSurfaceInfo.create(dsi.platformInfo());
                    return createContext(x11.display(), x11.drawable(), x11.visualID(), attribs, effective);
                } finally {
                    JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, drawingSurface.FreeDrawingSurfaceInfo());
                }
            } finally {
                JAWT_DrawingSurface_Unlock(drawingSurface, drawingSurface.Unlock());
            }
        } finally {
            JAWT_FreeDrawingSurface(drawingSurface, AWT.FreeDrawingSurface());
        }
    }

    private long createContext(long nativeDisplay, long drawable, long visualID,
            GLData attribs, GLData effective) throws AWTException {
        int screen = org.lwjgl.system.linux.X11.XDefaultScreen(nativeDisplay);
        displayRef = acquireDisplay(nativeDisplay, screen);
        eglDisplay = displayRef.eglDisplay;

        try {
            validateCapabilities(attribs, displayRef.capabilities);
            bindClientAPI(attribs.api);

            long shareContext = getShareContext(attribs);
            List<GLUtil.ContextVersion> candidates = GLUtil.contextVersionCandidates(attribs,
                    attribs.api == GLData.API.GLES ? 3 : 4,
                    attribs.api == GLData.API.GLES ? 2 : 6);
            long config = 0L;
            int lastError = EGL_SUCCESS;
            for (GLUtil.ContextVersion version : candidates) {
                long candidateConfig = chooseConfig(visualID, attribs, version);
                if (candidateConfig == 0L) {
                    if (attribs.versionPolicy == GLData.VersionPolicy.EXACT) {
                        throw new AWTException("No EGL framebuffer configuration matches the AWT window visual");
                    }
                    continue;
                }
                eglContext = eglCreateContext(eglDisplay, candidateConfig, shareContext,
                        contextAttributes(attribs, displayRef.capabilities, version));
                if (eglContext != EGL_NO_CONTEXT) {
                    config = candidateConfig;
                    break;
                }
                lastError = eglGetError();
                if (attribs.versionPolicy == GLData.VersionPolicy.EXACT) {
                    throw eglFailure("Failed to create EGL context", lastError);
                }
            }
            if (eglContext == EGL_NO_CONTEXT) {
                String message = "Failed to create an EGL context satisfying "
                        + GLUtil.describeVersionRequest(attribs) + " after " + candidates.size() + " attempts";
                if (lastError != EGL_SUCCESS) {
                    throw eglFailure(message, lastError);
                }
                throw new AWTException(message);
            }

            eglSurface = createWindowSurface(config, drawable, attribs);
            if (eglSurface == EGL_NO_SURFACE) {
                throw eglFailure("Failed to create EGL window surface");
            }

            if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw eglFailure("Failed to make EGL context current");
            }
            try {
                if (attribs.swapInterval != null) {
                    if (!eglSwapInterval(eglDisplay, attribs.swapInterval)) {
                        throw eglFailure("Failed to configure EGL swap interval");
                    }
                    effective.swapInterval = attribs.swapInterval;
                }
                populateEffectiveConfig(config, attribs, effective);
                populateEffectiveGLAttributes(attribs, effective);
            } finally {
                eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            }
            return eglContext;
        } catch (AWTException | RuntimeException | Error failure) {
            cleanupAfterFailedCreate();
            throw failure;
        }
    }

    private static void validateUnsupportedAttributes(GLData data) throws AWTException {
        if (data.stereo) {
            throw new AWTException("Stereo rendering is unavailable with EGL window surfaces");
        }
        if (data.accumRedSize != 0 || data.accumGreenSize != 0
                || data.accumBlueSize != 0 || data.accumAlphaSize != 0) {
            throw new AWTException("Accumulation buffers are unavailable with EGL window surfaces");
        }
        if (data.colorSamplesNV != 0 || data.swapGroupNV != 0 || data.swapBarrierNV != 0) {
            throw new AWTException("Requested NV framebuffer or swap features are unavailable with EGL");
        }
        if (data.contextResetIsolation) {
            throw new AWTException("Context reset isolation is unavailable with EGL");
        }
        if (data.swapInterval != null && data.swapInterval < 0) {
            throw new AWTException("Negative swap intervals are unavailable with EGL");
        }
    }

    private static void validateCapabilities(GLData data, EGLCapabilities capabilities)
            throws AWTException {
        if (!capabilities.EGL12) {
            throw new AWTException("EGL 1.2 or later is required");
        }
        if (data.api == GLData.API.GL && !capabilities.EGL14) {
            throw new AWTException("Desktop OpenGL requires EGL 1.4 or later");
        }

        boolean createContext = capabilities.EGL15 || capabilities.EGL_KHR_create_context;
        if (data.versionPolicy != GLData.VersionPolicy.EXACT && !createContext) {
            throw new AWTException("Context version policies require EGL_KHR_create_context");
        }
        if (data.api == GLData.API.GL && data.majorVersion > 0 && !createContext) {
            throw new AWTException("Versioned desktop OpenGL contexts require EGL_KHR_create_context");
        }
        if ((data.profile != null || data.debug || data.forwardCompatible) && !createContext) {
            throw new AWTException("Requested OpenGL context attributes require EGL_KHR_create_context");
        }
        if (data.robustness && !createContext && !capabilities.EGL_EXT_create_context_robustness) {
            throw new AWTException("Robust contexts require EGL_KHR_create_context or EGL_EXT_create_context_robustness");
        }
        if (data.contextReleaseBehavior != null && !capabilities.EGL_KHR_context_flush_control) {
            throw new AWTException("Context release behavior requires EGL_KHR_context_flush_control");
        }
        if (data.sRGB && !capabilities.EGL_KHR_gl_colorspace) {
            throw new AWTException("sRGB surfaces require EGL_KHR_gl_colorspace");
        }
        if (data.pixelFormatFloat && !capabilities.EGL_EXT_pixel_format_float) {
            throw new AWTException("Floating-point pixel formats require EGL_EXT_pixel_format_float");
        }
    }

    private long chooseConfig(long visualID, GLData data, GLUtil.ContextVersion version) throws AWTException {
        IntBuffer attributes = BufferUtils.createIntBuffer(40);
        attributes.put(EGL_SURFACE_TYPE).put(EGL_WINDOW_BIT);
        attributes.put(EGL_RENDERABLE_TYPE).put(renderableType(data, version));
        attributes.put(EGL_NATIVE_VISUAL_ID).put((int) visualID);
        attributes.put(EGL_RED_SIZE).put(data.redSize);
        attributes.put(EGL_GREEN_SIZE).put(data.greenSize);
        attributes.put(EGL_BLUE_SIZE).put(data.blueSize);
        attributes.put(EGL_ALPHA_SIZE).put(data.alphaSize);
        attributes.put(EGL_DEPTH_SIZE).put(data.depthSize);
        attributes.put(EGL_STENCIL_SIZE).put(data.stencilSize);
        attributes.put(EGL_SAMPLE_BUFFERS).put(data.samples > 0 ? 1 : 0);
        attributes.put(EGL_SAMPLES).put(data.samples);
        if (data.pixelFormatFloat) {
            attributes.put(EGL_COLOR_COMPONENT_TYPE_EXT).put(EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT);
        }
        attributes.put(EGL_NONE).flip();

        PointerBuffer configs = BufferUtils.createPointerBuffer(1);
        IntBuffer count = BufferUtils.createIntBuffer(1);
        if (!eglChooseConfig(eglDisplay, attributes, configs, count)) {
            throw eglFailure("Failed to choose EGL framebuffer configuration");
        }
        if (count.get(0) == 0) {
            return 0L;
        }
        return configs.get(0);
    }

    private static int renderableType(GLData data, GLUtil.ContextVersion version) {
        if (data.api == GLData.API.GL) {
            return EGL_OPENGL_BIT;
        }
        if (version.major >= 3) {
            return EGL_OPENGL_ES3_BIT_KHR;
        }
        return version.major >= 2 ? EGL_OPENGL_ES2_BIT : EGL_OPENGL_ES_BIT;
    }

    private static void bindClientAPI(GLData.API api) throws AWTException {
        int eglAPI = api == GLData.API.GLES ? EGL_OPENGL_ES_API : EGL_OPENGL_API;
        if (!eglBindAPI(eglAPI)) {
            throw eglFailure("Failed to bind EGL client API");
        }
    }

    private long getShareContext(GLData data) throws AWTException {
        if (data.shareContext == null) {
            return EGL_NO_CONTEXT;
        }
        if (data.shareContext.context == 0L) {
            throw new AWTException("The requested shared context has not been created");
        }
        if (!(data.shareContext.platformCanvas instanceof PlatformLinuxEGLCanvas)) {
            throw new AWTException("Cannot share an EGL context with a different platform backend");
        }
        PlatformLinuxEGLCanvas shared = (PlatformLinuxEGLCanvas) data.shareContext.platformCanvas;
        if (shared.eglDisplay != eglDisplay) {
            throw new AWTException("Shared EGL contexts must use the same EGL display");
        }
        return data.shareContext.context;
    }

    private static IntBuffer contextAttributes(GLData data, EGLCapabilities capabilities,
            GLUtil.ContextVersion version) {
        IntBuffer attributes = BufferUtils.createIntBuffer(32);
        boolean createContext = capabilities.EGL15 || capabilities.EGL_KHR_create_context;

        if (createContext) {
            if (version.major > 0) {
                attributes.put(EGL_CONTEXT_MAJOR_VERSION_KHR).put(version.major);
                attributes.put(EGL_CONTEXT_MINOR_VERSION_KHR).put(version.minor);
            }

            int flags = 0;
            if (data.debug) {
                flags |= EGL_CONTEXT_OPENGL_DEBUG_BIT_KHR;
            }
            if (data.forwardCompatible) {
                flags |= EGL_CONTEXT_OPENGL_FORWARD_COMPATIBLE_BIT_KHR;
            }
            if (data.robustness) {
                flags |= EGL_CONTEXT_OPENGL_ROBUST_ACCESS_BIT_KHR;
                attributes.put(EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_KHR)
                        .put(data.loseContextOnReset
                                ? EGL_LOSE_CONTEXT_ON_RESET_KHR
                                : EGL_NO_RESET_NOTIFICATION_KHR);
            }
            if (flags != 0) {
                attributes.put(EGL_CONTEXT_FLAGS_KHR).put(flags);
            }

            if (data.profile != null) {
                attributes.put(EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR)
                        .put(data.profile == GLData.Profile.CORE
                                ? EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR
                                : EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR);
            }
        } else {
            if (data.api == GLData.API.GLES && version.major > 0) {
                attributes.put(EGL_CONTEXT_CLIENT_VERSION).put(version.major);
            }
            if (data.robustness) {
                attributes.put(EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT).put(EGL_TRUE);
                attributes.put(EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT)
                        .put(data.loseContextOnReset
                                ? EGL_LOSE_CONTEXT_ON_RESET_EXT
                                : EGL_NO_RESET_NOTIFICATION_EXT);
            }
        }

        if (data.contextReleaseBehavior != null) {
            attributes.put(EGL_CONTEXT_RELEASE_BEHAVIOR_KHR)
                    .put(data.contextReleaseBehavior == GLData.ReleaseBehavior.NONE
                            ? EGL_CONTEXT_RELEASE_BEHAVIOR_NONE_KHR
                            : EGL_CONTEXT_RELEASE_BEHAVIOR_FLUSH_KHR);
        }
        attributes.put(EGL_NONE).flip();
        return attributes;
    }

    private long createWindowSurface(long config, long drawable, GLData data) {
        EGLCapabilities clientCapabilities = EGL.getCapabilities();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer nativeWindow = stack.pointers(drawable);
            if (clientCapabilities.EGL15 && clientCapabilities.EGL_KHR_platform_x11) {
                PointerBuffer attributes = platformSurfaceAttributes(stack, data);
                return eglCreatePlatformWindowSurface(
                        eglDisplay, config, memAddress(nativeWindow), attributes);
            }
            if (clientCapabilities.EGL_EXT_platform_base
                    && clientCapabilities.EGL_EXT_platform_x11) {
                IntBuffer attributes = surfaceAttributes(stack, data);
                return eglCreatePlatformWindowSurfaceEXT(
                        eglDisplay, config, memAddress(nativeWindow), attributes);
            }
            return eglCreateWindowSurface(eglDisplay, config, drawable, surfaceAttributes(stack, data));
        }
    }

    private static IntBuffer surfaceAttributes(MemoryStack stack, GLData data) {
        IntBuffer attributes = stack.mallocInt(5);
        if (data.sRGB) {
            attributes.put(EGL_GL_COLORSPACE_KHR).put(EGL_GL_COLORSPACE_SRGB_KHR);
        }
        if (!data.doubleBuffer) {
            attributes.put(EGL_RENDER_BUFFER).put(EGL_SINGLE_BUFFER);
        }
        attributes.put(EGL_NONE);
        attributes.flip();
        return attributes;
    }

    private static PointerBuffer platformSurfaceAttributes(MemoryStack stack, GLData data) {
        PointerBuffer attributes = stack.mallocPointer(5);
        if (data.sRGB) {
            attributes.put(EGL_GL_COLORSPACE_KHR).put(EGL_GL_COLORSPACE_SRGB_KHR);
        }
        if (!data.doubleBuffer) {
            attributes.put(EGL_RENDER_BUFFER).put(EGL_SINGLE_BUFFER);
        }
        return attributes.put(EGL_NONE).flip();
    }

    private void populateEffectiveConfig(long config, GLData requested, GLData effective)
            throws AWTException {
        effective.redSize = getConfigAttribute(config, EGL_RED_SIZE);
        effective.greenSize = getConfigAttribute(config, EGL_GREEN_SIZE);
        effective.blueSize = getConfigAttribute(config, EGL_BLUE_SIZE);
        effective.alphaSize = getConfigAttribute(config, EGL_ALPHA_SIZE);
        effective.depthSize = getConfigAttribute(config, EGL_DEPTH_SIZE);
        effective.stencilSize = getConfigAttribute(config, EGL_STENCIL_SIZE);
        effective.samples = getConfigAttribute(config, EGL_SAMPLES);
        effective.sampleBuffers = getConfigAttribute(config, EGL_SAMPLE_BUFFERS);
        effective.pixelFormatFloat = requested.pixelFormatFloat;
        effective.sRGB = requested.sRGB;

        IntBuffer renderBuffer = BufferUtils.createIntBuffer(1);
        if (eglQueryContext(eglDisplay, eglContext, EGL_RENDER_BUFFER, renderBuffer)) {
            effective.doubleBuffer = renderBuffer.get(0) == EGL_BACK_BUFFER;
        } else {
            eglGetError();
            effective.doubleBuffer = requested.doubleBuffer;
        }
    }

    private int getConfigAttribute(long config, int attribute) throws AWTException {
        IntBuffer value = BufferUtils.createIntBuffer(1);
        if (!eglGetConfigAttrib(eglDisplay, config, attribute, value)) {
            throw eglFailure("Failed to query EGL framebuffer configuration");
        }
        return value.get(0);
    }

    private static void populateEffectiveGLAttributes(GLData requested, GLData effective)
            throws AWTException {
        long glGetIntegerv = GL.getFunctionProvider().getFunctionAddress("glGetIntegerv");
        long glGetString = GL.getFunctionProvider().getFunctionAddress("glGetString");
        APIVersion version = APIUtil.apiParseVersion(getString(GL11.GL_VERSION, glGetString));

        effective.api = requested.api;
        effective.versionPolicy = requested.versionPolicy;
        effective.majorVersion = version.major;
        effective.minorVersion = version.minor;

        if (requested.api == GLData.API.GL && GLUtil.atLeast32(version.major, version.minor)) {
            int profileFlags = getInteger(GL32.GL_CONTEXT_PROFILE_MASK, glGetIntegerv);
            if ((profileFlags & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0) {
                effective.profile = GLData.Profile.CORE;
            } else if ((profileFlags & GL32.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0) {
                effective.profile = GLData.Profile.COMPATIBILITY;
            } else if (profileFlags != 0) {
                throw new AWTException("Unknown OpenGL profile " + profileFlags);
            }
        }

        if (requested.api == GLData.API.GL && version.major >= 3) {
            int contextFlags = getInteger(GL30.GL_CONTEXT_FLAGS, glGetIntegerv);
            effective.debug = (contextFlags & GL43.GL_CONTEXT_FLAG_DEBUG_BIT) != 0;
            effective.forwardCompatible =
                    (contextFlags & GL30.GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) != 0;
            effective.robustness =
                    (contextFlags & ARBRobustness.GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT_ARB) != 0;
        }
        if (effective.robustness) {
            int notificationStrategy = getInteger(
                    ARBRobustness.GL_RESET_NOTIFICATION_STRATEGY_ARB, glGetIntegerv);
            effective.loseContextOnReset =
                    notificationStrategy == ARBRobustness.GL_LOSE_CONTEXT_ON_RESET_ARB;
        }
        effective.samples = getInteger(GL13.GL_SAMPLES, glGetIntegerv);
        effective.sampleBuffers = effective.samples > 0 ? 1 : 0;
        effective.contextReleaseBehavior = requested.contextReleaseBehavior;
    }

    private static int getInteger(int name, long function) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.callocInt(1);
            JNI.callPV(name, memAddress(value), function);
            return value.get(0);
        }
    }

    private static String getString(int name, long function) {
        return memUTF8(Checks.check(JNI.callP(name, function)));
    }

    @Override
    public void lock() throws AWTException {
        if (ds != null) {
            throw new AWTException("JAWT drawing surface is already locked");
        }
        if (canvas == null) {
            throw new AWTException("Canvas has not been created or was disposed");
        }
        JAWTDrawingSurface drawingSurface = JAWT_GetDrawingSurface(canvas, AWT.GetDrawingSurface());
        if (drawingSurface == null) {
            throw new AWTException("Failed to get JAWT drawing surface");
        }
        int lock = JAWT_DrawingSurface_Lock(drawingSurface, drawingSurface.Lock());
        if ((lock & JAWT_LOCK_ERROR) != 0) {
            JAWT_FreeDrawingSurface(drawingSurface, AWT.FreeDrawingSurface());
            throw new AWTException("JAWT_DrawingSurface_Lock() failed");
        }
        ds = drawingSurface;
        drawingSurfaceThread = Thread.currentThread();
    }

    @Override
    public void unlock() throws AWTException {
        JAWTDrawingSurface drawingSurface = ds;
        if (drawingSurface == null) {
            throw new AWTException("JAWT drawing surface is not locked");
        }
        if (drawingSurfaceThread != Thread.currentThread()) {
            throw new AWTException("JAWT drawing surface must be unlocked by the thread that locked it");
        }
        ds = null;
        drawingSurfaceThread = null;
        try {
            JAWT_DrawingSurface_Unlock(drawingSurface, drawingSurface.Unlock());
        } finally {
            JAWT_FreeDrawingSurface(drawingSurface, AWT.FreeDrawingSurface());
        }
    }

    @Override
    public boolean makeCurrent(long context) {
        requireLockedDrawingSurface();
        if (eglDisplay == EGL_NO_DISPLAY) {
            return context == EGL_NO_CONTEXT;
        }
        if (context == EGL_NO_CONTEXT) {
            return eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        return eglMakeCurrent(eglDisplay, eglSurface, eglSurface, context);
    }

    private void requireLockedDrawingSurface() {
        if (ds == null) {
            throw new IllegalStateException("The JAWT drawing surface must be locked for this operation");
        }
        if (drawingSurfaceThread != Thread.currentThread()) {
            throw new IllegalStateException("JAWT drawing surface is locked by another thread");
        }
    }

    @Override
    public boolean isCurrent(long context) {
        return eglGetCurrentContext() == context;
    }

    @Override
    public boolean swapBuffers() {
        return eglSwapBuffers(eglDisplay, eglSurface);
    }

    @Override
    public boolean delayBeforeSwapNV(float seconds) {
        throw new UnsupportedOperationException("NYI");
    }

    @Override
    public boolean getFramebufferSize(int[] size) {
        if (eglDisplay == EGL_NO_DISPLAY || eglSurface == EGL_NO_SURFACE) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            if (!eglQuerySurface(eglDisplay, eglSurface, EGL_WIDTH, width)
                    || !eglQuerySurface(eglDisplay, eglSurface, EGL_HEIGHT, height)) {
                eglGetError();
                return false;
            }
            size[0] = width.get(0);
            size[1] = height.get(0);
            return true;
        }
    }

    @Override
    public boolean deleteContext(long context) {
        boolean success = true;
        if (eglDisplay != EGL_NO_DISPLAY) {
            if (eglGetCurrentContext() == context) {
                success &= eglMakeCurrent(
                        eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            }
            if (eglSurface != EGL_NO_SURFACE) {
                success &= eglDestroySurface(eglDisplay, eglSurface);
            }
            if (context != EGL_NO_CONTEXT) {
                success &= eglDestroyContext(eglDisplay, context);
            }
        }
        eglSurface = EGL_NO_SURFACE;
        eglContext = EGL_NO_CONTEXT;
        releaseDisplay();
        return success;
    }

    private void cleanupAfterFailedCreate() {
        if (eglDisplay != EGL_NO_DISPLAY) {
            eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (eglSurface != EGL_NO_SURFACE) {
                eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL_NO_CONTEXT) {
                eglDestroyContext(eglDisplay, eglContext);
            }
        }
        eglSurface = EGL_NO_SURFACE;
        eglContext = EGL_NO_CONTEXT;
        releaseDisplay();
    }

    private void releaseDisplay() {
        DisplayRef ref = displayRef;
        displayRef = null;
        eglDisplay = EGL_NO_DISPLAY;
        if (ref != null) {
            releaseDisplay(ref);
        }
    }

    @Override
    public void dispose() {
        canvas = null;
    }

    private static synchronized DisplayRef acquireDisplay(long nativeDisplay, int screen)
            throws AWTException {
        long eglDisplay = createDisplay(nativeDisplay, screen);
        if (eglDisplay == EGL_NO_DISPLAY) {
            throw eglFailure("Failed to obtain EGL display for the AWT X11 display");
        }

        DisplayRef existing = DISPLAY_REFS.get(eglDisplay);
        if (existing != null) {
            existing.references++;
            return existing;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer major = stack.mallocInt(1);
            IntBuffer minor = stack.mallocInt(1);
            if (!eglInitialize(eglDisplay, major, minor)) {
                throw eglFailure("Failed to initialize EGL display");
            }
            EGLCapabilities capabilities = EGL.createDisplayCapabilities(
                    eglDisplay, major.get(0), minor.get(0));
            DisplayRef created = new DisplayRef(eglDisplay, capabilities);
            DISPLAY_REFS.put(eglDisplay, created);
            return created;
        }
    }

    private static long createDisplay(long nativeDisplay, int screen) {
        EGLCapabilities capabilities = EGL.getCapabilities();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (capabilities.EGL15 && capabilities.EGL_KHR_platform_x11) {
                PointerBuffer attributes = stack.pointers(
                        EGL_PLATFORM_X11_SCREEN_KHR, screen, EGL_NONE);
                return eglGetPlatformDisplay(
                        EGL_PLATFORM_X11_KHR, nativeDisplay, attributes);
            }
            if (capabilities.EGL_EXT_platform_base && capabilities.EGL_EXT_platform_x11) {
                IntBuffer attributes = stack.ints(
                        EGL_PLATFORM_X11_SCREEN_EXT, screen, EGL_NONE);
                return eglGetPlatformDisplayEXT(
                        EGL_PLATFORM_X11_EXT, nativeDisplay, attributes);
            }
            return eglGetDisplay(nativeDisplay);
        }
    }

    private static synchronized void releaseDisplay(DisplayRef ref) {
        if (--ref.references == 0) {
            DISPLAY_REFS.remove(ref.eglDisplay);
            // EGLDisplay initialization is process-wide rather than reference-counted. Calling eglTerminate here
            // would invalidate contexts and surfaces owned by another toolkit that uses the same native display.
            // Leave termination to process teardown; removing our Java-side entry still releases its capabilities.
        }
    }

    private static AWTException eglFailure(String message) {
        return eglFailure(message, eglGetError());
    }

    private static AWTException eglFailure(String message, int error) {
        return new AWTException(message + ": " + eglErrorName(error)
                + " (0x" + Integer.toHexString(error).toUpperCase() + ")");
    }

    private static String eglErrorName(int error) {
        switch (error) {
        case EGL_SUCCESS:
            return "EGL_SUCCESS";
        case EGL_NOT_INITIALIZED:
            return "EGL_NOT_INITIALIZED";
        case EGL_BAD_ACCESS:
            return "EGL_BAD_ACCESS";
        case EGL_BAD_ALLOC:
            return "EGL_BAD_ALLOC";
        case EGL_BAD_ATTRIBUTE:
            return "EGL_BAD_ATTRIBUTE";
        case EGL_BAD_CONTEXT:
            return "EGL_BAD_CONTEXT";
        case EGL_BAD_CONFIG:
            return "EGL_BAD_CONFIG";
        case EGL_BAD_CURRENT_SURFACE:
            return "EGL_BAD_CURRENT_SURFACE";
        case EGL_BAD_DISPLAY:
            return "EGL_BAD_DISPLAY";
        case EGL_BAD_SURFACE:
            return "EGL_BAD_SURFACE";
        case EGL_BAD_MATCH:
            return "EGL_BAD_MATCH";
        case EGL_BAD_PARAMETER:
            return "EGL_BAD_PARAMETER";
        case EGL_BAD_NATIVE_PIXMAP:
            return "EGL_BAD_NATIVE_PIXMAP";
        case EGL_BAD_NATIVE_WINDOW:
            return "EGL_BAD_NATIVE_WINDOW";
        default:
            return "unknown EGL error";
        }
    }

    private static final class DisplayRef {
        private final long eglDisplay;
        private final EGLCapabilities capabilities;
        private int references = 1;

        private DisplayRef(long eglDisplay, EGLCapabilities capabilities) {
            this.eglDisplay = eglDisplay;
            this.capabilities = capabilities;
        }
    }
}
