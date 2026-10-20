package app.filo.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ViewMode { LIST, GRID }

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

data class Settings(
    val showHidden: Boolean = false,
    val viewMode: ViewMode = ViewMode.LIST,
    val sort: SortOrder = SortOrder(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** How long the hidden folder stays open while you're in another app. */
    val autoLockSeconds: Int = 5,
    val showSystemApps: Boolean = false,
    /** App + data + cache sizes; needs Usage access, which you grant yourself in Android settings. */
    val fullAppSizes: Boolean = false,
)

class SettingsStore(private val prefs: SharedPreferences) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        prefs.edit {
            putBoolean("showHidden", next.showHidden)
            putString("viewMode", next.viewMode.name)
            putString("sortField", next.sort.field.name)
            putBoolean("sortAscending", next.sort.ascending)
            putString("themeMode", next.themeMode.name)
            putBoolean("dynamicColor", next.dynamicColor)
            putInt("autoLockSeconds", next.autoLockSeconds)
            putBoolean("showSystemApps", next.showSystemApps)
            putBoolean("fullAppSizes", next.fullAppSizes)
        }
        _state.value = next
    }

    private fun read(): Settings {
        val defaults = Settings()
        return Settings(
            showHidden = prefs.getBoolean("showHidden", defaults.showHidden),
            viewMode = enumOr(prefs.getString("viewMode", null), defaults.viewMode),
            sort = SortOrder(
                field = enumOr(prefs.getString("sortField", null), defaults.sort.field),
                ascending = prefs.getBoolean("sortAscending", defaults.sort.ascending),
            ),
            themeMode = enumOr(prefs.getString("themeMode", null), defaults.themeMode),
            dynamicColor = prefs.getBoolean("dynamicColor", defaults.dynamicColor),
            autoLockSeconds = prefs.getInt("autoLockSeconds", defaults.autoLockSeconds),
            showSystemApps = prefs.getBoolean("showSystemApps", defaults.showSystemApps),
            fullAppSizes = prefs.getBoolean("fullAppSizes", defaults.fullAppSizes),
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}

class FavoritesStore(private val prefs: SharedPreferences) {
    private val _paths = MutableStateFlow(prefs.getStringSet("favorites", emptySet())!!.toSet())
    val paths: StateFlow<Set<String>> = _paths.asStateFlow()

    fun isFavorite(path: String) = path in _paths.value

    fun toggle(paths: Collection<String>) {
        val current = _paths.value
        val allFavorite = paths.all { it in current }
        save(if (allFavorite) current - paths.toSet() else current + paths)
    }

    /** Keeps favorites pointing at the right place after a rename or move. */
    fun replace(oldPath: String, newPath: String) {
        val current = _paths.value
        if (oldPath in current) save(current - oldPath + newPath)
    }

    fun remove(paths: Collection<String>) = save(_paths.value - paths.toSet())

    private fun save(set: Set<String>) {
        prefs.edit { putStringSet("favorites", set) }
        _paths.value = set
    }
}
