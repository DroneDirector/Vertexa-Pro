package com.dronedirector.vertexapro.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import com.dronedirector.vertexapro.data.Formats
import com.dronedirector.vertexapro.data.ModelSource
import java.io.File

/** Saves converted models (OBJ + MTL + textures, or STL) and archive contents into a folder the user picked. */
object Exporter {
    const val OBJ = 0
    const val STL = 1

    private fun parentDoc(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun create(ctx: Context, parent: Uri, name: String, mime: String = "application/octet-stream"): Uri =
        DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name)
            ?: error("Couldn't create $name in that folder.")

    private fun nameOf(ctx: Context, uri: Uri, fallback: String) = SiblingResolver.displayName(ctx, uri) ?: fallback

    private fun safeBase(name: String) =
        name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "model" }.take(80)

    fun export(
        ctx: Context,
        handle: Long,
        tree: Uri,
        src: ModelSource,
        resolver: SiblingResolver,
        format: Int,
        hidden: BooleanArray,
        materialTextures: Array<String>,
        split: Boolean,
    ): String {
        val cr = ctx.contentResolver
        val parent = parentDoc(tree)
        val base = safeBase(src.name)
        val ext = if (format == OBJ) "obj" else "stl"
        val created = mutableListOf<Uri>()
        try {
            val outUri = create(ctx, parent, "$base.$ext").also { created += it }
            val outFd = cr.openFileDescriptor(outUri, "rw")?.detachFd() ?: error("Couldn't open the output file.")
            var mtlFd = -1
            var mtlName = ""
            val texNames = Array(materialTextures.size) { "" }
            var texCount = 0
            if (format == OBJ) {
                val mtlUri = create(ctx, parent, "$base.mtl").also { created += it }
                mtlName = nameOf(ctx, mtlUri, "$base.mtl")
                mtlFd = cr.openFileDescriptor(mtlUri, "rw")?.detachFd() ?: -1
                val written = HashMap<String, String>()
                materialTextures.forEachIndexed { i, t ->
                    if (t.isEmpty()) return@forEachIndexed
                    val fileName = written.getOrPut(t) {
                        runCatching { copyTexture(ctx, handle, parent, t, resolver, created) }.getOrNull().orEmpty()
                    }
                    texNames[i] = fileName
                }
                texCount = written.values.count { it.isNotEmpty() }
            }
            val needsSeek = Formats.ext(src.name) !in Formats.native
            val inFd = SiblingResolver.openSeekableFd(ctx, src.uri, needsSeek)
            if (inFd < 0) error("Couldn't read the model file again.")
            val err = NativeBridge.nativeExport(
                handle, inFd, src.size, src.name, resolver, format, outFd, mtlFd, mtlName, hidden, texNames, split,
            )
            if (err.isNotEmpty()) error(if (err == "cancelled") "Export cancelled." else err)
            val outName = nameOf(ctx, outUri, "$base.$ext")
            return if (format == OBJ) "Saved $outName with its material file" +
                (if (texCount > 0) " and $texCount texture${if (texCount > 1) "s" else ""}" else "") + "."
            else "Saved $outName."
        } catch (e: Exception) {
            created.forEach { runCatching { DocumentsContract.deleteDocument(cr, it) } }
            throw e
        }
    }

    /** Copies one texture next to the exported OBJ; returns the file name used in the MTL. */
    private fun copyTexture(
        ctx: Context, handle: Long, parent: Uri, name: String, resolver: SiblingResolver, created: MutableList<Uri>,
    ): String? {
        val cr = ctx.contentResolver
        if (name.startsWith("*")) {
            val idx = name.substring(1).toIntOrNull() ?: return null
            val size = NativeBridge.nativeEmbeddedSize(handle, idx)
            val data = NativeBridge.nativeEmbeddedData(handle, idx) ?: return null
            if (size[0] > 0 && size[1] > 0) {
                val bmp = TextureDecoder.fromRgba(size[0], size[1], data, 16384)
                val u = create(ctx, parent, "texture_$idx.png", "image/png").also { created += it }
                cr.openOutputStream(u)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return nameOf(ctx, u, "texture_$idx.png")
            }
            val isPng = data.size > 4 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte()
            val ext = if (isPng) "png" else "jpg"
            val u = create(ctx, parent, "texture_$idx.$ext", if (isPng) "image/png" else "image/jpeg").also { created += it }
            cr.openOutputStream(u)?.use { it.write(data) }
            return nameOf(ctx, u, "texture_$idx.$ext")
        }
        val srcUri = resolver.findUri(name) ?: return null
        val file = name.replace('\\', '/').substringAfterLast('/')
        val u = create(ctx, parent, file).also { created += it }
        cr.openOutputStream(u)?.use { o -> cr.openInputStream(srcUri)?.use { it.copyTo(o, 1 shl 20) } }
        return nameOf(ctx, u, file)
    }

    /** Copies everything that came out of an archive into the chosen folder, keeping sub-folders. */
    fun extract(ctx: Context, loaded: ModelSource, tree: Uri, onProgress: (Float) -> Unit): Int {
        val path = loaded.uri.path ?: error("Nothing to extract.")
        var root = File(path).parentFile
        while (root != null && root.parentFile?.name != "unzipped") root = root.parentFile
        if (root == null) error("This model didn't come from an archive.")
        val files = root.walkTopDown().filter { it.isFile && it.name != ".done" }.toList()
        val cr = ctx.contentResolver
        val dirs = HashMap<String, Uri>()
        dirs[""] = parentDoc(tree)
        fun dirFor(rel: String): Uri {
            dirs[rel]?.let { return it }
            val parent = dirFor(rel.substringBeforeLast('/', ""))
            val made = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, rel.substringAfterLast('/'))
                ?: error("Couldn't create the folder ${rel.substringAfterLast('/')}.")
            dirs[rel] = made
            return made
        }
        files.forEachIndexed { i, f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            val dir = dirFor(rel.substringBeforeLast('/', ""))
            val u = create(ctx, dir, f.name)
            cr.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 20) } }
            onProgress((i + 1f) / files.size)
        }
        return files.size
    }
}
