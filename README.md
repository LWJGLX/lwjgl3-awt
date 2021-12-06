[![Build-and-publish Actions Status](https://github.com/LWJGLX/lwjgl3-awt/workflows/build-and-publish/badge.svg)](https://github.com/LWJGLX/lwjgl3-awt/actions) [![Maven Central](https://img.shields.io/maven-central/v/org.lwjglx/lwjgl3-awt.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22org.lwjglx%22%20AND%20a:%22lwjgl3-awt%22&core=gav) [![Maven Snapshot](https://img.shields.io/nexus/s/https/oss.sonatype.org/org.lwjglx/lwjgl3-awt.svg)](https://oss.sonatype.org/content/repositories/snapshots/org/lwjglx/lwjgl3-awt/)

This library allows for using OpenGL and Vulkan LWJGL 3 surfaces in AWT (and by extension, Swing) frames. Works with MacOS X, Windows, and Linux.

## What does it get me?

Support for OpenGL:
- creating OpenGL 3.0 and 3.2 core/compatibility contexts (including debug/forward compatible)
- OpenGL ES contexts
- floating-point and sRGB pixel formats
- multisampled framebuffers (also with different number of color samples - Nvidia only)
- v-sync/swap control
- context flush control
- robust buffer access (with application/share-group isolation)
- sync'ing buffer swaps over multiple windows and cards - Nvidia only
- delay before swap - Nvidia only

Support for Vulkan:
- Vulkan 1.0, 1.1, 1.2
- MoltenVK support

_Note about compatibility_:
The minimum macOS version for OpenGL is 10.5, and the minimum for Vulkan is 10.11, since Vulkan runs on top of the Metal API introduced in that version.

## How to use
Full code samples:
- [AWTTest](/test/org/lwjgl/opengl/awt/AWTTest.java)
- [AWTThreadTest](/test/org/lwjgl/opengl/awt/AWTThreadTest.java)
- [Core32Test](/test/org/lwjgl/opengl/awt/Core32Test.java)
- [SimpleDemo](/test/org/lwjgl/vulkan/awt/SimpleDemo.java) (Vulkan)

### OpenGL

In order to create an OpenGL 3.3 core profile context with 4 sample MSAA, use:

```Java
JFrame frame = new JFrame("AWT test");
frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
frame.setLayout(new BorderLayout());
GLData data = new GLData();
data.majorVersion = 3;
data.minorVersion = 3;
data.profile = GLData.Profile.CORE;
data.samples = 4;
frame.add(new AWTGLCanvas(data) {
  public void initGL() {
  }
  public void paintGL() {
  }
});
```

### Vulkan

```Java
if (!AWTVK.isPlatformSupported()) {
	throw new RuntimeException("Platform not supported.");
}

JFrame frame = new JFrame("AWT test");
frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
frame.setLayout(new BorderLayout());
frame.setPreferredSize(new Dimension(600, 600));
Canvas canvas = new Canvas();
frame.add(canvas, BorderLayout.CENTER);
frame.pack(); // Packing causes the canvas to be lockable, and is the earliest time it can be used

long surface = AWTVK.create(canvas, instance);

// Do things with the surface

frame.setVisible(true);
frame.addWindowListener(new WindowAdapter() {
    @Override
    public void windowClosing(WindowEvent e) {
        super.windowClosing(e);
		
        // Destroy the surface to prevent leaks and errors
        KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
}});
```

## How to rebuild the MacOS native library
```
gcc -dynamiclib lwjgl3awt/*.m -o native/macosx/liblwjgl3awt.dylib -framework CoreFoundation -framework AppKit -framework MetalKit -framework Metal 
```