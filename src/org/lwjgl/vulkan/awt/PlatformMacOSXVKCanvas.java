package org.lwjgl.vulkan.awt;

import org.lwjgl.PointerBuffer;
import org.lwjgl.awt.AWT;
import org.lwjgl.awt.MacOSX;
import org.lwjgl.system.*;
import org.lwjgl.system.jawt.JAWTRectangle;
import org.lwjgl.system.macosx.MacOSXLibrary;
import org.lwjgl.system.macosx.ObjCRuntime;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkMetalSurfaceCreateInfoEXT;
import org.lwjgl.vulkan.VkPhysicalDevice;

import javax.swing.*;
import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.JNI.invokeP;
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

    static {
        Library.loadSystem("org.lwjgl.awt", "lwjgl3awt");
    }

    /**
     * Creates the native Metal view.
     *
     * @param platformInfo pointer to the jawt platform information struct
     * @param x            x position of the window
     * @param y            y position of the window
     * @param width        window width
     * @param height       window height
     * @return pointer to a native window handle
     */

    private long createMTKView(long platformInfo, int x, int y, int width, int height) {
        SharedLibrary mtk = MacOSXLibrary.create("/System/Library/Frameworks/MetalKit.framework");
        SharedLibrary lib = MacOSXLibrary.create("/System/Library/Frameworks/Metal.framework");
        SharedLibrary cg = MacOSXLibrary.create("/System/Library/Frameworks/CoreGraphics.framework");
        long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        System.out.println("metalkit is " + mtk);
        mtk.getFunctionAddress("MTKView");

        // id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        long address = lib.getFunctionAddress("MTLCreateSystemDefaultDevice");
        long device = invokeP(address);
        System.out.println("Device address: " + Long.toHexString(device));

        // get offset in window from JAWTSurfaceLayers
        long H = JNI.invokePPPPP(platformInfo,
                ObjCRuntime.sel_getUid("windowLayer"),
                ObjCRuntime.sel_getUid("frame"),
                ObjCRuntime.sel_getUid("size"),
                objc_msgSend);
        // height is the 4th member of the 4*64bit struct
        double h = MemoryUtil.memGetDouble(H+3*8);
        System.out.println("Height is " + h);

        // CGRect creation, layout is x/y/width/height, all as doubles on 64bit architectures
        ByteBuffer frame = MemoryUtil.memCalloc(4*8);
        frame.putDouble(x);
        frame.putDouble(h-height-y);
        frame.putDouble(width);
        frame.putDouble(height);
        frame.flip();

        // MTKView *view = [[MTKView alloc] initWithFrame:frame device:device];
        // get MTKView class and allocate instance
        long MTKView = ObjCRuntime.objc_getClass("MTKView");
        System.out.println("MTKView is " + Long.toHexString(MTKView));
        long mtkView = JNI.invokePPP(MTKView,
                ObjCRuntime.sel_getUid("alloc"),
                objc_msgSend);
        System.out.println("MTKView instance is " + Long.toHexString(mtkView) + ", using buffer " + Long.toHexString(MemoryUtil.memAddress(frame)));

        // init MTKView with frame and device
        long view = JNI.invokePPPPP(mtkView,
                ObjCRuntime.sel_getUid("initWithFrame:device:"),
                MemoryUtil.memAddress(frame),
                device,
                objc_msgSend);
        System.out.println("view is "+ Long.toHexString(view) + " with " + x + "/" + (h-height-y) + "/" + width + "/" + height);

//      FFICIF cif = new FFICIF();
//      PointerBuffer types = MemoryUtil.memAllocPointer(2);
//      types.put(0, ffi_type_pointer)
//      ffi_prep_cif(cif, FFI_DEFAULT_ABI, ret, types);
//      ffi_call(cif, fn, rc, types);

        // get layer from MTKView instance
        long mtkViewLayer = JNI.invokePPP(mtkView,
                ObjCRuntime.sel_getUid("layer"),
                objc_msgSend);
        System.out.println("Layer is " + Long.toHexString(mtkViewLayer));


        // set layer on JAWTSurfaceLayers object
        JNI.invokePPPV(platformInfo,
                ObjCRuntime.sel_getUid("setLayer:"),
                mtkViewLayer,
                objc_msgSend);

//         surfaceLayers.layer = view.layer;
        // return view.layer
        return mtkViewLayer;
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
                // Using constructor because I don't know if it's backwards-compatible to be static
                long metalLayer = new PlatformMacOSXVKCanvas().createMTKView(awt.getPlatformInfo(), x, y, bounds.width(), bounds.height());
                // MacOSX.createMTKView(canvas, drawingSurfaceInfo.platformInfo());

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
}
