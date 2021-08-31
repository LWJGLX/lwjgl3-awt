
#import <MetalKit/MetalKit.h>
#include <JavaVM/jawt_md.h>
#include "org_lwjgl_opengl_awt_PlatformMacOSXGLCanvas.h"


JNIEXPORT jlong JNICALL Java_org_lwjgl_vulkan_awt_PlatformMacOSXVKCanvas_createMTKView
  (JNIEnv *env, jobject object, jlong platformInfo, jint x, jint y, jint width, jint height) {
      id<JAWT_SurfaceLayers> surfaceLayers = (id)platformInfo;

      // Get the preferred default Metal device object
      // Apple spec: 10.11 (OSX El Capitan; 2018) or higher
      id<MTLDevice> device = MTLCreateSystemDefaultDevice();

      // New rectangle with fixed coordinates
      // Apple spec: 10.0 (OSX 10; 2001) or higher
      CGRect frame = CGRectMake(x, surfaceLayers.windowLayer.frame.size.height-height-y, width, height);

      MTKView *view = [[MTKView alloc] initWithFrame:frame device:device];
      surfaceLayers.layer = view.layer;

      return (jlong) view.layer;
  }
