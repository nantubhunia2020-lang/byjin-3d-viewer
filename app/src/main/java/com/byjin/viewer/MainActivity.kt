package com.byjin.viewer

import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.*
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val HOME =
            "https://www.byjin.in/p/byjin-stl-viewer-body-margin-0-padding.html"
        private const val REQ_FILE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
        }

        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                val host = request.url.host ?: return false
                if (host.endsWith("byjin.in")) return false
                // Open every other site in the browser
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // File picker for loading STL / 3DM files
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*" // STL / 3DM have no reliable MIME type
                    putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    )
                }
                return try {
                    startActivityForResult(Intent.createChooser(intent, "Select 3D file"), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        // Snapshots / exports
        web.setDownloadListener { url, _, contentDisposition, mime, _ ->
            handleDownload(url, contentDisposition, mime)
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(HOME)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            )
            fileCallback = null
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun handleDownload(url: String, cd: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, cd, mime).replace("'", "").replace("\\", "")
        when {
            url.startsWith("blob:") -> {
                val js = """
                    (function(){
                      var x=new XMLHttpRequest();
                      x.open('GET','$url',true);
                      x.responseType='blob';
                      x.onload=function(){
                        var r=new FileReader();
                        r.onloadend=function(){AndroidBridge.save(r.result,'$name')};
                        r.readAsDataURL(x.response);
                      };
                      x.send();
                    })();
                """.trimIndent()
                web.evaluateJavascript(js, null)
            }
            url.startsWith("data:") -> Bridge().save(url, name)
            else -> {
                val req = DownloadManager.Request(Uri.parse(url))
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Downloading $name")
            }
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    inner class Bridge {
        @JavascriptInterface
        fun save(dataUrl: String, fileName: String) {
            try {
                val mime = dataUrl.substringAfter("data:").substringBefore(";")
                    .ifEmpty { "application/octet-stream" }
                val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
                var name = fileName
                if (!name.contains('.')) {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                        ?.let { name = "$name.$it" }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BYJIN")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create file")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                toast("Saved to Downloads/BYJIN/$name")
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onPause() { web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
