package com.loooong.reader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.OutputStream

internal object PdfDocumentWriter {
    fun saveCopy(document: PDDocument, output: OutputStream?) {
        // SAF 可能返回 null；此时必须保留未保存状态，不能向用户报告保存成功。
        requireNotNull(output) { "无法打开所选保存位置" }
            .use(document::save)
    }
}
