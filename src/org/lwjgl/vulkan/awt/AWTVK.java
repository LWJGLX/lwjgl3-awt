package org.lwjgl.vulkan.awt;

import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.awt.AWTException;
import java.awt.Canvas;

/**
 * Vulkan API.
 * <p>
 * To use the surface, {@link org.lwjgl.vulkan.KHRSurface#VK_KHR_SURFACE_EXTENSION_NAME VK_KHR_SURFACE_EXTENSION_NAME}
 * and {@link #getSurfaceExtensionName()} must be enabled extensions.
 */
public class AWTVK {

	/**
	 * Gets the required instance extension for the particular platform.
	 * <p>
	 * Note: {@link org.lwjgl.vulkan.KHRSurface#VK_KHR_SURFACE_EXTENSION_NAME VK_KHR_SURFACE_EXTENSION_NAME}
	 * must also be enabled when creating the instance.
	 */
	public static String getSurfaceExtensionName() {
		switch (Platform.get()) {
			case WINDOWS: return PlatformWin32VKCanvas.EXTENSION_NAME;
			case MACOSX: return PlatformMacOSXVKCanvas.EXTENSION_NAME;
			case LINUX: return PlatformX11VKCanvas.EXTENSION_NAME;

			default: throw new RuntimeException("Platform " + Platform.get() + " not supported in lwjgl3-awt.");
		}
	}

	/**
	 * Checks if the physical device supports presenting using
	 * the provided queue family index.
	 * <p>
	 * This does not guarantee compatibility with any given surface,
	 * but rather determines if the platform supports presentation
	 * at all.
	 *
	 * @param physicalDevice   the physical device to check
	 * @param queueFamilyIndex the index of the queue family to test
	 * @return true if the physical device supports the queue family index
	 */
	public static boolean checkSupport(VkPhysicalDevice physicalDevice, int queueFamilyIndex) {
		switch (Platform.get()) {
			case WINDOWS: return PlatformWin32VKCanvas.checkSupport(physicalDevice, queueFamilyIndex);
			case MACOSX: return PlatformMacOSXVKCanvas.checkSupport(physicalDevice, queueFamilyIndex);
			case LINUX: return PlatformX11VKCanvas.checkSupport(physicalDevice, queueFamilyIndex);

			default: throw new RuntimeException("Platform " + Platform.get() + " not supported in lwjgl3-awt.");
		}
	}

	/**
	 * Uses the provided canvas to create a Vulkan surface to draw on.
	 *
	 * @param canvas   canvas to render onto
	 * @param instance vulkan instance
	 * @return handle of the surface
	 * @throws AWTException if the surface creation fails
	 */
	public static long create(Canvas canvas, VkInstance instance) throws AWTException {
		switch (Platform.get()) {
			case WINDOWS: return PlatformWin32VKCanvas.create(canvas, instance);
			case MACOSX: return PlatformMacOSXVKCanvas.create(canvas, instance);
			case LINUX: return PlatformX11VKCanvas.create(canvas, instance);

			default: throw new RuntimeException("Platform " + Platform.get() + " not supported in lwjgl3-awt.");
		}
	}
}
