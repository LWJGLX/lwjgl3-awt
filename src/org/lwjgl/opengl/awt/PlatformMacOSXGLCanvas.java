package org.lwjgl.opengl.awt;

import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.awt.*;

import static org.lwjgl.opengl.CGL.*;
import static org.lwjgl.opengl.GL11.glFlush;
import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_getUid;

public class PlatformMacOSXGLCanvas implements PlatformGLCanvas {
    public static final JAWT awt;
    private static final long objc_msgSend;
    private static final long CATransaction;
    static {
        awt = JAWT.calloc();
        awt.version(JAWT_VERSION_1_7);
        if (!JAWT_GetAWT(awt))
            throw new AssertionError("GetAWT failed");
        System.loadLibrary("lwjgl3awt");
        objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        CATransaction = objc_getClass("CATransaction");
    }

    public JAWTDrawingSurface ds;
    private long view;
    private int width;
    private int height;

    // core animation flush
    private static void caFlush() {
        invokePPP(CATransaction, sel_getUid("flush"), objc_msgSend);
    }

    private native long createView(long platformInfo, int width, int height);

    @Override
    public long create(Canvas canvas, GLData attribs, GLData effective) throws AWTException {
        this.ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
        JAWTDrawingSurface ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
        try {
            int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
            if ((lock & JAWT_LOCK_ERROR) != 0)
                throw new AWTException("JAWT_DrawingSurface_Lock() failed");
            try {
                JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
                try {
                    width = dsi.bounds().width();
                    height = dsi.bounds().height();
                    view = createView(dsi.platformInfo(), width, height);
                    caFlush();
                    long openGLContext = invokePPP(view, sel_getUid("openGLContext"), objc_msgSend);
                    return invokePPP(openGLContext, sel_getUid("CGLContextObj"), objc_msgSend);
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
    public boolean swapBuffers() {
        glFlush();
        return true;
    }

    @Override
    public boolean deleteContext(long context) {
        // frees created NSOpenGLView
        invokePPP(view, sel_getUid("removeFromSuperviewWithoutNeedingDisplay"), objc_msgSend);
        invokePPP(view, sel_getUid("clearGLContext"), objc_msgSend);
        invokePPP(view, sel_getUid("release"), objc_msgSend);
        return false;
    }

    @Override
    public boolean makeCurrent(long context) {
        CGLSetCurrentContext(context);
        if (context != 0L) {
            JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
            try {
                int width = dsi.bounds().width();
                int height = dsi.bounds().height();
                if (width != this.width || height != this.height) {
                    // [NSOpenGLCotext update] seems bugged. Updating renderer context with CGL works.
                    CGLSetParameter(context, kCGLCPSurfaceBackingSize, new int[]{width, height});
                    CGLEnable(context, kCGLCESurfaceBackingSize);
                    this.width = width;
                    this.height = height;
                }
            } finally {
                JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
            }
        }
        return true;
    }

    @Override
    public boolean isCurrent(long context) {
        return CGLGetCurrentContext() == context;
    }


    @Override
    public boolean delayBeforeSwapNV(float seconds) {
        throw new UnsupportedOperationException("NYI");
    }

    @Override
    public void lock() throws AWTException {
        int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
        if ((lock & JAWT_LOCK_ERROR) != 0)
            throw new AWTException("JAWT_DrawingSurface_Lock() failed");
    }

    @Override
    public void unlock() throws AWTException {
        JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
    }

    @Override
    public void dispose() {
        JAWT_FreeDrawingSurface(this.ds, awt.FreeDrawingSurface());
        this.ds = null;
    }

}
