package com.dronedirector.vertexapro.viewer

import android.content.Context
import android.net.Uri
import com.dronedirector.vertexapro.data.Formats
import com.dronedirector.vertexapro.data.ModelSource
import java.io.File
import java.io.FilterInputStream
import java.util.zip.ZipInputStream

/**
 * Gets a model ready to load. Archives (e.g. Meshy / Sketchfab downloads) are detected by their
 * contents, not their name — a ZIP renamed to .obj still works — and unpacked to the cache.
 */
object ModelPrep {
    class Prepared(val source: ModelSource, val notes: List<ReportLine>)

    private val modelPriority = listOf("glb", "gltf", "fbx", "obj", "dae", "3mf", "blend", "3ds", "stl", "ply")

    fun isZip(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(4)
            var n = 0
            while (n < 4) {
                val r = s.read(b, n, 4 - n)
                if (r <= 0) break
                n += r
            }
            n == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4
        } ?: false
    }.getOrDefault(false)

    /** Returns the source to load, unpacking archives. [onProgress] gets 0..1 while unpacking. */
    fun prepare(context: Context, src: ModelSource, onProgress: (Float) -> Unit = {}): Prepared {
        if (!isZip(context, src.uri)) return Prepared(src, emptyList())
        val notes = mutableListOf<ReportLine>()
        notes += ReportLine(if (Formats.ext(src.name) == "zip") 0 else 1,
            if (Formats.ext(src.name) == "zip") "The file is a ZIP archive; unpacking it."
            else "The file is named .${Formats.ext(src.name)} but is really a ZIP archive (typical for Meshy downloads); unpacking it.")

        val root = File(context.cacheDir, "unzipped").apply { mkdirs() }
        val dir = File(root, ThumbnailStore.key(src.uri).take(20))
        val done = File(dir, ".done")
        if (!done.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
            val total = src.size
            var read = 0L
            val counting = object : FilterInputStream(context.contentResolver.openInputStream(src.uri) ?: error("Can't open the archive")) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                    return n
                }
            }
            val canonRoot = dir.canonicalPath + File.separator
            ZipInputStream(counting.buffered(1 shl 20)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val out = File(dir, e.name.replace('\\', '/'))
                    if (!out.canonicalPath.startsWith(canonRoot)) continue // ignore paths escaping the folder
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it, 1 shl 20) }
                }
            }
            done.writeText("1")
            cleanup(root, keep = dir)
        }
        val files = dir.walkTopDown().filter { it.isFile && it.name != ".done" }.toList()
        notes += ReportLine(0, "Archive contains ${files.size} file(s): " +
            files.take(8).joinToString { it.relativeTo(dir).path } + if (files.size > 8) ", …" else "")
        val models = files.filter { Formats.isModel(it.name) && Formats.ext(it.name) != "zip" }
        if (models.isEmpty()) error("The archive doesn't contain a supported 3D model.")
        val pick = models.sortedWith(
            compareBy<File> { modelPriority.indexOf(Formats.ext(it.name)).let { i -> if (i < 0) 99 else i } }
                .thenByDescending { it.length() },
        ).first()
        if (models.size > 1) {
            notes += ReportLine(0, "Several models in the archive; opening ${pick.name} (others: " +
                models.filter { it != pick }.take(5).joinToString { it.name } + ")")
        } else notes += ReportLine(0, "Opening ${pick.relativeTo(dir).path} from the archive")
        val extras = files.filter { it != pick }.map { Uri.fromFile(it) }
        return Prepared(
            ModelSource(Uri.fromFile(pick), pick.name, pick.length(), extras = extras),
            notes,
        )
    }

    /** Keeps only the three most recently used unpacked archives. */
    private fun cleanup(root: File, keep: File) {
        root.listFiles()?.filter { it.isDirectory && it != keep }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(2)
            ?.forEach { it.deleteRecursively() }
    }
}
