package org.lwjgl.vulkan.awt;

import java.awt.AWTException;
import java.awt.Canvas;

import org.lwjgl.vulkan.VkPhysicalDevice;

/**
 * Interface for platform-specific implementations of {@link AWTVKCanvas}.
 *
 * @author Kai Burjack
 */
public interface PlatformVKCanvas {
    long create(Canvas canvas, VKData data) throws AWTException;
    boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily);
}
