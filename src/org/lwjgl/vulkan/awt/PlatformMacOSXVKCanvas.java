package org.lwjgl.vulkan.awt;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.macosx.ObjCRuntime;
import org.lwjgl.vulkan.VkMetalSurfaceCreateInfoEXT;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.awt.*;
import java.nio.LongBuffer;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.MemoryUtil.memAllocLong;
import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_getUid;
import static org.lwjgl.vulkan.EXTMetalSurface.VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT;
import static org.lwjgl.vulkan.EXTMetalSurface.vkCreateMetalSurfaceEXT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

public class PlatformMacOSXVKCanvas implements PlatformVKCanvas {

    private static final JAWT awt;
    private static final long objc_msgSend;
    private static final long CATransaction;
    static {
        awt = JAWT.calloc();
        awt.version(JAWT_VERSION_1_7);
        if (!JAWT_GetAWT(awt))
            throw new AssertionError("GetAWT failed");
        Library.loadSystem("org.lwjgl.awt","lwjgl3awt");
        objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        CATransaction = objc_getClass("CATransaction");
    }

    // core animation flush
    public static void caFlush() {
        invokePPP(CATransaction, sel_getUid("flush"), objc_msgSend);
    }

    private native long createMTKView(long platformInfo, int width, int height);

    public long create(Canvas canvas, VKData data) throws AWTException {
        MemoryStack stack = MemoryStack.stackGet();
        int ptr = stack.getPointer();
        JAWTDrawingSurface ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
        try {
            int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
            if ((lock & JAWT_LOCK_ERROR) != 0)
                throw new AWTException("JAWT_DrawingSurface_Lock() failed");
            try {
                JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
                try {
                    long metalLayer = createMTKView(dsi.platformInfo(), dsi.bounds().width(), dsi.bounds().height());
                    caFlush();
                    PointerBuffer pLayer = PointerBuffer.create(metalLayer, 1);
                    VkMetalSurfaceCreateInfoEXT sci = VkMetalSurfaceCreateInfoEXT.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT)
                            .flags(0)
                            .pLayer(pLayer);
                    LongBuffer surfaceAddr = memAllocLong(1);
                    surfaceAddr.put(stack.nmalloc(8, 8));
                    surfaceAddr.rewind();
                    int err = vkCreateMetalSurfaceEXT(data.instance, sci, null, surfaceAddr);
                    long surface = surfaceAddr.get(0);
                    stack.setPointer(ptr);
                    if (err != VK_SUCCESS) {
                        throw new AWTException("Calling vkCreateMetalSurfaceEXT failed with error: " + err);
                    }
                    return surface;
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

    // TODO: add this
    public boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily) {
        return true;
    }

}
