package org.lwjgl.vulkan.awt;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTMetalSurface.VK_EXT_METAL_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRWin32Surface.*;
import static org.lwjgl.vulkan.KHRXlibSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.awt.VKUtil.*;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.ByteBuffer;

import javax.swing.JFrame;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

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
        VkApplicationInfo appInfo = VkApplicationInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(memUTF8("AWT Vulkan Demo"))
                .pEngineName(memUTF8(""))
                .apiVersion(VK_MAKE_VERSION(1, 0, 2));
        ByteBuffer VK_KHR_SURFACE_EXTENSION = memUTF8(VK_KHR_SURFACE_EXTENSION_NAME);
        ByteBuffer VK_KHR_OS_SURFACE_EXTENSION;
        if (Platform.get() == Platform.WINDOWS)
            VK_KHR_OS_SURFACE_EXTENSION = memUTF8(VK_KHR_WIN32_SURFACE_EXTENSION_NAME);
        else if (Platform.get() == Platform.LINUX)
            VK_KHR_OS_SURFACE_EXTENSION = memUTF8(VK_KHR_XLIB_SURFACE_EXTENSION_NAME);
        else
            VK_KHR_OS_SURFACE_EXTENSION = memUTF8(VK_EXT_METAL_SURFACE_EXTENSION_NAME);
        PointerBuffer ppEnabledExtensionNames = memAllocPointer(2);
        ppEnabledExtensionNames.put(VK_KHR_SURFACE_EXTENSION);
        ppEnabledExtensionNames.put(VK_KHR_OS_SURFACE_EXTENSION);
        ppEnabledExtensionNames.flip();
        VkInstanceCreateInfo pCreateInfo = VkInstanceCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pNext(0L)
                .pApplicationInfo(appInfo);
        if (ppEnabledExtensionNames.remaining() > 0) {
            pCreateInfo.ppEnabledExtensionNames(ppEnabledExtensionNames);
        }
        PointerBuffer pInstance = MemoryUtil.memAllocPointer(1);
        int err = vkCreateInstance(pCreateInfo, null, pInstance);
        if (err != VK_SUCCESS) {
            throw new RuntimeException("Failed to create VkInstance: " + translateVulkanResult(err));
        }
        long instance = pInstance.get(0);
        memFree(pInstance);
        VkInstance ret = new VkInstance(instance, pCreateInfo);
        memFree(ppEnabledExtensionNames);
        memFree(VK_KHR_OS_SURFACE_EXTENSION);
        memFree(VK_KHR_SURFACE_EXTENSION);
        appInfo.free();
        return ret;
    }

    public static void main(String[] args) {
        // Create the Vulkan instance
        VkInstance instance = createInstance();
        VKData data = new VKData();
        data.instance = instance; // <- set Vulkan instance
        JFrame frame = new JFrame("AWT test");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setPreferredSize(new Dimension(600, 600));
        frame.add(new AWTVKCanvas(data) {
            private static final long serialVersionUID = 1L;
            public void initVK() {
                @SuppressWarnings("unused")
                long surface = this.surface;

                // Do something with surface...
            }
            public void paintVK() {
            }
        }, BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);
    }

}
