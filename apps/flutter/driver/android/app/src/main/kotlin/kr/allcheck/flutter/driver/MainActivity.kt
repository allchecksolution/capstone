package kr.allcheck.flutter.driver

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var folderResult: MethodChannel.Result? = null
    private var exportResult: MethodChannel.Result? = null
    private var exportBytes: ByteArray? = null
    private val preferences get() = getSharedPreferences("allcheck_flutter_folder", MODE_PRIVATE)
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "allcheck/platform").setMethodCallHandler { call, result ->
            when (call.method) {
                "recordingStatus" -> result.success(readable())
                "connectRecordings" -> {
                    if (folderResult != null) { result.error("BUSY", "폴더 선택 중입니다.", null); return@setMethodCallHandler }
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music/Softphone/recordings"))
                    try { folderResult = result; startActivityForResult(intent, 42052051) }
                    catch (_: Exception) { folderResult = null; result.error("PICKER", "폴더 선택창을 열지 못했습니다.", null) }
                }
                "disconnectRecordings" -> {
                    val value = preferences.getString("folder", null)
                    if (value != null) try { contentResolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    preferences.edit().remove("folder").apply(); result.success(null)
                }
                "saveFile" -> {
                    if (exportResult != null) { result.error("BUSY", "파일 저장 중입니다.", null); return@setMethodCallHandler }
                    val bytes = call.argument<ByteArray>("bytes")
                    val name = call.argument<String>("name") ?: "올체크-내보내기.csv"
                    val mime = call.argument<String>("mime") ?: "text/csv"
                    if (bytes == null || name.contains('/') || name.contains('\\') || !(name.endsWith(".csv") || name.endsWith(".xlsx"))) { result.error("INPUT", "저장할 파일을 확인해 주세요.", null); return@setMethodCallHandler }
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name)
                    try { exportResult = result; exportBytes = bytes; startActivityForResult(intent, 42) }
                    catch (_: Exception) { exportResult = null; exportBytes = null; result.error("PICKER", "저장창을 열지 못했습니다.", null) }
                }
                else -> result.notImplemented()
            }
        }
    }
    private fun readable(): Boolean {
        val value = preferences.getString("folder", null) ?: return false
        return try {
            val uri = Uri.parse(value)
            if (contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return false
            val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) { false }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42052051) {
            val result = folderResult ?: return
            folderResult = null
            if (resultCode != Activity.RESULT_OK || data?.data == null) { result.success(false); return }
            val uri = data.data!!
            try {
                if (uri.authority != "com.android.externalstorage.documents" || DocumentsContract.getTreeDocumentId(uri) != "primary:Music/Softphone/recordings") { result.error("FOLDER", "Music/Softphone/recordings 폴더를 선택해 주세요.", null); return }
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                preferences.edit().putString("folder", uri.toString()).apply()
                if (!readable()) { preferences.edit().remove("folder").apply(); contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); result.error("READ", "폴더를 읽을 수 없습니다.", null); return }
                result.success(true)
            } catch (_: Exception) { result.error("PERMISSION", "폴더 권한을 저장하지 못했습니다.", null) }
        }
        if (requestCode == 42) {
            val result = exportResult ?: return
            val bytes = exportBytes
            exportResult = null; exportBytes = null
            if (resultCode != Activity.RESULT_OK || data?.data == null) { result.success(null); return }
            try { contentResolver.openOutputStream(data.data!!)?.use { stream -> stream.write(bytes ?: error("Missing bytes")) } ?: error("Missing stream"); result.success(null) }
            catch (_: Exception) { result.error("SAVE", "파일을 저장하지 못했습니다.", null) }
        }
    }
    override fun onDestroy() {
        folderResult?.error("CLOSED", "폴더 연결 화면이 종료되었습니다.", null)
        exportResult?.error("CLOSED", "파일 저장 화면이 종료되었습니다.", null)
        folderResult = null; exportResult = null; exportBytes = null
        super.onDestroy()
    }
}
