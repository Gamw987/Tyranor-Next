// 外部悬浮窗的 ImGui 界面绘制。
// 第 1 步只负责显示：一个固定在左上角的小卡片，不含任何游戏交互。
#pragma once

namespace OverlayUi {
    // cjk=true 表示已成功加载中文字体，可以使用中文文案。
    void Draw(float width, float height, bool cjk);
}
