## What is it?

Implementation of OpenGL support for AWT with LWJGL 3.

## What does it get me?

Support for:
- creating OpenGL 3.0 and 3.2 core/compatibility contexts (including debug/forward compatible)
- OpenGL ES contexts
- floating-point and sRGB pixel formats
- multisampled framebuffers (also with different number of color samples - Nvidia only)
- v-sync/swap control
- context flush control
- robust buffer access (with application/share-group isolation)
- sync'ing buffer swaps over multiple windows and cards - Nvidia only
- delay before swap - Nvidia only

## Why does it exist?

Because there is currently no supported way for AWT under LWJGL 3.

## How to use it?

```Java
JFrame frame = new JFrame("AWT test");
frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
frame.setLayout(new BorderLayout());
GLData data = new GLData();
data.samples = 4;
frame.add(new AWTGLCanvas(data) {
  public void initGL() {
  }
  public void paintGL() {
  }
});
```
