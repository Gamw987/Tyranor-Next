#include "overlay_ui.h"

#include "imgui.h"

namespace {

// 外部悬浮窗的固定布局：窗口铺满 TextureView，内部内容按卡片留白。
constexpr float kPadding = 14.0f;

}  // namespace

namespace OverlayUi {

void Draw(float width, float height, bool cjk) {
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2(width, height);

    ImGui::SetNextWindowPos(ImVec2(0.0f, 0.0f), ImGuiCond_Always);
    ImGui::SetNextWindowSize(ImVec2(width, height), ImGuiCond_Always);

    const ImGuiWindowFlags flags =
            ImGuiWindowFlags_NoMove |
            ImGuiWindowFlags_NoResize |
            ImGuiWindowFlags_NoCollapse |
            ImGuiWindowFlags_NoSavedSettings |
            ImGuiWindowFlags_NoBringToFrontOnFocus |
            ImGuiWindowFlags_NoScrollbar |
            ImGuiWindowFlags_NoScrollWithMouse;

    if (!ImGui::Begin(cjk ? "外部悬浮窗" : "External Overlay", nullptr, flags)) {
        ImGui::End();
        return;
    }

    ImGui::TextUnformatted("Tyranor Next");
    ImGui::Separator();
    ImGui::Dummy(ImVec2(0.0f, 4.0f));

    if (cjk) {
        ImGui::TextWrapped("悬浮层已注入到引擎宿主。");
        ImGui::TextWrapped("第 1 步：仅显示，暂不接管输入。");
    } else {
        ImGui::TextWrapped("Overlay attached to the engine host.");
        ImGui::TextWrapped("Step 1: display only, input not captured.");
    }

    ImGui::Dummy(ImVec2(0.0f, 6.0f));
    ImGui::TextDisabled(cjk ? "Tyranor Next · External Overlay" : "Tyranor Next - External Overlay");

    ImGui::End();
}

}  // namespace OverlayUi
