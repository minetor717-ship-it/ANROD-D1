import tr.hastane.sayim.ServerUrl;
public class ServerUrlTest {
    static int ok = 0, fail = 0;
    static void e(String girdi, String beklenen) {
        String g = ServerUrl.normalize(girdi);
        boolean t = beklenen == null ? g == null : beklenen.equals(g);
        if (t) ok++; else { fail++; System.out.println("FAIL: [" + girdi + "] -> " + g + " (beklenen " + beklenen + ")"); }
    }
    public static void main(String[] a) {
        e("https://abc-def.trycloudflare.com", "https://abc-def.trycloudflare.com");
        e("https://abc.trycloudflare.com/", "https://abc.trycloudflare.com");
        e("  https://ABC.trycloudflare.com/?x=1#y \n", "https://abc.trycloudflare.com");
        e("abc.trycloudflare.com", "https://abc.trycloudflare.com");
        e("192.168.1.5:5000", "http://192.168.1.5:5000");
        e("http://192.168.1.5:5000/giris", "http://192.168.1.5:5000");
        e("https://host.org:443", "https://host.org");
        e("http://host.org:80/", "http://host.org");
        e("https://sayim.hastane.org:8443/x", "https://sayim.hastane.org:8443");
        e("javascript:alert(1)", null);
        e("ftp://x.com", null);
        e("https://kul:sif@evil.com", null);
        e("sunucu: https://x.trycloudflare.com bla bla", "https://x.trycloudflare.com");
        e("merhaba", null);
        e("1021250670", null);
        e("", null);
        e(null, null);
        e("https://", null);
        e("localhost:5000", "http://localhost:5000");
        System.out.println("ServerUrl: " + ok + " geçti, " + fail + " başarısız");
        System.exit(fail == 0 ? 0 : 1);
    }
}
