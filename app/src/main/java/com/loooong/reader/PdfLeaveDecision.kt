package com.loooong.reader

// long: 所有可能替换或关闭当前 PDF 的入口共享同一目标类型，避免局部入口漏掉脏文档保护。
internal enum class PdfLeaveTarget {
    CLOSE_DOCUMENT,
    OPEN_EDIT_DOCUMENT,
    OPEN_READ_DOCUMENT,
}

internal sealed interface PdfLeaveDecision {
    data class Proceed(val target: PdfLeaveTarget) : PdfLeaveDecision
    data class Confirm(val target: PdfLeaveTarget) : PdfLeaveDecision
}

// long: 只有内存中存在未保存修改时才打断用户；干净文档保持返回和打开动作的一步完成。
internal fun decidePdfLeave(dirty: Boolean, target: PdfLeaveTarget): PdfLeaveDecision =
    if (dirty) PdfLeaveDecision.Confirm(target) else PdfLeaveDecision.Proceed(target)

// long: 保存副本失败或用户取消系统文件选择器时，必须留在确认状态，不能误认为修改已落盘。
internal fun resolvePdfLeaveSave(
    target: PdfLeaveTarget,
    saved: Boolean,
): PdfLeaveDecision = if (saved) {
    PdfLeaveDecision.Proceed(target)
} else {
    PdfLeaveDecision.Confirm(target)
}
