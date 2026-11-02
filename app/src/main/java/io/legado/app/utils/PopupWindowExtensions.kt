package io.legado.app.utils

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.PopupWindow
import io.legado.app.epd.EinkPopup
import io.legado.app.lib.theme.popupBackground

fun PopupWindow.applyMd3PopupStyle() {
    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    elevation = 8f.dpToPx()
    contentView?.let { it.background = it.context.popupBackground }
    isClippingEnabled = true
    // 墨水屏模式下的最后一层：去阴影/去动画/白底黑框 + 帧缓冲量化
    EinkPopup.prepare(this)
}
