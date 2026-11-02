package tr.hastane.sayim;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Telefondaki WebView'in konuştuğu YEREL köprü: http://127.0.0.1:PORT
 *
 * Neden var?
 *  - Sayfanın "origin"i hep aynı kalır. Tünel adresi her açılışta değişse bile telefondaki kuyruk (IndexedDB: gönderilmemiş
 *    kayıtlar), cihaz kimliği ve ayarlar (localStorage) kaybolmaz.
 *  - 127.0.0.1 "güvenli bağlam"dır: yerel ağdaki http sunucuda da kamera (getUserMedia) çalışır.
 *  - Sunucu kapalıyken bile sayfa açılır (son iyi kopya ya da APK içindeki yedek sayfa); kayıtlar telefonda bekler.
 *
 * Sunucu koduna dokunulmaz: istekler olduğu gibi (çerezler, çok parçalı fotoğraf gövdesi dahil) sunucuya aktarılır.
 * Android'e bağımlı değildir; masaüstü JVM'de test edilir.
 */
public final class LocalProxy {

    public interface Listener {
        /** Sunucuya erişim durumu: ok=false ise bağlantı kurulamadı ya da tünel/ağ geçidi hatası (502/503/504/530). */
        void onUpstream(boolean ok, String bilgi);
    }

    public interface Assets {
        byte[] oku(String ad) throws IOException;      // "sayfa.html", "bridge.js"
    }

    public static final String KOPRU_YOLU = "/__sayim/bridge.js";
    private static final String BETIK_ETIKETI = "<script src=\"" + KOPRU_YOLU + "\"></script>";
    private static final int MAX_GOVDE = 64 * 1024 * 1024;
    private static final int MAX_YANIT = 48 * 1024 * 1024;

    private final int istenenPort;
    private final File onbellekKlasoru;
    private final Assets assets;
    private volatile String sunucu = "";
    private volatile Listener dinleyici;
    private volatile ServerSocket soket;
    private ExecutorService havuz;
    private volatile boolean calisiyor;

    public LocalProxy(int port, File onbellekKlasoru, Assets assets) {
        this.istenenPort = port;
        this.onbellekKlasoru = onbellekKlasoru;
        this.assets = assets;
    }

    public void setSunucu(String kok) { this.sunucu = kok == null ? "" : kok; }
    public String getSunucu() { return sunucu; }
    public void setDinleyici(Listener l) { this.dinleyici = l; }

    /** Gerçekte dinlenen port (istenen doluysa sonraki; o durumda origin değişir ve telefon verisi ayrı tutulur). */
    public int baslat() throws IOException {
        if (calisiyor && soket != null) return soket.getLocalPort();
        IOException son = null;
        ServerSocket s = null;
        for (int p = istenenPort; p < istenenPort + 10; p++) {
            try {
                s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), istenenPort == 0 ? 0 : p), 64);
                break;
            } catch (IOException e) {
                son = e;
                s = null;
                if (istenenPort == 0) break;
            }
        }
        if (s == null) throw son != null ? son : new IOException("port açılamadı");
        soket = s;
        calisiyor = true;
        final AtomicInteger n = new AtomicInteger();
        havuz = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sayim-kopru-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        final ServerSocket ss = s;
        Thread kabul = new Thread(new Runnable() {
            @Override public void run() {
                while (calisiyor) {
                    try {
                        final Socket c = ss.accept();
                        havuz.execute(new Runnable() { @Override public void run() { isle(c); } });
                    } catch (IOException e) {
                        if (!calisiyor) break;
                    } catch (RuntimeException e) {
                        if (!calisiyor) break;
                    }
                }
            }
        }, "sayim-kopru-kabul");
        kabul.setDaemon(true);
        kabul.start();
        return s.getLocalPort();
    }

    public void durdur() {
        calisiyor = false;
        try { if (soket != null) soket.close(); } catch (IOException ignored) {}
        if (havuz != null) havuz.shutdownNow();
        soket = null;
    }

    // ------------------------------------------------------------------ istek ayrıştırma
    private static final class Istek {
        String yontem = "", hedef = "/";
        final Map<String, String> baslik = new LinkedHashMap<String, String>();
        byte[] govde = new byte[0];
        String yol() { int i = hedef.indexOf('?'); return i < 0 ? hedef : hedef.substring(0, i); }
    }

    private static Istek oku(InputStream in) throws IOException {
        ByteArrayOutputStream hb = new ByteArrayOutputStream(1024);
        int son4 = 0, b, okunan = 0;
        boolean bitti = false;
        while ((b = in.read()) != -1) {
            hb.write(b);
            okunan++;
            son4 = (son4 << 8) | (b & 0xFF);
            if (son4 == 0x0D0A0D0A) { bitti = true; break; }
            if (okunan > 65536) throw new IOException("başlık çok büyük");
        }
        if (!bitti) return null;
        String[] satirlar = new String(hb.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        String[] ilk = satirlar[0].split(" ");
        if (ilk.length < 2) throw new IOException("geçersiz istek satırı");
        Istek r = new Istek();
        r.yontem = ilk[0].toUpperCase(Locale.ROOT);
        r.hedef = ilk[1];
        for (int i = 1; i < satirlar.length; i++) {
            int k = satirlar[i].indexOf(':');
            if (k <= 0) continue;
            String ad = satirlar[i].substring(0, k).trim().toLowerCase(Locale.ROOT);
            String deger = satirlar[i].substring(k + 1).trim();
            if (r.baslik.containsKey(ad) && ad.equals("cookie")) deger = r.baslik.get(ad) + "; " + deger;
            r.baslik.put(ad, deger);
        }
        String te = r.baslik.get("transfer-encoding");
        String cl = r.baslik.get("content-length");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            r.govde = parcaliOku(in);
        } else if (cl != null) {
            long n;
            try { n = Long.parseLong(cl.trim()); } catch (NumberFormatException e) { throw new IOException("Content-Length geçersiz"); }
            if (n < 0 || n > MAX_GOVDE) throw new IOException("gövde çok büyük");
            byte[] g = new byte[(int) n];
            int o = 0;
            while (o < g.length) {
                int x = in.read(g, o, g.length - o);
                if (x < 0) throw new IOException("gövde yarım kaldı");
                o += x;
            }
            r.govde = g;
        }
        return r;
    }

    private static byte[] parcaliOku(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String satir = satirOku(in);
            if (satir == null) throw new IOException("parçalı gövde yarım");
            int ek = satir.indexOf(';');
            if (ek >= 0) satir = satir.substring(0, ek);
            int n;
            try { n = Integer.parseInt(satir.trim(), 16); } catch (NumberFormatException e) { throw new IOException("parça boyutu geçersiz"); }
            if (n == 0) {
                String s;
                while ((s = satirOku(in)) != null && !s.isEmpty()) { /* sonuç başlıkları yok sayılır */ }
                break;
            }
            if (out.size() + (long) n > MAX_GOVDE) throw new IOException("gövde çok büyük");
            byte[] p = new byte[n];
            int o = 0;
            while (o < n) {
                int x = in.read(p, o, n - o);
                if (x < 0) throw new IOException("parça yarım");
                o += x;
            }
            out.write(p);
            satirOku(in);       // parça sonu CRLF
        }
        return out.toByteArray();
    }

    private static String satirOku(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                int l = sb.length();
                if (l > 0 && sb.charAt(l - 1) == '\r') sb.setLength(l - 1);
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > 4096) throw new IOException("satır çok uzun");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------------ yönlendirme
    private static final Set<String> SABIT_YOLLAR = new HashSet<String>();
    static {
        SABIT_YOLLAR.add("/");
        SABIT_YOLLAR.add("/sw.js");
        SABIT_YOLLAR.add("/manifest.json");
        SABIT_YOLLAR.add("/icon-192.png");
        SABIT_YOLLAR.add("/icon-512.png");
    }

    static boolean izinliYol(String yol) {
        return SABIT_YOLLAR.contains(yol) || yol.startsWith("/api/") || yol.startsWith("/foto/");
    }

    private void isle(Socket c) {
        try {
            c.setTcpNoDelay(true);
            c.setSoTimeout(30000);                          // bağlanıp hiçbir şey göndermeyen ön bağlantılar 30 sn sonra kapanır
            InputStream in = new BufferedInputStream(c.getInputStream(), 16384);
            Istek r = oku(in);
            if (r == null) { kapat(c, false); return; }
            c.setSoTimeout(180000);
            yanitla(c, r);
        } catch (IOException e) {
            kapat(c, true);
        } catch (RuntimeException e) {
            kapat(c, true);
        }
    }

    private void yanitla(Socket c, Istek r) throws IOException {
        String yol = r.yol();
        boolean get = r.yontem.equals("GET"), head = r.yontem.equals("HEAD");
        if (!(get || head || r.yontem.equals("POST"))) { yaz(c, 405, "Method Not Allowed", "text/plain; charset=utf-8", "yontem yok".getBytes(StandardCharsets.UTF_8), null, false); return; }

        if (yol.equals(KOPRU_YOLU)) {
            byte[] b = assets.oku("bridge.js");
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Cache-Control", "no-store");
            yaz(c, 200, "OK", "application/javascript; charset=utf-8", b, h, head);
            return;
        }
        if (yol.equals("/favicon.ico")) { yaz(c, 404, "Not Found", "text/plain; charset=utf-8", new byte[0], null, head); return; }
        if (!izinliYol(yol)) { yaz(c, 404, "Not Found", "application/json; charset=utf-8", "{\"hata\":\"Bulunamadı\"}".getBytes(StandardCharsets.UTF_8), null, head); return; }

        if (yol.equals("/") && (get || head)) { kokSayfa(c, r, head); return; }
        if (yol.equals("/sw.js") && (get || head)) {
            // Hizmet işçisi: sunucudakini ver; olmazsa yerel yedek (sayfa kayıt eder, hata verirse yok sayar)
            Yanit u = yukari(r, false);
            if (u != null && u.kod == 200) { gonder(c, u, head); return; }
            byte[] b = assets.oku("sw.js");
            yaz(c, 200, "OK", "application/javascript; charset=utf-8", b, null, head);
            return;
        }

        Yanit u = yukari(r, true);
        if (u == null) { kapat(c, true); return; }          // ağ yok: tarayıcıdaki gibi bağlantı hatası → sayfa kuyruğa alır
        gonder(c, u, head);
    }

    // ------------------------------------------------------------------ ana sayfa: sunucudan, olmazsa son iyi kopya / yedek
    private void kokSayfa(Socket c, Istek r, boolean head) throws IOException {
        byte[] html = null;
        boolean cevrimdisi = false;
        Istek k = new Istek();
        k.yontem = "GET";
        k.hedef = "/";
        k.baslik.putAll(r.baslik);
        Yanit u = yukari(k, false);
        if (u != null && u.kod == 200 && u.govde.length > 2000 && u.tur.toLowerCase(Locale.ROOT).contains("html")) {
            html = u.govde;
            kopyaYaz(html);
        } else {
            cevrimdisi = true;
            html = kopyaOku();
            if (html == null) html = assets.oku("sayfa.html");
        }
        byte[] cikis = enjekte(html);
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Cache-Control", "no-cache");
        h.put("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
                + "script-src 'self' 'unsafe-inline'; media-src 'self' blob:; connect-src 'self'; worker-src 'self'");
        if (cevrimdisi) h.put("X-Sayim-Cevrimdisi", "1");
        if (u != null) for (String ck : u.setCookie) h.put("Set-Cookie\u0000" + h.size(), ck);
        yaz(c, 200, "OK", "text/html; charset=utf-8", cikis, h, head);
    }

    static byte[] enjekte(byte[] html) {
        String s = new String(html, StandardCharsets.UTF_8);
        if (s.contains(KOPRU_YOLU)) return html;
        int i = s.toLowerCase(Locale.ROOT).indexOf("<head>");
        String yeni = i >= 0 ? s.substring(0, i + 6) + BETIK_ETIKETI + s.substring(i + 6) : BETIK_ETIKETI + s;
        return yeni.getBytes(StandardCharsets.UTF_8);
    }

    private File kopyaDosyasi() { return new File(onbellekKlasoru, "sayfa_son.html"); }

    private void kopyaYaz(byte[] b) {
        try {
            onbellekKlasoru.mkdirs();
            File gecici = new File(onbellekKlasoru, "sayfa_son.html.tmp");
            FileOutputStream o = new FileOutputStream(gecici);
            try { o.write(b); o.getFD().sync(); } finally { o.close(); }
            Files.move(gecici.toPath(), kopyaDosyasi().toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { /* önbellek en iyi çaba */ }
    }

    private byte[] kopyaOku() {
        try {
            File f = kopyaDosyasi();
            if (f.isFile() && f.length() > 2000) return Files.readAllBytes(f.toPath());
        } catch (IOException ignored) {}
        return null;
    }

    // ------------------------------------------------------------------ sunucuya aktarım
    private static final class Yanit {
        int kod;
        String mesaj = "";
        String tur = "";
        byte[] govde = new byte[0];
        final Map<String, String> baslik = new LinkedHashMap<String, String>();
        final List<String> setCookie = new ArrayList<String>();
    }

    private static final Set<String> ILETILEN_ISTEK = new HashSet<String>();
    static {
        String[] a = {"content-type", "cookie", "accept", "accept-language", "user-agent", "if-none-match", "if-modified-since", "cache-control"};
        for (String x : a) ILETILEN_ISTEK.add(x);
    }

    private static final Set<String> ATLANAN_YANIT = new HashSet<String>();
    static {
        String[] a = {"content-length", "content-encoding", "transfer-encoding", "connection", "keep-alive", "strict-transport-security",
                "set-cookie", "location", "server", "date", "alt-svc", "nel", "report-to", "expect-ct", "server-timing", "content-type", "vary"};
        for (String x : a) ATLANAN_YANIT.add(x);
    }

    /** Sunucuya istek atar. Bağlantı kurulamazsa null (dinleyiciye bildirilir). 502/503/504/530 yanıtı döner ama "erişilemiyor" sayılır. */
    private Yanit yukari(Istek r, boolean govdeyiAktar) {
        String kok = sunucu;
        Listener l = dinleyici;
        if (kok.isEmpty()) {
            if (l != null) l.onUpstream(false, "Sunucu adresi yok");
            return null;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(kok + r.hedef).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(150000);
            c.setRequestMethod(r.yontem.equals("HEAD") ? "GET" : r.yontem);
            for (Map.Entry<String, String> e : r.baslik.entrySet()) {
                if (ILETILEN_ISTEK.contains(e.getKey())) c.setRequestProperty(e.getKey(), e.getValue());
            }
            if (r.yontem.equals("POST")) {
                c.setDoOutput(true);        // akışlı kip (setFixedLengthStreamingMode) KULLANILMAZ: 401/403 gibi yanıtlarda hata gövdesi kaybolabiliyor
                OutputStream o = c.getOutputStream();
                try { o.write(r.govde); o.flush(); } finally { o.close(); }
            }
            Yanit y = new Yanit();
            y.kod = c.getResponseCode();
            y.mesaj = c.getResponseMessage() == null ? "" : c.getResponseMessage();
            y.tur = c.getContentType() == null ? "" : c.getContentType();
            InputStream is = y.kod >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is != null && y.kod != 204 && y.kod != 304) {
                try { y.govde = tamOku(is); } finally { is.close(); }
            }
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                String ad = e.getKey();
                if (ad == null || e.getValue() == null) continue;
                String kucuk = ad.toLowerCase(Locale.ROOT);
                if (kucuk.equals("set-cookie")) {
                    for (String v : e.getValue()) y.setCookie.add(cerezDuzelt(v));
                } else if (kucuk.equals("location")) {
                    String v = e.getValue().get(0);
                    y.baslik.put("Location", v.startsWith(kok) ? v.substring(kok.length()).isEmpty() ? "/" : v.substring(kok.length()) : v);
                } else if (!ATLANAN_YANIT.contains(kucuk) && !kucuk.startsWith("cf-")) {
                    y.baslik.put(ad, e.getValue().get(e.getValue().size() - 1));
                }
            }
            boolean agGecidiHatasi = y.kod == 502 || y.kod == 503 || y.kod == 504 || y.kod == 530;
            if (l != null) l.onUpstream(!agGecidiHatasi, agGecidiHatasi ? "Sunucu yanıt vermiyor (" + y.kod + ")" : "");
            return y;
        } catch (IOException e) {
            if (l != null) l.onUpstream(false, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] tamOku(InputStream is) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(8192);
        byte[] t = new byte[16384];
        int n;
        while ((n = is.read(t)) > 0) {
            o.write(t, 0, n);
            if (o.size() > MAX_YANIT) throw new IOException("yanıt çok büyük");
        }
        return o.toByteArray();
    }

    /** Yerel adres http olduğundan "Secure" ve "Domain" çerez öznitelikleri çıkarılır (yoksa WebView çerezi saklamaz). */
    static String cerezDuzelt(String c) {
        String[] p = c.split(";");
        StringBuilder sb = new StringBuilder(p[0].trim());
        for (int i = 1; i < p.length; i++) {
            String t = p[i].trim();
            String k = t.toLowerCase(Locale.ROOT);
            if (k.equals("secure") || k.startsWith("domain=")) continue;
            if (k.startsWith("samesite=none")) t = "SameSite=Lax";
            sb.append("; ").append(t);
        }
        return sb.toString();
    }

    private void gonder(Socket c, Yanit y, boolean head) throws IOException {
        Map<String, String> h = new LinkedHashMap<String, String>(y.baslik);
        int i = 0;
        for (String ck : y.setCookie) h.put("Set-Cookie\u0000" + (i++), ck);
        byte[] b = y.kod == 204 || y.kod == 304 ? new byte[0] : y.govde;
        yaz(c, y.kod, y.mesaj.isEmpty() ? "OK" : y.mesaj, y.tur.isEmpty() ? null : y.tur, b, h, head);
    }

    // ------------------------------------------------------------------ yanıt yazma
    private static void yaz(Socket c, int kod, String mesaj, String tur, byte[] govde, Map<String, String> ek, boolean head) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(kod).append(' ').append(mesaj.replace('\r', ' ').replace('\n', ' ')).append("\r\n");
        if (tur != null) sb.append("Content-Type: ").append(tur).append("\r\n");
        if (kod != 204 && kod != 304) sb.append("Content-Length: ").append(govde.length).append("\r\n");
        sb.append("Connection: close\r\n");
        if (ek != null) {
            for (Map.Entry<String, String> e : ek.entrySet()) {
                String ad = e.getKey();
                int z = ad.indexOf('\u0000');          // yinelenen başlıklar (Set-Cookie) için ad\0sıra
                if (z >= 0) ad = ad.substring(0, z);
                String v = e.getValue().replace('\r', ' ').replace('\n', ' ');
                sb.append(ad).append(": ").append(v).append("\r\n");
            }
        }
        sb.append("\r\n");
        OutputStream o = c.getOutputStream();
        o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (!head && kod != 204 && kod != 304) o.write(govde);
        o.flush();
        try { c.shutdownOutput(); } catch (IOException ignored) {}
        kapat(c, false);
    }

    private static void kapat(Socket c, boolean aniden) {
        try {
            if (aniden) c.setSoLinger(true, 0);         // RST: tarayıcı "bağlantı hatası" görür (fetch reddedilir), boş yanıt sanmaz
        } catch (IOException ignored) {}
        try { c.close(); } catch (IOException ignored) {}
    }
}
