package com.dronedirector.vertexapro.generator

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Hands a freshly generated model to the viewer, which then scales it and cuts its control surfaces. */
object GeneratorHandoff {
    @Volatile private var pending: Pair<Uri, Int>? = null
    fun set(uri: Uri, profile: Int) { pending = uri to profile }
    fun take(uri: Uri): Int? {
        val p = pending ?: return null
        if (p.first != uri) return null
        pending = null
        return p.second
    }
}

/**
 * Text-to-3D with Meshy (api.meshy.ai): a preview mesh, then the textured refine, downloaded as GLB.
 * Runs on its own scope so it keeps going while you move around the app.
 */
object MeshyJob {
    sealed interface State {
        data object Idle : State
        data class Running(val stage: String, val progress: Float) : State
        data class Done(val file: File, val profile: Int) : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private const val BASE = "https://api.meshy.ai/openapi/v2/text-to-3d"

    fun cancel() { job?.cancel(); state.value = State.Idle }

    fun start(dir: File, key: String, prompt: String, texture: String, name: String, profile: Int) {
        job?.cancel()
        state.value = State.Running("Sending to the AI…", 0f)
        job = scope.launch {
            try {
                val preview = create(key, JSONObject().apply {
                    put("mode", "preview")
                    put("prompt", prompt.take(800))
                    put("ai_model", "latest")
                    put("should_remesh", true)
                    put("topology", "triangle")
                    put("target_polycount", 300000)
                    put("geometry_resolution", "2k")
                    put("target_formats", JSONArray(listOf("glb")))
                })
                waitFor(key, preview, "Shaping the aircraft", 0f, 0.5f)
                val refine = create(key, JSONObject().apply {
                    put("mode", "refine")
                    put("preview_task_id", preview)
                    put("enable_pbr", true)
                    put("texture_prompt", texture.take(800))
                    put("texture_resolution", "4k")
                    put("target_formats", JSONArray(listOf("glb")))
                })
                val done = waitFor(key, refine, "Painting textures", 0.5f, 0.95f)
                val url = done.optJSONObject("model_urls")?.optString("glb").orEmpty()
                if (url.isEmpty()) error("The AI didn't return a model.")
                state.value = State.Running("Downloading", 0.96f)
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                val file = File(dir, name.replace(Regex("[^A-Za-z0-9]+"), "_") + "_AI_" + stamp + ".glb")
                download(url, file)
                state.value = State.Done(file, profile)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = State.Failed(e.message ?: "Something went wrong.")
            }
        }
    }

    private fun conn(url: String, key: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }

    private fun create(key: String, body: JSONObject): String {
        val c = conn(BASE, key, "POST")
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        when (code) {
            in 200..299 -> {}
            401 -> error("The Meshy API key isn't valid.")
            402 -> error("Not enough Meshy credits (or the plan can't use the API).")
            429 -> error("Meshy is busy — too many requests. Try again in a minute.")
            else -> error("Meshy error $code: ${runCatching { JSONObject(text).optString("message") }.getOrNull() ?: text.take(200)}")
        }
        return JSONObject(text).getString("result")
    }

    private suspend fun waitFor(key: String, id: String, label: String, from: Float, to: Float): JSONObject {
        while (scope.isActive) {
            val c = conn("$BASE/$id", key, "GET")
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            c.disconnect()
            if (code !in 200..299) error("Meshy error $code while waiting.")
            val o = JSONObject(text)
            val pr = o.optInt("progress", 0) / 100f
            when (o.optString("status")) {
                "SUCCEEDED" -> return o
                "FAILED", "CANCELED" -> error(o.optJSONObject("task_error")?.optString("message")?.ifBlank { null } ?: "The AI couldn't make this model.")
                "PENDING" -> state.value = State.Running("$label (waiting in line)", from)
                else -> state.value = State.Running(label, from + (to - from) * pr)
            }
            delay(4000)
        }
        error("Cancelled")
    }

    private fun download(url: String, file: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 120_000
        val tmp = File(file.path + ".part")
        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
        c.disconnect()
        if (!tmp.renameTo(file)) error("Couldn't save the model.")
    }
}
