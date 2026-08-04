package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.ARBRobustness;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.APIUtil.APIVersion;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.macosx.ObjCRuntime;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static java.nio.ByteOrder.nativeOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.libffi.LibFFI.FFI_DEFAULT_ABI;
import static org.lwjgl.system.libffi.LibFFI.FFI_OK;
import static org.lwjgl.system.libffi.LibFFI.FFI_TYPE_STRUCT;
import static org.lwjgl.system.libffi.LibFFI.ffi_call;
import static org.lwjgl.system.libffi.LibFFI.ffi_prep_cif;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_double;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_void;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_getUid;
import static org.lwjgl.opengl.CGL.CGLDisable;
import static org.lwjgl.opengl.CGL.CGLGetParameter;
import static org.lwjgl.opengl.CGL.CGLIsEnabled;
import static org.lwjgl.opengl.CGL.kCGLCESurfaceBackingSize;
import static org.lwjgl.opengl.CGL.kCGLCPSwapInterval;
import static org.lwjgl.opengl.CGL.kCGLCPSurfaceBackingSize;
import static org.lwjgl.opengl.CGL.kCGLNoError;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FRONT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDrawBuffer;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_CONTEXT_FLAGS;
import static org.lwjgl.opengl.GL30.GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT;
import static org.lwjgl.opengl.GL32.GL_CONTEXT_CORE_PROFILE_BIT;
import static org.lwjgl.opengl.GL32.GL_CONTEXT_PROFILE_MASK;
import static org.lwjgl.opengl.GL43.GL_CONTEXT_FLAG_DEBUG_BIT;

@EnabledOnOs(OS.MAC)
class MacOSXGLCanvasLifecycleTest {

    @Test
    void computesNestedLayerBoundsWithoutScreenCoordinateLookup() {
        JRootPane rootPane = new JRootPane();
        rootPane.setSize(500, 400);
        JPanel outer = new JPanel(null);
        outer.setBounds(17, 29, 400, 300);
        JPanel inner = new JPanel(null);
        inner.setBounds(31, 43, 300, 200);
        CanvasWithoutScreenCoordinates canvas = new CanvasWithoutScreenCoordinates();
        canvas.setBounds(47, 53, 160, 120);
        inner.add(canvas);
        outer.add(inner);
        rootPane.getContentPane().setLayout(null);
        rootPane.getContentPane().add(outer);

        int[] bounds = PlatformMacOSXGLCanvas.getLayerBounds(canvas, 0, 0, 160, 120);

        assertEquals(95, bounds[0]);
        assertEquals(155, bounds[1]);
        assertEquals(160, bounds[2]);
        assertEquals(120, bounds[3]);
    }

    @Test
    void rejectsContextSharingInsteadOfSilentlyIgnoringIt() {
        GLData data = new GLData();
        data.shareContext = new TestCanvas();

        AWTException failure = assertThrows(AWTException.class,
                () -> MacOSXGLDataUtil.validateAttributes(data));

        assertTrue(failure.getMessage().contains("sharing"));
    }

    @Test
    void configuresInitialSurfaceBackingSizeBeforeInitGL() throws Exception {
        FrameState state = showSingleCanvas();
        try {
            renderCanvases(state);
            TestCanvas canvas = state.canvases[0];
            assertTrue(canvas.surfaceBackingSizeEnabled);
            assertEquals(canvas.getFramebufferWidth(), canvas.surfaceBackingWidth);
            assertEquals(canvas.getFramebufferHeight(), canvas.surfaceBackingHeight);
        } finally {
            SwingUtilities.invokeAndWait(state.frame::dispose);
        }
    }

    @Test
    void reassertsSurfaceBackingSizeOnEveryContextActivation() throws Exception {
        FrameState state = showSingleCanvas();
        try {
            renderCanvases(state);
            TestCanvas canvas = state.canvases[0];
            canvas.runInContext(() -> assertEquals(
                    kCGLNoError,
                    CGLDisable(canvas.context, kCGLCESurfaceBackingSize)));

            canvas.runInContext(() -> {
                int[] enabled = new int[1];
                assertEquals(kCGLNoError, CGLIsEnabled(canvas.context, kCGLCESurfaceBackingSize, enabled));
                assertEquals(1, enabled[0]);

                int[] size = new int[2];
                assertEquals(kCGLNoError, CGLGetParameter(canvas.context, kCGLCPSurfaceBackingSize, size));
                assertEquals(canvas.getFramebufferWidth(), size[0]);
                assertEquals(canvas.getFramebufferHeight(), size[1]);
            });
        } finally {
            SwingUtilities.invokeAndWait(state.frame::dispose);
        }
    }

    @Test
    void clearsIntermediateLayerReferenceWhenCanvasIsDisposed() throws Exception {
        FrameState state = showSingleCanvas();
        boolean disposed = false;
        try {
            renderCanvases(state);
            TestCanvas canvas = state.canvases[0];
            assertNotEquals(0L, getIntermediateLayer(canvas));

            SwingUtilities.invokeAndWait(state.frame::dispose);
            disposed = true;
            assertEquals(0L, getIntermediateLayer(canvas));
        } finally {
            if (!disposed) {
                SwingUtilities.invokeAndWait(state.frame::dispose);
            }
        }
    }

    @Test
    void appliesSwapIntervalAndPopulatesEffectiveData() throws Exception {
        GLData data = new GLData();
        data.majorVersion = 3;
        data.minorVersion = 2;
        data.profile = GLData.Profile.CORE;
        data.doubleBuffer = true;
        data.swapInterval = 0;
        data.stencilSize = 8;

        FrameState state = showConfiguredCanvas(data);
        try {
            renderCanvases(state);
            TestCanvas canvas = state.canvases[0];
            assertEquals(0, canvas.configuredSwapInterval);
            assertEquals(Integer.valueOf(0), canvas.effective.swapInterval);
            assertEquals(GLData.API.GL, canvas.effective.api);
            assertEquals(GLData.Profile.CORE, canvas.effective.profile);
            assertEquals(canvas.actualMajorVersion, canvas.effective.majorVersion);
            assertEquals(canvas.actualMinorVersion, canvas.effective.minorVersion);
            assertEquals((canvas.actualProfileMask & GL_CONTEXT_CORE_PROFILE_BIT) != 0,
                    canvas.effective.profile == GLData.Profile.CORE);
            assertEquals((canvas.actualContextFlags & GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) != 0,
                    canvas.effective.forwardCompatible);
            assertEquals((canvas.actualContextFlags & GL_CONTEXT_FLAG_DEBUG_BIT) != 0,
                    canvas.effective.debug);
            assertEquals((canvas.actualContextFlags & ARBRobustness.GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT_ARB) != 0,
                    canvas.effective.robustness);
            assertEquals(8, canvas.effective.redSize);
            assertEquals(8, canvas.effective.greenSize);
            assertEquals(8, canvas.effective.blueSize);
            assertEquals(8, canvas.effective.alphaSize);
            assertTrue(canvas.effective.stencilSize >= 8);
            assertTrue(canvas.effective.doubleBuffer);
        } finally {
            SwingUtilities.invokeAndWait(state.frame::dispose);
        }
    }

    @Test
    void honorsSingleBufferedPixelFormat() throws Exception {
        GLData data = new GLData();
        data.doubleBuffer = false;

        FrameState state = showConfiguredCanvas(data);
        try {
            renderCanvases(state);
            assertFalse(state.canvases[0].effective.doubleBuffer);
        } finally {
            SwingUtilities.invokeAndWait(state.frame::dispose);
        }
    }

    @Test
    void presentsDoubleBufferedFrame() throws Exception {
        GLData data = new GLData();
        data.doubleBuffer = true;
        data.swapInterval = 0;

        FrameState state = showPresentingCanvas(data);
        try {
            assertPresentedFrameEventually(state);
            TestCanvas canvas = state.canvases[0];
            assertTrue(canvas.effective.doubleBuffer);
        } finally {
            SwingUtilities.invokeAndWait(state.frame::dispose);
        }
    }

    @Test
    void updatesNativeLayerFrameWhenCanvasMovesInsideRootPane() throws Exception {
        FrameState state = showMovableCanvas();
        try {
            renderCanvases(state);

            double[] initialFrame = expectedLayerFrame(state.canvases[0]);
            assertLayerFrameEventually(state.canvases[0], initialFrame);

            SwingUtilities.invokeAndWait(() -> {
                TestCanvas canvas = state.canvases[0];
                canvas.setLocation(canvas.getX() + 80, canvas.getY());
            });

            double[] movedFrame = expectedLayerFrame(state.canvases[0]);
            assertNotEquals(initialFrame[0], movedFrame[0], "The test must move the canvas in its root pane");
            long layer = getIntermediateLayer(state.canvases[0]);
            SwingUtilities.invokeAndWait(() -> writeLayerFrame(layer, initialFrame));
            assertLayerFrameEventually(state.canvases[0], initialFrame);

            renderCanvases(state);
            assertLayerFrameEventually(state.canvases[0], movedFrame);
        } finally {
            SwingUtilities.invokeAndWait(state.frame::dispose);
        }
    }

    @Test
    void keepsOpenGLLayerAtCanvasSize() throws Exception {
        FrameState state = showMovableCanvas();
        try {
            renderCanvases(state);
            TestCanvas canvas = state.canvases[0];
            assertEquals(0L, readAutoresizingMask(getOpenGLLayer(canvas)));
            assertOpenGLLayerFrameEventually(canvas, new double[]{0.0, 0.0, 160.0, 120.0});

            SwingUtilities.invokeAndWait(() -> canvas.setSize(240, 160));
            renderCanvases(state);

            assertEquals(0L, readAutoresizingMask(getOpenGLLayer(canvas)));
            assertOpenGLLayerFrameEventually(canvas, new double[]{0.0, 0.0, 240.0, 160.0});
        } finally {
            SwingUtilities.invokeAndWait(state.frame::dispose);
        }
    }

    @Test
    void createsMultipleCanvasesAfterDisposingPreviousWindow() throws Exception {
        for (int i = 0; i < 5; i++) {
            runLifecycle();
        }
    }

    private static void runLifecycle() throws Exception {
        FrameState first = showSingleCanvas();
        try {
            renderCanvases(first);
        } finally {
            SwingUtilities.invokeAndWait(first.frame::dispose);
        }

        FrameState second = showSplitCanvases();
        try {
            renderCanvases(second);
        } finally {
            SwingUtilities.invokeAndWait(second.frame::dispose);
        }
    }

    private static FrameState showSingleCanvas() throws Exception {
        FrameState[] result = new FrameState[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("macOS OpenGL canvas lifecycle");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            TestCanvas canvas = createCanvas();
            frame.add(canvas);
            frame.pack();
            frame.setVisible(true);
            result[0] = new FrameState(frame, new TestCanvas[]{canvas});
        });
        return result[0];
    }

    private static FrameState showConfiguredCanvas(GLData data) throws Exception {
        FrameState[] result = new FrameState[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("macOS configured OpenGL canvas");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            TestCanvas canvas = new TestCanvas(data);
            canvas.setPreferredSize(new Dimension(160, 120));
            frame.add(canvas);
            frame.pack();
            frame.setVisible(true);
            result[0] = new FrameState(frame, new TestCanvas[]{canvas});
        });
        return result[0];
    }

    private static FrameState showPresentingCanvas(GLData data) throws Exception {
        FrameState[] result = new FrameState[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("macOS double-buffer presentation");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            TestCanvas canvas = new TestCanvas(data, true);
            canvas.setPreferredSize(new Dimension(160, 120));
            frame.add(canvas);
            frame.pack();
            frame.setVisible(true);
            result[0] = new FrameState(frame, new TestCanvas[]{canvas});
        });
        return result[0];
    }

    private static FrameState showSplitCanvases() throws Exception {
        FrameState[] result = new FrameState[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("macOS split OpenGL canvas lifecycle");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            TestCanvas[] canvases = new TestCanvas[]{
                    createCanvas(), createCanvas(), createCanvas(), createCanvas()
            };
            JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, canvases[0], canvases[1]);
            JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, canvases[2], canvases[3]);
            frame.add(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right));
            frame.pack();
            frame.setVisible(true);
            result[0] = new FrameState(frame, canvases);
        });
        return result[0];
    }

    private static FrameState showMovableCanvas() throws Exception {
        FrameState[] result = new FrameState[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("macOS dynamic OpenGL layer bounds");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            TestCanvas canvas = createCanvas();
            JPanel content = new JPanel(null);
            canvas.setBounds(40, 40, 160, 120);
            content.add(canvas);
            frame.setContentPane(content);
            frame.setSize(480, 280);
            frame.setVisible(true);
            result[0] = new FrameState(frame, new TestCanvas[]{canvas});
        });
        return result[0];
    }

    private static TestCanvas createCanvas() {
        TestCanvas canvas = new TestCanvas();
        canvas.setPreferredSize(new Dimension(160, 120));
        return canvas;
    }

    private static void renderCanvases(FrameState state) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (TestCanvas canvas : state.canvases) {
                canvas.render();
            }
        });
    }

    private static void assertColorComponent(int expected, int actual, String component) {
        assertTrue(Math.abs(expected - actual) <= 1,
                "Expected " + component + " component near " + expected + " but was " + actual);
    }

    private static void assertPresentedFrameEventually(FrameState state) throws Exception {
        TestCanvas canvas = state.canvases[0];
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            renderCanvases(state);
            if (colorComponentMatches(64, canvas.frontPixel[0])
                    && colorComponentMatches(128, canvas.frontPixel[1])
                    && colorComponentMatches(191, canvas.frontPixel[2])
                    && colorComponentMatches(255, canvas.frontPixel[3])) {
                return;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);

        assertColorComponent(64, canvas.frontPixel[0], "red");
        assertColorComponent(128, canvas.frontPixel[1], "green");
        assertColorComponent(191, canvas.frontPixel[2], "blue");
        assertColorComponent(255, canvas.frontPixel[3], "alpha");
    }

    private static boolean colorComponentMatches(int expected, int actual) {
        return Math.abs(expected - actual) <= 1;
    }

    private static double[] expectedLayerFrame(TestCanvas canvas) throws Exception {
        double[][] result = new double[1][];
        SwingUtilities.invokeAndWait(() -> {
            JRootPane rootPane = SwingUtilities.getRootPane(canvas);
            Point point = SwingUtilities.convertPoint(canvas, new Point(), rootPane);
            result[0] = new double[]{
                    point.x,
                    rootPane.getHeight() - point.y - canvas.getHeight(),
                    canvas.getWidth(),
                    canvas.getHeight()
            };
        });
        return result[0];
    }

    private static void assertLayerFrameEventually(TestCanvas canvas, double[] expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        long layer = getIntermediateLayer(canvas);
        double[] actual;
        do {
            actual = readLayerFrame(layer);
            if (framesEqual(expected, actual)) {
                return;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        fail("Expected native layer frame " + formatFrame(expected) + " but was " + formatFrame(actual));
    }

    private static void assertOpenGLLayerFrameEventually(TestCanvas canvas, double[] expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        double[] actual;
        do {
            actual = readLayerFrame(getOpenGLLayer(canvas));
            if (framesEqual(expected, actual)) {
                return;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        fail("Expected native OpenGL layer frame " + formatFrame(expected) + " but was " + formatFrame(actual));
    }

    private static long getIntermediateLayer(TestCanvas canvas) throws Exception {
        return getPlatformField(canvas, "interLayer");
    }

    private static long getOpenGLLayer(TestCanvas canvas) throws Exception {
        long view = getPlatformField(canvas, "view");
        long objcMsgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        return JNI.invokePPP(view, sel_getUid("layer"), objcMsgSend);
    }

    private static long getPlatformField(TestCanvas canvas, String name) throws Exception {
        Field field = PlatformMacOSXGLCanvas.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(canvas.platformCanvas);
    }

    private static long readAutoresizingMask(long layer) {
        long objcMsgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        return JNI.invokePPP(layer, sel_getUid("autoresizingMask"), objcMsgSend);
    }

    private static double[] readLayerFrame(long layer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FFIType cgRect = createCGRectType(stack);
            PointerBuffer argumentTypes = stack.pointers(
                    ffi_type_pointer.address(),
                    ffi_type_pointer.address());

            FFICIF cif = FFICIF.malloc(stack);
            int status = ffi_prep_cif(cif, FFI_DEFAULT_ABI, cgRect, argumentTypes);
            if (status != FFI_OK) {
                throw new IllegalStateException("ffi_prep_cif failed: " + status);
            }

            PointerBuffer pointerValues = stack.pointers(layer, sel_getUid("frame"));
            PointerBuffer arguments = stack.pointers(
                    memAddress(pointerValues, 0),
                    memAddress(pointerValues, 1));
            ByteBuffer frame = stack.malloc(4 * Double.BYTES).order(nativeOrder());
            Platform.Architecture architecture = Platform.getArchitecture();
            // Intel macOS returns a CGRect indirectly; arm64 uses the ordinary Objective-C message entry point.
            String messageFunction = architecture == Platform.Architecture.X64
                    || architecture == Platform.Architecture.X86
                    ? "objc_msgSend_stret"
                    : "objc_msgSend";
            ffi_call(cif, ObjCRuntime.getLibrary().getFunctionAddress(messageFunction), frame, arguments);
            return new double[]{
                    frame.getDouble(0),
                    frame.getDouble(Double.BYTES),
                    frame.getDouble(2 * Double.BYTES),
                    frame.getDouble(3 * Double.BYTES)
            };
        }
    }

    private static void writeLayerFrame(long layer, double[] frame) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FFIType cgRect = createCGRectType(stack);
            PointerBuffer argumentTypes = stack.pointers(
                    ffi_type_pointer.address(),
                    ffi_type_pointer.address(),
                    cgRect.address());

            FFICIF cif = FFICIF.malloc(stack);
            int status = ffi_prep_cif(cif, FFI_DEFAULT_ABI, ffi_type_void, argumentTypes);
            if (status != FFI_OK) {
                throw new IllegalStateException("ffi_prep_cif failed: " + status);
            }

            java.nio.DoubleBuffer frameValue = stack.doubles(frame[0], frame[1], frame[2], frame[3]);
            PointerBuffer pointerValues = stack.pointers(layer, sel_getUid("setFrame:"));
            PointerBuffer arguments = stack.pointers(
                    memAddress(pointerValues, 0),
                    memAddress(pointerValues, 1),
                    memAddress(frameValue));
            ffi_call(cif, ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend"), null, arguments);
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

    private static boolean framesEqual(double[] expected, double[] actual) {
        for (int i = 0; i < expected.length; i++) {
            if (Math.abs(expected[i] - actual[i]) > 0.01) {
                return false;
            }
        }
        return true;
    }

    private static String formatFrame(double[] frame) {
        return "[" + frame[0] + ", " + frame[1] + ", " + frame[2] + ", " + frame[3] + "]";
    }

    private static class FrameState {
        final JFrame frame;
        final TestCanvas[] canvases;

        FrameState(JFrame frame, TestCanvas[] canvases) {
            this.frame = frame;
            this.canvases = canvases;
        }
    }

    private static class TestCanvas extends AWTGLCanvas {
        boolean surfaceBackingSizeEnabled;
        int surfaceBackingWidth;
        int surfaceBackingHeight;
        int configuredSwapInterval;
        int actualMajorVersion;
        int actualMinorVersion;
        int actualProfileMask;
        int actualContextFlags;
        final boolean verifyPresentation;
        final int[] frontPixel = new int[4];

        TestCanvas() {
            verifyPresentation = false;
        }

        TestCanvas(GLData data) {
            super(data);
            verifyPresentation = false;
        }

        TestCanvas(GLData data, boolean verifyPresentation) {
            super(data);
            this.verifyPresentation = verifyPresentation;
        }

        @Override
        public void initGL() {
            GL.createCapabilities();
            APIVersion actualVersion = APIUtil.apiParseVersion(glGetString(GL_VERSION));
            actualMajorVersion = actualVersion.major;
            actualMinorVersion = actualVersion.minor;
            if (GLUtil.atLeast32(actualMajorVersion, actualMinorVersion)) {
                actualProfileMask = glGetInteger(GL_CONTEXT_PROFILE_MASK);
            }
            if (GLUtil.atLeast30(actualMajorVersion, actualMinorVersion)) {
                actualContextFlags = glGetInteger(GL_CONTEXT_FLAGS);
            }
            int[] enabled = new int[1];
            assertEquals(kCGLNoError, CGLIsEnabled(context, kCGLCESurfaceBackingSize, enabled));
            surfaceBackingSizeEnabled = enabled[0] != 0;

            int[] size = new int[2];
            assertEquals(kCGLNoError, CGLGetParameter(context, kCGLCPSurfaceBackingSize, size));
            surfaceBackingWidth = size[0];
            surfaceBackingHeight = size[1];

            int[] swapInterval = new int[1];
            assertEquals(kCGLNoError, CGLGetParameter(context, kCGLCPSwapInterval, swapInterval));
            configuredSwapInterval = swapInterval[0];
        }

        @Override
        public void paintGL() {
            if (verifyPresentation) {
                glDrawBuffer(GL_FRONT);
                glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);
                glFinish();

                glDrawBuffer(GL_BACK);
                glClearColor(0.25f, 0.5f, 0.75f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);
                swapBuffers();
                // CGLFlushDrawable schedules the swap; finish before inspecting the new front buffer.
                glFinish();

                glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);

                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadBuffer(GL_FRONT);
                glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                for (int i = 0; i < frontPixel.length; i++) {
                    frontPixel[i] = pixel.get(i) & 0xFF;
                }
                return;
            }
            swapBuffers();
        }
    }

    private static final class CanvasWithoutScreenCoordinates extends Canvas {
        @Override
        public Point getLocationOnScreen() {
            throw new IllegalComponentStateException("Screen-coordinate lookup must not be used");
        }
    }
}
