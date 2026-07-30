package SVS.pdfinspector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class AppMode {
    EDIT,
    READ,
}

data class ReaderHistoryEntry(
    val uri: String,
    val title: String,
    val pageIndex: Int,
    val updatedAt: Long,
)

data class ReaderSearchResult(
    val pageIndex: Int,
    val snippet: String,
)

data class ReaderOutlineEntry(
    val title: String,
    val pageIndex: Int,
    val level: Int,
)

data class ReaderPageInfo(
    val pageIndex: Int,
    val widthPoints: Float,
    val heightPoints: Float,
)

internal fun mergeReadingHistory(
    existing: List<ReaderHistoryEntry>,
    latest: ReaderHistoryEntry,
    limit: Int = 20,
): List<ReaderHistoryEntry> =
    buildList {
        add(latest)
        existing.asSequence()
            .filterNot { it.uri == latest.uri }
            .take((limit - 1).coerceAtLeast(0))
            .forEach(::add)
    }

internal fun togglePageBookmark(existing: Set<Int>, pageIndex: Int): Set<Int> =
    if (pageIndex in existing) existing - pageIndex else existing + pageIndex

internal fun searchPageTexts(pageTexts: List<String>, rawQuery: String): List<ReaderSearchResult> {
    val query = rawQuery.trim()
    if (query.isEmpty()) return emptyList()
    return pageTexts.mapIndexedNotNull { pageIndex, text ->
        val match = text.indexOf(query, ignoreCase = true)
        if (match < 0) null else ReaderSearchResult(pageIndex, searchSnippet(text, match, query.length))
    }
}

private fun searchSnippet(text: String, matchStart: Int, matchLength: Int): String {
    val compact = text.replace(Regex("\\s+"), " ").trim()
    val compactMatch = compact.indexOf(
        text.substring(matchStart, (matchStart + matchLength).coerceAtMost(text.length)),
        ignoreCase = true,
    ).coerceAtLeast(0)
    val start = (compactMatch - 28).coerceAtLeast(0)
    val end = (compactMatch + matchLength + 44).coerceAtMost(compact.length)
    return buildString {
        if (start > 0) append("…")
        append(compact.substring(start, end))
        if (end < compact.length) append("…")
    }
}

class ReaderPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadHistory(): List<ReaderHistoryEntry> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ReaderHistoryEntry(
                            uri = item.getString("uri"),
                            title = item.optString("title", "文档.pdf"),
                            pageIndex = item.optInt("pageIndex", 0).coerceAtLeast(0),
                            updatedAt = item.optLong("updatedAt", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(entry: ReaderHistoryEntry) {
        val merged = mergeReadingHistory(loadHistory(), entry)
        val array = JSONArray()
        merged.forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("title", item.title)
                    .put("pageIndex", item.pageIndex)
                    .put("updatedAt", item.updatedAt),
            )
        }
        preferences.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun loadBookmarks(documentUri: String): Set<Int> {
        val root = bookmarkRoot()
        val array = root.optJSONArray(documentUri) ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) add(array.optInt(index, -1))
        }.filterTo(sortedSetOf()) { it >= 0 }
    }

    fun saveBookmarks(documentUri: String, pages: Set<Int>) {
        val root = bookmarkRoot()
        val array = JSONArray()
        pages.sorted().forEach(array::put)
        root.put(documentUri, array)
        preferences.edit().putString(KEY_BOOKMARKS, root.toString()).apply()
    }

    private fun bookmarkRoot(): JSONObject = runCatching {
        JSONObject(preferences.getString(KEY_BOOKMARKS, null) ?: "{}")
    }.getOrElse { JSONObject() }

    private companion object {
        const val PREFS_NAME = "reader_library"
        const val KEY_HISTORY = "history"
        const val KEY_BOOKMARKS = "bookmarks"
    }
}
