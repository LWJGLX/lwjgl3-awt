package org.lwjgl.opengl.awt;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.awt.GLData.API;
import org.lwjgl.opengl.awt.GLData.Profile;
import org.lwjgl.opengl.awt.GLData.ReleaseBehavior;
import org.lwjgl.system.Checks;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo;
import org.lwjgl.system.windows.PIXELFORMATDESCRIPTOR;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;

import java.awt.AWTException;
import java.awt.Canvas;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.opengl.ARBMultisample.GL_SAMPLES_ARB;
import static org.lwjgl.opengl.ARBMultisample.GL_SAMPLE_BUFFERS_ARB;
import static org.lwjgl.opengl.ARBRobustness.GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT_ARB;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL43.GL_CONTEXT_FLAG_DEBUG_BIT;
import static org.lwjgl.opengl.NVMultisampleCoverage.GL_COLOR_SAMPLES_NV;
import static org.lwjgl.opengl.WGL.*;
import static org.lwjgl.opengl.WGLARBContextFlushControl.*;
import static org.lwjgl.opengl.WGLARBCreateContext.*;
import static org.lwjgl.opengl.WGLARBCreateContextProfile.*;
import static org.lwjgl.opengl.WGLARBCreateContextRobustness.*;
import static org.lwjgl.opengl.WGLARBFramebufferSRGB.WGL_FRAMEBUFFER_SRGB_CAPABLE_ARB;
import static org.lwjgl.opengl.WGLARBMultisample.WGL_SAMPLES_ARB;
import static org.lwjgl.opengl.WGLARBMultisample.WGL_SAMPLE_BUFFERS_ARB;
import static org.lwjgl.opengl.WGLARBPixelFormat.*;
import static org.lwjgl.opengl.WGLARBPixelFormatFloat.WGL_TYPE_RGBA_FLOAT_ARB;
import static org.lwjgl.opengl.WGLARBRobustnessApplicationIsolation.WGL_CONTEXT_RESET_ISOLATION_BIT_ARB;
import static org.lwjgl.opengl.WGLEXTCreateContextES2Profile.WGL_CONTEXT_ES2_PROFILE_BIT_EXT;
import static org.lwjgl.opengl.WGLEXTFramebufferSRGB.WGL_FRAMEBUFFER_SRGB_CAPABLE_EXT;
import static org.lwjgl.opengl.WGLNVMultisampleCoverage.WGL_COLOR_SAMPLES_NV;
import static org.lwjgl.opengl.awt.GLUtil.*;
import static org.lwjgl.system.APIUtil.APIVersion;
import static org.lwjgl.system.APIUtil.apiParseVersion;
import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.system.windows.GDI32.*;
import static org.lwjgl.system.windows.User32.*;
import static org.lwjgl.system.windows.WindowsLibrary.HINSTANCE;

/**
 * Windows-specific implementation of {@link PlatformGLCanvas}.
 *
 * @author Kai Burjack
 */
public class PlatformWin32GLCanvas implements PlatformGLCanvas {
    public static final JAWT awt;
    static {
        awt = JAWT.create(MemoryUtil.getAllocator().calloc(1, JAWT.SIZEOF)); // untracked allocation
        awt.version(JAWT_VERSION_1_4);
        if (!JAWT_GetAWT(awt))
            throw new AssertionError("GetAWT failed");
    }

    public long hwnd;
    public long wglDelayBeforeSwapNVAddr = 0L;
    public boolean wglDelayBeforeSwapNVAddr_set = false;
    public JAWTDrawingSurface ds;
    private JAWTDrawingSurfaceInfo dsi;
    private long hdc;
    private Thread drawingSurfaceThread;
    private Canvas canvas;

    /**
     * Encode the pixel format attributes stored in the given {@link GLData} into the given {@link IntBuffer} for wglChoosePixelFormatARB to consume.
     */
    private static void encodePixelFormatAttribs(IntBuffer ib, GLData attribs) {
        ib.put(WGL_DRAW_TO_WINDOW_ARB).put(1);
        ib.put(WGL_SUPPORT_OPENGL_ARB).put(1);
        ib.put(WGL_ACCELERATION_ARB).put(WGL_FULL_ACCELERATION_ARB);
        if (attribs.doubleBuffer)
            ib.put(WGL_DOUBLE_BUFFER_ARB).put(1);
        if (attribs.pixelFormatFloat)
            ib.put(WGL_PIXEL_TYPE_ARB).put(WGL_TYPE_RGBA_FLOAT_ARB);
        else
            ib.put(WGL_PIXEL_TYPE_ARB).put(WGL_TYPE_RGBA_ARB);
        if (attribs.redSize > 0)
            ib.put(WGL_RED_BITS_ARB).put(attribs.redSize);
        if (attribs.greenSize > 0)
            ib.put(WGL_GREEN_BITS_ARB).put(attribs.greenSize);
        if (attribs.blueSize > 0)
            ib.put(WGL_BLUE_BITS_ARB).put(attribs.blueSize);
        if (attribs.alphaSize > 0)
            ib.put(WGL_ALPHA_BITS_ARB).put(attribs.alphaSize);
        if (attribs.depthSize > 0)
            ib.put(WGL_DEPTH_BITS_ARB).put(attribs.depthSize);
        if (attribs.stencilSize > 0)
            ib.put(WGL_STENCIL_BITS_ARB).put(attribs.stencilSize);
        if (attribs.accumRedSize > 0)
            ib.put(WGL_ACCUM_RED_BITS_ARB).put(attribs.accumRedSize);
        if (attribs.accumGreenSize > 0)
            ib.put(WGL_ACCUM_GREEN_BITS_ARB).put(attribs.accumGreenSize);
        if (attribs.accumBlueSize > 0)
            ib.put(WGL_ACCUM_BLUE_BITS_ARB).put(attribs.accumBlueSize);
        if (attribs.accumAlphaSize > 0)
            ib.put(WGL_ACCUM_ALPHA_BITS_ARB).put(attribs.accumAlphaSize);
        if (attribs.accumRedSize > 0 || attribs.accumGreenSize > 0 || attribs.accumBlueSize > 0 || attribs.accumAlphaSize > 0)
            ib.put(WGL_ACCUM_BITS_ARB).put(attribs.accumRedSize + attribs.accumGreenSize + attribs.accumBlueSize + attribs.accumAlphaSize);
        if (attribs.sRGB)
            ib.put(attribs.extBuffer_sRGB ? WGL_FRAMEBUFFER_SRGB_CAPABLE_EXT : WGL_FRAMEBUFFER_SRGB_CAPABLE_ARB).put(1);
        if (attribs.samples > 0) {
            ib.put(WGL_SAMPLE_BUFFERS_ARB).put(1);
            ib.put(WGL_SAMPLES_ARB).put(attribs.samples);
            if (attribs.colorSamplesNV > 0) {
                ib.put(WGL_COLOR_SAMPLES_NV).put(attribs.colorSamplesNV);
            }
        }
        ib.put(0);
    }

    private static long createDummyWindow(MemoryStack stack) {
		String className = "AWTAPPWNDCLASS";

        WNDCLASSEX in = WNDCLASSEX
                .calloc(stack)
                .cbSize(WNDCLASSEX.SIZEOF)
                .lpfnWndProc(User32::DefWindowProc)
                .hInstance(HINSTANCE)
		        .lpszClassName(stack.UTF16(className));

        RegisterClassEx(null, in);
		return CreateWindowEx(null, WS_EX_APPWINDOW, className, "", 0, CW_USEDEFAULT, CW_USEDEFAULT,
                800, 600, NULL, NULL, HINSTANCE, NULL);
    }

    @Override
    public long create(Canvas canvas, GLData attribs, GLData effective) throws AWTException {
        this.canvas = canvas;
        JAWTDrawingSurface ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
        if (ds == null) {
            throw new AWTException("Failed to get JAWT drawing surface");
        }
        try {
            int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
            if ((lock & JAWT_LOCK_ERROR) != 0)
                throw new AWTException("JAWT_DrawingSurface_Lock() failed");
            try {
                JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
                if (dsi == null) {
                    throw new AWTException("Failed to get JAWT drawing surface info");
                }
                try {
                    JAWTWin32DrawingSurfaceInfo dsiWin = JAWTWin32DrawingSurfaceInfo.create(dsi.platformInfo());
                    this.hwnd = dsiWin.hwnd();
                    try (MemoryStack stack = stackPush()) {
                        long hwndDummy = createDummyWindow(stack);
                        if (hwndDummy == 0L) {
                            throw new AWTException("Failed to create dummy window");
                        }
                        try {
                            return create(stack, dsiWin.hdc(), hwndDummy, attribs, effective);
                        } finally {
                            DestroyWindow(null, hwndDummy);
                        }
                    }
                } finally {
                    JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
                }
            } finally {
                JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
            }
        } finally {
            JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
        }
    }

    private static long create(MemoryStack stack, long hDC, long dummyWindowHandle, GLData attribs, GLData effective) throws AWTException {
        long bufferAddr = stack.nmalloc(4, (4 * 2) << 2);

        validateAttributes(attribs);

        int flags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL;
        if (attribs.doubleBuffer)
            flags |= PFD_DOUBLEBUFFER;
        if (attribs.stereo)
            flags |= PFD_STEREO;
        PIXELFORMATDESCRIPTOR pfd = PIXELFORMATDESCRIPTOR.calloc(stack)
                .nSize((short) PIXELFORMATDESCRIPTOR.SIZEOF)
                .nVersion((short) 1)
                .dwLayerMask(PFD_MAIN_PLANE)
                .iPixelType(PFD_TYPE_RGBA)
                .dwFlags(flags)
                .cRedBits((byte) attribs.redSize)
                .cGreenBits((byte) attribs.greenSize)
                .cBlueBits((byte) attribs.blueSize)
                .cAlphaBits((byte) attribs.alphaSize)
                .cDepthBits((byte) attribs.depthSize)
                .cStencilBits((byte) attribs.stencilSize)
                .cAccumRedBits((byte) attribs.accumRedSize)
                .cAccumGreenBits((byte) attribs.accumGreenSize)
                .cAccumBlueBits((byte) attribs.accumBlueSize)
                .cAccumAlphaBits((byte) attribs.accumAlphaSize)
                .cAccumBits((byte) (attribs.accumRedSize + attribs.accumGreenSize
                        + attribs.accumBlueSize + attribs.accumAlphaSize));

        long hDCdummy = GetDC(dummyWindowHandle);
        if (hDCdummy == 0L) {
            throw new AWTException("Failed to get dummy window DC");
        }

        long currentContext = wglGetCurrentContext(null);
        long currentDc = wglGetCurrentDC();
        long dummyContext = 0L;
        try {
            int pixelFormat = ChoosePixelFormat(null, hDCdummy, pfd);
            if (pixelFormat == 0 || !SetPixelFormat(null, hDCdummy, pixelFormat, pfd)) {
                throw new AWTException("Unsupported pixel format");
            }

            dummyContext = wglCreateContext(null, hDCdummy);
            if (dummyContext == 0L) {
                throw new AWTException("Failed to create dummy OpenGL context");
            }
            if (!wglMakeCurrent(null, hDCdummy, dummyContext)) {
                throw new AWTException("Failed to make dummy OpenGL context current");
            }

            Set<String> wglExtensions = queryWGLExtensions(hDCdummy);
            boolean legacyContext = !atLeast30(attribs.majorVersion, attribs.minorVersion)
                    && attribs.samples == 0
                    && !attribs.sRGB
                    && !attribs.pixelFormatFloat
                    && attribs.contextReleaseBehavior == null
                    && !attribs.robustness
                    && attribs.api != API.GLES;
            if (legacyContext) {
                return createLegacyContext(hDC, pixelFormat, pfd,
                        attribs, effective, wglExtensions, bufferAddr);
            }
            return createExtendedContext(hDC, pixelFormat, pfd,
                    attribs, effective, wglExtensions, bufferAddr);
        } finally {
            if (!wglMakeCurrent(null, currentDc, currentContext)) {
                wglMakeCurrent(null, 0L, 0L);
            }
            if (dummyContext != 0L) {
                wglDeleteContext(null, dummyContext);
            }
            ReleaseDC(dummyWindowHandle, hDCdummy);
        }
    }

    private static Set<String> queryWGLExtensions(long hDC) {
        String extensions = "";
        long getExtensionsString = wglGetProcAddress(null, "wglGetExtensionsStringARB");
        if (getExtensionsString != 0L) {
            long address = callPP(hDC, getExtensionsString);
            if (address != 0L) {
                extensions = memASCII(address);
            }
        } else {
            getExtensionsString = wglGetProcAddress(null, "wglGetExtensionsStringEXT");
            if (getExtensionsString != 0L) {
                long address = callP(getExtensionsString);
                if (address != 0L) {
                    extensions = memASCII(address);
                }
            }
        }
        if (extensions.isEmpty()) {
            return Collections.emptySet();
        }
        String[] split = extensions.split(" ");
        Set<String> result = new HashSet<>(split.length);
        Collections.addAll(result, split);
        return result;
    }

    private static long createLegacyContext(long hDC, int pixelFormat,
            PIXELFORMATDESCRIPTOR pfd, GLData attribs, GLData effective,
            Set<String> wglExtensions, long bufferAddr) throws AWTException {
        applyPixelFormat(hDC, pixelFormat);
        long context = wglCreateContext(null, hDC);
        if (context == 0L) {
            throw new AWTException("Failed to create OpenGL context");
        }

        boolean success = false;
        try {
            boolean needsCurrentContext = attribs.swapInterval != null
                    || attribs.swapGroupNV > 0
                    || attribs.swapBarrierNV > 0;
            if (needsCurrentContext && !wglMakeCurrent(null, hDC, context)) {
                throw new AWTException("Could not make GL context current");
            }
            configureSwapInterval(attribs, wglExtensions);
            configureSwapGroup(attribs, wglExtensions, bufferAddr, hDC);

            if (attribs.shareContext != null
                    && !wglShareLists(null, attribs.shareContext.context, context)) {
                throw new AWTException("Failed while configuring context sharing");
            }

            if (DescribePixelFormat(null, hDC, pixelFormat, pfd) == 0) {
                throw new AWTException("Failed to describe pixel format");
            }
            effective.redSize = pfd.cRedBits();
            effective.greenSize = pfd.cGreenBits();
            effective.blueSize = pfd.cBlueBits();
            effective.alphaSize = pfd.cAlphaBits();
            effective.depthSize = pfd.cDepthBits();
            effective.stencilSize = pfd.cStencilBits();
            int pixelFormatFlags = pfd.dwFlags();
            effective.doubleBuffer = (pixelFormatFlags & PFD_DOUBLEBUFFER) != 0;
            effective.stereo = (pixelFormatFlags & PFD_STEREO) != 0;
            effective.accumRedSize = pfd.cAccumRedBits();
            effective.accumGreenSize = pfd.cAccumGreenBits();
            effective.accumBlueSize = pfd.cAccumBlueBits();
            effective.accumAlphaSize = pfd.cAccumAlphaBits();

            success = true;
            return context;
        } finally {
            if (!success) {
                wglMakeCurrent(null, 0L, 0L);
                wglDeleteContext(null, context);
            }
        }
    }

    private static long createExtendedContext(long hDC, int pixelFormat,
            PIXELFORMATDESCRIPTOR pfd, GLData attribs, GLData effective,
            Set<String> wglExtensions, long bufferAddr) throws AWTException {
        requireExtension(wglExtensions, "WGL_ARB_create_context",
                "Extended context attributes requested but WGL_ARB_create_context is unavailable");
        long createContextAttribs = wglGetProcAddress(null, "wglCreateContextAttribsARB");
        if (createContextAttribs == 0L) {
            throw new AWTException("WGL_ARB_create_context available but wglCreateContextAttribsARB is NULL");
        }

        IntBuffer attribList = BufferUtils.createIntBuffer(64);
        long attribListAddr = memAddress(attribList);

        if (attribs.samples > 0 || attribs.sRGB || attribs.pixelFormatFloat) {
            long choosePixelFormat = wglGetProcAddress(null, "wglChoosePixelFormatARB");
            if (choosePixelFormat == 0L) {
                choosePixelFormat = wglGetProcAddress(null, "wglChoosePixelFormatEXT");
            }
            if (choosePixelFormat == 0L) {
                throw new AWTException("No support for wglChoosePixelFormatARB/EXT. Cannot query supported pixel formats.");
            }
            if (attribs.samples > 0) {
                if (!wglExtensions.contains("WGL_ARB_multisample")
                        && !wglExtensions.contains("WGL_EXT_multisample")) {
                    throw new AWTException("Multisampling requested but neither WGL_ARB_multisample nor WGL_EXT_multisample available");
                }
                if (attribs.colorSamplesNV > 0) {
                    requireExtension(wglExtensions, "WGL_NV_multisample_coverage",
                            "Color samples requested but WGL_NV_multisample_coverage is unavailable");
                }
            }
            if (attribs.sRGB) {
                boolean hasExtFramebufferSrgb = wglExtensions.contains("WGL_EXT_framebuffer_sRGB");
                boolean hasArbFramebufferSrgb = wglExtensions.contains("WGL_ARB_framebuffer_sRGB");
                if (!hasExtFramebufferSrgb && !hasArbFramebufferSrgb) {
                    throw new AWTException("sRGB color space requested but WGL_EXT_framebuffer_sRGB is unavailable");
                }
                attribs.extBuffer_sRGB = hasExtFramebufferSrgb;
            }
            if (attribs.pixelFormatFloat) {
                requireExtension(wglExtensions, "WGL_ARB_pixel_format_float",
                        "Floating-point format requested but WGL_ARB_pixel_format_float is unavailable");
            }

            encodePixelFormatAttribs(attribList, attribs);
            boolean foundPixelFormat = callPPPPPI(hDC, attribListAddr, 0L, 1,
                    bufferAddr + 4, bufferAddr, choosePixelFormat) == 1;
            int numFormats = memGetInt(bufferAddr);
            if (!foundPixelFormat || numFormats == 0) {
                throw new AWTException("No supported pixel format found.");
            }
            pixelFormat = memGetInt(bufferAddr + 4);
            if (DescribePixelFormat(null, hDC, pixelFormat, pfd) == 0) {
                throw new AWTException("Failed to validate supported pixel format.");
            }
            readExtendedPixelFormat(hDC, pixelFormat, attribList, attribListAddr, effective);
        }

        attribList.rewind();
        if (attribs.api == API.GL && atLeast30(attribs.majorVersion, attribs.minorVersion)
                || attribs.api == API.GLES && attribs.majorVersion > 0) {
            attribList.put(WGL_CONTEXT_MAJOR_VERSION_ARB).put(attribs.majorVersion);
            attribList.put(WGL_CONTEXT_MINOR_VERSION_ARB).put(attribs.minorVersion);
        }
        int profile = 0;
        if (attribs.api == API.GL) {
            if (attribs.profile == Profile.COMPATIBILITY) {
                profile = WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB;
            } else if (attribs.profile == Profile.CORE) {
                profile = WGL_CONTEXT_CORE_PROFILE_BIT_ARB;
            }
        } else if (attribs.api == API.GLES) {
            requireExtension(wglExtensions, "WGL_EXT_create_context_es2_profile",
                    "OpenGL ES API requested but WGL_EXT_create_context_es2_profile is unavailable");
            profile = WGL_CONTEXT_ES2_PROFILE_BIT_EXT;
        }
        if (profile > 0) {
            requireExtension(wglExtensions, "WGL_ARB_create_context_profile",
                    "OpenGL profile requested but WGL_ARB_create_context_profile is unavailable");
            attribList.put(WGL_CONTEXT_PROFILE_MASK_ARB).put(profile);
        }

        int contextFlags = 0;
        if (attribs.debug) {
            contextFlags |= WGL_CONTEXT_DEBUG_BIT_ARB;
        }
        if (attribs.forwardCompatible) {
            contextFlags |= WGL_CONTEXT_FORWARD_COMPATIBLE_BIT_ARB;
        }
        if (attribs.robustness) {
            requireExtension(wglExtensions, "WGL_ARB_create_context_robustness",
                    "Context with robust buffer access requested but WGL_ARB_create_context_robustness is unavailable");
            contextFlags |= WGL_CONTEXT_ROBUST_ACCESS_BIT_ARB;
            if (attribs.loseContextOnReset) {
                attribList.put(WGL_CONTEXT_RESET_NOTIFICATION_STRATEGY_ARB)
                        .put(WGL_LOSE_CONTEXT_ON_RESET_ARB);
            }
            if (attribs.contextResetIsolation) {
                boolean applicationIsolation = wglExtensions.contains("WGL_ARB_robustness_application_isolation");
                boolean shareGroupIsolation = wglExtensions.contains("WGL_ARB_robustness_share_group_isolation");
                if (!applicationIsolation && !shareGroupIsolation) {
                    throw new AWTException(
                            "Robustness isolation requested but neither WGL_ARB_robustness_application_isolation nor WGL_ARB_robustness_share_group_isolation available");
                }
                contextFlags |= WGL_CONTEXT_RESET_ISOLATION_BIT_ARB;
            }
        }
        if (contextFlags > 0) {
            attribList.put(WGL_CONTEXT_FLAGS_ARB).put(contextFlags);
        }
        if (attribs.contextReleaseBehavior != null) {
            requireExtension(wglExtensions, "WGL_ARB_context_flush_control",
                    "Context release behavior requested but WGL_ARB_context_flush_control is unavailable");
            if (attribs.contextReleaseBehavior == ReleaseBehavior.NONE) {
                attribList.put(WGL_CONTEXT_RELEASE_BEHAVIOR_ARB)
                        .put(WGL_CONTEXT_RELEASE_BEHAVIOR_NONE_ARB);
            } else if (attribs.contextReleaseBehavior == ReleaseBehavior.FLUSH) {
                attribList.put(WGL_CONTEXT_RELEASE_BEHAVIOR_ARB)
                        .put(WGL_CONTEXT_RELEASE_BEHAVIOR_FLUSH_ARB);
            }
        }
        attribList.put(0).put(0);

        applyPixelFormat(hDC, pixelFormat);
        long context = callPPPP(hDC,
                attribs.shareContext != null ? attribs.shareContext.context : 0L,
                attribListAddr, createContextAttribs);
        if (context == 0L) {
            throw new AWTException("Failed to create OpenGL context.");
        }

        boolean success = false;
        try {
            if (!wglMakeCurrent(null, hDC, context)) {
                throw new AWTException("Could not make GL context current");
            }
            configureSwapInterval(attribs, wglExtensions);
            configureSwapGroup(attribs, wglExtensions, bufferAddr, hDC);
            readEffectiveContext(attribs, effective, wglExtensions, bufferAddr);
            success = true;
            return context;
        } finally {
            if (!success) {
                wglMakeCurrent(null, 0L, 0L);
                wglDeleteContext(null, context);
            }
        }
    }

    private static void readExtendedPixelFormat(long hDC, int pixelFormat, IntBuffer attribList,
            long attribListAddr, GLData effective) throws AWTException {
        long getPixelFormatAttribiv = wglGetProcAddress(null, "wglGetPixelFormatAttribivARB");
        if (getPixelFormatAttribiv == 0L) {
            getPixelFormatAttribiv = wglGetProcAddress(null, "wglGetPixelFormatAttribivEXT");
        }
        if (getPixelFormatAttribiv == 0L) {
            throw new AWTException(
                    "No support for wglGetPixelFormatAttribivARB/EXT. Cannot get effective pixel format attributes.");
        }

        attribList.rewind();
        attribList
                .put(WGL_DOUBLE_BUFFER_ARB)
                .put(WGL_STEREO_ARB)
                .put(WGL_PIXEL_TYPE_ARB)
                .put(WGL_RED_BITS_ARB)
                .put(WGL_GREEN_BITS_ARB)
                .put(WGL_BLUE_BITS_ARB)
                .put(WGL_ALPHA_BITS_ARB)
                .put(WGL_ACCUM_RED_BITS_ARB)
                .put(WGL_ACCUM_GREEN_BITS_ARB)
                .put(WGL_ACCUM_BLUE_BITS_ARB)
                .put(WGL_ACCUM_ALPHA_BITS_ARB)
                .put(WGL_DEPTH_BITS_ARB)
                .put(WGL_STENCIL_BITS_ARB);
        IntBuffer values = BufferUtils.createIntBuffer(attribList.position());
        boolean success = callPPPI(hDC, pixelFormat, PFD_MAIN_PLANE,
                attribList.position(), attribListAddr, memAddress(values), getPixelFormatAttribiv) == 1;
        if (!success) {
            throw new AWTException("Failed to get pixel format attributes.");
        }
        effective.doubleBuffer = values.get(0) == 1;
        effective.stereo = values.get(1) == 1;
        effective.pixelFormatFloat = values.get(2) == WGL_TYPE_RGBA_FLOAT_ARB;
        effective.redSize = values.get(3);
        effective.greenSize = values.get(4);
        effective.blueSize = values.get(5);
        effective.alphaSize = values.get(6);
        effective.accumRedSize = values.get(7);
        effective.accumGreenSize = values.get(8);
        effective.accumBlueSize = values.get(9);
        effective.accumAlphaSize = values.get(10);
        effective.depthSize = values.get(11);
        effective.stencilSize = values.get(12);
    }

    private static void configureSwapInterval(GLData attribs, Set<String> wglExtensions)
            throws AWTException {
        if (attribs.swapInterval == null) {
            return;
        }
        requireExtension(wglExtensions, "WGL_EXT_swap_control",
                "Swap interval requested but WGL_EXT_swap_control is unavailable");
        if (attribs.swapInterval < 0) {
            requireExtension(wglExtensions, "WGL_EXT_swap_control_tear",
                    "Negative swap interval requested but WGL_EXT_swap_control_tear is unavailable");
        }
        long swapInterval = wglGetProcAddress(null, "wglSwapIntervalEXT");
        if (swapInterval != 0L) {
            callI(attribs.swapInterval, swapInterval);
        }
    }

    private static void configureSwapGroup(GLData attribs, Set<String> wglExtensions,
            long bufferAddr, long hDC) throws AWTException {
        if (attribs.swapGroupNV == 0 && attribs.swapBarrierNV == 0) {
            return;
        }
        requireExtension(wglExtensions, "WGL_NV_swap_group",
                "Swap group or barrier requested but WGL_NV_swap_group is unavailable");
        wglNvSwapGroupAndBarrier(attribs, bufferAddr, hDC);
    }

    private static void readEffectiveContext(GLData attribs, GLData effective,
            Set<String> wglExtensions, long bufferAddr) {
        long getInteger = GL.getFunctionProvider().getFunctionAddress("glGetIntegerv");
        long getString = GL.getFunctionProvider().getFunctionAddress("glGetString");
        effective.api = attribs.api;
        if (atLeast30(attribs.majorVersion, attribs.minorVersion)) {
            callPV(GL_MAJOR_VERSION, bufferAddr, getInteger);
            effective.majorVersion = memGetInt(bufferAddr);
            callPV(GL_MINOR_VERSION, bufferAddr, getInteger);
            effective.minorVersion = memGetInt(bufferAddr);
            callPV(GL_CONTEXT_FLAGS, bufferAddr, getInteger);
            int effectiveContextFlags = memGetInt(bufferAddr);
            effective.debug = (effectiveContextFlags & GL_CONTEXT_FLAG_DEBUG_BIT) != 0;
            effective.forwardCompatible = (effectiveContextFlags & GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) != 0;
            effective.robustness = (effectiveContextFlags & GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT_ARB) != 0;
        } else {
            APIVersion version = apiParseVersion(
                    memUTF8(Checks.check(callP(GL_VERSION, getString))));
            effective.majorVersion = version.major;
            effective.minorVersion = version.minor;
        }
        if (attribs.api == API.GL && atLeast32(effective.majorVersion, effective.minorVersion)) {
            callPV(GL_CONTEXT_PROFILE_MASK, bufferAddr, getInteger);
            int effectiveProfileMask = memGetInt(bufferAddr);
            if ((effectiveProfileMask & GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0) {
                effective.profile = Profile.COMPATIBILITY;
            } else if ((effectiveProfileMask & GL_CONTEXT_CORE_PROFILE_BIT) != 0) {
                effective.profile = Profile.CORE;
            } else {
                effective.profile = null;
            }
        }
        if (attribs.samples >= 1) {
            callPV(GL_SAMPLES_ARB, bufferAddr, getInteger);
            effective.samples = memGetInt(bufferAddr);
            callPV(GL_SAMPLE_BUFFERS_ARB, bufferAddr, getInteger);
            effective.sampleBuffers = memGetInt(bufferAddr);
            if (wglExtensions.contains("WGL_NV_multisample_coverage")) {
                callPV(GL_COLOR_SAMPLES_NV, bufferAddr, getInteger);
                effective.colorSamplesNV = memGetInt(bufferAddr);
            }
        }
    }

    private static void requireExtension(Set<String> extensions, String extension, String message)
            throws AWTException {
        if (!extensions.contains(extension)) {
            throw new AWTException(message);
        }
    }

    private static void wglNvSwapGroupAndBarrier(GLData attribs, long bufferAddr, long hDC) throws AWTException {
        int success;
        long wglQueryMaxSwapGroupsNVAddr = wglGetProcAddress(null, "wglQueryMaxSwapGroupsNV");
        success = callPPPI(hDC, bufferAddr, bufferAddr + 4, wglQueryMaxSwapGroupsNVAddr);
        int maxGroups = memGetInt(bufferAddr);
        if (maxGroups < attribs.swapGroupNV) {
            throw new AWTException("Swap group exceeds maximum group index");
        }
        int maxBarriers = memGetInt(bufferAddr + 4);
        if (maxBarriers < attribs.swapBarrierNV) {
            throw new AWTException("Swap barrier exceeds maximum barrier index");
        }
        if (attribs.swapGroupNV > 0) {
            long wglJoinSwapGroupNVAddr = wglGetProcAddress(null, "wglJoinSwapGroupNV");
            if (wglJoinSwapGroupNVAddr == 0L) {
                throw new AWTException("WGL_NV_swap_group available but wglJoinSwapGroupNV is NULL");
            }
            success = callPI(hDC, attribs.swapGroupNV, wglJoinSwapGroupNVAddr);
            if (success == 0) {
                throw new AWTException("Failed to join swap group");
            }
            if (attribs.swapBarrierNV > 0) {
                long wglBindSwapBarrierNVAddr = wglGetProcAddress(null, "wglBindSwapBarrierNV");
                if (wglBindSwapBarrierNVAddr == 0L) {
                    throw new AWTException("WGL_NV_swap_group available but wglBindSwapBarrierNV is NULL");
                }
                success = callI(attribs.swapGroupNV, attribs.swapBarrierNV, wglBindSwapBarrierNVAddr);
                if (success == 0) {
                    throw new AWTException("Failed to bind swap barrier. Probably no G-Sync card installed.");
                }
            }
        }
    }

    @Override
    public boolean isCurrent(long context) {
        long ret = wglGetCurrentContext(null);
        return ret == context;
    }

    @Override
    public boolean makeCurrent(long context) {
        long hdc = requireLockedHdc();
        if (context == 0L)
            return wglMakeCurrent(null, 0L, 0L);
        return wglMakeCurrent(null, hdc, context);
    }

    @Override
    public boolean deleteContext(long context) {
        return wglDeleteContext(null, context);
    }

    @Override
    public boolean swapBuffers() {
        return SwapBuffers(null, requireLockedHdc());
    }

    @Override
    public boolean delayBeforeSwapNV(float seconds) {
        long hdc = requireLockedHdc();
        if (!wglDelayBeforeSwapNVAddr_set) {
            wglDelayBeforeSwapNVAddr = wglGetProcAddress(null, "wglDelayBeforeSwapNV");
            wglDelayBeforeSwapNVAddr_set = true;
        }
        if (wglDelayBeforeSwapNVAddr == 0L) {
            throw new UnsupportedOperationException("wglDelayBeforeSwapNV is unavailable");
        }
        return callPI(hdc, seconds, wglDelayBeforeSwapNVAddr) == 1;
    }

    @Override
    public void lock() throws AWTException {
        if (ds != null) {
            throw new AWTException("JAWT drawing surface is already locked");
        }
        if (canvas == null) {
            throw new AWTException("Canvas has not been created or was disposed");
        }
        JAWTDrawingSurface ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
        if (ds == null) {
            throw new AWTException("Failed to get JAWT drawing surface");
        }
        boolean locked = false;
        JAWTDrawingSurfaceInfo dsi = null;
        boolean success = false;
        try {
            int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
            if ((lock & JAWT_LOCK_ERROR) != 0) {
                throw new AWTException("JAWT_DrawingSurface_Lock() failed");
            }
            locked = true;
            dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
            if (dsi == null) {
                throw new AWTException("Failed to get JAWT drawing surface info");
            }
            JAWTWin32DrawingSurfaceInfo dsiWin = JAWTWin32DrawingSurfaceInfo.create(dsi.platformInfo());
            long currentHwnd = dsiWin.hwnd();
            long currentHdc = dsiWin.hdc();
            if (currentHwnd == 0L || currentHdc == 0L) {
                throw new AWTException("JAWT returned an invalid Win32 drawing surface");
            }
            if (currentHwnd != hwnd) {
                throw new AWTException(
                        "AWT recreated the canvas peer (HWND changed); the OpenGL context must be recreated");
            }
            this.ds = ds;
            this.dsi = dsi;
            this.hdc = currentHdc;
            this.drawingSurfaceThread = Thread.currentThread();
            success = true;
        } finally {
            if (!success) {
                if (dsi != null) {
                    JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
                }
                if (locked) {
                    JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
                }
                JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
            }
        }
    }

    @Override
    public void unlock() throws AWTException {
        JAWTDrawingSurface ds = this.ds;
        if (ds == null) {
            throw new AWTException("JAWT drawing surface is not locked");
        }
        if (drawingSurfaceThread != Thread.currentThread()) {
            throw new AWTException("JAWT drawing surface must be unlocked by the thread that locked it");
        }
        JAWTDrawingSurfaceInfo dsi = this.dsi;
        this.hdc = 0L;
        this.dsi = null;
        this.ds = null;
        this.drawingSurfaceThread = null;
        try {
            if (dsi != null) {
                JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
            }
        } finally {
            try {
                JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
            } finally {
                JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
            }
        }
    }

    private static void applyPixelFormat(long hdc, int pixelFormat) throws AWTException {
        if (pixelFormat == 0) {
            throw new AWTException("No pixel format is available for the AWT peer");
        }
        int currentPixelFormat = GetPixelFormat(null, hdc);
        if (currentPixelFormat == pixelFormat) {
            return;
        }
        if (currentPixelFormat != 0) {
            throw new AWTException("The AWT peer has an incompatible pixel format");
        }
        try (MemoryStack stack = stackPush()) {
            PIXELFORMATDESCRIPTOR pfd = PIXELFORMATDESCRIPTOR.calloc(stack)
                    .nSize((short) PIXELFORMATDESCRIPTOR.SIZEOF)
                    .nVersion((short) 1);
            if (DescribePixelFormat(null, hdc, pixelFormat, pfd) == 0
                    || !SetPixelFormat(null, hdc, pixelFormat, pfd)) {
                throw new AWTException("Failed to apply the pixel format to the AWT peer");
            }
        }
    }

    private long requireLockedHdc() {
        long hdc = this.hdc;
        if (hdc == 0L) {
            throw new IllegalStateException("The JAWT drawing surface must be locked for this operation");
        }
        if (drawingSurfaceThread != Thread.currentThread()) {
            throw new IllegalStateException("The JAWT drawing surface is locked by another thread");
        }
        return hdc;
    }

    @Override
    public void dispose() {
        canvas = null;
        hwnd = 0L;
    }

}
