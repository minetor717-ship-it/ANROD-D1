package tr.hastane.sayim

import android.content.Context
import java.io.File

/** Yerel köprü işlem ömrü boyunca tek kez başlatılır; WebView hep aynı adresi (aynı "origin") görür. */
object ProxyYonetici {
    const val PORT = 38461

    @Volatile private var proxy: LocalProxy? = null
    @Volatile var port: Int = 0
        private set

    @Synchronized
    fun baslat(ctx: Context): LocalProxy {
        proxy?.let { return it }
        val app = ctx.applicationContext
        val p = LocalProxy(PORT, File(app.filesDir, "kopru")) { ad -> app.assets.open(ad).use { it.readBytes() } }
        port = p.baslat()          // PORT doluysa sonraki boş port; o durumda telefon verisi ayrı bir origin'de tutulur
        proxy = p
        return p
    }

    fun kok(): String = "http://127.0.0.1:$port"
}
