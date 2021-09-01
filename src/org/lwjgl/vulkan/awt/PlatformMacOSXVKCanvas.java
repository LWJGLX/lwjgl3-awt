package org.lwjgl.vulkan.awt;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTRectangle;
import org.lwjgl.system.macosx.ObjCRuntime;
import org.lwjgl.vulkan.VkMetalSurfaceCreateInfoEXT;
import org.lwjgl.vulkan.VkPhysicalDevice;

import javax.swing.*;
import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.EXTMetalSurface.VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT;
import static org.lwjgl.vulkan.EXTMetalSurface.vkCreateMetalSurfaceEXT;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * MacOS-specific implementation of {@link PlatformVKCanvas}.
 *
 * @author Fox
 * @author SWinxy
 */
public class PlatformMacOSXVKCanvas implements PlatformVKCanvas {

    // 3.2.3 does not include the newest VkResult code
    private static final int VK_ERROR_UNKNOWN = -13;

    // Pointer to a method that sends a message to an instance of a class
    // Apple spec: macOS 10.0 (OSX 10; 2001) or higher
    private static final long objc_msgSend;

    // Pointer to the CATransaction class definition
    // Apple spec: macOS 10.5 (OSX Leopard; 2007) or higher
    private static final long CATransaction;

    // Pointer to the flush method
    // Apple spec: macOS 10.5 (OSX Leopard; 2007) or higher
    private static final long flush;

    static {
        Library.loadSystem("org.lwjgl.awt", "lwjgl3awt");
        objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        CATransaction = ObjCRuntime.objc_getClass("CATransaction");
        flush = ObjCRuntime.sel_getUid("flush");
    }

    /**
     * Flushes any extant implicit transaction.
     * <p>
     * From Apple's developer documentation:
     *
     * <blockquote>
     * Delays the commit until any nested explicit transactions have completed.
     * <p>
     * Flush is typically called automatically at the end of the current runloop,
     * regardless of the runloop mode. If your application does not have a runloop,
     * you must call this method explicitly.
     * <p>
     * However, you should attempt to avoid calling flush explicitly.
     * By allowing flush to execute during the runloop your application
     * will achieve better performance, atomic screen updates will be preserved,
     * and transactions and animations that work from transaction to transaction
     * will continue to function.
     * </blockquote>
     */
    public static void caFlush() {
        JNI.invokePPP(CATransaction, flush, objc_msgSend);
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
    private native long createMTKView(long platformInfo, int x, int y, int width, int height);

    public long create(Canvas canvas, VKData data) throws AWTException {
        try (AWT awt = new AWT(canvas)) {
            try (MemoryStack stack = MemoryStack.stackPush()) {

                JAWTDrawingSurfaceInfo drawingSurfaceInfo = awt.getDrawingSurfaceInfo();

                // if the canvas is inside e.g. a JSplitPane, the dsi coordinates are wrong and need to be corrected
                JAWTRectangle bounds = drawingSurfaceInfo.bounds();
                int x = bounds.x();
                int y = bounds.y();

                JRootPane rootPane = SwingUtilities.getRootPane(canvas);
                if (rootPane != null) {
                    Point point = SwingUtilities.convertPoint(canvas, new Point(), rootPane);
                    x = point.x;
                    y = point.y;
                }

                // Get pointer to CAMetalLayer object representing the renderable surface
                long metalLayer = createMTKView(drawingSurfaceInfo.platformInfo(), x, y, bounds.width(), bounds.height());

                caFlush();

                VkMetalSurfaceCreateInfoEXT pCreateInfo = VkMetalSurfaceCreateInfoEXT
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT)
                        .pLayer(PointerBuffer.create(metalLayer, 1));

                LongBuffer pSurface = stack.mallocLong(1);
                int result = vkCreateMetalSurfaceEXT(data.instance, pCreateInfo, null, pSurface);

                // Possible VkResult codes returned
                if (result == VK_SUCCESS) {
                    return pSurface.get(0);
                }
                if (result == VK_ERROR_OUT_OF_HOST_MEMORY) {
                    throw new AWTException("Failed to create a Vulkan surface: out of host memory.");
                }
                if (result == VK_ERROR_OUT_OF_DEVICE_MEMORY) {
                    throw new AWTException("Failed to create a Vulkan surface: out of device memory.");
                }
                if (result == VK_ERROR_NATIVE_WINDOW_IN_USE_KHR) {
                    throw new AWTException("Failed to create a Vulkan surface: the requested window is already in use.");
                }

                // Error unknown to the implementation
                if (result == VK_ERROR_UNKNOWN) {
                    throw new AWTException("An unknown error occurred. This may be because of an invalid input, " +
                            "or because the Vulkan implementation has a bug.");
                }

                // Unknown error not included in this list
                throw new AWTException("Calling vkCreateWin32SurfaceKHR failed with unknown Vulkan error: " + result);
            }
        }
    }

    // On macOS, all physical devices and queue families must be capable of presentation with any layer. As a result there is no macOS-specific query for these capabilities.
    public boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily) {
        return true;
    }

}
