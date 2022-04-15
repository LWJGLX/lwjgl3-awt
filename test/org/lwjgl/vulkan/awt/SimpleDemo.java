package org.lwjgl.vulkan.awt;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import static org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.awt.VKUtil.translateVulkanResult;

/**
 * Shows how to create a simple Vulkan instance and a canvas using {@link AWTVK}.
 *
 * @author Kai Burjack
 * @author SWinxy
 */
public class SimpleDemo {

	/**
     * Create a Vulkan instance using LWJGL 3.
     *
     * @return the VkInstance handle
     */
    private static VkInstance createInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo
                    .calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("AWT Vulkan Demo"))
                    .pEngineName(stack.UTF8(""))
                    .apiVersion(VK_MAKE_VERSION(1, 1, 0));

            PointerBuffer ppEnabledExtensionNames = stack.pointers(stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME), stack.UTF8(AWTVK.getSurfaceExtensionName()));

            VkInstanceCreateInfo pCreateInfo = VkInstanceCreateInfo
                    .calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(ppEnabledExtensionNames);

            PointerBuffer pInstance = stack.mallocPointer(1);
            int err = vkCreateInstance(pCreateInfo, null, pInstance);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("Failed to create VkInstance: " + translateVulkanResult(err));
            }

            long instance = pInstance.get(0);
            return new VkInstance(instance, pCreateInfo);

        }
    }

    public static void main(String[] args) throws AWTException {
//        if (!AWT.isPlatformSupported()) {
//            throw new RuntimeException("Platform not supported.");
//        }

        // Create the Vulkan instance
        VkInstance instance = createInstance();

        JFrame frame = new JFrame("AWT test");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setPreferredSize(new Dimension(600, 600));
        Canvas canvas = new Canvas();
        frame.add(canvas, BorderLayout.CENTER);
        frame.pack(); // Packing causes the canvas to be lockable, and is the earliest time it can be used

        long surface = AWTVK.create(canvas, instance);

        // ... Do things with the surface

        frame.setVisible(true);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                super.windowClosing(e);

                // Destroy the surface to prevent leaks and errors
                KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
            }
        });
    }

}
