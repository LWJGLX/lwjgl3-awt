package org.lwjgl.vulkan.awt;

import org.lwjgl.awt.AWT;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkXlibSurfaceCreateInfoKHR;

import java.awt.*;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRXlibSurface.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * X11-specific implementation of {@link PlatformVKCanvas}.
 *
 * @author Guenther
 * @author SWinxy
 */
public class PlatformX11VKCanvas implements PlatformVKCanvas {

    public static final String EXTENSION_NAME = VK_KHR_XLIB_SURFACE_EXTENSION_NAME;

    /**
     * @deprecated Please migrate to the {@link AWTVK} API.
     */
    @Deprecated
    public PlatformX11VKCanvas() {

    }

    /**
     * @deprecated use {@link AWTVK#create(Canvas, VkInstance)}
     */
    @Override
    @Deprecated
    public long create(Canvas canvas, VKData data) throws AWTException {
        return create(canvas, data.instance);
    }

    static long create(Canvas canvas, VkInstance instance) throws AWTException {
        try (AWT awt = new AWT(canvas)) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                JAWTX11DrawingSurfaceInfo dsiX11 = JAWTX11DrawingSurfaceInfo.create(awt.getPlatformInfo());

                VkXlibSurfaceCreateInfoKHR pCreateInfo = VkXlibSurfaceCreateInfoKHR
                        .calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR)
                        .dpy(dsiX11.display())
                        .window(dsiX11.drawable());

                LongBuffer pSurface = stack.mallocLong(1);
                int result = vkCreateXlibSurfaceKHR(instance, pCreateInfo, null, pSurface);

                switch (result) {
                    case VK_SUCCESS:
                        return pSurface.get(0);

                    // Possible VkResult codes returned
                    case VK_ERROR_OUT_OF_HOST_MEMORY:
                        throw new AWTException("Failed to create a Vulkan surface: a host memory allocation has failed.");
                    case VK_ERROR_OUT_OF_DEVICE_MEMORY:
                        throw new AWTException("Failed to create a Vulkan surface: a device memory allocation has failed.");

                    // Error unknown to the implementation
                    case VK_ERROR_UNKNOWN:
                        throw new AWTException("An unknown error has occurred;" +
                                " either the application has provided invalid input, or an implementation failure has occurred.");

                    // Unknown error not included in this list
                    default:
                        throw new AWTException("Calling vkCreateXlibSurfaceKHR failed with unknown Vulkan error: " + result);
                }
            }
        }
    }

    /**
     * @deprecated use {@link AWTVK#checkSupport(VkPhysicalDevice, int)}
     */
    @Override
    @Deprecated
    public boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily) {
        return checkSupport(physicalDevice, queueFamily);
    }

    static boolean checkSupport(VkPhysicalDevice physicalDevice, int queueFamilyIndex) {
        return vkGetPhysicalDeviceXlibPresentationSupportKHR(physicalDevice, queueFamilyIndex, 0, 0);
    }
}
