package org.lwjgl.awt;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTRectangle;
import org.lwjgl.system.macosx.MacOSXLibrary;
import org.lwjgl.system.macosx.ObjCRuntime;

import javax.swing.*;
import java.awt.*;
import java.nio.DoubleBuffer;

/**
 * Utility class to provide MacOSX-only stuff.
 *
 * @author SWinxy
 */
public class MacOSX {

	// ObjCRuntime does not expose objc_msgSend, so we have to get it ourselves
	// Pointer to a method that sends a message to an instance of a class
	// Apple spec: macOS 10.0 (OSX 10; 2001) or higher
	private static final long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

	// No.
	private MacOSX() {}

	/**
	 * Flushes any extant implicit transaction.
	 * Equivalent of <code>[CATransaction flush];</code> in Objective-C.
	 * <p>
	 * From Apple's developer documentation:
	 *
	 * <blockquote>
	 * Delays the commit until any nested explicit transactions have completed.
	 * <p>
	 * Flush is typically called automatically at the end of the current runloop,
	 * regardless of the runloop mode. If your application does not have a runloop,
	 * you must call this method explicitly.
	 * <p>
	 * However, you should attempt to avoid calling flush explicitly.
	 * By allowing flush to execute during the runloop your application
	 * will achieve better performance, atomic screen updates will be preserved,
	 * and transactions and animations that work from transaction to transaction
	 * will continue to function.
	 * </blockquote>
	 */
	public static void caFlush() {
		// Pointer to the CATransaction class definition
		// Apple spec: macOS 10.5 (OSX Leopard; 2007) or higher
		long CATransaction = ObjCRuntime.objc_getClass("CATransaction");

		// Pointer to the flush method
		// Apple spec: macOS 10.5 (OSX Leopard; 2007) or higher
		long flush = ObjCRuntime.sel_getUid("flush");

		JNI.invokePPP(CATransaction, flush, objc_msgSend);
	}

	/**
	 * Creates a native MacOSX view. TODO
	 * @param canvas component to get the view from
	 * @param drawingSurfaceInfo the drawing surface info struct
	 * @return a pointer to a native window handle
	 */
	private static long createMTKView(Canvas canvas, JAWTDrawingSurfaceInfo drawingSurfaceInfo) {

		// if the canvas is inside e.g. a JSplitPane, the dsi coordinates are wrong and need to be corrected
		JAWTRectangle bounds = drawingSurfaceInfo.bounds();
		int x = bounds.x();
		int y = bounds.y();

		JRootPane rootPane = SwingUtilities.getRootPane(canvas);
		if (rootPane != null) {
			Point point = SwingUtilities.convertPoint(canvas, new Point(), rootPane);
			x = point.x;
			y = point.y;
		}

		// Get the preferred default Metal device object
		// Apple spec: 10.11 (OSX El Capitan; 2018) or higher
		SharedLibrary metal = MacOSXLibrary.getWithIdentifier("com.apple.Metal");
		long pMTLCreateSystemDefaultDevice =  metal.getFunctionAddress("MTLCreateSystemDefaultDevice");
		long pDevice = JNI.invokeP(pMTLCreateSystemDefaultDevice);

		// surfaceLayers.windowLayer.frame.size.height
		// TODO: get height of the internal window frame via Obj-C bindings
		y = -bounds.height() - y;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			DoubleBuffer frame = stack.doubles(x, y, bounds.width(), bounds.height());

			// MTKView *view = [[MTKView alloc] initWithFrame:frame device:device];

			// surfaceLayers.layer = view.layer;
			// return view.layer
			return 0;
		}
	}

	/**
	 * Creates a native NSOpenGLView. TODO
	 * @param canvas component to get the view from
	 * @param drawingSurfaceInfo the drawing surface info struct
	 * @param pixelFormat
	 * @return a pointer to a native window handle
	 */
	private static long createOpenGLView(Canvas canvas, JAWTDrawingSurfaceInfo drawingSurfaceInfo, long pixelFormat) {

		// if the canvas is inside e.g. a JSplitPane, the dsi coordinates are wrong and need to be corrected
		JAWTRectangle bounds = drawingSurfaceInfo.bounds();
		int x = bounds.x();
		int y = bounds.y();

		JRootPane rootPane = SwingUtilities.getRootPane(canvas);
		if (rootPane != null) {
			Point point = SwingUtilities.convertPoint(canvas, new Point(), rootPane);
			x = point.x;
			y = point.y;
		}

		// surfaceLayers.windowLayer.frame.size.height
		// TODO: get height of the internal window frame via Obj-C bindings
		y = - bounds.height() - y;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			DoubleBuffer frame = stack.doubles(x, y, bounds.width(), bounds.height());

			// NSOpenGLView *view = [[NSOpenGLView alloc] initWithFrame:frame pixelFormat:(id)pixelFormat];
			// [view setWantsLayer:YES];

			// surfaceLayers.layer = view.layer;
			// return view.layer
			return 0;
		}
	}
}
