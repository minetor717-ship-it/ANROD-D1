# Hastane Sayım – Android istemcisi (APK projesi)

Sayım programının (`sayim_programi.py`, sürüm 38) telefonda tarayıcıda açılan bölümünün ayrı bir Android uygulamasıdır.
**Bilgisayardaki Flask sunucusuna dokunulmadı**; aynı API'ye (`/api/giris`, `/api/kayit`, `/api/kontrol`, `/api/qr_coz`, `/api/foto_oku`,
`/api/son`, `/api/tum`, `/foto/…`, `/api/geri_al` …) bağlanır. Bağlanırken bilgisayardaki QR okutulur.

## Nasıl çalışır
```
 WebView ──http──▶ 127.0.0.1:38461  (uygulama içi yerel köprü, LocalProxy)  ──https/http──▶  Flask sunucusu (tünel ya da yerel ağ)
   │                      │ sayfa: sunucudan; sunucu yoksa son kopya / APK içindeki yedek
   │                      └ çerez, çok parçalı fotoğraf gövdesi, hata kodları olduğu gibi aktarılır
   └─ SayimApp köprüsü: ses komutu, ML Kit QR/barkod, pano, kamera/mikrofon izni, fotoğraf çekme/seçme
```
Telefon sayfası **hep aynı yerel adresten** açıldığı için tünel adresi her "Sistemi Başlat"ta değişse bile telefondaki **bekleyen (gönderilmemiş)
kayıtlar, cihaz kimliği ve ayarlar kaybolmaz** (tarayıcıda adres değişince bunlar kaybolurdu). Sayfa sunucudan alındığı için sunucu güncellenince APK'yı
yeniden derlemeniz gerekmez.

## Derleme
1. Android Studio (Koala+) ile bu klasörü açın; JDK 17 ve Android SDK 34 gerekir. Gradle kendiliğinden indirilir (internet gerekir).
2. **Build ▸ Build APK(s)** ya da komut satırında: `gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
3. Telefona kopyalayın; "bilinmeyen kaynaklardan yükleme"ye izin verin. (`assembleRelease` de debug anahtarıyla imzalanır; kendi anahtarınız için `app/build.gradle.kts`.)

> **Önemli:** Bu proje, Android SDK'sı ve internet olmayan bir ortamda yazıldı; **APK derlenmedi ve gerçek telefonda denenmedi.**
> Derleme hatası çıkarsa (ör. sürüm uyumsuzluğu) hata metnini iletin; düzeltiriz.

## İlk kullanım
1. Bilgisayarda programı açıp **Sistemi Başlat** deyin, QR çıkana kadar bekleyin.
2. Uygulamayı açın → kamera izni → ekrandaki QR'ı çerçeveye alın (netleşmezse ekrana dokunun, uzaksa iki parmakla yakınlaştırın; 🔦 fener var).
3. Adres `/api/durum` ile doğrulanır, kaydedilir, giriş ekranı gelir (ad + PIN, aynen eskisi gibi).
Adres değişince: Ayarlar (⚙) ▸ **QR ile bağlan / sunucuyu değiştir**, uygulama simgesine uzun basıp **QR ile bağlan** ya da sunucuya 3 kez
ulaşılamadığında çıkan kırmızı şeritteki düğme. Elle adres yazma, panodan alma ve son bağlanılanlar da var.

## Eski telefon sayfasındaki işlevler
| İşlev | APK'da |
|---|---|
| Giriş, kayıt, geri alma, son/tüm kayıtlar, künye kontrolü, zimmet/HEK uyarıları, sesli uyarı (TTS) | Aynı sayfa/JS; aynı API |
| Çevrimdışı kuyruk (IndexedDB) ve tekrar gönderim koruması (UUID) | Aynı; adres değişince de korunur |
| Canlı QR/barkod tarama | **Telefonda ML Kit** ile (kareler sunucuya gitmez); sayfanın netlik/fener/odak mantığı aynen |
| Fotoğraf Çek / Galeriden Ekle (çoklu), otomatik iyileştirme, QR/künye okuma | Kamera uygulaması / sistem seçicisi; okuma sunucuda (`/api/foto_oku`) eskisi gibi |
| Sesli komut | Android SpeechRecognizer (Chrome'un Web Speech'i WebView'de yok) |
| Panodan al, Paylaş ▸ Hastane Sayım, "QR'daki bağlantıyı aç" | Var (bağlantılar tarayıcıda açılır) |
| Geri tuşu | Açık ekranı (görüntüleyici, tarayıcı, ayarlar, tüm kayıtlar) kapatır; ana ekranda 2 kez basınca çıkar |
| Ekran | Uygulama açıkken kapanmaz |

## Bilinen sınırlar
- **Cloudflare Access** (e-posta kodu) açıksa bağlanamaz: köprü Access girişini yapamaz. Geçici (trycloudflare) adreste Access zaten kurulamaz.
- Sesli komutta Android her dinlemeyi tek cümlede bitirir; sayfa yeniden başlatır → komutlar arasında çok kısa boşluk olur. Sistem "bip"i dinleme sırasında kısılır
  (Rahatsız Etme modunda kısılamayabilir). Türkçe tanıma genelde internet ister.
- Port 38461 doluysa köprü sonraki porta geçer; o zaman telefon verisi ayrı tutulur (nadir).
- Yalnızca tek sunucu aktiftir (geçmişten seçilebilir).

## Sayfayı yedeklemek (isteğe bağlı)
`assets/sayfa.html` ve `sw.js`, sunucu hiç görülmemişken açılacak **yedek** kopyalardır. Sayfa değişince: `py tools/sayfa_cikar.py ..\sayim_programi\sayim_programi.py`

## Güvenlik notları
- Köprü yalnızca 127.0.0.1'e bağlanır ve yalnızca bilinen yolları (`/`, `/api/*`, `/foto/*`, `sw.js`, `manifest.json`, simgeler) aktarır; PIN/parola saklamaz (çerezler WebView'dedir).
- JS köprüsü (`SayimApp`) yalnızca yerel köprüden yüklenen sayfada çalışır; dış bağlantılar WebView'de değil tarayıcıda açılır.
- `allowBackup=false`: oturum/kuyruk bulut yedeğine girmez.

## Yapılan testler (bu ortamda)
- `LocalProxy` ↔ **gerçek `sayim_programi.py` Flask sunucusu**: 34 kontrol geçti – giriş/çerez, çok parçalı 2 fotoğraflı kayıt, aynı UUID ile tekrar gönderim, `/foto`, parçalı gövde,
  yol denetimi, sunucu kapanınca yedek sayfa ve bağlantı hatası (JDK ile). `ServerUrl` 19, `ServerCheck` 3, `bridge.js` 19 (Node, sahte DOM).
  Çalıştırma: `jvmtest/` içindeki dosyalar (`run_server.py` sunucuyu başlatır).
- **Test edilemeyenler:** Kotlin/XML derlemesi, gerçek kamera/mikrofon/ML Kit, WebView davranışları, gerçek tünel.
