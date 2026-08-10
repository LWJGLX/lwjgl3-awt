package org.lwjgl.opengl.awt;

import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.opengl.GLX.*;
import static org.lwjgl.opengl.GLX13.*;
import static org.lwjgl.opengl.GLX14.GLX_SAMPLE_BUFFERS;
import static org.lwjgl.opengl.GLX14.GLX_SAMPLES;
import static org.lwjgl.opengl.GLXARBCreateContext.*;
import static org.lwjgl.opengl.GLXARBCreateContextProfile.*;
import static org.lwjgl.opengl.GLXARBCreateContextRobustness.*;
import static org.lwjgl.opengl.GLXARBRobustnessApplicationIsolation.*;
import static org.lwjgl.opengl.GLXEXTCreateContextESProfile.*;

import java.awt.AWTException;
import java.awt.Canvas;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.ARBRobustness;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.APIUtil.APIVersion;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.Checks;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.linux.X11;

import static org.lwjgl.system.libffi.LibFFI.*;
import static org.lwjgl.system.Pointer.POINTER_SIZE;

public class PlatformLinuxGLCanvas implements PlatformGLCanvas {
	private static final long X_GET_GEOMETRY = X11.getLibrary().getFunctionAddress("XGetGeometry");
	private static final long X_SET_ERROR_HANDLER = X11.getLibrary().getFunctionAddress("XSetErrorHandler");
	private static final long X_SYNC = X11.getLibrary().getFunctionAddress("XSync");
	private static final Object X_ERROR_HANDLER_LOCK = new Object();
	private static volatile boolean contextCreationXError;
	private static final XErrorHandlerI CONTEXT_CREATION_ERROR_HANDLER = (ignoredDisplay, ignoredEvent) -> {
		contextCreationXError = true;
		return 0;
	};
	private static final long CONTEXT_CREATION_ERROR_HANDLER_ADDRESS =
			CONTEXT_CREATION_ERROR_HANDLER.address();
	public static final JAWT awt;
	static {
		awt = JAWT.create(MemoryUtil.getAllocator().calloc(1, JAWT.SIZEOF)); // untracked allocation
		awt.version(JAWT_VERSION_1_4);
		if (!JAWT_GetAWT(awt))
			throw new AssertionError("GetAWT failed");
	}

	public long display;
	public long drawable;
	public JAWTDrawingSurface ds;
	private Canvas canvas;

	private long create(int depth, GLData attribs, GLData effective) throws AWTException {
		if (attribs.versionPolicy != GLData.VersionPolicy.EXACT) {
			GLUtil.validateVersionAttributes(attribs);
		}
		int screen = X11.XDefaultScreen(display);
		Set<String> extensions = GLXSwapInterval.parseExtensions(
				glXQueryExtensionsString(display, screen));
		IntBuffer attrib_list = BufferUtils.createIntBuffer(16 * 2);
		attrib_list.put(GLX_DRAWABLE_TYPE).put(GLX_WINDOW_BIT);
		attrib_list.put(GLX_RENDER_TYPE).put(GLX_RGBA_BIT);
		attrib_list.put(GLX_RED_SIZE).put(attribs.redSize);
		attrib_list.put(GLX_GREEN_SIZE).put(attribs.greenSize);
		attrib_list.put(GLX_BLUE_SIZE).put(attribs.blueSize);
		attrib_list.put(GLX_DEPTH_SIZE).put(attribs.depthSize);
		attrib_list.put(GLX_DOUBLEBUFFER).put(attribs.doubleBuffer ? 1 : 0);
		if (attribs.samples > 0) {
			attrib_list.put(GLX_SAMPLE_BUFFERS).put(1);
			attrib_list.put(GLX_SAMPLES).put(attribs.samples);
		}
		attrib_list.put(0);
		attrib_list.flip();
		PointerBuffer fbConfigs = glXChooseFBConfig(display, screen, attrib_list);
		long fbConfig;
		try {
			if (fbConfigs == null || fbConfigs.capacity() == 0) {
				// No framebuffer configurations supported!
				throw new AWTException("No supported framebuffer configurations found");
			}
			fbConfig = fbConfigs.get(0);
		} finally {
			if (fbConfigs != null) {
				X11.XFree(fbConfigs);
			}
		}

		GLXSwapInterval swapInterval = verifyGLXCapabilities(extensions, attribs);
		List<GLUtil.ContextVersion> candidates = GLUtil.contextVersionCandidates(attribs,
				attribs.api == GLData.API.GLES ? 3 : 4,
				attribs.api == GLData.API.GLES ? 2 : 6);

		long share_context = NULL;
		if(Objects.nonNull(attribs.shareContext)) {
			if(attribs.shareContext.context == NULL){
				throw new IllegalStateException(
						"Attributes specified shareContext but it is not yet created and thus cannot be shared");
			}
			share_context = attribs.shareContext.context;
		}
		
		long context = 0L;
		for (GLUtil.ContextVersion version : candidates) {
			context = tryCreateContext(fbConfig, share_context,
					bufferGLAttribs(attribs, version));
			if (context != 0L) {
				break;
			}
		}
		if (context == 0) {
			if (attribs.versionPolicy == GLData.VersionPolicy.EXACT) {
				throw new AWTException("Unable to create GLX context");
			}
			throw new AWTException("Unable to create a GLX context satisfying "
					+ GLUtil.describeVersionRequest(attribs) + " after " + candidates.size() + " attempts");
		}

		boolean initialized = false;
		try {
			populateEffectiveGLXAttribs(display, fbConfig, effective);

			if (!makeCurrent(context)) {
				throw new AWTException("Unable to make context current");
			}
			// Mesa resolves an implicit GLX drawable while binding it, so configure the
			// interval only after this context has been made current.
			if (swapInterval != null) {
				swapInterval.apply(display, drawable);
			}
			effective.versionPolicy = attribs.versionPolicy;
			populateEffectiveGLAttribs(effective);
			initialized = true;
			return context;
		} finally {
			makeCurrent(0 /* no context */);
			if (!initialized) {
				glXDestroyContext(display, context);
			}
		}
	}

	private long tryCreateContext(long fbConfig, long shareContext, IntBuffer attributes) {
		synchronized (X_ERROR_HANDLER_LOCK) {
			// Context creation failures are reported as X errors by GLX. Flush older errors before temporarily replacing
			// AWT's process-wide handler, then synchronize again while our handler is installed.
			JNI.callPI(display, 0, X_SYNC);
			contextCreationXError = false;
			long previousErrorHandler = JNI.callPP(CONTEXT_CREATION_ERROR_HANDLER_ADDRESS, X_SET_ERROR_HANDLER);
			long context;
			try {
				context = glXCreateContextAttribsARB(
						display, fbConfig, shareContext, true, attributes);
				JNI.callPI(display, 0, X_SYNC);
			} finally {
				JNI.callPP(previousErrorHandler, X_SET_ERROR_HANDLER);
			}
			if (contextCreationXError) {
				if (context != 0L) {
					glXDestroyContext(display, context);
				}
				return 0L;
			}
			return context;
		}
	}

	public void lock() throws AWTException {
		JAWTDrawingSurface ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
		if (ds == null) {
			throw new AWTException("Failed to get JAWT drawing surface");
		}
		int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
		if ((lock & JAWT_LOCK_ERROR) != 0) {
			JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
			throw new AWTException("JAWT_DrawingSurface_Lock() failed");
		}
		this.ds = ds;
	}

	public void unlock() throws AWTException {
		JAWTDrawingSurface ds = this.ds;
		if (ds == null) {
			throw new AWTException("JAWT drawing surface is not locked");
		}
		try {
			JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
		} finally {
			JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
			this.ds = null;
		}
	}

	public long create(Canvas canvas, GLData attribs, GLData effective) throws AWTException {
		this.canvas = canvas;
		JAWTDrawingSurface ds = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
		try {
			int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
			if ((lock & JAWT_LOCK_ERROR) != 0)
				throw new AWTException("JAWT_DrawingSurface_Lock() failed");
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
				JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
			}
		} finally {
			JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
		}
	}

	public boolean deleteContext(long context) {
		glXDestroyContext(display, context);
		return true;
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

	@Override
	public boolean getFramebufferSize(int[] size) {
		if (ds == null || display == 0L || drawable == 0L) {
			return false;
		}
		if (getDrawableSize(display, drawable, size)) {
			return true;
		}
		if (canvas == null) {
			return false;
		}
		JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
		if (dsi == null) {
			return false;
		}
		try {
			FramebufferSizeUtil.getScaledSize(canvas, dsi.bounds().width(), dsi.bounds().height(), size);
			return true;
		} finally {
			JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
		}
	}

	private static boolean getDrawableSize(long display, long drawable, int[] size) {
		if (X_GET_GEOMETRY == NULL) {
			return false;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer argumentTypes = stack.pointers(
					ffi_type_pointer.address(),
					ffi_type_ulong.address(),
					ffi_type_pointer.address(),
					ffi_type_pointer.address(),
					ffi_type_pointer.address(),
					ffi_type_pointer.address(),
					ffi_type_pointer.address(),
					ffi_type_pointer.address(),
					ffi_type_pointer.address());
			FFICIF cif = FFICIF.malloc(stack);
			if (ffi_prep_cif(cif, FFI_DEFAULT_ABI, ffi_type_sint32, argumentTypes) != FFI_OK) {
				return false;
			}

			PointerBuffer root = stack.mallocPointer(1);
			IntBuffer geometry = stack.mallocInt(6);
			PointerBuffer values = stack.pointers(
					display,
					drawable,
					memAddress(root),
					memAddress(geometry, 0),
					memAddress(geometry, 1),
					memAddress(geometry, 2),
					memAddress(geometry, 3),
					memAddress(geometry, 4),
					memAddress(geometry, 5));
			PointerBuffer arguments = stack.mallocPointer(values.remaining());
			for (int i = 0; i < values.remaining(); i++) {
				arguments.put(memAddress(values, i));
			}
			arguments.flip();

			// libffi widens integral return values to ffi_arg, which is pointer-sized.
			ByteBuffer result = stack.malloc(POINTER_SIZE);
			ffi_call(cif, X_GET_GEOMETRY, result, arguments);
			if (PointerBuffer.get(result, 0) == 0L) {
				return false;
			}
			size[0] = geometry.get(2);
			size[1] = geometry.get(3);
			return true;
		}
	}

	public void dispose() {
		canvas = null;
	}

	private static GLXSwapInterval verifyGLXCapabilities(Set<String> extensions, GLData data)
			throws AWTException {
		if (!extensions.contains("GLX_ARB_create_context")) {
			throw new AWTException("GLX_ARB_create_context is unavailable");
		}
		if (data.api == GLData.API.GLES && !extensions.contains("GLX_EXT_create_context_es_profile")) {
			throw new AWTException("OpenGL ES API requested but GLX_EXT_create_context_es_profile is unavailable");
		}
		if (data.profile != null && !extensions.contains("GLX_ARB_create_context_profile")) {
			throw new AWTException("OpenGL profile requested but GLX_ARB_create_context_profile is unavailable");
		}
		if (data.robustness && !extensions.contains("GLX_ARB_create_context_robustness")) {
			throw new AWTException("OpenGL robustness requested but GLX_ARB_create_context_robustness is unavailable");
		}
		if (data.contextResetIsolation && !extensions.contains("GLX_ARB_robustness_application_isolation")) {
			throw new AWTException("OpenGL robustness requested but GLX_ARB_robustness_application_isolation is unavailable");
		}
		return GLXSwapInterval.create(data.swapInterval, extensions);
	}

	private static IntBuffer bufferGLAttribs(GLData data, GLUtil.ContextVersion version) throws AWTException {
		IntBuffer gl_attrib_list = BufferUtils.createIntBuffer(16 * 2);

		// Set the render type and version
		gl_attrib_list.put(GLX_RENDER_TYPE).put(GLX_RGBA_TYPE);

		if (version.major > 0) {
			gl_attrib_list
				.put(GLX_CONTEXT_MAJOR_VERSION_ARB).put(version.major)
				.put(GLX_CONTEXT_MINOR_VERSION_ARB).put(version.minor);
		}

		// Set the profile based on GLData.api and GLData.profile
		int profile_attrib = -1;
		if (data.api == GLData.API.GLES) {
			if (data.profile != null) {
				throw new AWTException("Cannot request both OpenGL ES and profile: " + data.profile);
			}
			profile_attrib = GLX_CONTEXT_ES_PROFILE_BIT_EXT;
		} else if (data.api == GLData.API.GL || data.api == null) {
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
		if (data.robustness) {
			context_flags |= GLX_CONTEXT_ROBUST_ACCESS_BIT_ARB;

			int notificationStrategy;
			if (data.loseContextOnReset) {
				notificationStrategy = GLX_LOSE_CONTEXT_ON_RESET_ARB;

				if (data.contextResetIsolation) {
					context_flags |= GLX_CONTEXT_RESET_ISOLATION_BIT_ARB;
				}
			} else {
				notificationStrategy = GLX_NO_RESET_NOTIFICATION_ARB;
			}
			gl_attrib_list.put(GLX_CONTEXT_RESET_NOTIFICATION_STRATEGY_ARB).put(notificationStrategy);
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

		if ((profileFlags & GLX_CONTEXT_ES_PROFILE_BIT_EXT) != 0) {
			effective.api = GLData.API.GLES;
		} else {
			effective.api = GLData.API.GL;
		}

		if (version.major >= 3) {
			if (version.major >= 4 || version.minor >= 2) {
				if ((profileFlags & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0) {
					effective.profile = GLData.Profile.CORE;
				} else if ((profileFlags & GL32.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0) {
					effective.profile = GLData.Profile.COMPATIBILITY;
				} else if (
						(profileFlags & GLX_CONTEXT_ES_PROFILE_BIT_EXT) != 0) {
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
			effective.contextResetIsolation =
					(effectiveContextFlags & GLX_CONTEXT_RESET_ISOLATION_BIT_ARB) != 0;
		}

		if (effective.robustness) {
			int effectiveNotificationStrategy = getInteger(ARBRobustness.GL_RESET_NOTIFICATION_STRATEGY_ARB, glGetIntegerv);
			effective.loseContextOnReset = (effectiveNotificationStrategy & ARBRobustness.GL_LOSE_CONTEXT_ON_RESET_ARB) != 0;
		}

		effective.sampleBuffers = getInteger(GL13.GL_SAMPLE_BUFFERS, glGetIntegerv);
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

	@FunctionalInterface
	private interface XErrorHandlerI extends CallbackI {
		Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(
				XErrorHandlerI.class,
				java.lang.invoke.MethodHandles.lookup(),
				APIUtil.apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_sint32,
						ffi_type_pointer, ffi_type_pointer));

		@Override
		default Callback.Descriptor getDescriptor() {
			return DESCRIPTOR;
		}

		@Override
		default void callback(long returnValue, long arguments) {
			int result = invoke(
					memGetAddress(arguments),
					memGetAddress(arguments + POINTER_SIZE));
			memPutInt(returnValue, result);
		}

		int invoke(long display, long event);
	}
}
