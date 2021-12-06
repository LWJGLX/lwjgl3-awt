package org.lwjgl.vulkan.awt;

import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.awt.*;

/**
 * An AWT {@link Canvas} that supports to be drawn on using Vulkan.
 *
 * @deprecated This is now no longer the preferred method of getting the handle of the surface.
 * Instead of creating a class that extends {@link AWTVKCanvas}, you can create a generic canvas.
 * With that canvas, use {@link AWTVK#create(Canvas, VkInstance)} with it,
 * and it will return a surface handle. To draw, override {@link Canvas#paint(Graphics)}
 * (need not call {@code super.paint(g)}). Other helpful utilities exist in {@link AWTVK}.
 *
 * NOTE: Before the canvas can be passed into {@link AWTVK#create(Canvas, VkInstance)},
 * it must have a peer{{cn}}, and that can be forced by calling {@link Frame#pack()}.
 * @author Kai Burjack
 */
@Deprecated
public abstract class AWTVKCanvas extends Canvas {
    private static final long serialVersionUID = 1L;
    private static final PlatformVKCanvas platformCanvas;
    static {
        // Enhanced switch would work better :(
        switch (Platform.get()) {
        case WINDOWS:
            platformCanvas = new PlatformWin32VKCanvas();
            break;
        case LINUX:
            platformCanvas = new PlatformX11VKCanvas();
            break;
        case MACOSX:
            platformCanvas = new PlatformMacOSXVKCanvas();
            break;
        default:
            throw new AssertionError("NYI");
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
