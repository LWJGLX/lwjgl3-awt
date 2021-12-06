package org.lwjgl.vulkan.awt;

import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.awt.*;

/**
 * Interface for platform-specific implementations of {@link AWTVKCanvas}.
 *
 * @author Kai Burjack
 * @deprecated see {@link AWTVKCanvas}.
 */
@Deprecated
public interface PlatformVKCanvas {
    /**
     * @deprecated use {@link AWTVK#create(Canvas, VkInstance)}
     */
    @Deprecated
    long create(Canvas canvas, VKData data) throws AWTException;
    /**
     * @deprecated use {@link AWTVK#create(Canvas, VkInstance)}
     */
    @Deprecated
    boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily);
}
