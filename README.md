[![Build-and-publish Actions Status](https://github.com/LWJGLX/lwjgl3-awt/workflows/build-and-publish/badge.svg)](https://github.com/LWJGLX/lwjgl3-awt/actions) [![Maven Central](https://img.shields.io/maven-central/v/org.lwjglx/lwjgl3-awt.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22org.lwjglx%22%20AND%20a:%22lwjgl3-awt%22&core=gav) [![Maven Snapshot](https://img.shields.io/nexus/s/https/oss.sonatype.org/org.lwjglx/lwjgl3-awt.svg)](https://oss.sonatype.org/content/repositories/snapshots/org/lwjglx/lwjgl3-awt/)

## What is it?

OpenGL and Vulkan support for AWT with LWJGL 3.

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

## Why does it exist?

Because there is currently no other way to use OpenGL or Vulkan with AWT.

## How to use it with OpenGL?

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

## How to use it with Vulkan?

```Java
JFrame frame = new JFrame("AWT test");
frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
frame.setLayout(new BorderLayout());
VkInstance instance = ...; // <- Vulkan instance created somewhere else
VKData data = new VKData();
data.instance = instance; // <- set Vulkan instance
frame.add(new AWTVKCanvas(data) {
    public void initVK() {
        long surface = this.surface;
        // Do something with surface...
    }
    public void paintVK() {
    }
});
```

## How to rebuild the MacOS native library
```
gcc -dynamiclib lwjgl3awt/*.m -o native/macosx/liblwjgl3awt.dylib -framework CoreFoundation -framework AppKit -framework MetalKit -framework Metal 
```