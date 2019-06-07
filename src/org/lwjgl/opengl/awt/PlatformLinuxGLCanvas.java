package org.lwjgl.opengl.awt;

import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.opengl.GLX.*;
import static org.lwjgl.opengl.GLX13.*;
import static org.lwjgl.opengl.GLXARBCreateContext.*;
import static org.lwjgl.opengl.GLXARBCreateContextProfile.*;
import static org.lwjgl.opengl.GLXEXTCreateContextESProfile.*;

import java.awt.AWTException;
import java.awt.Canvas;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.ARBRobustness;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLXEXTCreateContextESProfile;
import org.lwjgl.system.APIUtil.APIVersion;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Checks;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;
import org.lwjgl.system.linux.X11;

public class PlatformLinuxGLCanvas implements PlatformGLCanvas {
	public static final JAWT awt;
	static {
		awt = JAWT.calloc();
		awt.version(JAWT_VERSION_1_4);
		if (!JAWT_GetAWT(awt))
			throw new AssertionError("GetAWT failed");
	}

	public long display;
	public long drawable;
	public JAWTDrawingSurface ds;

	private long create(int depth, GLData attribs, GLData effective) throws AWTException {
		int screen = X11.XDefaultScreen(display);
		IntBuffer attrib_list = BufferUtils.createIntBuffer(16 * 2);
		attrib_list.put(GLX_DRAWABLE_TYPE).put(GLX_WINDOW_BIT);
		attrib_list.put(GLX_RENDER_TYPE).put(GLX_RGBA_BIT);
		attrib_list.put(GLX_RED_SIZE).put(attribs.redSize);
		attrib_list.put(GLX_GREEN_SIZE).put(attribs.greenSize);
		attrib_list.put(GLX_BLUE_SIZE).put(attribs.blueSize);
		attrib_list.put(GLX_DEPTH_SIZE).put(attribs.depthSize);
		attrib_list.put(GLX_DOUBLEBUFFER).put(attribs.doubleBuffer ? 1 : 0);
		attrib_list.put(0);
		attrib_list.flip();
		PointerBuffer fbConfigs = glXChooseFBConfig(display, screen, attrib_list);
		if (fbConfigs == null || fbConfigs.capacity() == 0) {
			// No framebuffer configurations supported!
			throw new AWTException("No supported framebuffer configurations found");
		}

		GLData request = new GLData();
		cleanAttribsRequest(attribs, request);
		verifyGLXCapabilities(display, screen, request);
		IntBuffer gl_attrib_list = bufferGLAttribs(request);
		long context = glXCreateContextAttribsARB(display, fbConfigs.get(0), NULL, true, gl_attrib_list);
		if (context == 0) {
			throw new AWTException("Unable to create GLX context");
		}

		populateEffectiveGLXAttribs(display, fbConfigs.get(0), effective);

		if (!makeCurrent(context)) {
			throw new AWTException("Unable to make context current");
		}
		populateEffectiveGLAttribs(effective);
		makeCurrent(0 /* no context */);

		return context;
	}

	public void lock() throws AWTException {
		int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
		if ((lock & JAWT_LOCK_ERROR) != 0)
			throw new AWTException("JAWT_DrawingSurface_Lock() failed");
	}

	public void unlock() throws AWTException {
		JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
	}

	public long create(Canvas canvas, GLData attribs, GLData effective) throws AWTException {
		this.ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
		JAWTDrawingSurface ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
		try {
			lock();
			try {
				JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
				try {
					JAWTX11DrawingSurfaceInfo dsiWin = JAWTX11DrawingSurfaceInfo.create(dsi.platformInfo());
					int depth = dsiWin.depth();
					this.display = dsiWin.display();
					this.drawable = dsiWin.drawable();
					return create(depth, attribs, effective);
				} finally {
					JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
				}
			} finally {
				unlock();
			}
		} finally {
			JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
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

	public void dispose() {
		JAWT_FreeDrawingSurface(this.ds, awt.FreeDrawingSurface());
		this.ds = null;
	}

	private static void cleanAttribsRequest(GLData attribs, GLData request) throws AWTException {
		// Default to GL
		if (attribs.api == null) {
			request.api = GLData.API.GL;
		} else {
			request.api = attribs.api;
		}

		if (attribs.majorVersion > 0) {
			request.majorVersion = attribs.majorVersion;
			request.minorVersion = attribs.minorVersion;
		}

		// For versions >=3.0 determine the profile.
		if (request.majorVersion >= 3) {
			if (attribs.profile != null) {
				request.profile = attribs.profile;
			}

			// For versions >=3.2 copy forward compatible.
			if (request.majorVersion > 3 || request.minorVersion >= 2) {
				request.forwardCompatible = attribs.forwardCompatible;
			}
		}

		request.debug = attribs.debug;
	}

	private static void verifyGLXCapabilities(long display, int screen, GLData data) throws AWTException {
		List<String> extensions = Arrays.asList(glXQueryExtensionsString(display, screen).split(" "));
		if (!extensions.contains("GLX_ARB_create_context")) {
			throw new AWTException("GLX_ARB_create_context is unavailable");
		}
		if (data.api == GLData.API.GLES && !extensions.contains("GLX_EXT_create_context_es_profile")) {
			throw new AWTException("OpenGL ES API requested but GLX_EXT_create_context_es_profile is unavailable");
		}
		if (data.profile != null && !extensions.contains("GLX_ARB_create_context_profile")) {
			throw new AWTException("OpenGL profile requested but GLX_ARB_create_context_profile is unavailable");
		}
	}

	private static IntBuffer bufferGLAttribs(GLData data) throws AWTException {
		IntBuffer gl_attrib_list = BufferUtils.createIntBuffer(16 * 2);

		// Set the render type and version
		gl_attrib_list.put(GLX_RENDER_TYPE).put(GLX_RGBA_TYPE);

		if (data.majorVersion > 0) {
			gl_attrib_list
				.put(GLX_CONTEXT_MAJOR_VERSION_ARB).put(data.majorVersion)
				.put(GLX_CONTEXT_MINOR_VERSION_ARB).put(data.minorVersion);
		}

		// Set the profile based on GLData.api and GLData.profile
		int profile_attrib = -1;
		if (data.api == GLData.API.GLES) {
			if (data.profile != null) {
				throw new AWTException("Cannot request both OpenGL ES and profile: " + data.profile);
			}
			profile_attrib = GLX_CONTEXT_ES_PROFILE_BIT_EXT;
		} else if (data.api == GLData.API.GL) {
			if (data.profile == GLData.Profile.CORE) {
				profile_attrib = GLX_CONTEXT_CORE_PROFILE_BIT_ARB;
			} else if (data.profile == GLData.Profile.COMPATIBILITY) {
				profile_attrib = GLX_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB;
			} else if (data.profile != null) {
				throw new AWTException("Unknown requested profile: " + data.profile);
			}
		} else {
			throw new AWTException("Unknown requested API: " + data.api);
		}
		if (profile_attrib != -1) {
			gl_attrib_list.put(GLX_CONTEXT_PROFILE_MASK_ARB).put(profile_attrib);
		}

		// Set debugging and forward compatibility
		int context_flags = 0;
		if (data.debug) {
			context_flags |= GLX_CONTEXT_DEBUG_BIT_ARB;
		}
		if (data.forwardCompatible) {
			context_flags |= GLX_CONTEXT_FORWARD_COMPATIBLE_BIT_ARB;
		}
		gl_attrib_list.put(GLX_CONTEXT_FLAGS_ARB).put(context_flags);

		gl_attrib_list.put(0).flip();
		return gl_attrib_list;
	}

	private static void populateEffectiveGLXAttribs(long display, long fbId, GLData effective)
			throws AWTException {
		IntBuffer buffer = BufferUtils.createIntBuffer(1);

		glXGetFBConfigAttrib(display, fbId, GLX_RED_SIZE, buffer);
		effective.redSize = buffer.get(0);

		glXGetFBConfigAttrib(display, fbId, GLX_GREEN_SIZE, buffer);
		effective.greenSize = buffer.get(0);

		glXGetFBConfigAttrib(display, fbId, GLX_BLUE_SIZE, buffer);
		effective.blueSize = buffer.get(0);

		glXGetFBConfigAttrib(display, fbId, GLX_DEPTH_SIZE, buffer);
		effective.depthSize = buffer.get(0);

		glXGetFBConfigAttrib(display, fbId, GLX_DOUBLEBUFFER, buffer);
		effective.doubleBuffer = buffer.get(0) == 1;
	}

	private static void populateEffectiveGLAttribs(GLData effective) throws AWTException {
		long glGetIntegerv = GL.getFunctionProvider().getFunctionAddress("glGetIntegerv");
		long glGetString = GL.getFunctionProvider().getFunctionAddress("glGetString");
		APIVersion version = APIUtil.apiParseVersion(getString(GL11.GL_VERSION, glGetString));

		effective.majorVersion = version.major;
		effective.minorVersion = version.minor;

		int profileFlags = getInteger(GL32.GL_CONTEXT_PROFILE_MASK, glGetIntegerv);

		if (version.major >= 3) {
			if (version.major >= 4 || version.minor >= 2) {
				if ((profileFlags & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0) {
					effective.profile = GLData.Profile.CORE;
				} else if ((profileFlags & GL32.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0) {
					effective.profile = GLData.Profile.COMPATIBILITY;
				} else if (
						(profileFlags & GLXEXTCreateContextESProfile.GLX_CONTEXT_ES_PROFILE_BIT_EXT) != 0) {
					// OpenGL ES allows checking for profiles at versions below 3.2, so avoid branching into
					// the if and actually check later.
				} else if (profileFlags != 0) {
					throw new AWTException("Unknown profile " + profileFlags);
				}
			}

			int effectiveContextFlags = getInteger(GL30.GL_CONTEXT_FLAGS, glGetIntegerv);
			effective.debug = (effectiveContextFlags & GL43.GL_CONTEXT_FLAG_DEBUG_BIT) != 0;
			effective.forwardCompatible =
					(effectiveContextFlags & GL30.GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) != 0;
			effective.robustness =
					(effectiveContextFlags & ARBRobustness.GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT_ARB) != 0;
		}

		if ((profileFlags & GLXEXTCreateContextESProfile.GLX_CONTEXT_ES_PROFILE_BIT_EXT) != 0) {
			effective.api = GLData.API.GLES;
		} else {
			effective.api = GLData.API.GL;
		}

		effective.samples = getInteger(GL13.GL_SAMPLES, glGetIntegerv);
	}

	private static int getInteger(int pname, long function) {
		MemoryStack stack = MemoryStack.stackGet();
		int stackPointer = stack.getPointer();
		try {
			IntBuffer params = stack.callocInt(1);
			JNI.callPV(pname, memAddress(params), function);
			return params.get(0);
		} finally {
			stack.setPointer(stackPointer);
		}
	}

	private static String getString(int pname, long function) {
		return memUTF8(Checks.check(JNI.callP(pname, function)));
	}
}
