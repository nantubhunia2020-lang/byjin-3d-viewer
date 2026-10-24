package com.byjin.viewer

import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val HOME =
            "https://www.byjin.in/p/byjin-stl-viewer-body-margin-0-padding.html"
        private const val REQ_FILE = 1001

        // Layout width the page is drawn at (like Chrome's "Desktop site").
        // Smaller number = bigger buttons, larger number = more desktop-like.
        private const val DESKTOP_WIDTH = 1000

        private val PAGE_JS = """
(function(){
  var m=document.querySelector('meta[name=viewport]');
  if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}
  m.setAttribute('content','width=%W%');

  function findPanel(){
    var all=document.querySelectorAll('h1,h2,h3,h4,div,span,p'),h=null;
    for(var i=0;i<all.length;i++){
      var e=all[i];
      if(e.children.length===0&&e.textContent.trim()==='BYJIN Creator'){h=e;break;}
    }
    if(!h)return null;
    var el=h.parentElement,best=null,vh=window.innerHeight,vw=window.innerWidth;
    while(el&&el!==document.body&&el!==document.documentElement){
      var r=el.getBoundingClientRect();
      if(r.width<vw*0.75&&r.height>vh*0.5){best=el;}
      el=el.parentElement;
    }
    return best;
  }

  window.__byjinTogglePanel=function(){
    var p=(window.__byjinPanel&&document.contains(window.__byjinPanel))?window.__byjinPanel:findPanel();
    if(!p)return 'none';
    window.__byjinPanel=p;
    if(!window.__byjinHidden){
      var r=p.getBoundingClientRect();
      window.__byjinDir=(r.left+r.width/2>window.innerWidth/2)?1:-1;
      p.style.transition='transform .25s ease';
      p.style.transform='translateX('+(window.__byjinDir*105)+'%)';
      setTimeout(function(){p.style.display='none';window.dispatchEvent(new Event('resize'));},260);
      window.__byjinHidden=true;
      return 'hidden';
    }
    p.style.display='';
    p.style.transform='translateX('+(window.__byjinDir*105)+'%)';
    void p.offsetWidth;
    p.style.transform='translateX(0)';
    setTimeout(function(){window.dispatchEvent(new Event('resize'));},60);
    window.__byjinHidden=false;
    return 'shown';
  };
})();
""".replace("%W%", DESKTOP_WIDTH.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        val root = FrameLayout(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(buildToggleButton())
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Desktop site: pretend to be desktop Chrome
            userAgentString = userAgentString
                .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
                .replace(" Mobile", "")
        }

        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                setImmersive(false)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                web.evaluateJavascript(PAGE_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                web.evaluateJavascript(PAGE_JS, null)
            }

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

    private fun buildToggleButton(): View {
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt()
        return TextView(this).apply {
            text = "\u2630"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6FF9800"))
            }
            elevation = 8 * d
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins((16 * d).toInt(), 0, 0, (24 * d).toInt())
            }
            setOnClickListener { togglePanel() }
        }
    }

    /** Slides the options panel away (or back) and hides the system bars. */
    private fun togglePanel() {
        web.evaluateJavascript(
            "window.__byjinTogglePanel ? window.__byjinTogglePanel() : 'none'"
        ) { result ->
            when (result) {
                "\"hidden\"" -> setImmersive(true)
                "\"shown\"" -> setImmersive(false)
                else -> toast("Options panel not found on this page")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setImmersive(on: Boolean) {
        runOnUiThread {
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
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
