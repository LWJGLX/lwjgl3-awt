package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.opengl.GL;

import javax.swing.JFrame;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.Dimension;

@EnabledOnOs(OS.MAC)
class MacOSXGLCanvasLifecycleTest {

    @Test
    void createsMultipleCanvasesAfterDisposingPreviousWindow() throws Exception {
        for (int i = 0; i < 5; i++) {
            runLifecycle();
        }
    }

    private static void runLifecycle() throws Exception {
        FrameState first = showSingleCanvas();
        try {
            renderCanvases(first);
        } finally {
            SwingUtilities.invokeAndWait(first.frame::dispose);
        }

        FrameState second = showSplitCanvases();
        try {
            renderCanvases(second);
        } finally {
            SwingUtilities.invokeAndWait(second.frame::dispose);
        }
    }

    private static FrameState showSingleCanvas() throws Exception {
        FrameState[] result = new FrameState[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("macOS OpenGL canvas lifecycle");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            TestCanvas canvas = createCanvas();
            frame.add(canvas);
            frame.pack();
            frame.setVisible(true);
            result[0] = new FrameState(frame, new TestCanvas[]{canvas});
        });
        return result[0];
    }

    private static FrameState showSplitCanvases() throws Exception {
        FrameState[] result = new FrameState[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("macOS split OpenGL canvas lifecycle");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            TestCanvas[] canvases = new TestCanvas[]{
                    createCanvas(), createCanvas(), createCanvas(), createCanvas()
            };
            JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, canvases[0], canvases[1]);
            JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, canvases[2], canvases[3]);
            frame.add(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right));
            frame.pack();
            frame.setVisible(true);
            result[0] = new FrameState(frame, canvases);
        });
        return result[0];
    }

    private static TestCanvas createCanvas() {
        TestCanvas canvas = new TestCanvas();
        canvas.setPreferredSize(new Dimension(160, 120));
        return canvas;
    }

    private static void renderCanvases(FrameState state) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (TestCanvas canvas : state.canvases) {
                canvas.render();
            }
        });
    }

    private static class FrameState {
        final JFrame frame;
        final TestCanvas[] canvases;

        FrameState(JFrame frame, TestCanvas[] canvases) {
            this.frame = frame;
            this.canvases = canvases;
        }
    }

    private static class TestCanvas extends AWTGLCanvas {
        @Override
        public void initGL() {
            GL.createCapabilities();
        }

        @Override
        public void paintGL() {
            swapBuffers();
        }
    }
}
