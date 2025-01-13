package org.lwjgl.opengl.awt;

import java.awt.AWTException;
import java.awt.Canvas;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.*;
import org.lwjgl.system.Checks;
import org.lwjgl.system.JNI;

import static org.lwjgl.egl.EGL10.*;
import static org.lwjgl.egl.EGL15.*;
import static org.lwjgl.egl.EXTPresentOpaque.*;
import static org.lwjgl.egl.KHRContextFlushControl.*;
import static org.lwjgl.egl.KHRCreateContext.*;
import static org.lwjgl.egl.KHRGLColorspace.*;
import static org.lwjgl.opengl.GL.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.*;
import static org.lwjgl.opengl.ARBRobustness.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.jawt.JAWTFunctions.*;

/**
 * Class where the GL context (Using EGL) for the Wayland platform is implemented 
 * along with the AWT components using XWayland.
 * 
 * @author wil
 */
public class PlatformWLGLCanvas implements PlatformGLCanvas {
    
    public static final JAWT awt;

    static {
        awt = JAWT.calloc();
        awt.version(JAWT_VERSION_1_7);
        if (!JAWT_GetAWT(awt))
            throw new AssertionError("GetAWT failed");
    }
    
    // --- [ JAWT ] ---
    private JAWTDrawingSurface ds;
    private long nativeDisplay;
    private long drawable;
    
    // --- [ EGL ] ---
    private long display;    
    private long surface;
        
    private static String getEGLErrorString(int error) {
        switch (error) {
            case EGL_SUCCESS:
                return "Success";
            case EGL_NOT_INITIALIZED:
                return "EGL is not or could not be initialized";
            case EGL_BAD_ACCESS:
                return "EGL cannot access a requested resource";
            case EGL_BAD_ALLOC:
                return "EGL failed to allocate resources for the requested operation";
            case EGL_BAD_ATTRIBUTE:
                return "An unrecognized attribute or attribute value was passed in the attribute list";
            case EGL_BAD_CONTEXT:
                return "An EGLContext argument does not name a valid EGL rendering context";
            case EGL_BAD_CONFIG:
                return "An EGLConfig argument does not name a valid EGL frame buffer configuration";
            case EGL_BAD_CURRENT_SURFACE:
                return "The current surface of the calling thread is a window, pixel buffer or pixmap that is no longer valid";
            case EGL_BAD_DISPLAY:
                return "An EGLDisplay argument does not name a valid EGL display connection";
            case EGL_BAD_SURFACE:
                return "An EGLSurface argument does not name a valid surface configured for GL rendering";
            case EGL_BAD_MATCH:
                return "Arguments are inconsistent";
            case EGL_BAD_PARAMETER:
                return "One or more argument values are invalid";
            case EGL_BAD_NATIVE_PIXMAP:
                return "A NativePixmapType argument does not refer to a valid native pixmap";
            case EGL_BAD_NATIVE_WINDOW:
                return "A NativeWindowType argument does not refer to a valid native window";
            case EGL_CONTEXT_LOST:
                return "The application must destroy all contexts and reinitialise";
            default:
                return "ERROR: UNKNOWN EGL ERROR";
        }
    }
    
    private int getEGLConfigAttrib(long config, int attrib) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer value = stack.mallocInt(1);
            eglGetConfigAttrib(display, config, attrib, value);
            return value.get(0);
        }
    }
    
    private void wlEffectiveEGLAttribs(long n, GLData effective) {
        effective.redSize = getEGLConfigAttrib(n, EGL_RED_SIZE);
        effective.greenSize = getEGLConfigAttrib(n, EGL_GREEN_SIZE);
        effective.blueSize = getEGLConfigAttrib(n, EGL_BLUE_SIZE);
        
        effective.alphaSize = getEGLConfigAttrib(n, EGL_ALPHA_SIZE);
        effective.depthSize = getEGLConfigAttrib(n, EGL_DEPTH_SIZE);
        effective.stencilSize = getEGLConfigAttrib(n, EGL_STENCIL_SIZE);
        effective.samples = getEGLConfigAttrib(n, EGL_SAMPLES);
        effective.doubleBuffer = false;
    }
    
    private void wlRefreshContextAttribs(GLData effective) throws AWTException {
        long GetIntegerv = getFunctionProvider().getFunctionAddress("glGetIntegerv"),
             GetString   = getFunctionProvider().getFunctionAddress("glGetString");
        
        if (GetIntegerv == NULL || GetString == NULL) {
            throw new AWTException("Entry point retrieval is broken");
        }
        
        String[] prefixes = new String[] {
            "OpenGL ES-CM ",
            "OpenGL ES-CL ",
            "OpenGL ES ",
        };
        String version = getString(GL_VERSION, GetString);
        if (version == null) {
            throw new AWTException("OpenGL|OpenGL ES version string retrieval is broken");
        }
        
        effective.api = GLData.API.GL;
        for (final String pref : prefixes) {
            int length = pref.length();
            if (version.substring(0, length).compareTo(pref) == 0) {
                effective.api = GLData.API.GLES;
                break;
            }
        }
        
        APIUtil.APIVersion pIVersion = APIUtil.apiParseVersion(version);
        List<String> extensions = Arrays.asList(getString(GL_EXTENSIONS, GetString).split(" "));
        
        effective.majorVersion = pIVersion.major;
        effective.minorVersion = pIVersion.minor;
        
        if (effective.api == GLData.API.GL) {
            if (pIVersion.major >= 3) {
                int flags = getInteger(GL_CONTEXT_FLAGS, GetIntegerv);
                if ((flags & GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) != 0)
                    effective.forwardCompatible = true;
                
                if ((flags & GL_CONTEXT_FLAG_DEBUG_BIT) != 0)
                    effective.debug = true;
                else if (extensions.contains("GL_ARB_debug_output"))
                    effective.debug = true;
            }
            
            if (pIVersion.major >= 4 || (pIVersion.major == 3 && pIVersion.minor >= 2)) {
                int mask = getInteger(GL_CONTEXT_PROFILE_MASK, GetIntegerv);
                
                if ((mask & GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0)
                    effective.profile = GLData.Profile.COMPATIBILITY;
                else if ((mask & GL_CONTEXT_CORE_PROFILE_BIT) != 0)
                    effective.profile = GLData.Profile.CORE;
                else if (extensions.contains("GL_ARB_compatibility")) {
                    effective.profile = GLData.Profile.COMPATIBILITY;
                }
            }
            
            if (extensions.contains("GL_ARB_robustness")) {
                int strategy = getInteger(GL_RESET_NOTIFICATION_STRATEGY_ARB, GetIntegerv);
                
                if (strategy == GL_LOSE_CONTEXT_ON_RESET_ARB) 
                    effective.loseContextOnReset = true;
                else if (strategy == GL_NO_RESET_NOTIFICATION_ARB)
                    effective.contextResetIsolation = true;
                
                effective.robustness = true;
            }
        } else {
            if (extensions.contains("GL_EXT_robustness")) {
                int strategy = getInteger(GL_RESET_NOTIFICATION_STRATEGY_ARB, GetIntegerv);
                
                if (strategy == GL_LOSE_CONTEXT_ON_RESET_ARB)
                    effective.loseContextOnReset = true;
                else if (strategy == GL_NO_RESET_NOTIFICATION_ARB)
                    effective.contextResetIsolation = true;
                
                effective.robustness = true;
            }
        }
        
        if (extensions.contains("GL_KHR_context_flush_control")) {
            int behavior = getInteger(GL_CONTEXT_RELEASE_BEHAVIOR, GetIntegerv);
            if (behavior == GL_NONE)
                effective.contextReleaseBehavior = GLData.ReleaseBehavior.NONE;
            else if (behavior == GL_CONTEXT_RELEASE_BEHAVIOR_FLUSH)
                effective.contextReleaseBehavior = GLData.ReleaseBehavior.FLUSH;
        }
        
        effective.samples = getInteger(GL_SAMPLER, GetIntegerv);
    }
    
    
    public long wlCreateNativeSurface(GLData desired, GLData falternatives) throws AWTException {
        // === ---------------------------------------------------------------------------------------------===
        // ===                                       Initialize EGL                                         ===
        // === ---------------------------------------------------------------------------------------------===
        display = eglGetDisplay(nativeDisplay);        
        if (display == EGL_NO_DISPLAY) {
            wlEglTerminateEGL();
            throw new AWTException(String.format("EGL: Failed to get EGL display: %s", getEGLErrorString(eglGetError())));
        }
        
        try (MemoryStack stack = stackPush()) {
            IntBuffer major = stack.mallocInt(1);
            IntBuffer minor = stack.mallocInt(1);
            
            if (!eglInitialize(display, major, minor)) {
                wlEglTerminateEGL();
                throw new AWTException(String.format("EGL: Failed to initialize EGL: %s", getEGLErrorString(eglGetError())));
            }
        }
        
        // === ---------------------------------------------------------------------------------------------===
        // ===                           Create the OpenGL or OpenGL ES context                             ===
        // === ---------------------------------------------------------------------------------------------===
        List<String> extensions = Arrays.asList(eglQueryString(display, EGL_EXTENSIONS).split(" ")); 
        IntBuffer attrib_list = BufferUtils.createIntBuffer(18);
        attrib_list.put(EGL_RENDERABLE_TYPE).put(desired.api == GLData.API.GL ? EGL_OPENGL_BIT : EGL_OPENGL_ES_BIT);
        attrib_list.put(EGL_RED_SIZE).put(desired.redSize);
        attrib_list.put(EGL_GREEN_SIZE).put(desired.greenSize);
        attrib_list.put(EGL_BLUE_SIZE).put(desired.blueSize);        
        attrib_list.put(EGL_ALPHA_SIZE).put(desired.alphaSize);
        attrib_list.put(EGL_DEPTH_SIZE).put(desired.depthSize);
        attrib_list.put(EGL_STENCIL_SIZE).put(desired.stencilSize);
        attrib_list.put(EGL_SAMPLES).put(desired.samples);        
        attrib_list.put(EGL_NONE);
        attrib_list.flip();
        
        PointerBuffer fbConfigs = BufferUtils.createPointerBuffer(1);
        IntBuffer numConfigs = BufferUtils.createIntBuffer(1);
        
        if (!eglChooseConfig(display, attrib_list, fbConfigs, numConfigs) || fbConfigs.capacity() == 0) {
            throw new AWTException("No supported framebuffer configurations found");
        }
        
        long share = EGL_NO_CONTEXT;        
        if (desired.shareContext != null && desired.shareContext.context != NULL) {
            share = desired.shareContext.context;
        }
        
        if (desired.api == GLData.API.GLES) {
            if (!eglBindAPI(EGL_OPENGL_ES_API)) {
                throw new AWTException(String.format("EGL: Failed to bind OpenGL ES: %s", getEGLErrorString(eglGetError())));
            }
        } else {
            if (!eglBindAPI(EGL_OPENGL_API)) {
                throw new AWTException(String.format("EGL: Failed to bind OpenGL: %s", getEGLErrorString(eglGetError())));
            }
        }
        
        IntBuffer attribs = BufferUtils.createIntBuffer(40);
        if (extensions.contains("EGL_KHR_create_context")) {
            int mask = 0, flags = 0;
            if (desired.api == GLData.API.GL) {
                if (desired.forwardCompatible)
                    flags |= EGL_CONTEXT_OPENGL_FORWARD_COMPATIBLE_BIT_KHR;
                
                if (desired.profile == GLData.Profile.CORE)
                    mask |= EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR;
                else if (desired.profile == GLData.Profile.COMPATIBILITY)
                    mask |= EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR;
                
            }
            
            if (desired.debug)
                flags |= EGL_CONTEXT_OPENGL_DEBUG_BIT_KHR;

            if (desired.robustness) {
                if (desired.contextResetIsolation) {
                    attribs.put(EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_KHR)
                            .put(EGL_NO_RESET_NOTIFICATION_KHR);
                } else if (desired.loseContextOnReset) {
                    attribs.put(EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_KHR)
                            .put(EGL_LOSE_CONTEXT_ON_RESET_KHR);
                }

                flags |= EGL_CONTEXT_OPENGL_ROBUST_ACCESS_BIT_KHR;
            }

            if (desired.majorVersion != 0 && desired.majorVersion != 0) {
                attribs.put(EGL_CONTEXT_MAJOR_VERSION_KHR).put(desired.majorVersion);
                attribs.put(EGL_CONTEXT_MINOR_VERSION_KHR).put(desired.minorVersion);
            }

            if (mask != 0) {
                attribs.put(EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR).put(mask);
            }

            if (flags != 0)
                attribs.put(EGL_CONTEXT_FLAGS_KHR).put(flags);
            
        } else {
            if (desired.api == GLData.API.GL && desired.majorVersion != 0)
                attribs.put(EGL_CONTEXT_CLIENT_VERSION).put(desired.majorVersion);
        }
        
        if (extensions.contains("EGL_KHR_context_flush_control")) {
            if (desired.contextReleaseBehavior ==  GLData.ReleaseBehavior.NONE) {
                attribs.put(EGL_CONTEXT_RELEASE_BEHAVIOR_KHR)
                       .put(EGL_CONTEXT_RELEASE_BEHAVIOR_NONE_KHR);
            } else if (desired.contextReleaseBehavior == GLData.ReleaseBehavior.FLUSH) {
                attribs.put(EGL_CONTEXT_RELEASE_BEHAVIOR_KHR)
                       .put(EGL_CONTEXT_RELEASE_BEHAVIOR_FLUSH_KHR);
            }
        }
        
        attribs.put(EGL_NONE);
        attribs.flip();
        
        long handle = eglCreateContext(display, fbConfigs.get(0), share, attribs);
        if (handle == EGL_NO_CONTEXT) {
            throw new AWTException(String.format("EGL: Failed to create context: %s", getEGLErrorString(eglGetError())));
        }
        
        attribs.rewind();
        
        if (desired.sRGB) {
            if (extensions.contains("EGL_KHR_gl_colorspace"))
                attribs.put(EGL_GL_COLORSPACE_KHR).put(EGL_GL_COLORSPACE_SRGB_KHR);
        }
        
        if (!desired.doubleBuffer)
            attribs.put(EGL_RENDER_BUFFER).put(EGL_SINGLE_BUFFER);
        
        if (extensions.contains("EGL_EXT_present_opaque"))
            attribs.put(EGL_PRESENT_OPAQUE_EXT).put(0);
        
        attribs.put(EGL_NONE);
        attribs.flip();
        
        surface = eglCreateWindowSurface(display, fbConfigs.get(0), drawable, attribs);
        if (surface == EGL_NO_SURFACE) {
            throw new AWTException(String.format("EGL: Failed to create window surface: %s", getEGLErrorString(eglGetError())));
        }
        wlEffectiveEGLAttribs(fbConfigs.get(0), falternatives);
        
        if (!eglMakeCurrent(display,surface,surface, handle)) {
            throw new AWTException(String.format("Unable to make context current: %s", getEGLErrorString(eglGetError())));
        }
        
        if (desired.swapInterval != null) {
            eglSwapInterval(display, desired.swapInterval);
        }
        
        wlRefreshContextAttribs(falternatives);
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return handle;
    }
    
    @Override
    public long create(Canvas canvas, GLData data, GLData effective) throws AWTException {
        this.ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
        JAWTDrawingSurface ds0 = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
        try {
            lock();
            try {
                JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds0, ds0.GetDrawingSurfaceInfo());
                try {                    
                    JAWTX11DrawingSurfaceInfo dsiWin = JAWTX11DrawingSurfaceInfo.create(dsi.platformInfo());
                    this.nativeDisplay = dsiWin.display();
                    this.drawable      = dsiWin.drawable();
                    return wlCreateNativeSurface(data, effective);
                } finally {
                    JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds0.FreeDrawingSurfaceInfo());
                }
            } finally {
                unlock();
            }
        } finally {
            JAWT_FreeDrawingSurface(ds0, awt.FreeDrawingSurface());
        }
    }
    
    private void wlEglTerminateEGL() {
        if (display != NULL) {
            eglTerminate(display);
            display = NULL;
        }
    }

    @Override
    public boolean deleteContext(long context) {
        if (! eglDestroySurface(display, surface)) {
            return false;
        }
        boolean b = eglDestroyContext(display, context);
        wlEglTerminateEGL();
        return b;
    }

    @Override
    public boolean makeCurrent(long context) {
        if (context == NULL) {
            return eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        return eglMakeCurrent(display, surface, surface, context);
    }

    @Override
    public boolean isCurrent(long context) {
        return context == eglGetCurrentContext();
    }

    @Override
    public boolean swapBuffers() {
        return eglSwapBuffers(display, surface);
    }

    @Override
    public boolean delayBeforeSwapNV(float seconds) {
        throw new UnsupportedOperationException("NYI.");
    }

    @Override
    public void lock() throws AWTException {
        int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
	if ((lock & JAWT_LOCK_ERROR) != 0)
            throw new AWTException("JAWT_DrawingSurface_Lock() failed");
    }

    @Override
    public void unlock() throws AWTException {
        JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
    }

    @Override
    public void dispose() {
        if (this.ds != null) {
            JAWT_FreeDrawingSurface(this.ds, awt.FreeDrawingSurface());
            this.ds = null;
	}
    }
    
    private static int getInteger(int pname, long function) {
        MemoryStack stack = MemoryStack.stackGet();
        int stackPointer = stack.getPointer();
        try {
            IntBuffer params = stack.callocInt(1);
            JNI.callPV(pname, memAddress(params), function);
            return params.get(0);
        } finally {
            stack.setPointer(stackPointer);
        }
    }

    private static String getString(int pname, long function) {
        return memUTF8(Checks.check(JNI.callP(pname, function)));
    }
}
