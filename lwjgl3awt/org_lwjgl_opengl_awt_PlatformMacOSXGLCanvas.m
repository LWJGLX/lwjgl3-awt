
#import <Cocoa/Cocoa.h>
#include <JavaVM/jawt_md.h>
#include "org_lwjgl_opengl_awt_PlatformMacOSXGLCanvas.h"


JNIEXPORT jlong JNICALL Java_org_lwjgl_opengl_awt_PlatformMacOSXGLCanvas_createView
 (JNIEnv *env, jobject object, jlong platformInfo, jlong pixelFormat, jint x, jint y, jint width, jint height) {
     id <JAWT_SurfaceLayers> surfaceLayers = (id)platformInfo;
     CGRect frame = CGRectMake(x, surfaceLayers.windowLayer.frame.size.height-height-y, width, height);
     NSOpenGLView *view = [[NSOpenGLView alloc] initWithFrame:frame pixelFormat:(id)pixelFormat];
     [view setWantsLayer:YES];
     surfaceLayers.layer = view.layer;
     return (jlong) view;
 }
