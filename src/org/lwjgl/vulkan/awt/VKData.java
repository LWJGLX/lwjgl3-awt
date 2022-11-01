package org.lwjgl.vulkan.awt;

import org.lwjgl.vulkan.VkInstance;

import java.awt.*;

/**
 * Contains necessary data to create an {@link AWTVKCanvas}.
 *
 * @deprecated Please use the {@link AWTVK} API.
 * @author Kai Burjack
 */
@Deprecated
public class VKData {

    /**
     * The {@link VkInstance} on behalf of which to create the surface on an AWT {@link Canvas}.
     */
    public VkInstance instance;

    /**
     * Sets the {@link #instance instance field} and returns this object.
     * @param instance Vulkan instance
     * @return this
     */
    public VKData setInstance(VkInstance instance) {
    	this.instance = instance;
    	return this;
    }

    /**
     * @return the {@link #instance instance field}
     */
    public VkInstance getInstance() {
        return instance;
    }
}
