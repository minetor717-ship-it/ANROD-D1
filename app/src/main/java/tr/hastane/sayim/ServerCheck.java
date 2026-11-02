package tr.hastane.sayim;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Adresin gerçekten bir Sayım sunucusu olduğunu /api/durum ile doğrular (QR yanlışlıkla başka bir adres olabilir). */
public final class ServerCheck {
    private ServerCheck() {}

    public static final int TAMAM = 0, SAYIM_DEGIL = 1, ULASILAMADI = 2;

    public static final class Sonuc {
        public final int tur;
        public final String bilgi;
        Sonuc(int tur, String bilgi) { this.tur = tur; this.bilgi = bilgi; }
    }

    public static Sonuc dene(String kok) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(kok + "/api/durum").openConnection();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            int kod = c.getResponseCode();
            if (kod == 502 || kod == 503 || kod == 504 || kod == 530) return new Sonuc(ULASILAMADI, "Tünel/sunucu yanıt vermiyor (" + kod + ")");
            InputStream is = kod >= 400 ? c.getErrorStream() : c.getInputStream();
            String govde = "";
            if (is != null) {
                try {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    byte[] t = new byte[4096];
                    int n;
                    while ((n = is.read(t)) > 0 && o.size() < 65536) o.write(t, 0, n);
                    govde = new String(o.toByteArray(), StandardCharsets.UTF_8);
                } finally { is.close(); }
            }
            if (kod == 200 && govde.contains("\"giris\"") && govde.contains("\"foto_modu\"")) return new Sonuc(TAMAM, "");
            if (kod >= 300 && kod < 400) return new Sonuc(SAYIM_DEGIL, "Adres yönlendiriyor (Cloudflare Access açık olabilir)");
            return new Sonuc(SAYIM_DEGIL, "Bu adres Sayım sunucusu gibi yanıt vermiyor (" + kod + ")");
        } catch (IOException e) {
            return new Sonuc(ULASILAMADI, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
