package com.loooong.reader

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

enum class EbookPageTheme {
    SYSTEM,
    LIGHT,
    DARK,
    SEPIA,
}

data class EbookReaderSettings(
    val fontSizeSp: Float = 18f,
    val lineHeight: Float = 1.55f,
    val horizontalPaddingDp: Float = 20f,
    val pageTheme: EbookPageTheme = EbookPageTheme.SYSTEM,
)

enum class EbookSourceKind {
    LOCAL,
    ONLINE,
}

data class EbookHistoryEntry(
    val sourceId: String,
    val title: String,
    val format: EbookFormat,
    val sourceKind: EbookSourceKind,
    val updatedAt: Long,
    val cacheFileName: String? = null,
    val progress: Float? = null,
)

internal fun mergeEbookHistory(
    existing: List<EbookHistoryEntry>,
    latest: EbookHistoryEntry,
    limit: Int = 20,
): List<EbookHistoryEntry> = if (limit <= 0) {
    emptyList()
} else {
    val previous = existing.firstOrNull { it.sourceId == latest.sourceId }
    val mergedLatest = if (latest.progress == null && previous?.progress != null) {
        latest.copy(progress = previous.progress)
    } else {
        latest
    }
    buildList {
        add(mergedLatest)
        existing.asSequence()
            .filterNot { it.sourceId == latest.sourceId }
            .take(limit - 1)
            .forEach(::add)
    }
}

class EbookPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadHistory(): List<EbookHistoryEntry> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        EbookHistoryEntry(
                            sourceId = item.getString("sourceId"),
                            title = item.optString("title", "电子书"),
                            format = EbookFormat.valueOf(item.getString("format")),
                            sourceKind = EbookSourceKind.valueOf(item.getString("sourceKind")),
                            updatedAt = item.optLong("updatedAt", 0L),
                            cacheFileName = item.optString("cacheFileName").takeIf { it.isNotBlank() },
                            progress = item.optDouble("progress", Double.NaN)
                                .takeUnless(Double::isNaN)
                                ?.toFloat()
                                ?.coerceIn(0f, 1f),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun recordHistory(entry: EbookHistoryEntry) {
        val merged = mergeEbookHistory(loadHistory(), entry)
        saveHistory(merged)
    }

    fun removeHistory(sourceId: String) {
        saveHistory(loadHistory().filterNot { it.sourceId == sourceId })
    }

    fun removePosition(sourceId: String) {
        val root = runCatching {
            JSONObject(preferences.getString(KEY_POSITIONS, null) ?: "{}")
        }.getOrElse { JSONObject() }
        root.remove(sourceId)
        preferences.edit { putString(KEY_POSITIONS, root.toString()) }
    }

    fun updateHistoryProgress(sourceId: String, progress: Float) {
        saveHistory(
            loadHistory().map { entry ->
                if (entry.sourceId == sourceId) entry.copy(progress = progress.coerceIn(0f, 1f)) else entry
            },
        )
    }

    private fun saveHistory(entries: List<EbookHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { item ->
            array.put(
                JSONObject()
                    .put("sourceId", item.sourceId)
                    .put("title", item.title)
                    .put("format", item.format.name)
                    .put("sourceKind", item.sourceKind.name)
                    .put("updatedAt", item.updatedAt)
                    .put("cacheFileName", item.cacheFileName)
                    .put("progress", item.progress),
            )
        }
        preferences.edit { putString(KEY_HISTORY, array.toString()) }
    }

    fun loadPosition(sourceId: String): EbookReadingPosition? {
        val raw = runCatching {
            JSONObject(preferences.getString(KEY_POSITIONS, null) ?: "{}")
                .optString(sourceId)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return ebookReadingPositionFromJson(raw)
    }

    fun savePosition(position: EbookReadingPosition) {
        val root = runCatching {
            JSONObject(preferences.getString(KEY_POSITIONS, null) ?: "{}")
        }.getOrElse { JSONObject() }
        root.put(position.sourceId, position.toJson())
        preferences.edit { putString(KEY_POSITIONS, root.toString()) }
    }

    fun loadSettings(): EbookReaderSettings {
        val json = runCatching {
            JSONObject(preferences.getString(KEY_SETTINGS, null) ?: "{}")
        }.getOrElse { JSONObject() }
        return EbookReaderSettings(
            fontSizeSp = json.optDouble("fontSizeSp", 18.0).toFloat().coerceIn(14f, 32f),
            lineHeight = json.optDouble("lineHeight", 1.55).toFloat().coerceIn(1.2f, 2.2f),
            horizontalPaddingDp = json.optDouble("horizontalPaddingDp", 20.0).toFloat().coerceIn(8f, 40f),
            pageTheme = runCatching {
                EbookPageTheme.valueOf(json.optString("pageTheme", EbookPageTheme.SYSTEM.name))
            }.getOrDefault(EbookPageTheme.SYSTEM),
        )
    }

    fun saveSettings(settings: EbookReaderSettings) {
        val json = JSONObject()
            .put("fontSizeSp", settings.fontSizeSp)
            .put("lineHeight", settings.lineHeight)
            .put("horizontalPaddingDp", settings.horizontalPaddingDp)
            .put("pageTheme", settings.pageTheme.name)
        preferences.edit { putString(KEY_SETTINGS, json.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "ebook_reader"
        const val KEY_HISTORY = "history"
        const val KEY_POSITIONS = "positions"
        const val KEY_SETTINGS = "settings"
    }
}
