cmake_minimum_required(VERSION 3.22.1)
project(tyranor_native)

add_library(krkr_bridge_v2 SHARED krkr_bridge.cpp krkr_raw_frame_bridge.cpp)
add_library(krkrsdl3_graphics_bridge SHARED krkrsdl3_graphics.cpp)
add_library(artemis_audio_bridge SHARED artemis_audio_bridge.cpp)
add_library(artemis_loader SHARED artemis_loader.cpp artemis_host_class.cpp artemis_official_graphics.cpp)
add_library(siglus_bridge SHARED siglus_bridge.cpp)
add_library(rfvp_bridge SHARED rfvp_bridge.cpp)
add_library(games_bridge SHARED games_bridge.cpp)
add_library(ethornell_bridge SHARED ethornell_bridge.cpp)
add_library(cmvs_loader SHARED cmvs_loader.cpp)

find_library(log-lib log)
find_library(dl-lib dl)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles-lib GLESv2)
find_library(gles3-lib GLESv3)

target_link_libraries(krkr_bridge_v2 ${log-lib} ${dl-lib} ${gles3-lib})
target_link_libraries(krkrsdl3_graphics_bridge ${log-lib} ${dl-lib} ${egl-lib} ${gles3-lib})
target_compile_features(krkrsdl3_graphics_bridge PRIVATE cxx_std_17)
target_compile_features(krkr_bridge_v2 PRIVATE cxx_std_17)
target_link_libraries(artemis_audio_bridge ${log-lib} ${dl-lib})
target_link_libraries(artemis_loader ${log-lib} ${dl-lib} ${android-lib} ${egl-lib} ${gles-lib})
target_link_libraries(siglus_bridge ${log-lib} ${dl-lib} ${android-lib})
target_link_libraries(rfvp_bridge ${log-lib} ${dl-lib} ${android-lib})
target_link_libraries(games_bridge ${log-lib} ${dl-lib} ${android-lib})
target_link_libraries(ethornell_bridge ${log-lib} ${dl-lib} ${android-lib})
target_link_libraries(cmvs_loader ${log-lib} ${dl-lib} ${android-lib})

add_library(graphics_timer SHARED graphics_timer.cpp)
target_link_libraries(graphics_timer ${egl-lib} ${gles-lib})

target_compile_features(artemis_loader PRIVATE cxx_std_17)

# 外部悬浮窗：宿主 Activity 里的 TextureView 提供 Surface，本库自建 EGL/GLES3
# 上下文并用 ImGui 绘制。不注入引擎自身的 GL 上下文，也不接管游戏输入。
add_library(imgui_overlay SHARED
        imgui_overlay/overlay_jni.cpp
        imgui_overlay/overlay_ui.cpp
        imgui_overlay/imgui/imgui.cpp
        imgui_overlay/imgui/imgui_draw.cpp
        imgui_overlay/imgui/imgui_tables.cpp
        imgui_overlay/imgui/imgui_widgets.cpp
        imgui_overlay/imgui/imgui_impl_opengl3.cpp)
target_include_directories(imgui_overlay PRIVATE imgui_overlay/imgui)
target_compile_definitions(imgui_overlay PRIVATE IMGUI_IMPL_OPENGL_ES3)
target_compile_features(imgui_overlay PRIVATE cxx_std_17)
target_link_libraries(imgui_overlay ${log-lib} ${android-lib} ${egl-lib} ${gles3-lib})
