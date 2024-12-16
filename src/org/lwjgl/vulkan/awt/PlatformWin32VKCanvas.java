package org.lwjgl.vulkan.awt;

import org.lwjgl.awt.AWT;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo;
import org.lwjgl.system.windows.WinBase;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;

import java.awt.AWTException;
import java.awt.Canvas;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRWin32Surface.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Window-specific implementation of {@link PlatformVKCanvas}.
 *
 * @author Kai Burjack
 * @author SWinxy
 */
public class PlatformWin32VKCanvas implements PlatformVKCanvas {

    public static final String EXTENSION_NAME = VK_KHR_WIN32_SURFACE_EXTENSION_NAME;

    /**
     * @deprecated Please migrate to the {@link AWTVK} API.
     */
    @Deprecated
    public PlatformWin32VKCanvas() {

    }

    /**
     * @deprecated use {@link AWTVK#create(Canvas, VkInstance)}
     */
    @Deprecated
    @Override
    public long create(Canvas canvas, VKData data) throws AWTException {
        return create(canvas, data.instance);
    }

    static long create(Canvas canvas, VkInstance instance) throws AWTException {
        try (AWT awt = new AWT(canvas)) {
            try (MemoryStack stack = MemoryStack.stackPush()) {

                // Get ptr to win32 struct
                JAWTWin32DrawingSurfaceInfo dsiWin = JAWTWin32DrawingSurfaceInfo.create(awt.getPlatformInfo());

                // Gets a handle to the file used to create the calling process (.exe file)
                long handle = WinBase.nGetModuleHandle(MemoryUtil.NULL, MemoryUtil.NULL);

                VkWin32SurfaceCreateInfoKHR sci = VkWin32SurfaceCreateInfoKHR
                        .calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR)
                        .hinstance(handle)
                        .hwnd(dsiWin.hwnd());

                LongBuffer pSurface = stack.mallocLong(1);
                int result = vkCreateWin32SurfaceKHR(instance, sci, null, pSurface);

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
                        throw new AWTException("Calling vkCreateWin32SurfaceKHR failed with unknown Vulkan error: " + result);
                }
            }
        }
    }

    /**
     * @deprecated use {@link AWTVK#checkSupport(VkPhysicalDevice, int)}
     */
    @Override
    @Deprecated
    public boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamilyIndex) {
        return checkSupport(physicalDevice, queueFamilyIndex);
    }

    static boolean checkSupport(VkPhysicalDevice physicalDevice, int queueFamilyIndex) {
        return vkGetPhysicalDeviceWin32PresentationSupportKHR(physicalDevice, queueFamilyIndex);
    }
}
