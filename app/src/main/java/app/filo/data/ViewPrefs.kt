package app.filo.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** How big items are shown. Pinch out moves up the list (bigger), pinch in moves down (smaller). */
enum class ViewLevel(val columns: Int, val label: String) {
    LIST_LARGE(0, "Large list"),
    LIST(0, "List"),
    GRID_2(2, "Big grid"),
    GRID_3(3, "Grid"),
    GRID_4(4, "Small grid"),
    GRID_5(5, "Tiny grid");

    val isGrid: Boolean get() = columns > 0
    fun bigger(): ViewLevel = entries.getOrElse(ordinal - 1) { this }
    fun smaller(): ViewLevel = entries.getOrElse(ordinal + 1) { this }
}

/** View settings for one place (a folder, a category, Favorites...). */
data class ViewPrefs(val level: ViewLevel = ViewLevel.LIST, val sort: SortOrder = SortOrder())

/** Remembers view settings separately for every folder and list. */
class ViewPrefsStore(private val prefs: SharedPreferences) {
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, ViewPrefs>> = _all.asStateFlow()

    fun get(key: String, default: ViewPrefs): ViewPrefs = _all.value[key] ?: default

    fun set(key: String, value: ViewPrefs) {
        prefs.edit { putString(key, encode(value)) }
        _all.value = _all.value + (key to value)
    }

    private fun load(): Map<String, ViewPrefs> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let(::decode)?.let { key to it } }.toMap()

    companion object {
        fun folderKey(dir: File) = "folder:${dir.absolutePath}"
        fun categoryKey(category: Category) = "category:${category.name}"
        const val FAVORITES = "favorites"
        const val SEARCH = "search"

        private val pictureRoots = setOf("dcim", "pictures", "movies")
        private val pictureNames = setOf("screenshots", "camera")

        /** Photo folders start as a grid with the newest first; everything else as a list by name. */
        fun defaultsForFolder(dir: File, root: File): ViewPrefs {
            val relative = dir.absolutePath.removePrefix(root.absolutePath).trim('/').lowercase()
            val top = relative.substringBefore('/')
            val photos = top in pictureRoots || dir.name.lowercase() in pictureNames
            return if (photos) ViewPrefs(ViewLevel.GRID_3, SortOrder(SortField.DATE, ascending = false)) else ViewPrefs()
        }

        fun defaultsForCategory(category: Category): ViewPrefs = when (category) {
            Category.IMAGES, Category.VIDEOS -> ViewPrefs(ViewLevel.GRID_3, SortOrder(SortField.DATE, ascending = false))
            else -> ViewPrefs(ViewLevel.LIST, SortOrder(SortField.DATE, ascending = false))
        }

        fun encode(p: ViewPrefs) = "${p.level.name}|${p.sort.field.name}|${p.sort.ascending}"

        fun decode(text: String): ViewPrefs? {
            val parts = text.split('|')
            if (parts.size != 3) return null
            val level = ViewLevel.entries.firstOrNull { it.name == parts[0] } ?: return null
            val field = SortField.entries.firstOrNull { it.name == parts[1] } ?: return null
            return ViewPrefs(level, SortOrder(field, parts[2].toBoolean()))
        }
    }
}
