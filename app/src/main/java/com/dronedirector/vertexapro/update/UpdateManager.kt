package com.dronedirector.vertexapro.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.dronedirector.vertexapro.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
)

/** Checks GitHub releases, downloads the APK and hands it to the system installer. */
object UpdateManager {
    const val REPO = "DroneDirector/Vertexa-Pro"

    /** Install result messages from [InstallReceiver] (null = none). */
    val installStatus = MutableStateFlow<String?>(null)

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    private fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
        .split('.', '-', '_').mapNotNull { it.toIntOrNull() }

    fun isNewer(remote: String, local: String = currentVersion()): Boolean {
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Returns the latest release if newer than the installed version, else null. Throws on network errors. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VertexaPro/${currentVersion()}")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode == 404) error("no releases are visible — the GitHub repository may be private")
            if (conn.responseCode !in 200..299) error("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets")
            var url = ""
            var size = -1L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", true)) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size", -1)
                        break
                    }
                }
            }
            if (url.isEmpty() || !isNewer(tag)) null
            else Release(tag.removePrefix("v"), json.optString("body").trim(), url, size)
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK, reporting (fraction, bytesDone, bytesPerSecond). */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Float, Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "VertexaPro-${release.version}.apk")
        var url = URL(release.apkUrl)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "VertexaPro/${currentVersion()}")
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 6) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                redirects++
                continue
            }
            if (code !in 200..299) error("Download failed ($code)")
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
        var done = 0L
        val start = System.nanoTime()
        var lastReport = 0L
        conn.inputStream.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    val now = System.nanoTime()
                    if (now - lastReport > 50_000_000L) {
                        lastReport = now
                        val secs = (now - start) / 1e9
                        val speed = if (secs > 0) (done / secs).toLong() else 0L
                        onProgress(if (total > 0) done.toFloat() / total else 0f, done, speed)
                    }
                }
            }
        }
        conn.disconnect()
        onProgress(1f, done, 0)
        out
    }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { inp ->
                session.openWrite("base.apk", 0, apk.length()).use { o ->
                    inp.copyTo(o, 1 shl 16)
                    session.fsync(o)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pi.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.installStatus.value = "ok"
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateManager.installStatus.value = "aborted"
            else -> UpdateManager.installStatus.value =
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update could not be installed."
        }
    }
}
