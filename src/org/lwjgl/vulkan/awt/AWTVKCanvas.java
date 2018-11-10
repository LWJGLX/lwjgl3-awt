package org.lwjgl.vulkan.awt;

import java.awt.AWTException;
import java.awt.Canvas;
import java.awt.Graphics;

import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.VkPhysicalDevice;

/**
 * An AWT {@link Canvas} that supports to be drawn on using Vulkan.
 * 
 * @author Kai Burjack
 */
public abstract class AWTVKCanvas extends Canvas {
    private static final long serialVersionUID = 1L;
    private static PlatformVKCanvas platformCanvas;
    static {
        String platformClassName;
        switch (Platform.get()) {
        case WINDOWS:
            platformClassName = "org.lwjgl.vulkan.awt.PlatformWin32VKCanvas";
            break;
        case LINUX:
            platformClassName = "org.lwjgl.vulkan.awt.PlatformX11VKCanvas";
            break;
        default:
            throw new AssertionError("NYI");
        }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends PlatformVKCanvas> clazz = (Class<? extends PlatformVKCanvas>) AWTVKCanvas.class.getClassLoader().loadClass(platformClassName);
            platformCanvas = clazz.newInstance();
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Platform-specific VKCanvas class not found: " + platformClassName);
        } catch (InstantiationException e) {
            throw new AssertionError("Could not instantiate platform-specific VKCanvas class: " + platformClassName);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Could not instantiate platform-specific VKCanvas class: " + platformClassName);
        }
    }

    private final VKData data;
    public long surface;

    protected AWTVKCanvas(VKData data) {
        this.data = data;
    }

    @Override
    public void paint(Graphics g) {
        boolean created = false;
        if (surface == 0L) {
            try {
                surface = platformCanvas.create(this, data);
                created = true;
            } catch (AWTException e) {
                throw new RuntimeException("Exception while creating the Vulkan surface", e);
            }
        }
        if (created)
            initVK();
        paintVK();
    }

    /**
     * Determine whether there is presentation support for the given {@link VkPhysicalDevice} in a command queue of the specified <code>queueFamiliy</code>.
     * 
     * @param physicalDevice
     *            the Vulkan {@link VkPhysicalDevice}
     * @param queueFamily
     *            the command queue family
     * @return <code>true</code> of <code>false</code>
     */
    public boolean getPhysicalDevicePresentationSupport(VkPhysicalDevice physicalDevice, int queueFamily) {
        return platformCanvas.getPhysicalDevicePresentationSupport(physicalDevice, queueFamily);
    }

    /**
     * Will be called once after the Vulkan surface has been created.
     */
    public abstract void initVK();

    /**
     * Will be called whenever the {@link Canvas} needs to paint itself.
     */
    public abstract void paintVK();

}
