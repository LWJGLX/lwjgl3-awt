package org.lwjgl.opengl.awt;

import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.opengl.GLX.*;
import static org.lwjgl.opengl.GLX13.*;

import java.awt.AWTException;
import java.awt.Canvas;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;
import org.lwjgl.system.linux.X11;

class PlatformLinuxGLCanvas implements PlatformGLCanvas {

	private static final JAWT awt;
	static {
		awt = JAWT.calloc();
		awt.version(JAWT_VERSION_1_4);
		if (!JAWT_GetAWT(awt))
			throw new AssertionError("GetAWT failed");
	}

	private long display;
	private long drawable;
	private JAWTDrawingSurface ds;

	private long create(int depth, GLData attribs, GLData effective) throws AWTException {
		int screen = X11.XDefaultScreen(display);
		IntBuffer attrib_list = BufferUtils.createIntBuffer(16 * 2);
		attrib_list.put(GLX_DRAWABLE_TYPE).put(GLX_WINDOW_BIT);
		attrib_list.put(GLX_RENDER_TYPE).put(GLX_RGBA_BIT);
		attrib_list.put(GLX_RED_SIZE).put(attribs.redSize);
		attrib_list.put(GLX_GREEN_SIZE).put(attribs.greenSize);
		attrib_list.put(GLX_BLUE_SIZE).put(attribs.blueSize);
		attrib_list.put(GLX_DEPTH_SIZE).put(attribs.depthSize);
		if (attribs.doubleBuffer)
		    attrib_list.put(GLX_DOUBLEBUFFER).put(1);
		attrib_list.put(0);
		attrib_list.flip();
		PointerBuffer fbConfigs = glXChooseFBConfig(display, screen, attrib_list);
		if (fbConfigs == null || fbConfigs.capacity() == 0) {
			// No framebuffer configurations supported!
			throw new AWTException("No supported framebuffer configurations found");
		}
		long context = glXCreateNewContext(display, fbConfigs.get(0), GLX_RGBA_TYPE, NULL, true);
		return context;
	}

	public void lock() throws AWTException {
		int lock = JAWT_DrawingSurface_Lock(ds.Lock(), ds);
		if ((lock & JAWT_LOCK_ERROR) != 0)
			throw new AWTException("JAWT_DrawingSurface_Lock() failed");
	}

	public void unlock() throws AWTException {
		JAWT_DrawingSurface_Unlock(ds.Unlock(), ds);
	}

	public long create(Canvas canvas, GLData attribs, GLData effective) throws AWTException {
	    this.ds = JAWT_GetDrawingSurface(awt.GetDrawingSurface(), canvas);
		JAWTDrawingSurface ds = JAWT_GetDrawingSurface(awt.GetDrawingSurface(), canvas);
		try {
			lock();
			try {
				JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds.GetDrawingSurfaceInfo(), ds);
				try {
					JAWTX11DrawingSurfaceInfo dsiWin = JAWTX11DrawingSurfaceInfo.create(dsi.platformInfo());
					int depth = dsiWin.depth();
					this.display = dsiWin.display();
					this.drawable = dsiWin.drawable();
					return create(depth, attribs, effective);
				} finally {
					JAWT_DrawingSurface_FreeDrawingSurfaceInfo(ds.FreeDrawingSurfaceInfo(), dsi);
				}
			} finally {
				unlock();
			}
		} finally {
			JAWT_FreeDrawingSurface(awt.FreeDrawingSurface(), ds);
		}
	}

	public boolean deleteContext(long context) {
		return false;
	}

	public boolean makeCurrent(long context) {
		if (context == 0L)
			return glXMakeCurrent(display, 0L, 0L);
		return glXMakeCurrent(display, drawable, context);
	}

	public boolean isCurrent(long context) {
		return glXGetCurrentContext() == context;
	}

	public boolean swapBuffers() {
		glXSwapBuffers(display, drawable);
		return true;
	}

	public boolean delayBeforeSwapNV(float seconds) {
		throw new UnsupportedOperationException("NYI");
	}

}
