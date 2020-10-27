

#import <MetalKit/MetalKit.h>
#include <JavaVM/jawt_md.h>
#include "org_lwjgl_opengl_awt_PlatformMacOSXGLCanvas.h"


JNIEXPORT jlong JNICALL Java_org_lwjgl_vulkan_awt_PlatformMacOSXVKCanvas_createMTKView
  (JNIEnv *env, jobject object, jlong platformInfo, jint x, jint y, jint width, jint height) {
      id<JAWT_SurfaceLayers> surfaceLayers = (id)platformInfo;
      id<MTLDevice> device = MTLCreateSystemDefaultDevice();
      CGRect frame = CGRectMake(x, surfaceLayers.windowLayer.frame.size.height-height-y, width, height);
      MTKView *view = [[MTKView alloc] initWithFrame:frame device:device];
      surfaceLayers.layer = view.layer;
      return (jlong) view.layer;
  }
