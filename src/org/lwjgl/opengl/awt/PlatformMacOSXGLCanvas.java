package org.lwjgl.opengl.awt;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.ARBRobustness;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
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
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.macosx.ObjCRuntime;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.CGL.*;
import static org.lwjgl.opengl.GL11.glFlush;
import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.Pointer.POINTER_SIZE;
import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.system.libffi.LibFFI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_getUid;

public class PlatformMacOSXGLCanvas implements PlatformGLCanvas {
    public static final JAWT awt;
    private static final long objc_msgSend;
    private static final long objc_autoreleasePoolPush;
    private static final long objc_autoreleasePoolPop;
    private static final long NSOpenGLPixelFormat;

    static {
        awt = JAWT.calloc();
        awt.version(JAWT_VERSION_1_7);
        if (!JAWT_GetAWT(awt))
            throw new AssertionError("GetAWT failed");
        objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        objc_autoreleasePoolPush = ObjCRuntime.getLibrary().getFunctionAddress("objc_autoreleasePoolPush");
        objc_autoreleasePoolPop = ObjCRuntime.getLibrary().getFunctionAddress("objc_autoreleasePoolPop");
        NSOpenGLPixelFormat = objc_getClass("NSOpenGLPixelFormat");
    }

    public JAWTDrawingSurface ds;
    private Canvas canvas;
    private long view;
    private long interLayer;
    private long surfaceLayer;
    private boolean hierarchyListenerAdded;
    private int framebufferWidth;
    private int framebufferHeight;
    private final int[] currentFramebufferSize = new int[2];
    private int layerX;
    private int layerY;
    private int layerWidth;
    private int layerHeight;
    private long context;
    private boolean doubleBuffered;

    @Override
    public long create(Canvas canvas, GLData attribs, GLData effective) throws AWTException {
        MacOSXGLDataUtil.validateAttributes(attribs);
        this.canvas = canvas;
        try {
            if (!hierarchyListenerAdded) {
                canvas.addHierarchyListener(e -> {
                    // if the canvas, or a parent component is hidden/shown, we must update the hidden state of the layer
                    if (view != 0L && (e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) > 0) {
                        long layer = invokePPP(view, sel_getUid("layer"), objc_msgSend);
                        setLayerHiddenOnMainThread(layer, !e.getChanged().isShowing());
                    }
                });
                hierarchyListenerAdded = true;
            }
            long context;
            JAWTDrawingSurface ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
            if (ds == null) {
                throw new AWTException("Failed to get JAWT drawing surface");
            }
            try {
                int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
                if ((lock & JAWT_LOCK_ERROR) != 0) {
                    throw new AWTException("JAWT_DrawingSurface_Lock() failed");
                }
                try {
                    JAWTDrawingSurfaceInfo dsi =
                            JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
                    if (dsi == null) {
                        throw new AWTException("Failed to get JAWT drawing surface information");
                    }
                    try {
                        int width = dsi.bounds().width();
                        int height = dsi.bounds().height();
                        int[] layerBounds = getLayerBounds(canvas, dsi.bounds().x(), dsi.bounds().y(), width, height);
                        FramebufferSizeUtil.getScaledSize(canvas, width, height, currentFramebufferSize);
                        this.framebufferWidth = currentFramebufferSize[0];
                        this.framebufferHeight = currentFramebufferSize[1];
                        this.layerX = layerBounds[0];
                        this.layerY = layerBounds[1];
                        this.layerWidth = layerBounds[2];
                        this.layerHeight = layerBounds[3];

                        MacOSXGLDataUtil.PixelFormatSelection selection =
                                MacOSXGLDataUtil.choosePixelFormat(attribs, PlatformMacOSXGLCanvas::createPixelFormat);
                        long pixelFormat = selection.pixelFormat;
                        try {
                            view = createNSOpenGLView(pixelFormat,
                                    layerBounds[0], layerBounds[1], layerBounds[2], layerBounds[3]);
                            // The surface layer belongs to the peer view rather than to the drawing surface info, but
                            // retain it so it stays alive until the context is deleted.
                            surfaceLayer = invokePPP(dsi.platformInfo(), sel_getUid("retain"), objc_msgSend);
                            if (surfaceLayer == 0L) {
                                throw new AWTException("JAWT returned no macOS surface layer");
                            }
                            long openGLContext = invokePPP(view, sel_getUid("openGLContext"), objc_msgSend);
                            if (openGLContext == 0L) {
                                throw new AWTException("NSOpenGLView returned no NSOpenGLContext");
                            }
                            context = invokePPP(openGLContext, sel_getUid("CGLContextObj"), objc_msgSend);
                            if (context == 0L) {
                                throw new AWTException("NSOpenGLView returned no OpenGL context");
                            }
                            this.context = context;
                            configureSwapInterval(context, attribs.swapInterval);
                            populateEffectiveData(context, effective);
                            this.doubleBuffered = effective.doubleBuffer;
                        } finally {
                            invokePPV(pixelFormat, sel_getUid("release"), objc_msgSend);
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

            attachSurfaceLayer(surfaceLayer, interLayer);
            performSelectorOnMainThread(interLayer, sel_getUid("release"), MemoryUtil.NULL);
            return context;
        } catch (AWTException | RuntimeException | Error failure) {
            try {
                releaseFailedCreation();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Queues the view's layer tree for attachment to the JAWT surface layer on AppKit's main thread.
     *
     * <p>This must not wait for AppKit: the caller may be AWT's event thread while AppKit is synchronously calling
     * back into AWT, for example to query accessibility state.</p>
     */
    private static void attachSurfaceLayer(long surfaceLayer, long layer) {
        performSelectorOnMainThread(surfaceLayer, sel_getUid("setLayer:"), layer);
    }

    private static long createPixelFormat(int[] attributes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributeBuffer = stack.ints(attributes);
            long pixelFormat = invokePPP(NSOpenGLPixelFormat, sel_getUid("alloc"), objc_msgSend);
            return invokePPPP(pixelFormat, sel_getUid("initWithAttributes:"),
                    MemoryUtil.memAddress(attributeBuffer), objc_msgSend);
        }
    }

    private static void configureSwapInterval(long context, Integer swapInterval) throws AWTException {
        if (swapInterval == null) {
            return;
        }
        int error = CGLSetParameter(context, kCGLCPSwapInterval, swapInterval);
        if (error != kCGLNoError) {
            throw cglException("Failed to set the swap interval", error);
        }
    }

    private static void populateEffectiveData(long context, GLData effective) throws AWTException {
        long pixelFormat = CGLGetPixelFormat(context);
        if (pixelFormat == 0L) {
            throw new AWTException("CGL returned no pixel format for the new context");
        }

        effective.alphaSize = describePixelFormat(pixelFormat, kCGLPFAAlphaSize);
        int colorSize = Math.max(0, describePixelFormat(pixelFormat, kCGLPFAColorSize) - effective.alphaSize);
        effective.redSize = colorSize / 3 + (colorSize % 3 > 0 ? 1 : 0);
        effective.greenSize = colorSize / 3 + (colorSize % 3 > 1 ? 1 : 0);
        effective.blueSize = colorSize / 3;
        effective.depthSize = describePixelFormat(pixelFormat, kCGLPFADepthSize);
        effective.stencilSize = describePixelFormat(pixelFormat, kCGLPFAStencilSize);
        effective.doubleBuffer = describePixelFormat(pixelFormat, kCGLPFADoubleBuffer) != 0;
        effective.stereo = describePixelFormat(pixelFormat, kCGLPFAStereo) != 0;
        effective.pixelFormatFloat = describePixelFormat(pixelFormat, kCGLPFAColorFloat) != 0;
        effective.sampleBuffers = describePixelFormat(pixelFormat, kCGLPFASampleBuffers);
        effective.samples = describePixelFormat(pixelFormat, kCGLPFASamples);

        int accumSize = describePixelFormat(pixelFormat, kCGLPFAAccumSize);
        effective.accumRedSize = accumSize / 4 + (accumSize % 4 > 0 ? 1 : 0);
        effective.accumGreenSize = accumSize / 4 + (accumSize % 4 > 1 ? 1 : 0);
        effective.accumBlueSize = accumSize / 4 + (accumSize % 4 > 2 ? 1 : 0);
        effective.accumAlphaSize = accumSize / 4;

        effective.api = GLData.API.GL;
        effective.sRGB = false;
        effective.contextReleaseBehavior = null;
        effective.colorSamplesNV = 0;
        effective.swapGroupNV = 0;
        effective.swapBarrierNV = 0;
        effective.robustness = false;
        effective.loseContextOnReset = false;
        effective.contextResetIsolation = false;

        int[] swapInterval = new int[1];
        int error = CGLGetParameter(context, kCGLCPSwapInterval, swapInterval);
        if (error != kCGLNoError) {
            throw cglException("Failed to query the effective swap interval", error);
        }
        effective.swapInterval = swapInterval[0];
        populateEffectiveContextData(context, effective);
    }

    private static void populateEffectiveContextData(long context, GLData effective) throws AWTException {
        long previousContext = CGLGetCurrentContext();
        int error = CGLSetCurrentContext(context);
        if (error != kCGLNoError) {
            throw cglException("Failed to make the new context current while querying it", error);
        }
        try {
            queryEffectiveContextData(effective);
        } catch (AWTException | RuntimeException | Error failure) {
            int restoreError = CGLSetCurrentContext(previousContext);
            if (restoreError != kCGLNoError) {
                failure.addSuppressed(cglException("Failed to restore the previous OpenGL context", restoreError));
            }
            throw failure;
        }
        error = CGLSetCurrentContext(previousContext);
        if (error != kCGLNoError) {
            throw cglException("Failed to restore the previous OpenGL context", error);
        }
    }

    private static void queryEffectiveContextData(GLData effective) throws AWTException {
        long glGetString = GL.getFunctionProvider().getFunctionAddress("glGetString");
        long glGetIntegerv = GL.getFunctionProvider().getFunctionAddress("glGetIntegerv");
        if (glGetString == 0L || glGetIntegerv == 0L) {
            throw new AWTException("Failed to resolve OpenGL context-query functions");
        }

        APIVersion version = APIUtil.apiParseVersion(getString(GL11.GL_VERSION, glGetString));
        effective.majorVersion = version.major;
        effective.minorVersion = version.minor;
        effective.profile = null;
        effective.debug = false;
        effective.forwardCompatible = false;
        effective.robustness = false;
        effective.loseContextOnReset = false;
        effective.contextResetIsolation = false;

        if (GLUtil.atLeast32(version.major, version.minor)) {
            int profileMask = getInteger(GL32.GL_CONTEXT_PROFILE_MASK, glGetIntegerv);
            if ((profileMask & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0) {
                effective.profile = GLData.Profile.CORE;
            } else if ((profileMask & GL32.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0) {
                effective.profile = GLData.Profile.COMPATIBILITY;
            } else if (profileMask != 0) {
                throw new AWTException("Unknown OpenGL context profile mask: " + profileMask);
            }
        }
        if (GLUtil.atLeast30(version.major, version.minor)) {
            int contextFlags = getInteger(GL30.GL_CONTEXT_FLAGS, glGetIntegerv);
            effective.debug = (contextFlags & GL43.GL_CONTEXT_FLAG_DEBUG_BIT) != 0;
            effective.forwardCompatible =
                    (contextFlags & GL30.GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) != 0;
            effective.robustness =
                    (contextFlags & ARBRobustness.GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT_ARB) != 0;
            if (effective.robustness) {
                int resetStrategy =
                        getInteger(ARBRobustness.GL_RESET_NOTIFICATION_STRATEGY_ARB, glGetIntegerv);
                effective.loseContextOnReset =
                        resetStrategy == ARBRobustness.GL_LOSE_CONTEXT_ON_RESET_ARB;
            }
        }
    }

    private static int getInteger(int pname, long function) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.callocInt(1);
            JNI.callPV(pname, memAddress(value), function);
            return value.get(0);
        }
    }

    private static String getString(int pname, long function) {
        return MemoryUtil.memUTF8(Checks.check(JNI.callP(pname, function)));
    }

    private static int describePixelFormat(long pixelFormat, int attribute) throws AWTException {
        int[] value = new int[1];
        int error = CGLDescribePixelFormat(pixelFormat, 0, attribute, value);
        if (error != kCGLNoError) {
            throw cglException("Failed to query macOS pixel format attribute " + attribute, error);
        }
        return value[0];
    }

    private static AWTException cglException(String message, int error) {
        return new AWTException(message + ": " + CGLErrorString(error) + " (" + error + ")");
    }

    private long createNSOpenGLView(long pixelFormat, int x, int y, int width, int height) {
        long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

        // NSOpenGLView *nsOpenGLView = [NSOpenGLView alloc];
		long nsOpenGLView = JNI.invokePPP(
                ObjCRuntime.objc_getClass("NSOpenGLView"),
                ObjCRuntime.sel_getUid("alloc"),
                objc_msgSend);

        // init NSOpenGLView with frame and device
        // NSOpenGLView *view = [nsOpenGLView initWithFrame:pixelFormat:];
        long view = NSOpenGLView_initWithFrame(nsOpenGLView, 0, 0, width, height, pixelFormat);
        this.view = view;

        // make NSOpenGLView layer-backed
        // [view setWantsLayer:YES];
        JNI.invokePPV(view,
                ObjCRuntime.sel_getUid("setWantsLayer:"),
                true,
                objc_msgSend);

        // get layer from NSOpenGLView instance
        // CALayer *layer = nsOpenGLView.layer;
        long openglViewLayer = JNI.invokePPJ(view,
                ObjCRuntime.sel_getUid("layer"),
                objc_msgSend);
        if (openglViewLayer == 0L) {
            throw new IllegalStateException("NSOpenGLView returned no Core Animation layer");
        }

        // The layer must not autoresize with the intermediate layer. Core Animation's autoresizing applies the
        // superlayer's bounds *delta* to its sublayers, and the intermediate layer's frame is applied
        // asynchronously on AppKit's main thread. Whenever that frame lands after this layer has been added, the
        // intermediate layer grows from its default 0x0 to width x height, and kCALayerWidthSizable |
        // kCALayerHeightSizable would add that same width/height to this layer, presenting it at twice its size.
        // The canvas dimensions are known here and are reapplied by updateLayerBounds, so size the layer directly.
        // [layer setAutoresizingMask:kCALayerNotSizable];
        JNI.callPPPV(openglViewLayer,
                ObjCRuntime.sel_getUid("setAutoresizingMask:"),
                0,
                objc_msgSend);

        // create intermediate layer and set its frame
        // CALayer *interLayer = [CALayer layer];
        long intermediateLayer = JNI.invokePPP(
                ObjCRuntime.objc_getClass("CALayer"),
                ObjCRuntime.sel_getUid("layer"),
                objc_msgSend);
        if (intermediateLayer == 0L) {
            throw new IllegalStateException("Unable to create the intermediate Core Animation layer");
        }
        invokePPP(intermediateLayer, sel_getUid("retain"), objc_msgSend);
        interLayer = intermediateLayer;

        // [interLayer setFrame:CGRectMake(x, y, width, height)];
        setFrameOnMainThread(interLayer, x, y, width, height);

        // add NSOpenGLView's layer to the intermediate layer
        // [interLayer addSublayer:layer];
        JNI.callPPPV(interLayer,
                ObjCRuntime.sel_getUid("addSublayer:"),
                openglViewLayer,
                objc_msgSend);

        // the intermediate layer is handed to the JAWTSurfaceLayer by the caller, once it has released the
        // drawing surface lock

        return view;
    }

    private static void setLayerHiddenOnMainThread(long layer, boolean hidden) {
        // Core Animation transactions are thread-local. Run the mutation on AppKit's main thread so its
        // run loop commits the change, instead of explicitly flushing a transaction created on the AWT EDT.
        long setHidden = sel_getUid("setHidden:");
        long methodSignature = invokePPPP(layer, sel_getUid("methodSignatureForSelector:"), setHidden, objc_msgSend);
        long invocation = invokePPPP(objc_getClass("NSInvocation"),
                sel_getUid("invocationWithMethodSignature:"), methodSignature, objc_msgSend);

        invokePPPV(invocation, sel_getUid("setTarget:"), layer, objc_msgSend);
        invokePPPV(invocation, sel_getUid("setSelector:"), setHidden, objc_msgSend);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer hiddenValue = stack.malloc(1);
            hiddenValue.put(0, hidden ? (byte) 1 : 0);
            JNI.callPPPPV(invocation, sel_getUid("setArgument:atIndex:"), memAddress(hiddenValue), 2, objc_msgSend);
        }

        // Hierarchy notifications run on AWT's event thread, which AppKit may itself be waiting for. Queue the
        // mutation rather than introducing a synchronous AWT/AppKit cross-thread wait.
        invokePPP(invocation, sel_getUid("retain"), objc_msgSend);
        performSelectorOnMainThread(invocation, sel_getUid("invoke"), MemoryUtil.NULL);
        performSelectorOnMainThread(invocation, sel_getUid("release"), MemoryUtil.NULL);
    }

    /**
     * Queues a {@code setFrame:} on AppKit's main thread. The target may be a CALayer or an NSView; both take a
     * CGRect, so the same invocation is used for either.
     */
    private static void setFrameOnMainThread(long target, int x, int y, int width, int height) {
        long autoreleasePool = invokeP(objc_autoreleasePoolPush);
        try {
            // Core Animation frame changes must be committed by AppKit's run loop. NSInvocation copies the CGRect
            // argument during setArgument:atIndex:, so stack storage remains valid for the complete native read.
            long setFrame = sel_getUid("setFrame:");
            long methodSignature = invokePPPP(target, sel_getUid("methodSignatureForSelector:"), setFrame, objc_msgSend);
            long invocation = invokePPPP(objc_getClass("NSInvocation"),
                    sel_getUid("invocationWithMethodSignature:"), methodSignature, objc_msgSend);

            invokePPPV(invocation, sel_getUid("setTarget:"), target, objc_msgSend);
            invokePPPV(invocation, sel_getUid("setSelector:"), setFrame, objc_msgSend);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                DoubleBuffer frame = stack.doubles(x, y, width, height);
                JNI.callPPPPV(invocation, sel_getUid("setArgument:atIndex:"), memAddress(frame), 2, objc_msgSend);
            }

            invokePPP(invocation, sel_getUid("retain"), objc_msgSend);
            performSelectorOnMainThread(invocation, sel_getUid("invoke"), MemoryUtil.NULL);
            performSelectorOnMainThread(invocation, sel_getUid("release"), MemoryUtil.NULL);
        } finally {
            invokePV(autoreleasePool, objc_autoreleasePoolPop);
        }
    }

    private static void performSelectorOnMainThread(long target, long selector, long argument) {
        JNI.callPPPPV(target,
                sel_getUid("performSelectorOnMainThread:withObject:waitUntilDone:"),
                selector,
                argument,
                ObjCRuntime.NO,
                objc_msgSend);
    }

    private static long NSOpenGLView_initWithFrame(long nsopenglView, double x, double y, double width, double height, long pixelFormat) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FFIType cgRect = createCGRectType(stack);
            PointerBuffer argumentTypes = stack.pointers(
                    ffi_type_pointer.address(), // NSOpenGLView*
                    ffi_type_pointer.address(), // initWithFrame:pixelFormat:
                    cgRect.address(),            // CGRect
                    ffi_type_pointer.address()); // pixelFormat*

            FFICIF cif = FFICIF.malloc(stack);
            int status = ffi_prep_cif(cif, FFI_DEFAULT_ABI, ffi_type_pointer, argumentTypes);
            if (status != FFI_OK) {
                throw new IllegalStateException("ffi_prep_cif failed: " + status);
            }

            DoubleBuffer frame = stack.doubles(x, y, width, height);
            PointerBuffer pointerValues = stack.pointers(
                    nsopenglView,
                    ObjCRuntime.sel_getUid("initWithFrame:pixelFormat:"),
                    pixelFormat);
            PointerBuffer arguments = stack.pointers(
                    memAddress(pointerValues, 0),
                    memAddress(pointerValues, 1),
                    memAddress(frame),
                    memAddress(pointerValues, 2));

            ByteBuffer view = stack.malloc(POINTER_SIZE);
            ffi_call(cif, objc_msgSend, view, arguments);

            long result = PointerBuffer.get(view, 0);
            if (result == 0L) {
                throw new IllegalStateException("[NSOpenGLView initWithFrame:pixelFormat:] returned null.");
            }
            return result;
        }
    }

    private static FFIType createCGRectType(MemoryStack stack) {
        PointerBuffer elements = stack.mallocPointer(5);
        elements.put(ffi_type_double.address());
        elements.put(ffi_type_double.address());
        elements.put(ffi_type_double.address());
        elements.put(ffi_type_double.address());
        elements.put(MemoryUtil.NULL);
        elements.flip();
        return FFIType.calloc(stack).type(FFI_TYPE_STRUCT).elements(elements);
    }

    private void releaseFailedCreation() {
        long failedContext = this.context;
        long failedView = this.view;
        long failedInterLayer = this.interLayer;
        long failedSurfaceLayer = this.surfaceLayer;
        this.context = 0L;
        this.view = 0L;
        this.interLayer = 0L;
        this.surfaceLayer = 0L;
        this.doubleBuffered = false;
        this.canvas = null;

        Throwable cleanupFailure = null;
        if (failedContext != 0L && CGLGetCurrentContext() == failedContext) {
            int clearContextError = CGLSetCurrentContext(0L);
            if (clearContextError != kCGLNoError) {
                cleanupFailure = new IllegalStateException("Failed to clear the partially created OpenGL context: "
                        + CGLErrorString(clearContextError) + " (" + clearContextError + ")");
            }
        }

        // These selectors share AppKit's FIFO queue with layer creation, so teardown remains ordered even when
        // creation fails after some native objects have already been initialized.
        if (failedSurfaceLayer != 0L) {
            cleanupFailure = runCleanup(cleanupFailure,
                    () -> performSelectorOnMainThread(
                            failedSurfaceLayer, sel_getUid("setLayer:"), MemoryUtil.NULL));
        }
        if (failedView != 0L) {
            cleanupFailure = runCleanup(cleanupFailure,
                    () -> performSelectorOnMainThread(failedView,
                            sel_getUid("removeFromSuperviewWithoutNeedingDisplay"), MemoryUtil.NULL));
            cleanupFailure = runCleanup(cleanupFailure,
                    () -> performSelectorOnMainThread(
                            failedView, sel_getUid("clearGLContext"), MemoryUtil.NULL));
            cleanupFailure = runCleanup(cleanupFailure,
                    () -> performSelectorOnMainThread(failedView, sel_getUid("release"), MemoryUtil.NULL));
        }
        if (failedSurfaceLayer != 0L) {
            cleanupFailure = runCleanup(cleanupFailure,
                    () -> performSelectorOnMainThread(
                            failedSurfaceLayer, sel_getUid("release"), MemoryUtil.NULL));
        }
        if (failedInterLayer != 0L) {
            cleanupFailure = runCleanup(cleanupFailure,
                    () -> performSelectorOnMainThread(
                            failedInterLayer, sel_getUid("release"), MemoryUtil.NULL));
        }
        if (cleanupFailure instanceof Error) {
            throw (Error) cleanupFailure;
        }
        if (cleanupFailure != null) {
            throw (RuntimeException) cleanupFailure;
        }
    }

    private static Throwable runCleanup(Throwable previousFailure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error failure) {
            if (previousFailure == null) {
                return failure;
            }
            if (previousFailure != failure) {
                previousFailure.addSuppressed(failure);
            }
        }
        return previousFailure;
    }

    @Override
    public boolean swapBuffers() {
        if (!doubleBuffered) {
            glFlush();
            return true;
        }
        long currentContext = CGLGetCurrentContext();
        return currentContext == context && CGLFlushDrawable(context) == kCGLNoError;
    }

    @Override
    public boolean deleteContext(long context) {
        long view = this.view;
        long surfaceLayer = this.surfaceLayer;
        this.view = 0L;
        this.interLayer = 0L;
        this.surfaceLayer = 0L;
        this.context = 0L;
        this.doubleBuffered = false;

        // Keep teardown ordered behind any pending layer updates and run it on AppKit's main thread. Clearing an
        // NSOpenGLContext concurrently with Core Animation displaying its backing layer can abort inside setView:.
        performSelectorOnMainThread(surfaceLayer, sel_getUid("setLayer:"), MemoryUtil.NULL);
        performSelectorOnMainThread(view, sel_getUid("removeFromSuperviewWithoutNeedingDisplay"), MemoryUtil.NULL);
        performSelectorOnMainThread(view, sel_getUid("clearGLContext"), MemoryUtil.NULL);
        performSelectorOnMainThread(view, sel_getUid("release"), MemoryUtil.NULL);
        performSelectorOnMainThread(surfaceLayer, sel_getUid("release"), MemoryUtil.NULL);
        return true;
    }

    @Override
    public boolean makeCurrent(long context) {
        if (CGLSetCurrentContext(context) != kCGLNoError) {
            return false;
        }
        if (context != 0L) {
            JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
            if (dsi == null) {
                return false;
            }
            try {
                int width = dsi.bounds().width();
                int height = dsi.bounds().height();
                int[] layerBounds = getLayerBounds(canvas, dsi.bounds().x(), dsi.bounds().y(), width, height);
                updateLayerBounds(layerBounds);
                FramebufferSizeUtil.getScaledSize(canvas, width, height, currentFramebufferSize);
                int backingWidth = currentFramebufferSize[0];
                int backingHeight = currentFramebufferSize[1];
                // AppKit may recreate a layer-backed drawable without changing the component dimensions, so keep
                // the CGL backing-size override authoritative on every context activation.
                if (CGLSetParameter(context, kCGLCPSurfaceBackingSize,
                        new int[]{backingWidth, backingHeight}) != kCGLNoError
                        || CGLEnable(context, kCGLCESurfaceBackingSize) != kCGLNoError) {
                    return false;
                }
                if (CGLGetParameter(context, kCGLCPSurfaceBackingSize, currentFramebufferSize) != kCGLNoError) {
                    return false;
                }
                this.framebufferWidth = Math.max(0, currentFramebufferSize[0]);
                this.framebufferHeight = Math.max(0, currentFramebufferSize[1]);
            } finally {
                JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
            }
        }
        return true;
    }

    private void updateLayerBounds(int[] bounds) {
        if (bounds[0] == layerX && bounds[1] == layerY
                && bounds[2] == layerWidth && bounds[3] == layerHeight) {
            return;
        }
        boolean resized = bounds[2] != layerWidth || bounds[3] != layerHeight;
        layerX = bounds[0];
        layerY = bounds[1];
        layerWidth = bounds[2];
        layerHeight = bounds[3];
        if (interLayer != 0L) {
            setFrameOnMainThread(interLayer, layerX, layerY, layerWidth, layerHeight);
        }
        if (resized && view != 0L) {
            // The OpenGL layer does not autoresize with the intermediate layer, so it has to follow the canvas
            // explicitly. Resizing the view keeps NSOpenGLView's own drawable in step with its layer.
            setFrameOnMainThread(view, 0, 0, layerWidth, layerHeight);
            setFrameOnMainThread(invokePPP(view, sel_getUid("layer"), objc_msgSend),
                    0, 0, layerWidth, layerHeight);
        }
    }

    static int[] getLayerBounds(Canvas canvas, int x, int y, int width, int height) {
        // JAWT reports incorrect coordinates for canvases nested in containers such as JSplitPane.
        // Do not use SwingUtilities.convertPoint here: rendering holds the lifecycle lock, while screen-coordinate
        // conversion may acquire AWT's tree lock in the opposite order to removeNotify. Reading the hierarchy directly
        // is intentionally lock-free. A concurrent layout can yield one stale frame, but the next activation converges.
        int rootX = 0;
        int rootY = 0;
        Component child = canvas;
        Container parent = canvas.getParent();
        while (parent != null) {
            rootX += child.getX();
            rootY += child.getY();
            if (parent instanceof JRootPane) {
                x = rootX;
                y = parent.getHeight() - rootY - height;
                break;
            }
            child = parent;
            parent = parent.getParent();
        }
        return new int[]{x, y, width, height};
    }

    @Override
    public boolean getFramebufferSize(int[] size) {
        if (canvas == null) {
            return false;
        }
        size[0] = framebufferWidth;
        size[1] = framebufferHeight;
        return true;
    }

    @Override
    public boolean isCurrent(long context) {
        return CGLGetCurrentContext() == context;
    }


    @Override
    public boolean delayBeforeSwapNV(float seconds) {
        throw new UnsupportedOperationException("NYI");
    }

    @Override
    public void lock() throws AWTException {
        JAWTDrawingSurface ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
        if (ds == null) {
            throw new AWTException("Failed to get JAWT drawing surface");
        }
        int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
        if ((lock & JAWT_LOCK_ERROR) != 0) {
            JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
            throw new AWTException("JAWT_DrawingSurface_Lock() failed");
        }
        this.ds = ds;
    }

    @Override
    public void unlock() throws AWTException {
        JAWTDrawingSurface ds = this.ds;
        if (ds == null) {
            throw new AWTException("JAWT drawing surface is not locked");
        }
        try {
            JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
        } finally {
            JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
            this.ds = null;
        }
    }

    @Override
    public void dispose() {
        canvas = null;
    }

}
