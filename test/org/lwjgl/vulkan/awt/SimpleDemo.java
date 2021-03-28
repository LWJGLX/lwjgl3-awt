package org.lwjgl.vulkan.awt;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.EXTMetalSurface.VK_EXT_METAL_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRXlibSurface.VK_KHR_XLIB_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.awt.VKUtil.translateVulkanResult;

/**
 * Shows how to create a simple Vulkan instance and a {@link AWTVKCanvas}.
 * 
 * @author Kai Burjack
 */
public class SimpleDemo {

	/**
     * Create a Vulkan instance using LWJGL 3.
     * 
     * @return the VkInstance handle
     */
    private static VkInstance createInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("AWT Vulkan Demo"))
                    .pEngineName(stack.UTF8(""))
                    .apiVersion(VK_MAKE_VERSION(1, 0, 2));

            // Enhanced switch statement would work better :(
            String surfaceExtension;
            switch (Platform.get()) {
                case WINDOWS: {
                    surfaceExtension = VK_KHR_WIN32_SURFACE_EXTENSION_NAME;
                    break;
                }
                case LINUX: {
                    surfaceExtension = VK_KHR_XLIB_SURFACE_EXTENSION_NAME;
                    break;
                }
                case MACOSX: {
                    surfaceExtension = VK_EXT_METAL_SURFACE_EXTENSION_NAME;
                    break;
                }
                default:
                    throw new RuntimeException("Failed to find the appropriate platform surface extension.");
            }


            ByteBuffer VK_KHR_SURFACE_EXTENSION = stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME);
            ByteBuffer VK_KHR_OS_SURFACE_EXTENSION = stack.UTF8(surfaceExtension);

            PointerBuffer ppEnabledExtensionNames = stack.mallocPointer(2);
            ppEnabledExtensionNames.put(VK_KHR_SURFACE_EXTENSION);
            ppEnabledExtensionNames.put(VK_KHR_OS_SURFACE_EXTENSION);
            ppEnabledExtensionNames.flip();
            VkInstanceCreateInfo pCreateInfo = VkInstanceCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pNext(0L)
                    .pApplicationInfo(appInfo);
            if (ppEnabledExtensionNames.remaining() > 0) {
                pCreateInfo.ppEnabledExtensionNames(ppEnabledExtensionNames);
            }
            PointerBuffer pInstance = stack.mallocPointer(1);
            int err = vkCreateInstance(pCreateInfo, null, pInstance);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("Failed to create VkInstance: " + translateVulkanResult(err));
            }
            long instance = pInstance.get(0);
            return new VkInstance(instance, pCreateInfo);

        }
    }

    public static void main(String[] args) {
        // Create the Vulkan instance
        VkInstance instance = createInstance();
        VKData data = new VKData().setInstance(instance); // <- set Vulkan instance
        JFrame frame = new JFrame("AWT test");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setPreferredSize(new Dimension(600, 600));
        AWTVKCanvas awtvkCanvas = new AWTVKCanvas(data) {
            private static final long serialVersionUID = 1L;

            public void initVK() {
                @SuppressWarnings("unused")
                long surface = this.surface;

                // Do something with surface...
            }

            public void paintVK() {
            }
        };
        frame.add(awtvkCanvas, BorderLayout.CENTER);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                super.windowClosing(e);

                KHRSurface.vkDestroySurfaceKHR(instance, awtvkCanvas.surface, null);
            }
        });

        frame.pack();
        frame.setVisible(true);
    }

}
