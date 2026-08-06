package org.lwjgl.vulkan.awt;

import org.lwjgl.awt.AWT;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;
import org.lwjgl.system.linux.X11;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkXlibSurfaceCreateInfoKHR;

import java.awt.*;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memUTF8;
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

    private static final Object DISPLAY_LOCK = new Object();
    private static final Map<String, Long> VULKAN_DISPLAYS = new HashMap<>();
    private static final Map<Long, Long> VULKAN_DISPLAY_VISUALS = new HashMap<>();
    private static final long X_DISPLAY_STRING = getRequiredX11Function("XDisplayString");
    private static final long X_DEFAULT_VISUAL = getRequiredX11Function("XDefaultVisual");
    private static final long X_VISUAL_ID_FROM_VISUAL = getRequiredX11Function("XVisualIDFromVisual");

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
                long display = getVulkanDisplay(dsiX11.display());

                VkXlibSurfaceCreateInfoKHR pCreateInfo = VkXlibSurfaceCreateInfoKHR
                        .calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR)
                        .dpy(display)
                        .window(dsiX11.drawable());

                LongBuffer pSurface = stack.mallocLong(1);
                int result;
                synchronized (DISPLAY_LOCK) {
                    result = vkCreateXlibSurfaceKHR(instance, pCreateInfo, null, pSurface);
                }

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
        try {
            synchronized (DISPLAY_LOCK) {
                long display = getDefaultVulkanDisplay();
                return vkGetPhysicalDeviceXlibPresentationSupportKHR(
                        physicalDevice, queueFamilyIndex, display, getDefaultVisualID(display));
            }
        } catch (AWTException e) {
            return false;
        }
    }

    static long getVulkanDisplay(long awtDisplay) throws AWTException {
        if (awtDisplay == NULL) {
            throw new AWTException("JAWT returned no X11 display");
        }
        return getVulkanDisplay(getDisplayName(awtDisplay));
    }

    static long getDefaultVisualID(long display) throws AWTException {
        synchronized (DISPLAY_LOCK) {
            Long visualID = VULKAN_DISPLAY_VISUALS.get(display);
            if (visualID == null) {
                throw new AWTException("Unknown Vulkan X11 display connection");
            }
            return visualID;
        }
    }

    private static long queryDefaultVisualID(long display) throws AWTException {
        int screen = X11.XDefaultScreen(display);
        long visual = JNI.callPP(display, screen, X_DEFAULT_VISUAL);
        if (visual == NULL) {
            throw new AWTException("X11 returned no default visual for screen " + screen);
        }
        long visualID = JNI.callPP(visual, X_VISUAL_ID_FROM_VISUAL);
        if (visualID == 0L) {
            throw new AWTException("X11 returned an invalid default visual ID for screen " + screen);
        }
        return visualID;
    }

    private static long getDefaultVulkanDisplay() throws AWTException {
        return getVulkanDisplay(System.getenv("DISPLAY"));
    }

    private static long getVulkanDisplay(String displayName) throws AWTException {
        synchronized (DISPLAY_LOCK) {
            Long display = VULKAN_DISPLAYS.get(displayName);
            return display == null ? openVulkanDisplay(displayName) : display;
        }
    }

    private static long openVulkanDisplay(String displayName) throws AWTException {
        synchronized (DISPLAY_LOCK) {
            long display = displayName == null
                    ? X11.nXOpenDisplay(NULL)
                    : X11.XOpenDisplay(displayName);
            if (display == NULL) {
                throw new AWTException("Failed to open a dedicated X11 display connection for Vulkan"
                        + (displayName == null ? "" : ": " + displayName));
            }

            String canonicalName;
            long defaultVisualID;
            try {
                canonicalName = getDisplayName(display);
                defaultVisualID = queryDefaultVisualID(display);
            } catch (AWTException | RuntimeException | Error failure) {
                X11.XCloseDisplay(display);
                throw failure;
            }
            Long existing = VULKAN_DISPLAYS.get(canonicalName);
            if (existing != null) {
                X11.XCloseDisplay(display);
                display = existing;
            } else {
                // VkSurfaceKHR retains the Display pointer, while AWTVK exposes only the raw surface handle.
                // Keep one isolated connection per X server alive for the lifetime of the process.
                VULKAN_DISPLAYS.put(canonicalName, display);
                VULKAN_DISPLAY_VISUALS.put(display, defaultVisualID);
            }
            VULKAN_DISPLAYS.put(displayName, display);
            return display;
        }
    }

    private static String getDisplayName(long display) throws AWTException {
        long name = JNI.callPP(display, X_DISPLAY_STRING);
        if (name == NULL) {
            throw new AWTException("X11 returned no display name");
        }
        return memUTF8(name);
    }

    private static long getRequiredX11Function(String name) {
        long address = X11.getLibrary().getFunctionAddress(name);
        if (address == NULL) {
            throw new AssertionError("Failed to resolve X11 function " + name);
        }
        return address;
    }
}
