package cl.relojnoche

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Comprueba si hay una versión nueva publicada en GitHub Releases y permite
 * descargar e instalar el APK desde la propia app.
 *
 * El versionName de la app es "1.0.<run_number>" y el tag del Release es
 * "v1.0.<run_number>" (los pone el workflow con github.run_number), así que
 * comparar quitando la "v" es directo y consistente.
 *
 * Android NO instala solo: descargamos el APK y lanzamos el instalador del
 * sistema, que pide confirmación. Requiere REQUEST_INSTALL_PACKAGES y, la 1a
 * vez, que el usuario permita "instalar apps de origen desconocido".
 */
object UpdateChecker {

    private const val OWNER = "totoNacho"
    private const val REPO = "reloj.noche"
    private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,   // ej. "1.0.12"
        val apkUrl: String,        // URL directa al APK
        val notes: String,         // descripción del Release
    )

    /**
     * Consulta el último Release. Devuelve UpdateInfo solo si es MÁS NUEVO que
     * [currentVersion]; null si ya estás al día o si falla la consulta.
     * Llamar SIEMPRE fuera del hilo principal (hace red).
     */
    fun checkForUpdate(currentVersion: String): UpdateInfo? {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())

            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isEmpty()) return null

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl == null) return null
            if (!isNewer(tag, currentVersion)) return null

            return UpdateInfo(versionName = tag, apkUrl = apkUrl, notes = json.optString("body"))
        }
    }

    /** true si [candidate] (p. ej. "1.0.12") es estrictamente mayor que [current]. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val cur = current.split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(c.size, cur.size)
        for (i in 0 until n) {
            val a = c.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Descarga el APK a la cache y lanza el instalador del sistema.
     * [onProgress] reporta 0..100. Lanza excepción si algo falla.
     * Llamar fuera del hilo principal.
     */
    fun downloadAndInstall(context: Context, apkUrl: String, onProgress: (Int) -> Unit) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apkFile = File(dir, "relojnoche-update.apk")

        val req = Request.Builder().url(apkUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("No se pudo descargar la actualización (HTTP ${resp.code})")
            }
            val body = resp.body ?: throw RuntimeException("Descarga vacía")
            val total = body.contentLength()
            var written = 0L
            apkFile.outputStream().use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    written += read
                    if (total > 0) onProgress((written * 100 / total).toInt())
                }
            }
        }

        val apkUri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
