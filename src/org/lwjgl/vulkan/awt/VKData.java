package org.lwjgl.vulkan.awt;

import java.awt.Canvas;

import org.lwjgl.vulkan.VkInstance;

/**
 * Contains necessary data to create an {@link AWTVKCanvas}.
 * 
 * @author Kai Burjack
 */
public class VKData {

    /**
     * The {@link VkInstance} on behalf of which to create the surface on an AWT {@link Canvas}.
     */
    public VkInstance instance;

}
