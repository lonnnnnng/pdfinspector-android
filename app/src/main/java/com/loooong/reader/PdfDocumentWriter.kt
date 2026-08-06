package com.loooong.reader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.OutputStream

internal object PdfDocumentWriter {
    fun saveCopy(document: PDDocument, openOutput: () -> OutputStream?, stagingFile: File) {
        try {
            stagingFile.parentFile?.mkdirs()
            stagingFile.outputStream().buffered().use(document::save)

            // long: 先重新解析完整临时文件，确保序列化成功后才触碰用户选择的目标，避免把半成品当成副本。
            PDDocument.load(stagingFile).use { saved ->
                check(saved.numberOfPages == document.numberOfPages) { "导出的 PDF 页数校验失败" }
            }

            // long: SAF 打开 "wt" 输出流时可能立即截断目标，所以必须延迟到临时文件校验完成后调用。
            val destination = requireNotNull(openOutput()) { "无法打开所选保存位置" }
            destination.buffered().use { target ->
                stagingFile.inputStream().buffered().use { source -> source.copyTo(target) }
                target.flush()
            }
        } finally {
            runCatching { stagingFile.delete() }
        }
    }
}
