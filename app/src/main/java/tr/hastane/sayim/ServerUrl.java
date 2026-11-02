package tr.hastane.sayim;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** QR/elle yazılan metinden sunucu adresini (şema://alan[:port]) ayıklar. Android'e bağımlı değildir (JVM'de test edilir). */
public final class ServerUrl {
    private ServerUrl() {}

    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s\"'<>]+");
    private static final Pattern BARE = Pattern.compile("(?i)^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:\\d{2,5})?(/.*)?$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d{2,5})?(/.*)?$");

    /** Geçerli bir sunucu kökü ("https://x.trycloudflare.com" ya da "http://192.168.1.5:5000") ya da null. */
    public static String normalize(String ham) {
        if (ham == null) return null;
        String t = ham.trim();
        if (t.isEmpty() || t.length() > 2000) return null;
        String aday;
        Matcher m = URL.matcher(t);
        if (m.find()) {
            aday = m.group();
        } else if (BARE.matcher(t).matches() || IPV4.matcher(t).matches() || t.toLowerCase(Locale.ROOT).startsWith("localhost")) {
            boolean yerel = IPV4.matcher(t).matches() || t.toLowerCase(Locale.ROOT).startsWith("localhost");
            aday = (yerel ? "http://" : "https://") + t;       // şemasız yazıldıysa: yerel ağ http, alan adı https
        } else {
            return null;
        }
        try {
            URI u = new URI(aday);
            String sema = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost();
            if (!(sema.equals("http") || sema.equals("https")) || host == null || host.isEmpty()) return null;
            if (u.getUserInfo() != null) return null;           // https://kullanici@host biçimi reddedilir
            int port = u.getPort();
            boolean varsayilan = port == -1 || (sema.equals("http") && port == 80) || (sema.equals("https") && port == 443);
            return sema + "://" + host.toLowerCase(Locale.ROOT) + (varsayilan ? "" : ":" + port);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ekranda kısa gösterim: şemasız alan adı. */
    public static String kisa(String kok) {
        if (kok == null) return "";
        return kok.replaceFirst("(?i)^https?://", "");
    }
}
