package com.byjin.viewer

import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val HOME = "https://www.byjin.in/p/byjin-stl-viewer-body-margin-0-padding.html"
        private const val REQ_FILE = 1001

        // Width the page is laid out at (like Chrome's "Desktop site").
        // Smaller number = bigger buttons, larger number = more desktop-like.
        private const val DESKTOP_WIDTH = 1000

        private val PAGE_JS: String = """
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
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(buildToggleButton())
        setContentView(root)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        // "Desktop site": present ourselves as desktop Chrome
        val mobileUa: String = s.userAgentString
        val desktopUa: String = mobileUa
            .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
            .replace(" Mobile", "")
        s.userAgentString = desktopUa

        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                setImmersive(false)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                web.evaluateJavascript(PAGE_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                web.evaluateJavascript(PAGE_JS, null)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (request == null) return false
                if (!request.isForMainFrame) return false
                val target: Uri = request.url
                val host: String = target.host ?: return false
                if (host.endsWith("byjin.in")) return false
                // Open every other site in the browser
                startActivity(Intent(Intent.ACTION_VIEW, target))
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // File picker for loading STL / 3DM files
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                fileCallback?.onReceiveValue(arrayOf<Uri>())
                fileCallback = filePathCallback

                val pick = Intent(Intent.ACTION_GET_CONTENT)
                pick.addCategory(Intent.CATEGORY_OPENABLE)
                pick.type = "*/*" // STL / 3DM have no reliable MIME type
                val multiple = fileChooserParams != null &&
                    fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)

                try {
                    startActivityForResult(Intent.createChooser(pick, "Select 3D file"), REQ_FILE)
                } catch (e: Exception) {
                    fileCallback = null
                    return false
                }
                return true
            }
        }

        // Snapshots / exports
        web.setDownloadListener(
            DownloadListener { url, _, contentDisposition, mimeType, _ ->
                handleDownload(url, contentDisposition, mimeType)
            }
        )

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(HOME)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val cb = fileCallback
            fileCallback = null
            if (cb != null) {
                val picked: Array<Uri>? =
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                cb.onReceiveValue(picked ?: arrayOf<Uri>())
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun handleDownload(url: String, contentDisposition: String?, mimeType: String?) {
        val name: String = URLUtil.guessFileName(url, contentDisposition, mimeType)
            .replace("'", "")
            .replace("\\", "")

        if (url.startsWith("blob:")) {
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
        } else if (url.startsWith("data:")) {
            Bridge().save(url, name)
        } else {
            val req = DownloadManager.Request(Uri.parse(url))
            req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("Downloading $name")
        }
    }

    private fun toast(msg: String) {
        runOnUiThread {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun save(dataUrl: String, fileName: String) {
            try {
                var mime: String = dataUrl.substringAfter("data:").substringBefore(";")
                if (mime.isEmpty()) mime = "application/octet-stream"
                val bytes: ByteArray = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)

                var name: String = fileName
                if (!name.contains('.')) {
                    val ext: String? = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    if (ext != null) name = name + "." + ext
                }

                val values = ContentValues()
                values.put(MediaStore.Downloads.DISPLAY_NAME, name)
                values.put(MediaStore.Downloads.MIME_TYPE, mime)
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BYJIN")

                val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create file")
                val out = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open file")
                out.use { stream -> stream.write(bytes) }
                toast("Saved to Downloads/BYJIN/" + name)
            } catch (e: Exception) {
                toast("Save failed: " + e.message)
            }
        }
    }

    private fun buildToggleButton(): View {
        val d: Float = resources.displayMetrics.density
        val size: Int = (52 * d).toInt()

        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#E6FF9800"))

        val lp = FrameLayout.LayoutParams(size, size)
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.setMargins((16 * d).toInt(), 0, 0, (24 * d).toInt())

        val button = TextView(this)
        button.text = "\u2630"
        button.setTextColor(Color.WHITE)
        button.textSize = 22f
        button.gravity = Gravity.CENTER
        button.background = bg
        button.elevation = 8 * d
        button.layoutParams = lp
        button.setOnClickListener { togglePanel() }
        return button
    }

    /** Slides the options panel away (or back) and hides the system bars. */
    private fun togglePanel() {
        web.evaluateJavascript(
            "window.__byjinTogglePanel ? window.__byjinTogglePanel() : 'none'",
            ValueCallback<String> { result ->
                if (result == "\"hidden\"") {
                    setImmersive(true)
                } else if (result == "\"shown\"") {
                    setImmersive(false)
                } else {
                    toast("Options panel not found on this page")
                }
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun setImmersive(on: Boolean) {
        runOnUiThread {
            val flags: Int = if (on) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
