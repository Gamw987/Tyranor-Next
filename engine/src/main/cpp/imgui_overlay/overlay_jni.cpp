// 外部悬浮窗渲染层：TextureView 提供 Surface，本文件在同一渲染线程内建 EGL/GLES3
// 上下文并用 ImGui 绘制。第 1 步只负责显示，不注入引擎的 GL 上下文，也不接管输入。
#include <jni.h>

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <unistd.h>

#include "imgui.h"
#include "imgui_impl_opengl3.h"
#include "overlay_ui.h"

#define LOG_TAG "TyranorOverlay"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

ANativeWindow* g_window = nullptr;
EGLDisplay g_display = EGL_NO_DISPLAY;
EGLSurface g_surface = EGL_NO_SURFACE;
EGLContext g_context = EGL_NO_CONTEXT;
bool g_initialized = false;
bool g_cjk_font = false;
int g_width = 0;
int g_height = 0;

const char* const kCjkFontCandidates[] = {
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/DroidSansFallback.ttf",
        "/system/fonts/DroidSansFallbackFull.ttf",
};

void releaseEgl() {
    if (g_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_surface != EGL_NO_SURFACE) {
            eglDestroySurface(g_display, g_surface);
        }
        if (g_context != EGL_NO_CONTEXT) {
            eglDestroyContext(g_display, g_context);
        }
        eglTerminate(g_display);
    }
    g_surface = EGL_NO_SURFACE;
    g_context = EGL_NO_CONTEXT;
    g_display = EGL_NO_DISPLAY;
}

bool createEgl(ANativeWindow* window) {
    g_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }
    if (!eglInitialize(g_display, nullptr, nullptr)) {
        LOGE("eglInitialize failed");
        return false;
    }

    const EGLint config_attributes[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_STENCIL_SIZE, 0,
            EGL_NONE,
    };
    EGLConfig config = nullptr;
    EGLint config_count = 0;
    if (!eglChooseConfig(g_display, config_attributes, &config, 1, &config_count) || config_count < 1) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    const EGLint context_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE,
    };
    g_context = eglCreateContext(g_display, config, EGL_NO_CONTEXT, context_attributes);
    if (g_context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return false;
    }

    g_surface = eglCreateWindowSurface(g_display, config, window, nullptr);
    if (g_surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }
    if (!eglMakeCurrent(g_display, g_surface, g_surface, g_context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }
    eglSwapInterval(g_display, 0);
    return true;
}

bool loadFont(ImGuiIO& io) {
    for (const char* path : kCjkFontCandidates) {
        if (access(path, R_OK) != 0) {
            continue;
        }
        ImFontConfig config;
        config.FontNo = 0;
        config.OversampleH = 2;
        config.OversampleV = 1;
        ImFont* font = io.Fonts->AddFontFromFileTTF(
                path, 22.0f, &config, io.Fonts->GetGlyphRangesChineseSimplifiedCommon());
        if (font != nullptr) {
            LOGI("loaded CJK font: %s", path);
            return true;
        }
        LOGW("font load returned null: %s", path);
    }
    LOGW("no CJK font found; falling back to default font");
    io.Fonts->AddFontDefault();
    return false;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_core_overlay_ImGuiOverlayView_nativeStart(JNIEnv* env, jclass, jobject surface, jint width, jint height) {
    if (g_initialized) {
        LOGW("nativeStart ignored: already initialized");
        return;
    }
    if (surface == nullptr) {
        LOGE("nativeStart: surface is null");
        return;
    }
    g_width = width;
    g_height = height;
    g_window = ANativeWindow_fromSurface(env, surface);
    if (g_window == nullptr) {
        LOGE("ANativeWindow_fromSurface failed");
        return;
    }
    ANativeWindow_setBuffersGeometry(g_window, width, height, WINDOW_FORMAT_RGBA_8888);

    if (!createEgl(g_window)) {
        releaseEgl();
        ANativeWindow_release(g_window);
        g_window = nullptr;
        return;
    }

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.LogFilename = nullptr;
    io.DisplaySize = ImVec2((float) width, (float) height);
    io.DeltaTime = 1.0f / 60.0f;

    ImGui_ImplOpenGL3_Init("#version 300 es");
    g_cjk_font = loadFont(io);
    io.Fonts->Build();

    g_initialized = true;
    LOGI("overlay ready: %dx%d cjk=%d", width, height, g_cjk_font ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_core_overlay_ImGuiOverlayView_nativeResize(JNIEnv*, jclass, jint width, jint height) {
    if (width > 0 && height > 0) {
        g_width = width;
        g_height = height;
    }
}

JNIEXPORT void JNICALL
Java_com_core_overlay_ImGuiOverlayView_nativeStep(JNIEnv*, jclass) {
    if (!g_initialized) {
        return;
    }
    if (g_width <= 0 || g_height <= 0) {
        return;
    }

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();
    OverlayUi::Draw((float) g_width, (float) g_height, g_cjk_font);
    ImGui::Render();

    glViewport(0, 0, g_width, g_height);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    eglSwapBuffers(g_display, g_surface);
}

JNIEXPORT void JNICALL
Java_com_core_overlay_ImGuiOverlayView_nativeStop(JNIEnv*, jclass) {
    if (!g_initialized) {
        return;
    }
    ImGui_ImplOpenGL3_Shutdown();
    ImGui::DestroyContext();
    g_initialized = false;
    releaseEgl();
    if (g_window != nullptr) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
    LOGI("overlay stopped");
}

}  // extern "C"
