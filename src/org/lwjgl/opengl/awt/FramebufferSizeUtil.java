package org.lwjgl.opengl.awt;

import java.awt.Canvas;
import java.awt.GraphicsConfiguration;
import java.awt.geom.AffineTransform;

final class FramebufferSizeUtil {
    private FramebufferSizeUtil() {
    }

    static void getScaledSize(Canvas canvas, int width, int height, int[] size) {
        double scaleX = 1.0;
        double scaleY = 1.0;
        GraphicsConfiguration configuration = canvas.getGraphicsConfiguration();
        if (configuration != null) {
            AffineTransform transform = configuration.getDefaultTransform();
            scaleX = transform.getScaleX();
            scaleY = transform.getScaleY();
        }
        size[0] = scaledDimension(width, scaleX);
        size[1] = scaledDimension(height, scaleY);
    }

    private static int scaledDimension(int dimension, double scale) {
        return Math.max(0, (int) Math.round(dimension * scale));
    }
}
