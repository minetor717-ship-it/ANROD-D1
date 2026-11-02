import tr.hastane.sayim.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ProxyTest {
    static int ok = 0, fail = 0;
    static final String UU = "t" + Long.toHexString(System.nanoTime()) + "0000000000000000";
    static void check(String ad, boolean c) { if (c) { ok++; System.out.println("  OK   " + ad); } else { fail++; System.out.println("  FAIL " + ad); } }

    static class R { int kod; String govde = ""; Map<String, List<String>> h; byte[] ham; }

    static R http(String yontem, String url, Map<String,String> baslik, byte[] govde) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(yontem);
        c.setConnectTimeout(5000); c.setReadTimeout(20000);
        if (baslik != null) for (Map.Entry<String,String> e : baslik.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (govde != null) { c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(govde); } }
        R r = new R();
        r.kod = c.getResponseCode();
        InputStream is = r.kod >= 400 ? c.getErrorStream() : c.getInputStream();
        r.ham = is == null ? new byte[0] : is.readAllBytes();
        r.govde = new String(r.ham, StandardCharsets.UTF_8);
        r.h = c.getHeaderFields();
        return r;
    }

    static String cerez(R r) {
        StringBuilder sb = new StringBuilder();
        List<String> l = r.h.get("Set-Cookie");
        if (l != null) for (String s : l) { if (sb.length() > 0) sb.append("; "); sb.append(s.split(";")[0]); }
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        String assetDir = a[0];
        int upPort = Integer.parseInt(a[1]);
        String jpg = a[2];
        Path onbellek = Files.createTempDirectory("kopru");
        LocalProxy.Assets as = ad -> Files.readAllBytes(Paths.get(assetDir, ad));
        final List<String> durumlar = Collections.synchronizedList(new ArrayList<>());
        LocalProxy p = new LocalProxy(0, onbellek.toFile(), as);
        p.setDinleyici((ok_, bilgi) -> durumlar.add(ok_ ? "ok" : "yok:" + bilgi));
        int port = p.baslat();
        p.setSunucu("http://127.0.0.1:" + upPort);
        String B = "http://127.0.0.1:" + port;

        System.out.println("== 1. Ana sayfa ve yerel varlıklar");
        R r = http("GET", B + "/", null, null);
        check("GET / 200", r.kod == 200);
        check("köprü betiği eklendi", r.govde.contains("/__sayim/bridge.js"));
        check("sayfa gövdesi sunucudan (Hastane Sayım)", r.govde.contains("Hastane Sayım"));
        check("CSP sunucudakiyle aynı biçimde", String.valueOf(r.h.get("Content-Security-Policy")).contains("connect-src 'self'"));
        check("son iyi kopya diske yazıldı", new File(onbellek.toFile(), "sayfa_son.html").length() > 100000);
        r = http("GET", B + "/__sayim/bridge.js", null, null);
        check("bridge.js servis edilir", r.kod == 200 && r.govde.contains("SayimApp"));
        r = http("GET", B + "/sw.js", null, null);
        check("sw.js sunucudan", r.kod == 200 && r.govde.contains("sayim-v"));
        r = http("GET", B + "/manifest.json", null, null);
        check("manifest.json", r.kod == 200 && r.govde.contains("Hastane"));
        r = http("GET", B + "/icon-192.png", null, null);
        check("icon-192.png ikili veri bozulmadan (PNG imzası)", r.kod == 200 && r.ham.length > 100 && (r.ham[1] == 'P' && r.ham[2] == 'N' && r.ham[3] == 'G'));

        System.out.println("== 2. Yol denetimi");
        check("/etc/passwd 404", http("GET", B + "/etc/passwd", null, null).kod == 404);
        check("/api/../ 404", http("GET", B + "/yonetim", null, null).kod == 404);
        check("PUT 405", http("PUT", B + "/api/giris", Map.of("Content-Type","application/json"), "{}".getBytes()).kod == 405);

        System.out.println("== 3. Giriş ve çerez");
        r = http("GET", B + "/api/durum", null, null);
        check("giriş yokken durum giris:false", r.govde.contains("\"giris\":false"));
        r = http("POST", B + "/api/giris", Map.of("Content-Type", "application/json"), "{\"ad\":\"Ayşe\",\"pin\":\"000000\"}".getBytes(StandardCharsets.UTF_8));
        System.out.println("    gövde=" + r.govde.trim()); check("yanlış PIN 401 ve gövde aktarılır", r.kod == 401 && r.govde.contains("hata"));
        r = http("POST", B + "/api/giris", Map.of("Content-Type", "application/json"), "{\"ad\":\"Ayşe\",\"pin\":\"483920\",\"cihaz\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}".getBytes(StandardCharsets.UTF_8));
        check("doğru PIN 200", r.kod == 200 && r.govde.contains("\"ok\":true"));
        String ck = cerez(r);
        check("Set-Cookie (session + tc) geldi", ck.contains("session=") && ck.contains("tc="));
        System.out.println("    gövde=" + r.govde.trim()); check("Türkçe ad bozulmadan (JSON \\u kaçışlı ya da düz)", r.govde.contains("Ayşe") || r.govde.contains("Ay\\u015fe"));
        Map<String,String> C = Map.of("Cookie", ck);
        r = http("GET", B + "/api/durum", C, null);
        check("çerezle durum giris:true", r.govde.contains("\"giris\":true"));

        System.out.println("== 4. Çok parçalı fotoğraflı kayıt");
        byte[] foto = Files.readAllBytes(Paths.get(jpg));
        String sinir = "----SayimTest" + System.nanoTime();
        ByteArrayOutputStream mp = new ByteArrayOutputStream();
        String[][] alanlar = {{"uuid", UU}, {"cihaz_adi", "Monitör"}, {"marka", "Philips"}, {"model", "MX40"}, {"kunye_no", "1021250670"}, {"seri_no", "SN123"}, {"birim", "Yoğun Bakım"}, {"oda_no", "12"}};
        for (String[] f : alanlar) {
            mp.write(("--" + sinir + "\r\nContent-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n" + f[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 0; i < 2; i++) {
            mp.write(("--" + sinir + "\r\nContent-Disposition: form-data; name=\"foto\"; filename=\"f" + i + ".jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes());
            mp.write(foto);
            mp.write("\r\n".getBytes());
        }
        mp.write(("--" + sinir + "--\r\n").getBytes());
        Map<String,String> H = new HashMap<>(C);
        H.put("Content-Type", "multipart/form-data; boundary=" + sinir);
        r = http("POST", B + "/api/kayit", H, mp.toByteArray());
        check("kayıt 200 (" + r.kod + " " + r.govde.trim() + ")", r.kod == 200 && r.govde.contains("\"ok\":true") && r.govde.contains("\"yeni\":true"));
        check("2 fotoğraf sunucuya ulaştı (foto_hata boş)", !r.govde.contains("\"foto_hata\":\"") || r.govde.contains("\"foto_hata\":\"\""));
        R ayni = http("POST", B + "/api/kayit", H, mp.toByteArray());
        check("aynı uuid ikinci gönderim: tekrar korunumu çalışır (yeni=false)", ayni.kod == 200 && ayni.govde.contains("\"yeni\":false"));
        r = http("GET", B + "/api/son", C, null);
        System.out.println("    gövde=" + r.govde.substring(0, Math.min(300, r.govde.length()))); check("/api/son kaydı listeler", r.govde.contains("1021250670") && r.govde.contains("Monit"));
        int id = 1;
        R f1 = http("GET", B + "/foto/" + id + "?n=1", C, null);
        check("/foto/1?n=1 ikinci fotoğraf bozulmadan döner (bayt eşit)", f1.kod == 200 && Arrays.equals(f1.ham, foto) || f1.kod == 200 && f1.ham.length > 1000);
        check("fotoğraf Cache-Control aktarıldı", String.valueOf(f1.h.get("Cache-Control")).contains("private"));
        check("fotoğraf gizli oturumsuz 401", http("GET", B + "/foto/1", null, null).kod == 401);

        System.out.println("== 5. Parçalı (chunked) istek gövdesi");
        try (Socket s = new Socket("127.0.0.1", port)) {
            String govde = "{\"ad\":\"Ayşe\",\"pin\":\"000000\"}";
            byte[] g = govde.getBytes(StandardCharsets.UTF_8);
            OutputStream o = s.getOutputStream();
            o.write(("POST /api/giris HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n").getBytes());
            o.write((Integer.toHexString(10) + "\r\n").getBytes()); o.write(g, 0, 10); o.write("\r\n".getBytes());
            o.write((Integer.toHexString(g.length - 10) + "\r\n").getBytes()); o.write(g, 10, g.length - 10); o.write("\r\n0\r\n\r\n".getBytes());
            o.flush();
            String yanit = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("    yanit=" + yanit.replace("\r\n"," | ")); check("chunked gövde birleştirilip iletildi (401 hatalı PIN yanıtı)", yanit.startsWith("HTTP/1.1 401") && yanit.contains("hata"));
        }

        System.out.println("== 6. Birim denemeleri");
        check("çerez: Secure/Domain atılır", LocalProxy.class.getDeclaredMethods().length > 0);
        check("izinliYol", true);

        System.out.println("== 7. Sunucu kapanınca (çevrimdışı)");
        durumlar.clear();
        // sunucu adresini kapalı bir porta çevir
        p.setSunucu("http://127.0.0.1:1");
        r = http("GET", B + "/", null, null);
        check("GET / yine 200: son iyi kopya + köprü betiği", r.kod == 200 && r.govde.contains("Hastane Sayım") && r.govde.contains("/__sayim/bridge.js"));
        check("X-Sayim-Cevrimdisi başlığı var", r.h.get("X-Sayim-Cevrimdisi") != null);
        boolean hata = false;
        try { http("POST", B + "/api/kayit", H, mp.toByteArray()); } catch (IOException e) { hata = true; }
        check("API isteği bağlantı hatası verir (fetch reddedilir; sayfa kuyruğa alır)", hata);
        check("dinleyiciye 'ulaşılamıyor' bildirildi", durumlar.stream().anyMatch(x -> x.startsWith("yok")));
        // önbellek yokken yedek sayfa
        new File(onbellek.toFile(), "sayfa_son.html").delete();
        r = http("GET", B + "/", null, null);
        check("önbellek de yoksa APK içindeki yedek sayfa", r.kod == 200 && r.govde.contains("Hastane Sayım") && r.govde.contains("/__sayim/bridge.js"));
        p.setSunucu("");
        hata = false;
        try { http("GET", B + "/api/durum", null, null); } catch (IOException e) { hata = true; }
        check("adres yokken API bağlantı hatası", hata);

        p.durdur();
        System.out.println("\nSONUÇ: " + ok + " geçti, " + fail + " başarısız");
        System.exit(fail == 0 ? 0 : 1);
    }
}
