package org.lwjgl.vulkan.awt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo;
import org.lwjgl.system.windows.WinBase;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;

import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.vulkan.KHRWin32Surface.*;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * Window-specific implementation of {@link PlatformVKCanvas}.
 * 
 * @author Kai Burjack
 */
public class PlatformWin32VKCanvas implements PlatformVKCanvas {
    private static final JAWT awt;
    static {
        awt = JAWT.callocStack();
        awt.version(JAWT_VERSION_1_4);
        if (!JAWT_GetAWT(awt))
            throw new AssertionError("GetAWT failed");
    }

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
                    JAWTWin32DrawingSurfaceInfo dsiWin = JAWTWin32DrawingSurfaceInfo.create(dsi.platformInfo());
                    long hwnd = dsiWin.hwnd();
                    VkWin32SurfaceCreateInfoKHR sci = VkWin32SurfaceCreateInfoKHR.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR)
                            .hinstance(WinBase.GetModuleHandle((ByteBuffer) null))
                            .hwnd(hwnd);
                    LongBuffer pSurface = stack.mallocLong(1);
                    int err = vkCreateWin32SurfaceKHR(data.instance, sci, null, pSurface);
                    if (err != VK_SUCCESS) {
                        throw new AWTException("Calling vkCreateWin32SurfaceKHR failed with error: " + err);
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

    public boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily) {
        return vkGetPhysicalDeviceWin32PresentationSupportKHR(physicalDevice, queueFamily);
    }

}
