package org.lwjgl.vulkan.awt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;
import org.lwjgl.vulkan.KHRXlibSurface;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkXlibSurfaceCreateInfoKHR;

import java.awt.*;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRXlibSurface.vkCreateXlibSurfaceKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * X11-specific implementation of {@link PlatformVKCanvas}.
 *
 * @author Guenther
 * @author SWinxy
 */
public class PlatformX11VKCanvas implements PlatformVKCanvas {

    @Override
    public long create(Canvas canvas, VKData data) throws AWTException {
        try (AWT awt = new AWT(canvas)) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                JAWTX11DrawingSurfaceInfo dsiX11 = JAWTX11DrawingSurfaceInfo.create(awt.getPlatformInfo());

                VkXlibSurfaceCreateInfoKHR pCreateInfo = VkXlibSurfaceCreateInfoKHR
                        .calloc(stack)
                        .dpy(dsiX11.display())
                        .window(dsiX11.drawable());

                LongBuffer pSurface = stack.mallocLong(1);
                int result = vkCreateXlibSurfaceKHR(data.instance, pCreateInfo, null, pSurface);

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

    @Override
    public boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily) {
        return KHRXlibSurface.vkGetPhysicalDeviceXlibPresentationSupportKHR(physicalDevice, queueFamily, 0, 0);
    }
}
