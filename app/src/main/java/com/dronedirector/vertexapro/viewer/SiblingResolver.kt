package com.dronedirector.vertexapro.viewer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/**
 * Finds files that a model refers to (MTL, textures, .bin buffers) among the files the
 * user picked together with the model, or inside a folder the user linked.
 * [open] is called from native code.
 */
class SiblingResolver(
    private val context: Context,
    extras: List<Uri>,
    tree: Uri?,
    private val modelDir: String?,
) {
    private val byPath = HashMap<String, Uri>()
    private val byName = HashMap<String, Uri>()
    private val treeIndexed = HashSet<String>()
    private var trees = mutableListOf<Uri>()

    init {
        for (u in extras) {
            val n = displayName(context, u) ?: continue
            byName.putIfAbsent(n.lowercase(Locale.ROOT), u)
        }
        tree?.let { trees.add(it) }
    }

    @Synchronized
    fun addTree(tree: Uri) {
        trees.add(0, tree)
    }

    private fun indexTrees() {
        for (t in trees) {
            val key = t.toString()
            if (!treeIndexed.add(key)) continue
            runCatching { indexTree(t) }
        }
    }

    private fun indexTree(tree: Uri) {
        val cr = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        data class Dir(val id: String, val path: String, val depth: Int)
        val queue = ArrayDeque<Dir>()
        queue.add(Dir(rootId, "", 0))
        var count = 0
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        while (queue.isNotEmpty() && count < 50_000) {
            val d = queue.removeFirst()
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, d.id)
            cr.query(childUri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    count++
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val rel = if (d.path.isEmpty()) name else "${d.path}/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (d.depth < 5) queue.add(Dir(id, rel, d.depth + 1))
                    } else {
                        val u = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        byPath.putIfAbsent(rel.lowercase(Locale.ROOT), u)
                        byName.putIfAbsent(name.lowercase(Locale.ROOT), u)
                    }
                }
            }
        }
    }

    @Synchronized
    fun findUri(rawName: String): Uri? {
        indexTrees()
        var n = rawName.replace('\\', '/').trim().trim('"')
        n = n.replace(Regex("^[A-Za-z]:/"), "")
        while (n.startsWith("./")) n = n.substring(2)
        n = n.trimStart('/')
        val lower = n.lowercase(Locale.ROOT)
        val dir = modelDir?.lowercase(Locale.ROOT)?.trim('/')
        if (!dir.isNullOrEmpty()) byPath["$dir/$lower"]?.let { return it }
        byPath[lower]?.let { return it }
        byPath.entries.firstOrNull { it.key.endsWith("/$lower") }?.let { return it.value }
        val base = lower.substringAfterLast('/')
        return byName[base]
    }

    /** Called from native code. Returns a detached fd owned by the caller, or -1. */
    fun open(name: String): Int {
        val u = findUri(name) ?: return -1
        return openSeekableFd(context, u)
    }

    companion object {
        fun displayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

        fun size(context: Context, uri: Uri): Long = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L

        fun modified(context: Context, uri: Uri): Long = runCatching {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            }
        }.getOrNull() ?: 0L

        /** Opens a file descriptor; content that can't be seeked (cloud streams) is copied to cache first. */
        fun openSeekableFd(context: Context, uri: Uri, requireSeek: Boolean = true): Int = runCatching {
            val cr = context.contentResolver
            val pfd = cr.openFileDescriptor(uri, "r") ?: return -1
            if (pfd.statSize >= 0 || !requireSeek) return pfd.detachFd()
            pfd.close()
            val dir = File(context.cacheDir, "import").apply { mkdirs() }
            val f = File(dir, "s_" + uri.toString().hashCode().toUInt().toString(16))
            cr.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it, 1 shl 20) } }
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        }.getOrDefault(-1)
    }
}
