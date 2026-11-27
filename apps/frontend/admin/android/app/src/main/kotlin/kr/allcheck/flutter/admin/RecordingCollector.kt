package kr.allcheck.flutter.admin

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** SAF references only: original audio is never deleted or copied to public storage. */
class RecordingCollector(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("allcheck_recording_collection", Context.MODE_PRIVATE)
    private val folderPrefs get() = context.getSharedPreferences("allcheck_flutter_folder", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    data class Source(val documentId: String, val name: String, val size: Long, val modified: Long)

    fun configure(enabled: Boolean, includeExisting: Boolean) = synchronized(lock) {
        if (enabled && !prefs.getBoolean("enabled", false)) {
            val tree = tree()
            val baseline = JSONArray()
            if (!includeExisting) for (source in sources(tree)) baseline.put(identity(tree, source))
            prefs.edit().putString("baseline", baseline.toString()).putString("observed", "{}")
                .putBoolean("enabled", true).putString("error", "").commit()
        } else if (!enabled) prefs.edit().putBoolean("enabled", false).commit()
        val manager = WorkManager.getInstance(context)
        if (enabled) {
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RecordingScanWorker>(15, TimeUnit.MINUTES).build())
            manager.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RecordingScanWorker>()
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS).build())
        } else {
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(IMMEDIATE)
        }
    }

    /** Returns true if another stability observation is needed. */
    fun scan(now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (!prefs.getBoolean("enabled", false)) return false
        try {
            val tree = tree()
            val queue = JSONObject(prefs.getString("queue", "{}")!!)
            val observed = JSONObject(prefs.getString("observed", "{}")!!)
            val nextObserved = JSONObject()
            val baselineArray = JSONArray(prefs.getString("baseline", "[]")!!)
            val baseline = (0 until baselineArray.length()).map { baselineArray.getString(it) }.toSet()
            var waiting = false
            for (source in sources(tree)) {
                val id = identity(tree, source)
                if (id in baseline || queue.has(id) || source.size <= 12) continue
                val first = observed.optLong(id, now)
                if (now - first < STABLE_MS || (source.modified > 0 && now - source.modified < STABLE_MS)) {
                    nextObserved.put(id, first)
                    waiting = true
                    continue
                }
                if (!waveIsClosed(tree, source)) { nextObserved.put(id, first); waiting = true; continue }
                queue.put(id, JSONObject().put("sourceId", id).put("documentId", source.documentId)
                    .put("tree", tree.toString()).put("name", source.name).put("sizeBytes", source.size)
                    .put("modifiedAt", source.modified).put("collectedAt", now).put("uploaded", false))
            }
            prefs.edit().putString("observed", nextObserved.toString()).putString("queue", queue.toString())
                .putString("error", "").commit()
            waiting
        } catch (_: SecurityException) {
            prefs.edit().putString("error", "녹음 폴더 읽기 권한이 없습니다. 폴더를 다시 연결해 주세요.").commit()
            false
        } catch (_: Exception) {
            prefs.edit().putString("error", "녹음 폴더를 확인하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.").commit()
            false
        }
    }

    fun status(): Map<String, Any> = synchronized(lock) {
        val queue = JSONObject(prefs.getString("queue", "{}")!!)
        val items = queue.keys().asSequence().map { key ->
            val row = queue.getJSONObject(key)
            mapOf<String, Any>("sourceId" to key, "name" to row.getString("name"),
                "sizeBytes" to row.getLong("sizeBytes"), "collectedAt" to row.getLong("collectedAt"),
                "uploaded" to row.optBoolean("uploaded"))
        }.sortedByDescending { it["collectedAt"] as Long }.toList()
        mapOf("enabled" to prefs.getBoolean("enabled", false), "error" to prefs.getString("error", "")!!, "items" to items)
    }

    fun read(sourceId: String): ByteArray = synchronized(lock) {
        val row = JSONObject(prefs.getString("queue", "{}")!!).getJSONObject(sourceId)
        val tree = tree()
        check(row.getString("tree") == tree.toString()) { "Folder changed" }
        val source = sources(tree).firstOrNull { identity(tree, it) == sourceId } ?: error("Original changed or removed")
        require(source.size <= MAX_BYTES) { "Recording exceeds local memory limit" }
        check(waveIsClosed(tree, source))
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, source.documentId)
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                count += length
                check(count <= MAX_BYTES && count <= source.size)
                output.write(buffer, 0, length)
            }
            output.toByteArray()
        } ?: error("Cannot read recording")
        check(bytes.size.toLong() == source.size)
        check(sources(tree).any { identity(tree, it) == sourceId }) { "Recording changed during read" }
        bytes
    }

    fun complete(sourceId: String) = synchronized(lock) {
        val queue = JSONObject(prefs.getString("queue", "{}")!!)
        queue.getJSONObject(sourceId).put("uploaded", true)
        prefs.edit().putString("queue", queue.toString()).commit()
    }

    private fun tree(): Uri {
        val raw = folderPrefs.getString("folder", null) ?: throw SecurityException()
        val uri = Uri.parse(raw)
        if (uri.authority != "com.android.externalstorage.documents" ||
            DocumentsContract.getTreeDocumentId(uri) != "primary:Music/Softphone/recordings" ||
            resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) throw SecurityException()
        return uri
    }

    private fun sources(tree: Uri): List<Source> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val fields = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return resolver.query(uri, fields, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (name.endsWith(".wav", true)) add(Source(cursor.getString(0), name, cursor.getLong(2), cursor.getLong(3)))
                }
            }
        } ?: error("Folder query failed")
    }

    private fun waveIsClosed(tree: Uri, source: Source): Boolean {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, source.documentId)
        return resolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(12)
            var offset = 0
            while (offset < 12) { val length = input.read(header, offset, 12 - offset); if (length < 0) return@use false; offset += length }
            RecordingFileRules.closedWave(header, source.size)
        } ?: false
    }

    private fun identity(tree: Uri, source: Source): String = MessageDigest.getInstance("SHA-256")
        .digest("$tree|${source.documentId}|${source.size}|${source.modified}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val lock = Any()
        const val STABLE_MS = 10_000L
        const val MAX_BYTES = 64L * 1024 * 1024
        private const val PERIODIC = "allcheck-recordings-periodic"
        private const val IMMEDIATE = "allcheck-recordings-immediate"
    }
}

object RecordingFileRules {
    fun closedWave(header: ByteArray, size: Long): Boolean {
        if (header.size != 12 || size <= 12 || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return false
        val kind = String(header, 0, 4, Charsets.US_ASCII)
        if (kind != "RIFF" && kind != "RIFX") return false
        val indices = if (kind == "RIFF") listOf(4, 5, 6, 7) else listOf(7, 6, 5, 4)
        val declared = indices.withIndex().sumOf { (shift, index) -> (header[index].toLong() and 255L) shl (shift * 8) }
        return declared + 8L == size
    }
}
