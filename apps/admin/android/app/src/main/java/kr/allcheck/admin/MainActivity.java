package kr.allcheck.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.view.View;
import android.view.WindowInsets;
import android.os.Build;
import android.graphics.Insets;
import androidx.webkit.WebViewAssetLoader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String HOME = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int SAVE_CSV = 10;
    private static final int SELECT_RECORDINGS = 11;
    private boolean folderPickerOpen;
    private WebView web;
    private String pendingCsv;
    private byte[] pendingXlsx;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        // Apply insets to the container so the actual WebView viewport is reduced.
        // WebView padding alone does not move CSS position:fixed navigation.
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(android.graphics.Color.WHITE);
        container.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(container);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return new WindowInsets.Builder(insets).setInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime(), Insets.NONE).build();
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        container.requestApplyInsets();
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = assets.shouldInterceptRequest(request.getUrl());
                return response != null ? response : new WebResourceResponse("text/plain", "UTF-8", 403,
                    "Forbidden", java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())
                    && "/assets/www/index.html".equals(uri.getPath()));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                    .setPositiveButton("확인", (dialog, which) -> result.confirm())
                    .setNegativeButton("취소", (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
        });
        web.addJavascriptInterface(new ExportBridge(), "AllcheckApp");
        if (state != null) pendingCsv = state.getString("pendingCsv");
        web.loadUrl(HOME);
    }

    private String recordingFolderUri() {
        return getPreferences(MODE_PRIVATE).getString("recordingFolder", "");
    }

    private boolean canReadRecordingFolder(String value) {
        if (value.isEmpty()) return false;
        try {
            Uri tree = Uri.parse(value);
            boolean granted = false;
            for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                if (permission.getUri().equals(tree) && permission.isReadPermission()) granted = true;
            }
            if (!granted) return false;
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = getContentResolver().query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                return cursor != null && cursor.moveToFirst();
            }
        } catch (Exception exception) { return false; }
    }

    private void notifyRecordingFolder(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        web.evaluateJavascript("window.dispatchEvent(new Event('recordingfolderchange'))", null);
    }

    public final class ExportBridge {
        @JavascriptInterface public void exportXlsx(String encoded) {
            runOnUiThread(() -> {
                if (pendingXlsx != null) return;
                try {
                    pendingXlsx = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .putExtra(Intent.EXTRA_TITLE, "올체크-의뢰목록.xlsx");
                    startActivityForResult(intent, 12);
                } catch (Exception exception) { pendingXlsx = null; notifyRecordingFolder("파일 저장창을 열지 못했습니다."); }
            });
        }

        @JavascriptInterface public String recordingFolderStatus() {
            return canReadRecordingFolder(recordingFolderUri()) ? "connected" : "disconnected";
        }

        @JavascriptInterface public void connectRecordingFolder() {
            runOnUiThread(() -> {
                if (folderPickerOpen) return;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music/Softphone/recordings"));
                try { folderPickerOpen = true; startActivityForResult(intent, SELECT_RECORDINGS); }
                catch (android.content.ActivityNotFoundException exception) {
                    folderPickerOpen = false;
                    notifyRecordingFolder("폴더 선택 앱을 찾을 수 없습니다.");
                }
            });
        }

        @JavascriptInterface public void disconnectRecordingFolder() {
            runOnUiThread(() -> {
                String value = recordingFolderUri();
                if (!value.isEmpty()) {
                    try { getContentResolver().releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (SecurityException ignored) { }
                }
                getPreferences(MODE_PRIVATE).edit().remove("recordingFolder").apply();
                notifyRecordingFolder("녹음 폴더 연결을 해제했습니다. 원본 파일은 유지됩니다.");
            });
        }

        @JavascriptInterface public void exportCsv(String csv) {
            runOnUiThread(() -> {
                if (pendingCsv != null) return;
                pendingCsv = csv;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv")
                    .putExtra(Intent.EXTRA_TITLE, "올체크-의뢰목록.csv");
                try { startActivityForResult(intent, SAVE_CSV); }
                catch (android.content.ActivityNotFoundException exception) {
                    pendingCsv = null;
                    Toast.makeText(MainActivity.this, "파일 저장 앱을 찾을 수 없습니다.", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 12) {
            byte[] bytes = pendingXlsx; pendingXlsx = null;
            if (result != RESULT_OK || data == null || data.getData() == null || bytes == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new java.io.IOException();
                out.write(bytes);
                Toast.makeText(this, "파일을 저장했습니다.", Toast.LENGTH_SHORT).show();
            } catch (Exception exception) { Toast.makeText(this, "파일을 저장하지 못했습니다.", Toast.LENGTH_LONG).show(); }
            return;
        }
        if (request == SELECT_RECORDINGS) {
            folderPickerOpen = false;
            if (result != RESULT_OK || data == null || data.getData() == null) {
                notifyRecordingFolder("폴더 선택을 취소했습니다.");
                return;
            }
            Uri tree = data.getData();
            try {
                if (!"com.android.externalstorage.documents".equals(tree.getAuthority())
                        || !"primary:Music/Softphone/recordings".equals(DocumentsContract.getTreeDocumentId(tree))) {
                    notifyRecordingFolder("Music/Softphone/recordings 폴더를 선택해 주세요.");
                    return;
                }
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (!canReadRecordingFolder(tree.toString())) {
                    getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    notifyRecordingFolder("폴더를 읽을 수 없습니다. 다시 연결해 주세요.");
                    return;
                }
                getPreferences(MODE_PRIVATE).edit().putString("recordingFolder", tree.toString()).apply();
                notifyRecordingFolder("Softphone 녹음 폴더 접근 권한을 저장했습니다.");
            } catch (Exception exception) {
                notifyRecordingFolder("폴더 접근 권한을 저장하지 못했습니다. 다시 시도해 주세요.");
            }
            return;
        }
        if (request != SAVE_CSV) return;
        String csv = pendingCsv;
        pendingCsv = null;
        if (result != RESULT_OK || data == null || data.getData() == null || csv == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new java.io.IOException("No output stream");
            out.write(csv.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "CSV 파일을 저장했습니다.", Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            Toast.makeText(this, "파일을 저장하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("location.hash", hash -> {
            if (!"\"\"".equals(hash) && !"\"#/home\"".equals(hash) && !"\"#/login\"".equals(hash)) {
                web.evaluateJavascript("location.hash='#/home'", null);
            } else {
                new AlertDialog.Builder(this).setMessage("앱을 종료할까요?")
                    .setPositiveButton("종료", (dialog, which) -> finish())
                    .setNegativeButton("취소", null).show();
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (pendingCsv != null) state.putString("pendingCsv", pendingCsv);
    }

    @Override protected void onDestroy() {
        web.removeJavascriptInterface("AllcheckApp");
        web.destroy();
        super.onDestroy();
    }
}
