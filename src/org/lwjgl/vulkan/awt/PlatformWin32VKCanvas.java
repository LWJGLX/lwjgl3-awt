package org.lwjgl.vulkan.awt;

import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.vulkan.KHRWin32Surface.*;
import static org.lwjgl.vulkan.VK10.*;

import java.awt.AWTException;
import java.awt.Canvas;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo;
import org.lwjgl.system.windows.WinBase;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;

/**
 * Window-specific implementation of {@link PlatformVKCanvas}.
 * 
 * @author Kai Burjack
 */
public class PlatformWin32VKCanvas implements PlatformVKCanvas {
    private static final JAWT awt;
    static {
        awt = JAWT.calloc();
        awt.version(JAWT_VERSION_1_4);
        if (!JAWT_GetAWT(awt))
            throw new AssertionError("GetAWT failed");
    }

    public long create(Canvas canvas, VKData data) throws AWTException {
        JAWTDrawingSurface ds = JAWT_GetDrawingSurface(awt.GetDrawingSurface(), canvas);
        try {
            int lock = JAWT_DrawingSurface_Lock(ds.Lock(), ds);
            if ((lock & JAWT_LOCK_ERROR) != 0)
                throw new AWTException("JAWT_DrawingSurface_Lock() failed");
            try {
                JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds.GetDrawingSurfaceInfo(), ds);
                try {
                    JAWTWin32DrawingSurfaceInfo dsiWin = JAWTWin32DrawingSurfaceInfo.create(dsi.platformInfo());
                    long hwnd = dsiWin.hwnd();
                    VkWin32SurfaceCreateInfoKHR sci = VkWin32SurfaceCreateInfoKHR.callocStack().sType(VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR)
                            .hinstance(WinBase.GetModuleHandle((ByteBuffer) null)).hwnd(hwnd);
                    LongBuffer pSurface = stackMallocLong(1);
                    int err = vkCreateWin32SurfaceKHR(data.instance, sci, null, pSurface);
                    long surface = pSurface.get(0);
                    if (err != VK_SUCCESS) {
                        throw new AWTException("Calling vkCreateWin32SurfaceKHR failed with error: " + err);
                    }
                    return surface;
                } finally {
                    JAWT_DrawingSurface_FreeDrawingSurfaceInfo(ds.FreeDrawingSurfaceInfo(), dsi);
                }
            } finally {
                JAWT_DrawingSurface_Unlock(ds.Unlock(), ds);
            }
        } finally {
            JAWT_FreeDrawingSurface(awt.FreeDrawingSurface(), ds);
        }
    }

    public boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily) {
        return vkGetPhysicalDeviceWin32PresentationSupportKHR(physicalDevice, queueFamily) == 1;
    }

}
