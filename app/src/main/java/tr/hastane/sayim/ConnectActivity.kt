package tr.hastane.sayim

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sunucuya bağlanma ekranı: bilgisayardaki programın gösterdiği QR okutulur, adres /api/durum ile doğrulanıp kaydedilir.
 * Elle adres yazma, panodan alma ve son bağlanılanlardan seçme de vardır.
 */
class ConnectActivity : AppCompatActivity() {
    private lateinit var onizleme: PreviewView
    private lateinit var durum: TextView
    private lateinit var elle: EditText
    private lateinit var son: LinearLayout
    private lateinit var izinKutu: View
    private lateinit var fenerBtn: Button

    private var kamera: Camera? = null
    private val analizYurutucu = Executors.newSingleThreadExecutor()
    private val tarayici = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )
    private val dogrulaniyor = AtomicBoolean(false)
    private var sonHam = ""
    private var sonHamZamani = 0L
    private var fenerAcik = false

    private val izinIstek = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) kameraBaslat() else izinKutu.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        onizleme = findViewById(R.id.onizleme)
        durum = findViewById(R.id.durum)
        elle = findViewById(R.id.elle)
        son = findViewById(R.id.son)
        izinKutu = findViewById(R.id.izinKutu)
        fenerBtn = findViewById(R.id.fenerBtn)

        findViewById<Button>(R.id.izinBtn).setOnClickListener { izinIstek.launch(Manifest.permission.CAMERA) }
        findViewById<Button>(R.id.baglanBtn).setOnClickListener { elleDene() }
        findViewById<Button>(R.id.yapistirBtn).setOnClickListener { panodanAl() }
        fenerBtn.setOnClickListener { fenerDegistir() }
        elle.setOnEditorActionListener { _, aksiyon, _ ->
            if (aksiyon == EditorInfo.IME_ACTION_GO || aksiyon == EditorInfo.IME_ACTION_DONE) { elleDene(); true } else false
        }
        gecmisiDoldur()
        dokunmaKur()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            kameraBaslat()
        } else {
            izinIstek.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tarayici.close()
        analizYurutucu.shutdown()
    }

    // ---------------------------------------------------------------- kamera
    private fun kameraBaslat() {
        izinKutu.visibility = View.GONE
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val saglayici = f.get()
                val goruntu = Preview.Builder().build().also { it.setSurfaceProvider(onizleme.surfaceProvider) }
                val analiz = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                            ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analiz.setAnalyzer(analizYurutucu) { proxy -> analizle(proxy) }
                saglayici.unbindAll()
                kamera = saglayici.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, goruntu, analiz)
                fenerBtn.visibility = if (kamera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                durumYaz("Kamera açılamadı: ${e.message ?: e.javaClass.simpleName}. Adresi elle yazabilirsiniz.", true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
    private fun analizle(proxy: ImageProxy) {
        val img = proxy.image
        if (img == null || dogrulaniyor.get()) { proxy.close(); return }
        val girdi = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
        tarayici.process(girdi)
            .addOnSuccessListener { liste ->
                val ham = liste.firstNotNullOfOrNull { it.rawValue }
                if (ham != null) adresBulundu(ham)
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun dokunmaKur() {
        val olcek = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val k = kamera ?: return true
                val z = k.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                k.cameraControl.setZoomRatio(z * d.scaleFactor)
                return true
            }
        })
        var pinch = false
        onizleme.setOnTouchListener { v, ev ->
            olcek.onTouchEvent(ev)
            if (olcek.isInProgress) pinch = true
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                if (!pinch) { odakla(ev.x, ev.y); v.performClick() }
                pinch = false
            }
            true
        }
    }

    /** Dokunarak netleme (odak + pozlama, 4 sn sonra otomatik moda döner). */
    private fun odakla(x: Float, y: Float) {
        val k = kamera ?: return
        val nokta = onizleme.meteringPointFactory.createPoint(x, y)
        val eylem = FocusMeteringAction.Builder(nokta, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        k.cameraControl.startFocusAndMetering(eylem)
    }

    private fun fenerDegistir() {
        val k = kamera ?: return
        fenerAcik = !fenerAcik
        k.cameraControl.enableTorch(fenerAcik)
        fenerBtn.alpha = if (fenerAcik) 1f else 0.7f
    }

    // ---------------------------------------------------------------- adres alma
    private fun adresBulundu(ham: String) {
        val simdi = System.currentTimeMillis()
        if (ham == sonHam && simdi - sonHamZamani < 2500) return      // aynı QR art arda işlenmesin
        sonHam = ham; sonHamZamani = simdi
        runOnUiThread {
            val kok = ServerUrl.normalize(ham)
            if (kok == null) durumYaz("Bu QR bir sunucu adresi değil: ${ham.take(60)}", true) else dene(kok)
        }
    }

    private fun elleDene() {
        val kok = ServerUrl.normalize(elle.text.toString())
        if (kok == null) durumYaz("Geçerli bir adres yazın (örn. https://….trycloudflare.com ya da 192.168.1.20:5000).", true) else dene(kok)
    }

    private fun panodanAl() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (t.isBlank()) { durumYaz("Panoda adres yok.", true); return }
        elle.setText(t.trim())
        elleDene()
    }

    private fun gecmisiDoldur() {
        son.removeAllViews()
        val g = ServerStore.gecmis(this)
        findViewById<View>(R.id.sonBaslik).visibility = if (g.isEmpty()) View.GONE else View.VISIBLE
        for (kok in g) {
            val b = Button(this)
            b.text = ServerUrl.kisa(kok)
            b.isAllCaps = false
            b.setOnClickListener { dene(kok) }
            son.addView(b)
        }
    }

    private fun durumYaz(metin: String, hata: Boolean) {
        durum.text = metin
        durum.setTextColor(ContextCompat.getColor(this, if (hata) R.color.kirmizi else R.color.soluk))
    }

    private fun dene(kok: String) {
        if (!dogrulaniyor.compareAndSet(false, true)) return
        durumYaz("Sunucu denetleniyor: ${ServerUrl.kisa(kok)} …", false)
        Thread {
            val s = ServerCheck.dene(kok)
            runOnUiThread {
                when (s.tur) {
                    ServerCheck.TAMAM -> kaydet(kok)
                    ServerCheck.ULASILAMADI -> {
                        dogrulaniyor.set(false)
                        durumYaz("Sunucuya ulaşılamadı: ${s.bilgi}", true)
                        AlertDialog.Builder(this)
                            .setTitle("Sunucuya ulaşılamadı")
                            .setMessage("${ServerUrl.kisa(kok)}\n${s.bilgi}\n\nTünel henüz hazır olmayabilir. Adres yine de kaydedilsin mi? (Telefon bağlantı gelince kendisi bağlanır.)")
                            .setPositiveButton("Kaydet") { _, _ -> kaydet(kok) }
                            .setNegativeButton("Vazgeç", null)
                            .show()
                    }
                    else -> {
                        dogrulaniyor.set(false)
                        durumYaz(s.bilgi, true)
                    }
                }
            }
        }.start()
    }

    private fun kaydet(kok: String) {
        ServerStore.set(this, kok)
        setResult(RESULT_OK, Intent().putExtra("sunucu", kok))
        finish()
    }
}
