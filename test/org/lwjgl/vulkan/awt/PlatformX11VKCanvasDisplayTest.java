package org.lwjgl.vulkan.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.awt.AWT;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Canvas;
import java.awt.Dimension;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.LINUX)
class PlatformX11VKCanvasDisplayTest {
    @Test
    void usesDedicatedReusableDisplayForTheAWTServer() throws Exception {
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<Canvas> canvasRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                Canvas canvas = new Canvas();
                canvas.setPreferredSize(new Dimension(320, 240));

                JFrame frame = new JFrame("Vulkan X11 display isolation test");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.add(canvas);
                frame.pack();
                frame.setVisible(true);
                frameRef.set(frame);
                canvasRef.set(canvas);
            });

            try (AWT awt = new AWT(canvasRef.get())) {
                long awtDisplay = JAWTX11DrawingSurfaceInfo
                        .create(awt.getPlatformInfo())
                        .display();
                long vulkanDisplay = PlatformX11VKCanvas.getVulkanDisplay(awtDisplay);

                assertNotEquals(0L, awtDisplay);
                assertNotEquals(0L, vulkanDisplay);
                assertNotEquals(awtDisplay, vulkanDisplay,
                        "Vulkan must not share AWT's X11 connection");
                assertEquals(vulkanDisplay, PlatformX11VKCanvas.getVulkanDisplay(awtDisplay),
                        "Canvases on the same X server should reuse the isolated connection");
                assertTrue(PlatformX11VKCanvas.getDefaultVisualID(vulkanDisplay) > 0L,
                        "The isolated connection must expose a valid default visual");
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = frameRef.get();
                if (frame != null) {
                    frame.dispose();
                }
            });
        }
    }
}
