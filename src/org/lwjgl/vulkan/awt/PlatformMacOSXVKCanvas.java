package org.lwjgl.vulkan.awt;

import org.lwjgl.PointerBuffer;
import org.lwjgl.awt.AWT;
import org.lwjgl.awt.MacOSX;
import org.lwjgl.system.*;
import org.lwjgl.system.jawt.JAWTRectangle;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.MacOSXLibrary;
import org.lwjgl.system.macosx.ObjCRuntime;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkMetalSurfaceCreateInfoEXT;
import org.lwjgl.vulkan.VkPhysicalDevice;

import javax.swing.*;
import java.awt.*;
import java.nio.DoubleBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.EXTMetalSurface.*;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * MacOS-specific implementation of {@link PlatformVKCanvas}.
 *
 * @author Fox
 * @author SWinxy
 */
public class PlatformMacOSXVKCanvas implements PlatformVKCanvas {

    public static final String EXTENSION_NAME = VK_EXT_METAL_SURFACE_EXTENSION_NAME;

    /**
     * @deprecated Please migrate to the {@link AWTVK} API.
     */
    @Deprecated
    public PlatformMacOSXVKCanvas() {

    }

    /**
     * Creates the native Metal view.
     * <p>
     * Because {@link JNI} does not provide a method signature for {@code PPDDDDPP},
     * we have to construct a call interface ourselves via {@link LibFFI}.
     * <p>
	 * This method is equivalent to the following Objective-C code:
     * <pre>
     * id<MTLDevice> device = MTLCreateSystemDefaultDevice();
     * MTKView *view = [[MTKView alloc] initWithFrame:CGRectMake(x, y, width, height) device:device];
	 * CALayer *layer = view.layer;
	 * [layer setAutoresizingMask:(kCALayerWidthSizable | kCAHeightSizable)];
	 * CALayer *interLayer = [CALayer layer];
	 * [interLayer setFrame:CGRectMake(x, y, width, height)];
	 * [interLayer addSublayer:layer];
	 * [platformInfo performSelectorOnMainThread:@selector(setLayer:) withObject:interLayer waitUntilDone:YES];
     * return layer;
     * </pre>
     *
     * @param platformInfo pointer to the jawt platform information struct
     * @param x            x position of the window
     * @param y            y position of the window
     * @param width        window width
     * @param height       window height
     * @return pointer to a native window handle
     */
    private static long createMTKView(long platformInfo, int x, int y, int width, int height) throws AWTException {
		// Load the MetalKit bundle. getFunctionAddress forces it:
		// https://developer.apple.com/documentation/corefoundation/1537143-cfbundlegetfunctionpointerfornam?language=objc
		MacOSXLibrary
				.create("/System/Library/Frameworks/MetalKit.framework")
				.getFunctionAddress("");

		SharedLibrary metal = MacOSXLibrary.create("/System/Library/Frameworks/Metal.framework");
		long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

		// id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		long device = JNI.invokeP(metal.getFunctionAddress("MTLCreateSystemDefaultDevice"));


		// MTKView *view = [MTKView alloc];
		long alloc = JNI.invokePPP(
				ObjCRuntime.objc_getClass("MTKView"),
				ObjCRuntime.sel_getUid("alloc"),
				objc_msgSend);

		// [view initWithFrame:CGRectMake(x, y, width, height) device:device];
		long mtkView = invokePPDDDDPP(alloc,
				ObjCRuntime.sel_getUid("initWithFrame:device:"),
				x, y, width, height,
				device,
				objc_msgSend);
		if (mtkView == ObjCRuntime.nil) {
			throw new AWTException("Failed to invoke initWithFrame:device:");
		}

		// CALayer *layer = view.layer;
		long layer = JNI.invokePPP(mtkView,
				ObjCRuntime.sel_getUid("layer"),
				objc_msgSend);

		// [layer setAutoresizingMask:(kCALayerWidthSizable | kCAHeightSizable)];
		JNI.invokePPPV(layer,
				ObjCRuntime.sel_getUid("setAutoresizingMask:"),
				18,
				objc_msgSend);

		// create intermediate layer and set its frame
		// CALayer *interLayer = [CALayer layer];
		long interLayer = JNI.invokePPP(
				ObjCRuntime.objc_getClass("CALayer"),
				ObjCRuntime.sel_getUid("layer"),
				objc_msgSend);

		// [interLayer setFrame:CGRectMake(x, y, width, height)];
		invokePPDDDDV(interLayer,
				ObjCRuntime.sel_getUid("setFrame:"),
				x, y, width, height,
				objc_msgSend);

		// add NSOpenGLView's layer to the intermediate layer
		// [interLayer addSublayer:layer];
		JNI.invokePPPV(interLayer,
				ObjCRuntime.sel_getUid("addSublayer:"),
				layer,
				objc_msgSend);

		// set intermediate layer as JAWTSurfaceLayer's layer
		// [platformInfo performSelectorOnMainThread:@selector(setLayer:) withObject:interLayer waitUntilDone:YES];
		JNI.invokePPPPV(platformInfo,
				ObjCRuntime.sel_getUid("performSelectorOnMainThread:withObject:waitUntilDone:"),
				ObjCRuntime.sel_getUid("setLayer:"),
				interLayer,
				ObjCRuntime.YES,
				objc_msgSend);

		// return layer;
		return layer;
	}

    /**
     * @deprecated use {@link AWTVK#create(Canvas, VkInstance)}
     */
    @Deprecated
    public long create(Canvas canvas, VKData data) throws AWTException {
        return create(canvas, data.instance);
    }

    static long create(Canvas canvas, VkInstance instance) throws AWTException {
        try (AWT awt = new AWT(canvas)) {
            try (MemoryStack stack = MemoryStack.stackPush()) {

                // if the canvas is inside e.g. a JSplitPane, the dsi coordinates are wrong and need to be corrected
                JAWTRectangle bounds = awt.getDrawingSurfaceInfo().bounds();
                int x = bounds.x();
                int y = bounds.y();

                JRootPane rootPane = SwingUtilities.getRootPane(canvas);
                if (rootPane != null) {
                    Point point = SwingUtilities.convertPoint(canvas, new Point(), rootPane);
                    x = point.x;
                    y = point.y;
                }

                // Get pointer to CAMetalLayer object representing the renderable surface
                long metalLayer = createMTKView(awt.getPlatformInfo(), x, y, bounds.width(), bounds.height());

                MacOSX.caFlush();

                VkMetalSurfaceCreateInfoEXT pCreateInfo = VkMetalSurfaceCreateInfoEXT
                        .calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT)
                        .pLayer(PointerBuffer.create(metalLayer, 1));

                LongBuffer pSurface = stack.mallocLong(1);
                int result = vkCreateMetalSurfaceEXT(instance, pCreateInfo, null, pSurface);

                switch (result) {
                    case VK_SUCCESS:
                        return pSurface.get(0);

                    // Possible VkResult codes returned
                    case VK_ERROR_OUT_OF_HOST_MEMORY:
                        throw new AWTException("Failed to create a Vulkan surface: a host memory allocation has failed.");
                    case VK_ERROR_OUT_OF_DEVICE_MEMORY:
                        throw new AWTException("Failed to create a Vulkan surface: a device memory allocation has failed.");

                    // vkCreateMetalSurfaceEXT return code
                    case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR:
                        throw new AWTException("Failed to create a Vulkan surface:" +
                                " the requested window is already in use by Vulkan or another API in a manner which prevents it from being used again.");

                    // Error unknown to the implementation
                    case VK_ERROR_UNKNOWN:
                        throw new AWTException("An unknown error has occurred;" +
                                " either the application has provided invalid input, or an implementation failure has occurred.");

                    // Unknown error not included in this list
                    default:
                        throw new AWTException("Calling vkCreateMetalSurfaceEXT failed with unknown Vulkan error: " + result);
                }
            }
        }
    }

    /**
     * @deprecated use {@link AWTVK#checkSupport(VkPhysicalDevice, int)}
     */
    @Deprecated
    public boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily) {
        return true;
    }

    // On macOS, all physical devices and queue families must be capable of presentation with any layer.
    // As a result there is no macOS-specific query for these capabilities.
    static boolean checkSupport(VkPhysicalDevice physicalDevice, int queueFamilyIndex) {
        return true;
    }

	private static long invokePPDDDDPP(long mtkView, long sel, double x, double y, double width, double height, long device, long objc_msgSend) throws AWTException {
		try (MemoryStack stack = MemoryStack.stackPush()) {

			PointerBuffer argumentTypes = stack.pointers(
					LibFFI.ffi_type_pointer, // MTKView*
					LibFFI.ffi_type_pointer, // @selector(initWithFrame:device:)
					LibFFI.ffi_type_double, // CGRect
					LibFFI.ffi_type_double, // CGRect
					LibFFI.ffi_type_double, // CGRect
					LibFFI.ffi_type_double, // CGRect
					LibFFI.ffi_type_pointer // device*
			);

			// Prepare the call interface
			FFICIF cif = FFICIF.malloc(stack);
			int status = LibFFI.ffi_prep_cif(cif, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_pointer, argumentTypes);
			if (status != LibFFI.FFI_OK) {
				throw new AWTException("ffi_prep_cif failed: " + status);
			}

			// Storage for the actual arguments
			DoubleBuffer struct = stack.doubles(x, y, width, height);
			PointerBuffer pointers = stack.pointers(mtkView, sel, device);

			// Point the arguments to the actual values
			PointerBuffer arguments = stack.pointers(
					MemoryUtil.memAddress(pointers, 0),
					MemoryUtil.memAddress(pointers, 1),
					MemoryUtil.memAddress(struct, 0),
					MemoryUtil.memAddress(struct, 1),
					MemoryUtil.memAddress(struct, 2),
					MemoryUtil.memAddress(struct, 3),
					MemoryUtil.memAddress(pointers, 2)
			);

			// [view initWithFrame:rect device:device];
			// Returns itself, we just need to know if it's NULL
			LongBuffer pMTKView = stack.mallocLong(1);
			LibFFI.ffi_call(cif, objc_msgSend, MemoryUtil.memByteBuffer(pMTKView), arguments);

			return pMTKView.get(0);
		}
	}

	private static void invokePPDDDDV(long interLayer, long sel, double x, double y, double width, double height, long objc_msgSend) throws AWTException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// Prepare the call interface
			FFICIF cif = FFICIF.malloc(stack);

			PointerBuffer argumentTypes = stack.pointers(
					LibFFI.ffi_type_pointer, // interLayer*
					LibFFI.ffi_type_pointer, // @selector(setFrame:)
					LibFFI.ffi_type_double, // CGRect
					LibFFI.ffi_type_double, // CGRect
					LibFFI.ffi_type_double, // CGRect
					LibFFI.ffi_type_double // CGRect
			);

			int status = LibFFI.ffi_prep_cif(cif, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_void, argumentTypes);
			if (status != LibFFI.FFI_OK) {
				throw new AWTException("ffi_prep_cif failed: " + status);
			}

			DoubleBuffer cgRect = stack.doubles(x, y, width, height);
			PointerBuffer pointers = stack.pointers(interLayer, sel);

			// An array of pointers that point to the actual argument values.
			PointerBuffer arguments = stack.mallocPointer(6)
					.put(0, MemoryUtil.memAddress(pointers, 0))
					.put(1, MemoryUtil.memAddress(pointers, 1))
					.put(2, MemoryUtil.memAddress(cgRect, 0))
					.put(3, MemoryUtil.memAddress(cgRect, 1))
					.put(4, MemoryUtil.memAddress(cgRect, 2))
					.put(5, MemoryUtil.memAddress(cgRect, 3));

			// Invoke the function and validate
			LibFFI.ffi_call(cif, objc_msgSend, null, arguments);
		}
	}
}
