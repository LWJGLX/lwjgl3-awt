package org.lwjgl.vulkan.awt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;
import org.lwjgl.vulkan.KHRXlibSurface;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkXlibSurfaceCreateInfoKHR;

import java.awt.*;
import java.nio.LongBuffer;

import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.vulkan.KHRXlibSurface.*;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

public class PlatformX11VKCanvas implements PlatformVKCanvas {
    private static final JAWT awt;
    static {
        awt = JAWT.calloc();
        awt.version(JAWT_VERSION_1_4);
        if (!JAWT_GetAWT(awt))
            throw new AssertionError("GetAWT failed");
    }

    @Override
    public long create(Canvas canvas, VKData data) throws AWTException {
        MemoryStack stack = MemoryStack.stackGet();
        JAWTDrawingSurface ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
        try {
            int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
            if ((lock & JAWT_LOCK_ERROR) != 0)
                throw new AWTException("JAWT_DrawingSurface_Lock() failed");
            try {
                JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
                try {
                    JAWTX11DrawingSurfaceInfo dsiX11 = JAWTX11DrawingSurfaceInfo.create(dsi.platformInfo());
                    long display = dsiX11.display();
                    long window = dsiX11.drawable();

                    VkXlibSurfaceCreateInfoKHR sci = VkXlibSurfaceCreateInfoKHR.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR)
                            .dpy(display)
                            .window(window);

                    LongBuffer pSurface = stack.mallocLong(1);
                    int err = vkCreateXlibSurfaceKHR(data.instance, sci, null, pSurface);
                    if (err != VK_SUCCESS) {
                        throw new AWTException("Calling vkCreateXlibSurfaceKHR failed with error: " + err);
                    }

                    return pSurface.get(0);
                } finally {
                    JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
                }
            } finally {
                JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
            }
        } finally {
            JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
        }
    }

    @Override
    public boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily) {
        return KHRXlibSurface.vkGetPhysicalDeviceXlibPresentationSupportKHR(physicalDevice, queueFamily, 0, 0);
    }
}
