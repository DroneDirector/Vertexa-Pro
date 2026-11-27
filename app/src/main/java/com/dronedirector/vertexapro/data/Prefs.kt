package com.dronedirector.vertexapro.data

import android.content.Context
import android.net.Uri
import com.dronedirector.vertexapro.ui.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object Formats {
    val native = setOf("obj", "stl", "ply")
    val assimp = setOf(
        "fbx", "gltf", "glb", "dae", "3ds", "3mf", "dxf", "x", "off", "lwo", "lws",
        "blend", "ac", "ms3d",
    )
    val all = native + assimp
    val chips = listOf("OBJ", "FBX", "GLTF", "GLB", "STL", "PLY", "DAE", "3DS", "3MF", "BLEND", "DXF", "OFF", "LWO", "X")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun isModel(name: String) = ext(name) in all
    /** STL / PLY / 3MF are Z-up by convention (3D printing, scanners). */
    fun defaultZUp(name: String) = ext(name) in setOf("stl", "ply", "3mf")
}

data class ModelSource(
    val uri: Uri,
    val name: String,
    val size: Long,
    val extras: List<Uri> = emptyList(),
    val tree: Uri? = null,
    val relPath: String? = null,
)

enum class SortKey(val label: String) { NAME("Name"), DATE("Date"), SIZE("Size"), TYPE("Type") }

/** A model shown in the browser. */
data class BrowseItem(
    val uri: Uri,
    val name: String,
    val relPath: String,
    val size: Long,
    val modified: Long,
    val tree: Uri? = null,
    val extras: List<Uri> = emptyList(),
) {
    val ext get() = Formats.ext(name)
    fun toSource() = ModelSource(uri, name, size, extras, tree, if (tree != null) relPath else null)
}

fun sortItems(items: List<BrowseItem>, key: SortKey, asc: Boolean): List<BrowseItem> {
    val byName = compareBy<BrowseItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    val cmp: Comparator<BrowseItem> = when (key) {
        SortKey.NAME -> byName
        SortKey.DATE -> compareBy<BrowseItem> { it.modified }.then(byName)
        SortKey.SIZE -> compareBy<BrowseItem> { it.size }.then(byName)
        SortKey.TYPE -> compareBy<BrowseItem> { it.ext }.then(byName)
    }
    return items.sortedWith(if (asc) cmp else cmp.reversed())
}

data class SavedFolder(val uri: String, val name: String, val time: Long)

data class RecentModel(
    val uri: String,
    val name: String,
    val size: Long,
    val time: Long,
    val extras: List<String>,
    val tree: String?,
    val relPath: String?,
) {
    fun toSource() = ModelSource(
        Uri.parse(uri), name, size, extras.map(Uri::parse), tree?.let(Uri::parse), relPath,
    )
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("vertexa", Context.MODE_PRIVATE)

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(sp.getString("theme", "DARK")!!) }.getOrDefault(ThemeMode.DARK)
        set(v) = sp.edit().putString("theme", v.name).apply()

    var autoCheckUpdates: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()

    var viewMode: Int
        get() = sp.getInt("viewMode", 0)
        set(v) = sp.edit().putInt("viewMode", v).apply()

    var showGrid: Boolean
        get() = sp.getBoolean("grid", true)
        set(v) = sp.edit().putBoolean("grid", v).apply()

    var sortKey: SortKey
        get() = runCatching { SortKey.valueOf(sp.getString("sortKey", "DATE")!!) }.getOrDefault(SortKey.DATE)
        set(v) = sp.edit().putString("sortKey", v.name).apply()

    var sortAsc: Boolean
        get() = sp.getBoolean("sortAsc", false)
        set(v) = sp.edit().putBoolean("sortAsc", v).apply()

    var browserGrid: Boolean
        get() = sp.getBoolean("browserGrid", true)
        set(v) = sp.edit().putBoolean("browserGrid", v).apply()

    fun folders(): List<SavedFolder> {
        val arr = runCatching { JSONArray(sp.getString("folders", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                SavedFolder(o.getString("uri"), o.getString("name"), o.optLong("time"))
            }.getOrNull()
        }
    }

    fun addFolder(uri: String, name: String) {
        val list = folders().filterNot { it.uri == uri }.toMutableList()
        list.add(0, SavedFolder(uri, name, System.currentTimeMillis()))
        saveFolders(list.take(20))
    }

    fun removeFolder(uri: String) = saveFolders(folders().filterNot { it.uri == uri })

    private fun saveFolders(list: List<SavedFolder>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.time)) }
        sp.edit().putString("folders", arr.toString()).apply()
    }

    fun recents(): List<RecentModel> {
        val arr = runCatching { JSONArray(sp.getString("recents", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val ex = o.optJSONArray("extras") ?: JSONArray()
                RecentModel(
                    uri = o.getString("uri"),
                    name = o.getString("name"),
                    size = o.optLong("size", -1),
                    time = o.optLong("time", 0),
                    extras = (0 until ex.length()).map { ex.getString(it) },
                    tree = o.optString("tree").ifEmpty { null },
                    relPath = o.optString("rel").ifEmpty { null },
                )
            }.getOrNull()
        }
    }

    fun addRecent(src: ModelSource) {
        val list = recents().filterNot { it.uri == src.uri.toString() }.toMutableList()
        list.add(
            0,
            RecentModel(
                src.uri.toString(), src.name, src.size, System.currentTimeMillis(),
                src.extras.map { it.toString() }, src.tree?.toString(), src.relPath,
            ),
        )
        save(list.take(30))
    }

    fun removeRecent(uri: String) = save(recents().filterNot { it.uri == uri })

    private fun save(list: List<RecentModel>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("uri", r.uri).put("name", r.name).put("size", r.size).put("time", r.time)
                    .put("extras", JSONArray(r.extras)).put("tree", r.tree ?: "").put("rel", r.relPath ?: ""),
            )
        }
        sp.edit().putString("recents", arr.toString()).apply()
    }
}

fun formatBytes(b: Long): String {
    if (b < 0) return "—"
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun formatCount(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", n / 1e9)
    n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1e6)
    n >= 10_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
    else -> n.toString()
}
