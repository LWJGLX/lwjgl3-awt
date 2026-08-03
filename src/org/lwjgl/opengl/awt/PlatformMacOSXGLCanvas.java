package org.lwjgl.opengl.awt;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
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
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;

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
    private static final int NSOpenGLPFAAllRenderers = 1;    /* choose from all available renderers          */
    private static final int NSOpenGLPFATripleBuffer = 3;    /* choose a triple buffered pixel format        */
    private static final int NSOpenGLPFADoubleBuffer = 5;    /* choose a double buffered pixel format        */
    private static final int NSOpenGLPFAAuxBuffers = 7;    /* number of aux buffers                        */
    private static final int NSOpenGLPFAColorSize = 8;    /* number of color buffer bits                  */
    private static final int NSOpenGLPFAAlphaSize = 11;    /* number of alpha component bits               */
    private static final int NSOpenGLPFADepthSize = 12;    /* number of depth buffer bits                  */
    private static final int NSOpenGLPFAStencilSize = 13;    /* number of stencil buffer bits                */
    private static final int NSOpenGLPFAAccumSize = 14;    /* number of accum buffer bits                  */
    private static final int NSOpenGLPFAMinimumPolicy = 51;    /* never choose smaller buffers than requested  */
    private static final int NSOpenGLPFAMaximumPolicy = 52;    /* choose largest buffers of type requested     */
    private static final int NSOpenGLPFASampleBuffers = 55;    /* number of multi sample buffers               */
    private static final int NSOpenGLPFASamples = 56;    /* number of samples per multi sample buffer    */
    private static final int NSOpenGLPFAAuxDepthStencil = 57;    /* each aux buffer has its own depth stencil    */
    private static final int NSOpenGLPFAColorFloat = 58;    /* color buffers store floating point pixels    */
    private static final int NSOpenGLPFAMultisample = 59;    /* choose multisampling                         */
    private static final int NSOpenGLPFASupersample = 60;    /* choose supersampling                         */
    private static final int NSOpenGLPFASampleAlpha = 61;    /* request alpha filtering                      */
    private static final int NSOpenGLPFARendererID = 70;    /* request renderer by ID                       */
    private static final int NSOpenGLPFANoRecovery = 72;    /* disable all failure recovery systems         */
    private static final int NSOpenGLPFAAccelerated = 73;    /* choose a hardware accelerated renderer       */
    private static final int NSOpenGLPFAClosestPolicy = 74;    /* choose the closest color buffer to request   */
    private static final int NSOpenGLPFABackingStore = 76;    /* back buffer contents are valid after swap    */
    private static final int NSOpenGLPFAScreenMask = 84;    /* bit mask of supported physical screens       */
    private static final int NSOpenGLPFAAllowOfflineRenderers = 96;  /* allow use of offline renderers               */
    private static final int NSOpenGLPFAAcceleratedCompute = 97;    /* choose a hardware accelerated compute device */
    private static final int NSOpenGLPFAOpenGLProfile = 99;    /* specify an OpenGL Profile to use             */
    private static final int NSOpenGLPFAVirtualScreenCount = 128;    /* number of virtual screens in this format     */

    private static final int NSOpenGLPFAStereo = 6;
    private static final int NSOpenGLPFAOffScreen = 53;
    private static final int NSOpenGLPFAFullScreen = 54;
    private static final int NSOpenGLPFASingleRenderer = 71;
    private static final int NSOpenGLPFARobust = 75;
    private static final int NSOpenGLPFAMPSafe = 78;
    private static final int NSOpenGLPFAWindow = 80;
    private static final int NSOpenGLPFAMultiScreen = 81;
    private static final int NSOpenGLPFACompliant = 83;
    private static final int NSOpenGLPFAPixelBuffer = 90;
    private static final int NSOpenGLPFARemotePixelBuffer = 91;

    private static final int NSOpenGLProfileVersion3_2Core = 0x3200;
    private static final int NSOpenGLProfileVersionLegacy = 0x1000;
    private static final int NSOpenGLProfileVersion4_1Core = 0x4100;

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

    @Override
    public long create(Canvas canvas, GLData attribs, GLData effective) throws AWTException {
        this.canvas = canvas;
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
        try {
            int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
            if ((lock & JAWT_LOCK_ERROR) != 0)
                throw new AWTException("JAWT_DrawingSurface_Lock() failed");
            try {
                JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
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

                    //TODO: we don't really need 100
                    ByteBuffer attribsArray = ByteBuffer.allocateDirect(4 * 100).order(ByteOrder.nativeOrder());
                    attribsArray.putInt(NSOpenGLPFAAccelerated);
                    attribsArray.putInt(NSOpenGLPFAClosestPolicy);
                    if (attribs.stereo) {
                        attribsArray.putInt(NSOpenGLPFAStereo);
                    }
                    if (attribs.doubleBuffer) {
                        //doesn't work currently
                        //attribsArray.putInt(NSOpenGLPFADoubleBuffer);
                    }

                    if (attribs.pixelFormatFloat) {
                        attribsArray.putInt(NSOpenGLPFAColorFloat);
                    }

                    attribsArray.putInt(NSOpenGLPFAAccumSize);
                    attribsArray.putInt(attribs.accumRedSize + attribs.accumGreenSize + attribs.accumBlueSize + attribs.accumAlphaSize);

                    int colorBits = attribs.redSize +
                            attribs.greenSize +
                            attribs.blueSize;

                    // macOS needs non-zero color size, so set reasonable values
                    if (colorBits == 0)
                        colorBits = 24;
                    else if (colorBits < 15)
                        colorBits = 15;

                    attribsArray.putInt(NSOpenGLPFAColorSize);
                    attribsArray.putInt(colorBits);

                    attribsArray.putInt(NSOpenGLPFAAlphaSize);
                    attribsArray.putInt(attribs.alphaSize);

                    attribsArray.putInt(NSOpenGLPFADepthSize);
                    attribsArray.putInt(attribs.depthSize);

                    attribsArray.putInt(NSOpenGLPFAStencilSize);
                    attribsArray.putInt(attribs.stencilSize);

                    if (attribs.samples == 0) {
                        attribsArray.putInt(NSOpenGLPFASampleBuffers);
                        attribsArray.putInt(0);
                    } else {
                        attribsArray.putInt(NSOpenGLPFASampleBuffers);
                        attribsArray.putInt(1);
                        attribsArray.putInt(NSOpenGLPFASamples);
                        attribsArray.putInt(attribs.samples);
                    }

                    if (attribs.profile == GLData.Profile.CORE) {
                        attribsArray.putInt(NSOpenGLPFAOpenGLProfile);
                        attribsArray.putInt(NSOpenGLProfileVersion3_2Core);
                    }
                    if (attribs.profile == GLData.Profile.COMPATIBILITY) {
                        attribsArray.putInt(NSOpenGLPFAOpenGLProfile);
                        attribsArray.putInt(NSOpenGLProfileVersionLegacy);
                    } else {
                        if (attribs.majorVersion >= 4) {
                            attribsArray.putInt(NSOpenGLPFAOpenGLProfile);
                            attribsArray.putInt(NSOpenGLProfileVersion4_1Core);
                        } else if (attribs.majorVersion >= 3) {
                            attribsArray.putInt(NSOpenGLPFAOpenGLProfile);
                            attribsArray.putInt(NSOpenGLProfileVersion3_2Core);
                        } else {
                            attribsArray.putInt(NSOpenGLPFAOpenGLProfile);
                            attribsArray.putInt(NSOpenGLProfileVersionLegacy);
                        }
                    }

                    // 0 Terminated
                    attribsArray.putInt(0).rewind();

                    long pixelFormat = invokePPP(NSOpenGLPixelFormat, sel_getUid("alloc"), objc_msgSend);
                    pixelFormat = invokePPPP(pixelFormat, sel_getUid("initWithAttributes:"), MemoryUtil.memAddress(attribsArray), objc_msgSend);

                    view = createNSOpenGLView(pixelFormat,
                            layerBounds[0], layerBounds[1], layerBounds[2], layerBounds[3]);
                    // The surface layer belongs to the peer view rather than to the drawing surface info, but
                    // retain it so it stays alive until the context is deleted.
                    surfaceLayer = invokePPP(dsi.platformInfo(), sel_getUid("retain"), objc_msgSend);
                    long openGLContext = invokePPP(view, sel_getUid("openGLContext"), objc_msgSend);
                    context = invokePPP(openGLContext, sel_getUid("CGLContextObj"), objc_msgSend);
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
		interLayer = JNI.invokePPP(
                ObjCRuntime.objc_getClass("CALayer"),
                ObjCRuntime.sel_getUid("layer"),
                objc_msgSend);
        invokePPP(interLayer, sel_getUid("retain"), objc_msgSend);

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

        ByteBuffer hiddenValue = BufferUtils.createByteBuffer(1);
        hiddenValue.put(0, hidden ? (byte) 1 : 0);
        JNI.callPPPPV(invocation, sel_getUid("setArgument:atIndex:"), memAddress(hiddenValue), 2, objc_msgSend);

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
            // Core Animation frame changes must be committed by AppKit's run loop. NSInvocation also copies the
            // CGRect argument, so the temporary direct buffer can be released as soon as the mutation has been queued.
            long setFrame = sel_getUid("setFrame:");
            long methodSignature = invokePPPP(target, sel_getUid("methodSignatureForSelector:"), setFrame, objc_msgSend);
            long invocation = invokePPPP(objc_getClass("NSInvocation"),
                    sel_getUid("invocationWithMethodSignature:"), methodSignature, objc_msgSend);

            invokePPPV(invocation, sel_getUid("setTarget:"), target, objc_msgSend);
            invokePPPV(invocation, sel_getUid("setSelector:"), setFrame, objc_msgSend);

            ByteBuffer frame = BufferUtils.createByteBuffer(4 * Double.BYTES).order(ByteOrder.nativeOrder());
            frame.putDouble(0, x);
            frame.putDouble(Double.BYTES, y);
            frame.putDouble(2 * Double.BYTES, width);
            frame.putDouble(3 * Double.BYTES, height);
            JNI.callPPPPV(invocation, sel_getUid("setArgument:atIndex:"), memAddress(frame), 2, objc_msgSend);

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

    @Override
    public boolean swapBuffers() {
        glFlush();
        return true;
    }

    @Override
    public boolean deleteContext(long context) {
        long view = this.view;
        long surfaceLayer = this.surfaceLayer;
        this.view = 0L;
        this.interLayer = 0L;
        this.surfaceLayer = 0L;

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
                int[] layerBounds = getLayerBounds(canvas, dsi.bounds().x(), dsi.bounds().y(), width, height);
                updateLayerBounds(layerBounds);
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

    private static int[] getLayerBounds(Canvas canvas, int x, int y, int width, int height) {
        // JAWT reports incorrect coordinates for canvases nested in containers such as JSplitPane.
        // These component reads may occur off the EDT. A concurrent layout can yield one stale frame, but the bounds
        // are sampled again on the next context activation and converge without blocking AppKit or AWT.
        JRootPane rootPane = SwingUtilities.getRootPane(canvas);
        if (rootPane != null) {
            Point point = SwingUtilities.convertPoint(canvas, new Point(), rootPane);
            x = point.x;
            y = rootPane.getHeight() - point.y - height;
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
