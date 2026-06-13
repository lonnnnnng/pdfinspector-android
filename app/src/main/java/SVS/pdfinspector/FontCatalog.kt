package SVS.pdfinspector

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.fontbox.ttf.TrueTypeCollection
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.io.FileInputStream
import java.io.IOException

const val AUTO_FONT_ID = "auto"

enum class FontSource { BUNDLED, SYSTEM, CUSTOM }

class FontOption(
    val id: String,
    val displayName: String,
    val source: FontSource,
)

// Supplies replacement fonts for text edits from three places: Liberation
// fonts bundled in assets, the device's /system/fonts, and user imports kept
// in filesDir. resolve embeds a chosen font into the open document; small
// fonts are embedded whole and cached so reuse dedups, large ones (CJK) are
// subset fresh per edit so pdfbox can finalize each subset on the next save.
class FontCatalog(private val appContext: Context) {

    private val customDir: File = File(appContext.filesDir, "fonts").apply { mkdirs() }

    private var cacheDoc: PDDocument? = null
    private val fullEmbedCache = HashMap<String, PDFont>()

    private val bundled: List<Entry> = buildBundled()
    private val system: List<Entry> by lazy { scanSystem() }

    private class Entry(
        val id: String,
        val displayName: String,
        val source: FontSource,
        val embedSubset: Boolean,
        val load: (PDDocument) -> PDFont,
    )

    fun options(): List<FontOption> {
        val all = ArrayList<Entry>(bundled.size + system.size + 8)
        all.addAll(bundled)
        all.addAll(system)
        all.addAll(scanCustom())
        return all.map { FontOption(it.id, it.displayName, it.source) }
    }

    fun resolve(doc: PDDocument, id: String): PDFont? {
        if (doc !== cacheDoc) {
            cacheDoc = doc
            fullEmbedCache.clear()
        }
        val entry = findEntry(id) ?: return null
        return try {
            if (entry.embedSubset) entry.load(doc)
            else fullEmbedCache.getOrPut(entry.id) { entry.load(doc) }
        } catch (_: Exception) {
            null
        }
    }

    // Picks the closest bundled Liberation face to the original's descriptor.
    fun autoMatchId(original: PDFont?): String? {
        original ?: return null
        val desc = try {
            original.fontDescriptor
        } catch (_: Exception) {
            null
        }
        val name = (desc?.fontName ?: "").substringAfter('+').lowercase()
        val mono = desc?.isFixedPitch == true ||
            listOf("mono", "courier", "consol").any { name.contains(it) }
        val serif = !mono && (desc?.isSerif == true ||
            listOf("times", "serif", "roman", "georgia", "minion", "garamond")
                .any { name.contains(it) })
        val bold = desc?.isForceBold == true || (desc?.fontWeight ?: 0f) >= 600f ||
            listOf("bold", "black", "heavy", "semibold").any { name.contains(it) }
        val italic = desc?.isItalic == true || (desc?.italicAngle ?: 0f) != 0f ||
            listOf("italic", "oblique").any { name.contains(it) }
        val family = when {
            mono -> "Mono"
            serif -> "Serif"
            else -> "Sans"
        }
        val style = when {
            family == "Mono" -> if (bold) "Bold" else "Regular"
            bold && italic -> "BoldItalic"
            bold -> "Bold"
            italic -> "Italic"
            else -> "Regular"
        }
        return "bundled:Liberation$family-$style"
    }

    fun importFont(uri: Uri): Boolean {
        return try {
            val target = uniqueFile(sanitize(queryName(uri) ?: "font.ttf"))
            appContext.contentResolver.openInputStream(uri).use { input ->
                input ?: return false
                target.outputStream().use { input.copyTo(it) }
            }
            if (target.length() > 0) {
                true
            } else {
                target.delete()
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun findEntry(id: String): Entry? {
        bundled.firstOrNull { it.id == id }?.let { return it }
        system.firstOrNull { it.id == id }?.let { return it }
        return scanCustom().firstOrNull { it.id == id }
    }

    private fun buildBundled(): List<Entry> {
        val defs = listOf(
            "LiberationSans-Regular" to "Liberation Sans",
            "LiberationSans-Bold" to "Liberation Sans Bold",
            "LiberationSans-Italic" to "Liberation Sans Italic",
            "LiberationSans-BoldItalic" to "Liberation Sans Bold Italic",
            "LiberationSerif-Regular" to "Liberation Serif",
            "LiberationSerif-Bold" to "Liberation Serif Bold",
            "LiberationSerif-Italic" to "Liberation Serif Italic",
            "LiberationSerif-BoldItalic" to "Liberation Serif Bold Italic",
            "LiberationMono-Regular" to "Liberation Mono",
            "LiberationMono-Bold" to "Liberation Mono Bold",
            "Carlito-Regular" to "Carlito",
            "Carlito-Bold" to "Carlito Bold",
            "Carlito-Italic" to "Carlito Italic",
            "Carlito-BoldItalic" to "Carlito Bold Italic",
            "Caladea-Regular" to "Caladea",
            "Caladea-Bold" to "Caladea Bold",
            "Caladea-Italic" to "Caladea Italic",
            "Caladea-BoldItalic" to "Caladea Bold Italic",
        )
        return defs.map { (file, label) ->
            Entry("bundled:$file", label, FontSource.BUNDLED, false) { doc ->
                appContext.assets.open("fonts/$file.ttf").use { PDType0Font.load(doc, it, false) }
            }
        }
    }

    private fun scanSystem(): List<Entry> {
        val files = File("/system/fonts").listFiles() ?: return emptyList()
        val out = ArrayList<Entry>()
        for (f in files.sortedBy { it.name }) {
            val lower = f.name.lowercase()
            if (!lower.endsWith(".ttf") && !lower.endsWith(".ttc")) continue
            if (!systemAllowed(f.name)) continue
            val ttc = lower.endsWith(".ttc")
            val subset = ttc || f.length() > SUBSET_THRESHOLD
            out.add(
                Entry("system:${f.absolutePath}", prettyName(f.name), FontSource.SYSTEM, subset) { doc ->
                    if (ttc) loadFromCollection(doc, f) else loadFile(doc, f, subset)
                },
            )
        }
        return out
    }

    private fun scanCustom(): List<Entry> {
        val files = customDir.listFiles() ?: return emptyList()
        val out = ArrayList<Entry>()
        for (f in files.sortedBy { it.name }) {
            val lower = f.name.lowercase()
            val ttc = lower.endsWith(".ttc")
            if (!lower.endsWith(".ttf") && !lower.endsWith(".otf") && !ttc) continue
            val subset = ttc || f.length() > SUBSET_THRESHOLD
            out.add(
                Entry("custom:${f.name}", prettyName(f.name), FontSource.CUSTOM, subset) { doc ->
                    if (ttc) loadFromCollection(doc, f) else loadFile(doc, f, subset)
                },
            )
        }
        return out
    }

    private fun loadFile(doc: PDDocument, file: File, embedSubset: Boolean): PDFont =
        FileInputStream(file).use { PDType0Font.load(doc, it, embedSubset) }

    private fun loadFromCollection(doc: PDDocument, file: File): PDFont =
        TrueTypeCollection(file).use { coll ->
            var first: TrueTypeFont? = null
            coll.processAllFonts { ttf -> if (first == null) first = ttf }
            PDType0Font.load(doc, first ?: throw IOException("empty font collection"), true)
        }

    private fun systemAllowed(name: String): Boolean = SYSTEM_PREFIXES.any { name.startsWith(it) }

    private fun prettyName(fileName: String): String {
        val stem = fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .trim()
        return stem.ifEmpty { fileName }
    }

    private fun queryName(uri: Uri): String? = try {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (base.contains('.')) base else "$base.ttf"
    }

    private fun uniqueFile(name: String): File {
        var f = File(customDir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "ttf")
        var i = 1
        while (f.exists()) {
            f = File(customDir, "$stem-$i.$ext")
            i++
        }
        return f
    }

    companion object {
        private const val SUBSET_THRESHOLD = 4L * 1024 * 1024
        private val SYSTEM_PREFIXES = listOf(
            "Roboto-", "RobotoFlex",
            "NotoSans-", "NotoSerif-", "NotoSansMono",
            "NotoSansCJK", "NotoSerifCJK", "NotoSansKR", "NotoSansJP",
            "NotoSansSC", "NotoSansTC", "NotoSansHK",
            "NotoNaskhArabic", "NotoSansArabic", "NotoSansHebrew",
            "NotoSansDevanagari", "NotoSansThai", "NotoSansBengali",
            "NotoSansTamil", "NotoSansKannada", "NotoSansTelugu",
            "DroidSans",
        )
    }
}
