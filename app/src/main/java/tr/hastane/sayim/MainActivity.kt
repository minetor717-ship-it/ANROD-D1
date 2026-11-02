package tr.hastane.sayim

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hastane Sayım telefon istemcisi. Sayfa (HTML/JS) sunucudaki sayım programından, yerel köprü (LocalProxy) üzerinden gelir;
 * bu sınıf WebView'i barındırır ve WebView'de eksik olan kamera/mikrofon izni, fotoğraf çekme/seçme, sesli komut, yerel QR/barkod
 * okuma ve pano özelliklerini sağlar.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var ilerleme: ProgressBar
    private lateinit var banner: View
    private lateinit var hataKatmani: View
    private lateinit var hataMetin: TextView

    private val ana = Handler(Looper.getMainLooper())
    private val ardisikHata = AtomicInteger(0)
    @Volatile private var guvenli = false        // şu an yüklü sayfa yerel köprüden mi? (JS köprüsü yalnız o zaman iş görür)
    private var sonGeri = 0L

    // ---------------------------------------------------------------- izinler / sonuç başlatıcıları
    private var bekleyenIzin: PermissionRequest? = null
    private val cokluIzin = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { sonuc ->
        val r = bekleyenIzin ?: return@registerForActivityResult
        bekleyenIzin = null
        val verilecek = r.resources.filter { kaynakIzni(it)?.let { iz -> sonuc[iz] == true || izinVar(iz) } == true }
        if (verilecek.isEmpty()) r.deny() else r.grant(verilecek.toTypedArray())
    }

    private var izinSonrasi: ((Boolean) -> Unit)? = null
    private val tekIzin = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val f = izinSonrasi
        izinSonrasi = null
        f?.invoke(ok)
    }

    private var dosyaCb: ValueCallback<Array<Uri>>? = null
    private var kameraUri: Uri? = null
    private var kameraDosya: File? = null
    private val dosyaSonuc = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { s ->
        val cb = dosyaCb
        dosyaCb = null
        var sonuc: Array<Uri>? = null
        if (s.resultCode == RESULT_OK) {
            sonuc = WebChromeClient.FileChooserParams.parseResult(s.resultCode, s.data)
            val kd = kameraDosya
            if (sonuc == null && kameraUri != null && kd != null && kd.length() > 0) sonuc = arrayOf(kameraUri!!)
        }
        kameraUri = null
        kameraDosya = null
        cb?.onReceiveValue(sonuc)
    }

    private val baglanSonuc = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { s ->
        if (s.resultCode == RESULT_OK) {
            ardisikHata.set(0)
            banner.visibility = View.GONE
            sayfayiAc("/")
        } else if (ServerStore.get(this) == null) {
            finish()
        }
    }

    // ---------------------------------------------------------------- yaşam döngüsü
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)      // sayım sırasında ekran kararmasın
        web = findViewById(R.id.web)
        ilerleme = findViewById(R.id.ilerleme)
        banner = findViewById(R.id.banner)
        hataKatmani = findViewById(R.id.hataKatmani)
        hataMetin = findViewById(R.id.hataMetin)
        findViewById<Button>(R.id.bannerQr).setOnClickListener { qrBaglan() }
        findViewById<Button>(R.id.hataTekrar).setOnClickListener { sayfayiAc("/") }
        findViewById<Button>(R.id.hataQr).setOnClickListener { qrBaglan() }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, false)
        }
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.addJavascriptInterface(Kopru(), "SayimApp")
        web.webViewClient = istemci
        web.webChromeClient = krom

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { geri() }
        })

        eskiFotolariSil()
        if (ServerStore.get(this) == null) {
            qrBaglan()                       // ilk açılış: önce QR ile bağlan
        } else {
            sayfayiAc(paylasimYolu(intent))
            if (intent.action == ACTION_QR_BAGLAN) qrBaglan()      // başlatıcı kısayolu: "QR ile bağlan"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_QR_BAGLAN) { qrBaglan(); return }
        if (ServerStore.get(this) != null) sayfayiAc(paylasimYolu(intent))
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        srIptal()
        CookieManager.getInstance().flush()
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        srIptal()
        bdYurutucu.shutdown()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sayfa açma
    private fun sayfayiAc(yol: String) {
        hataKatmani.visibility = View.GONE
        try {
            val p = ProxyYonetici.baslat(this)
            p.setSunucu(ServerStore.get(this) ?: "")
            p.setDinleyici(LocalProxy.Listener { ok, bilgi -> ana.post { sunucuDurumu(ok, bilgi) } })
            web.loadUrl(ProxyYonetici.kok() + yol)
        } catch (e: Exception) {
            hataGoster("Yerel köprü başlatılamadı: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Paylaşılan metin (ör. künye adresi) sayfaya ?text= ile iletilir; sayfa bunu forma aktarır. */
    private fun paylasimYolu(i: Intent?): String {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)?.take(2000)
            if (!t.isNullOrBlank()) return "/?text=" + Uri.encode(t)
        }
        return "/"
    }

    private fun sunucuDurumu(ok: Boolean, bilgi: String) {
        if (ok) {
            ardisikHata.set(0)
            banner.visibility = View.GONE
        } else {
            Log.w(TAG, "Sunucu erişilemiyor: $bilgi")
            if (ardisikHata.incrementAndGet() >= 3) banner.visibility = View.VISIBLE
        }
    }

    private fun qrBaglan() {
        baglanSonuc.launch(Intent(this, ConnectActivity::class.java))
    }

    private fun hataGoster(m: String) {
        hataMetin.text = m
        hataKatmani.visibility = View.VISIBLE
    }

    private fun yerelMi(u: Uri?): Boolean =
        u != null && u.scheme == "http" && u.host == "127.0.0.1" && u.port == ProxyYonetici.port

    // ---------------------------------------------------------------- geri tuşu
    private fun geri() {
        web.evaluateJavascript("(window.__sayimGeri && window.__sayimGeri()) === true") { r ->
            if (r == "true") return@evaluateJavascript
            if (web.canGoBack()) { web.goBack(); return@evaluateJavascript }
            val simdi = System.currentTimeMillis()
            if (simdi - sonGeri < 2000) finish() else {
                sonGeri = simdi
                Toast.makeText(this, R.string.cikis_uyari, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------------------------------------------------------- WebViewClient
    private val istemci = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url ?: return true
            if (yerelMi(u)) return false
            disariAc(u)                       // künye sayfası vb.: tarayıcıda açılır, sayım sayfası yerinde kalır
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            guvenli = yerelMi(Uri.parse(url ?: ""))
            ilerleme.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            ilerleme.visibility = View.GONE
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) hataGoster("Sayfa açılamadı: ${error?.description ?: ""}")
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
            recreate()
            return true
        }
    }

    private fun disariAc(u: Uri) {
        val s = u.scheme ?: return
        if (s != "http" && s != "https" && s != "mailto" && s != "tel") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Bağlantı açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- WebChromeClient (izin, dosya seçici)
    private fun izinVar(izin: String) =
        ContextCompat.checkSelfPermission(this, izin) == PackageManager.PERMISSION_GRANTED

    private fun kaynakIzni(k: String): String? = when (k) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }

    private val krom = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            runOnUiThread {
                if (!yerelMi(request.origin)) { request.deny(); return@runOnUiThread }
                val iste = request.resources.mapNotNull { kaynakIzni(it) }
                if (iste.isEmpty()) { request.deny(); return@runOnUiThread }
                val eksik = iste.filter { !izinVar(it) }
                if (eksik.isEmpty()) {
                    request.grant(request.resources.filter { kaynakIzni(it) != null }.toTypedArray())
                } else {
                    bekleyenIzin?.deny()
                    bekleyenIzin = request
                    cokluIzin.launch(eksik.toTypedArray())
                }
            }
        }

        override fun onShowFileChooser(
            webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            if (filePathCallback == null || fileChooserParams == null) return false
            dosyaCb?.onReceiveValue(null)
            dosyaCb = filePathCallback
            if (fileChooserParams.isCaptureEnabled) kamerayiAc() else galeriAc(fileChooserParams)
            return true
        }

        override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
            if (BuildConfig.DEBUG && m != null) Log.d(TAG, "JS: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
            return true
        }
    }

    private fun izinIste(izin: String, sonra: (Boolean) -> Unit) {
        if (izinVar(izin)) sonra(true) else { izinSonrasi = sonra; tekIzin.launch(izin) }
    }

    private fun dosyaIptal() {
        dosyaCb?.onReceiveValue(null)
        dosyaCb = null
    }

    /** Fotoğraf Çek: telefonun kamera uygulaması açılır, çekilen fotoğraf sayfaya verilir. */
    private fun kamerayiAc() {
        izinIste(Manifest.permission.CAMERA) { ok ->         // CAMERA manifestte tanımlı olduğundan izin verilmeden ACTION_IMAGE_CAPTURE SecurityException atabilir
            if (!ok) { Toast.makeText(this, "Kamera izni verilmedi", Toast.LENGTH_SHORT).show(); dosyaIptal(); return@izinIste }
            try {
                val klasor = File(cacheDir, "photos").apply { mkdirs() }
                val f = File.createTempFile("foto_", ".jpg", klasor)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                kameraDosya = f
                kameraUri = uri
                val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.clipData = ClipData.newRawUri("", uri)
                dosyaSonuc.launch(i)
            } catch (e: Exception) {
                Log.e(TAG, "Kamera açılamadı", e)
                Toast.makeText(this, "Kamera uygulaması açılamadı", Toast.LENGTH_SHORT).show()
                dosyaIptal()
            }
        }
    }

    /** Galeriden Ekle: sayfanın isteği (çoklu seçim dahil) olduğu gibi sistem seçicisine verilir. */
    private fun galeriAc(p: WebChromeClient.FileChooserParams) {
        try {
            dosyaSonuc.launch(p.createIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "Dosya seçici açılamadı", Toast.LENGTH_SHORT).show()
            dosyaIptal()
        }
    }

    private fun eskiFotolariSil() {
        Thread {
            val sinir = System.currentTimeMillis() - 24L * 3600 * 1000
            File(cacheDir, "photos").listFiles()?.forEach { if (it.lastModified() < sinir) it.delete() }
        }.start()
    }

    // ---------------------------------------------------------------- sesli komut (SpeechRecognizer)
    private var rec: SpeechRecognizer? = null
    private var aktifSr = 0
    private val sessizlestirilen = mutableListOf<Int>()

    private fun js(kod: String) { runOnUiThread { if (!isFinishing && !isDestroyed) web.evaluateJavascript(kod, null) } }

    private fun srHata(id: Int, kod: String) = js("window.__sayimSR&&window.__sayimSR.hata($id,${JSONObject.quote(kod)})")
    private fun srBitti(id: Int) = js("window.__sayimSR&&window.__sayimSR.bitti($id)")

    /** Sistemin başlama/bitme "bip"i her komutta çalmasın: bildirim ve sistem akışları dinleme süresince kısılır (müzik/konuşma akışı değil). */
    private fun sesiKapat() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (s in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (!am.isStreamMute(s)) { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0); sessizlestirilen.add(s) }
            } catch (e: Exception) { /* Rahatsız Etme modu: izin yok, yok say */ }
        }
    }

    private fun sesiAc() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (s in sessizlestirilen) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        }
        sessizlestirilen.clear()
    }

    /** Dinlemeyi bırakır; sayfadaki tanıma nesnesi "bitti" olayını alır (yoksa sayfa kendini hâlâ dinliyor sanır). */
    private fun srIptal() {
        val r = rec
        if (r != null) {
            rec = null
            try { r.cancel(); r.destroy() } catch (e: Exception) { }
            srBitti(aktifSr)
        }
        sesiAc()
    }

    private fun srBasla(id: Int, dil: String, alt: Int) {
        srIptal()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { srHata(id, "service-not-allowed"); srBitti(id); return }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        aktifSr = id
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { js("window.__sayimSR&&window.__sayimSR.basladi($id)") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onResults(res: Bundle?) {
                val l = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!l.isNullOrEmpty() && aktifSr == id) {
                    js("window.__sayimSR&&window.__sayimSR.sonuc($id,${JSONObject.quote(JSONArray(l).toString())})")
                }
                bitir(id)
            }
            override fun onError(kod: Int) {
                if (aktifSr == id) srHata(id, srHataKodu(kod))
                bitir(id)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, dil)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, alt)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        sesiKapat()
        r.startListening(i)
    }

    private fun bitir(id: Int) {
        if (aktifSr == id) srIptal()
    }

    private fun srHataKodu(k: Int) = when (k) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
        else -> "aborted"
    }

    // ---------------------------------------------------------------- yerel QR/barkod okuma (BarcodeDetector yerine)
    private val bdYurutucu = Executors.newSingleThreadExecutor()
    private val bdTarayici by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
    }

    private fun bicimAdi(f: Int) = when (f) {
        Barcode.FORMAT_QR_CODE -> "qr_code"
        Barcode.FORMAT_EAN_13 -> "ean_13"
        Barcode.FORMAT_EAN_8 -> "ean_8"
        Barcode.FORMAT_CODE_128 -> "code_128"
        Barcode.FORMAT_CODE_39 -> "code_39"
        Barcode.FORMAT_CODE_93 -> "code_93"
        Barcode.FORMAT_ITF -> "itf"
        Barcode.FORMAT_UPC_A -> "upc_a"
        Barcode.FORMAT_UPC_E -> "upc_e"
        Barcode.FORMAT_DATA_MATRIX -> "data_matrix"
        Barcode.FORMAT_PDF417 -> "pdf417"
        Barcode.FORMAT_AZTEC -> "aztec"
        Barcode.FORMAT_CODABAR -> "codabar"
        else -> "unknown"
    }

    private fun bdDon(id: Int, json: String) = js("window.__sayimBD&&window.__sayimBD.sonuc($id,${JSONObject.quote(json)})")

    private fun barkodOku(id: Int, b64: String) {
        bdYurutucu.execute {
            try {
                val bayt = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bayt, 0, bayt.size)
                if (bmp == null) { bdDon(id, "[]"); return@execute }
                bdTarayici.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { liste ->
                        val a = JSONArray()
                        for (b in liste) {
                            val raw = b.rawValue ?: continue
                            a.put(JSONObject().put("raw", raw).put("format", bicimAdi(b.format)))
                        }
                        bdDon(id, a.toString())
                        bmp.recycle()
                    }
                    .addOnFailureListener { bdDon(id, "[]"); bmp.recycle() }
            } catch (e: Exception) {
                bdDon(id, "[]")
            }
        }
    }

    // ---------------------------------------------------------------- sayfaya açılan köprü (window.SayimApp)
    /** Yalnızca yerel köprüden yüklenen sayfa çağırabilir (guvenli bayrağı); dış sayfa asla WebView'e girmez, yine de ikinci kilit. */
    inner class Kopru {
        @JavascriptInterface fun sunucu(): String = ServerUrl.kisa(ServerStore.get(this@MainActivity))
        @JavascriptInterface fun surum(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun qrBaglan() { if (guvenli) runOnUiThread { this@MainActivity.qrBaglan() } }

        @JavascriptInterface fun srVar(): Boolean = guvenli && SpeechRecognizer.isRecognitionAvailable(this@MainActivity)
        @JavascriptInterface fun srBaslat(id: Int, dil: String, alt: Int) {
            if (!guvenli) return
            runOnUiThread {
                izinIste(Manifest.permission.RECORD_AUDIO) { ok ->
                    if (ok) srBasla(id, dil, alt) else { srHata(id, "not-allowed"); srBitti(id) }
                }
            }
        }
        @JavascriptInterface fun srDurdur(id: Int, iptal: Boolean) {
            if (!guvenli) return
            runOnUiThread {
                if (aktifSr != id) return@runOnUiThread
                val r = rec
                if (r == null) return@runOnUiThread
                if (iptal) { bitir(id) } else try { r.stopListening() } catch (e: Exception) { bitir(id) }
            }
        }

        @JavascriptInterface fun barkodOku(id: Int, b64: String) {
            if (!guvenli) { bdDon(id, "[]"); return }
            this@MainActivity.barkodOku(id, b64)
        }

        @JavascriptInterface fun panoOku(): String {
            if (!guvenli) return ""
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val c = cm.primaryClip ?: return ""
            if (c.itemCount == 0) return ""
            return c.getItemAt(0).coerceToText(this@MainActivity)?.toString() ?: ""
        }

        @JavascriptInterface fun panoYaz(t: String) {
            if (!guvenli) return
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("sayim", t))
        }
    }

    companion object {
        private const val TAG = "SayimApp"
        const val ACTION_QR_BAGLAN = "tr.hastane.sayim.QR_BAGLAN"
    }
}
