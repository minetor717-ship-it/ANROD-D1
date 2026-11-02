package tr.hastane.sayim

import android.content.Context

/** Bağlanılan sunucu adresi ve son bağlanılanlar (telefonda saklanır; PIN/parola saklanmaz). */
object ServerStore {
    private const val AD = "sayim_sunucu"

    private fun p(c: Context) = c.getSharedPreferences(AD, Context.MODE_PRIVATE)

    fun get(c: Context): String? = p(c).getString("sunucu", null)?.takeIf { it.isNotBlank() }

    fun set(c: Context, kok: String) {
        val g = gecmis(c).filter { it != kok }.toMutableList()
        g.add(0, kok)
        p(c).edit().putString("sunucu", kok).putString("gecmis", g.take(6).joinToString("\n")).commit()
    }

    fun gecmis(c: Context): List<String> =
        (p(c).getString("gecmis", "") ?: "").split("\n").filter { it.isNotBlank() }
}
