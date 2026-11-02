/* Hastane Sayım APK – WebView köprü betiği.
   Sayfa sunucudan gelirken yerel köprü bunu <head> içine ekler (sunucu koduna dokunulmaz). Yalnızca APK içinde (window.SayimApp varken) çalışır.
   Amaç: Android WebView'de olmayan üç tarayıcı özelliğini yerel (native) karşılıklarla tamamlamak ve sayfanın bildiği API'leri aynen sürdürmek:
     1) webkitSpeechRecognition  → Android SpeechRecognizer      (sesli komut)
     2) BarcodeDetector          → ML Kit (cihazda, çevrimdışı)   (canlı QR/barkod tarama; kareler sunucuya gönderilmez)
     3) navigator.clipboard      → Android panosu                 (“Panodan al”)
   Ek: geri tuşu için açık ekranları kapatan window.__sayimGeri(), Ayarlar'a “Sunucu bağlantısı” bölümü. */
(function () {
  'use strict';
  var A = window.SayimApp;
  if (!A) return;
  window.__sayimAPK = true;

  function guvenli(f) { try { return f(); } catch (e) { return undefined; } }

  /* ---------------------------------------------------------------- 1) Sesli komut */
  var tanimalar = {}, tanimaSira = 0;

  function Tanima() {
    this.lang = 'tr-TR';
    this.continuous = false;
    this.interimResults = false;
    this.maxAlternatives = 1;
    this.onstart = this.onend = this.onerror = this.onresult = this.onspeechend = this.onnomatch = null;
    this._id = ++tanimaSira;
    this._acik = false;
    this._dinleyiciler = {};
    tanimalar[this._id] = this;
  }
  Tanima.prototype.addEventListener = function (tur, f) { (this._dinleyiciler[tur] = this._dinleyiciler[tur] || []).push(f); };
  Tanima.prototype.removeEventListener = function (tur, f) {
    var l = this._dinleyiciler[tur] || [], i = l.indexOf(f);
    if (i >= 0) l.splice(i, 1);
  };
  Tanima.prototype._yay = function (tur, olay) {
    olay.type = tur;
    olay.target = this;
    var h = this['on' + tur];
    if (typeof h === 'function') guvenli(function () { h.call(olay.target, olay); });
    (this._dinleyiciler[tur] || []).slice().forEach(function (f) { guvenli(function () { f.call(olay.target, olay); }); });
  };
  Tanima.prototype.start = function () {
    if (this._acik) { var e = new Error('recognition has already started'); e.name = 'InvalidStateError'; throw e; }
    this._acik = true;
    A.srBaslat(this._id, String(this.lang || 'tr-TR'), Math.max(1, this.maxAlternatives | 0));
  };
  Tanima.prototype.stop = function () { if (this._acik) A.srDurdur(this._id, false); };
  Tanima.prototype.abort = function () { if (this._acik) A.srDurdur(this._id, true); };

  window.__sayimSR = {
    basladi: function (id) { var t = tanimalar[id]; if (t) t._yay('start', {}); },
    sonuc: function (id, json) {
      var t = tanimalar[id];
      if (!t) return;
      var alt = guvenli(function () { return JSON.parse(json); }) || [];
      if (!alt.length) return;
      var r = alt.map(function (s, i) { return {transcript: String(s), confidence: i === 0 ? 0.9 : 0.5}; });
      r.isFinal = true;
      t._yay('result', {resultIndex: 0, results: [r]});
    },
    hata: function (id, kod) { var t = tanimalar[id]; if (t) t._yay('error', {error: String(kod), message: ''}); },
    bitti: function (id) {
      var t = tanimalar[id];
      if (!t) return;
      t._acik = false;
      t._yay('end', {});
    }
  };
  if (guvenli(function () { return A.srVar(); })) {
    window.SpeechRecognition = Tanima;
    window.webkitSpeechRecognition = Tanima;
  }

  /* ---------------------------------------------------------------- 2) BarcodeDetector (ML Kit) */
  var bdBekleyen = {}, bdSira = 0;
  var BD_BICIMLER = ['aztec', 'codabar', 'code_39', 'code_93', 'code_128', 'data_matrix', 'ean_8', 'ean_13', 'itf', 'pdf417', 'qr_code', 'upc_a', 'upc_e'];
  var BD_CV = document.createElement('canvas');

  function BarcodeDetector(secenek) {
    var f = secenek && secenek.formats;
    this._bicimler = (f && f.length) ? f.map(String) : null;
  }
  BarcodeDetector.getSupportedFormats = function () { return Promise.resolve(BD_BICIMLER.slice()); };
  BarcodeDetector.prototype.detect = function (kaynak) {
    var bicimler = this._bicimler;
    return gorselHazirla(kaynak).then(function (b64) {
      if (!b64) return [];
      return new Promise(function (res) {
        var id = ++bdSira;
        var tm = setTimeout(function () { if (bdBekleyen[id]) { delete bdBekleyen[id]; res([]); } }, 4000);
        bdBekleyen[id] = function (liste) {
          clearTimeout(tm);
          res(bicimler ? liste.filter(function (x) { return bicimler.indexOf(x.format) >= 0; }) : liste);
        };
        try { A.barkodOku(id, b64); } catch (e) { clearTimeout(tm); delete bdBekleyen[id]; res([]); }
      });
    }, function () { return []; });
  };
  window.__sayimBD = {
    sonuc: function (id, json) {
      var f = bdBekleyen[id];
      if (!f) return;
      delete bdBekleyen[id];
      var liste = guvenli(function () { return JSON.parse(json); }) || [];
      f(liste.map(function (x) {
        return {rawValue: String(x.raw), format: String(x.format), boundingBox: null, cornerPoints: []};
      }));
    }
  };

  /* video / canvas / img / ImageBitmap / Blob → en çok 1280 px JPEG (base64, öneksiz) */
  function gorselHazirla(k) {
    return new Promise(function (res) {
      if (k && typeof Blob !== 'undefined' && k instanceof Blob && typeof createImageBitmap === 'function') {
        createImageBitmap(k).then(function (b) { res(b); }, function () { res(null); });
      } else { res(k); }
    }).then(function (src) {
      if (!src) return '';
      var w = src.videoWidth || src.naturalWidth || src.width, h = src.videoHeight || src.naturalHeight || src.height;
      if (!w || !h) return '';
      var olcek = Math.min(1, 1280 / Math.max(w, h));
      BD_CV.width = Math.max(1, Math.round(w * olcek));
      BD_CV.height = Math.max(1, Math.round(h * olcek));
      var g = BD_CV.getContext('2d');
      g.drawImage(src, 0, 0, BD_CV.width, BD_CV.height);
      var u = BD_CV.toDataURL('image/jpeg', 0.85);
      var i = u.indexOf(',');
      return i >= 0 ? u.slice(i + 1) : '';
    });
  }
  window.BarcodeDetector = BarcodeDetector;      // sayfa 'BarcodeDetector' in window ile bakar: artık kareler telefonda okunur

  /* ---------------------------------------------------------------- 3) Pano */
  guvenli(function () {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        readText: function () { return new Promise(function (res, rej) { try { res(String(A.panoOku() || '')); } catch (e) { rej(e); } }); },
        writeText: function (t) { return new Promise(function (res, rej) { try { A.panoYaz(String(t)); res(); } catch (e) { rej(e); } }); }
      }
    });
  });

  /* ---------------------------------------------------------------- Geri tuşu: açık ekranı kapatır; bir şey kapattıysa true */
  window.__sayimGeri = function () {
    var g = guvenli(function () {
      var gorsel = document.getElementById('gorsel');
      if (gorsel && !gorsel.classList.contains('gizli') && typeof gorselKapat === 'function') { gorselKapat(); return true; }
      if (document.body.classList.contains('tarama') && typeof barkodKapat === 'function') { barkodKapat(); return true; }
      var foto = document.getElementById('sesFoto') || document.getElementById('fotoCekEkrani');
      if (foto && !foto.classList.contains('gizli') && typeof sesFotoKapat === 'function') { sesFotoKapat(); return true; }
      var ayar = document.getElementById('ayarPanel');
      if (ayar && !ayar.classList.contains('gizli') && typeof ayarAc === 'function') { ayarAc(false); return true; }
      if (typeof tumAcikMi === 'function' && tumAcikMi() && typeof tumKapat === 'function') { tumKapat(); return true; }
      return false;
    });
    return g === true;
  };

  /* ---------------------------------------------------------------- Ayarlar: sunucu bağlantısı bölümü (yalnızca textContent) */
  function ayarEkle() {
    var panel = document.getElementById('ayarPanel');
    if (!panel || document.getElementById('apkBaglanti')) return;
    var kutu = document.createElement('div');
    kutu.id = 'apkBaglanti';
    kutu.className = 'altgrup';
    var b = document.createElement('b');
    b.textContent = '📡 Sunucu bağlantısı';
    b.style.display = 'block';
    var adres = document.createElement('div');
    adres.style.cssText = 'margin:6px 0;word-break:break-all;font-size:14px';
    adres.textContent = 'Bağlı sunucu: ' + (guvenli(function () { return A.sunucu(); }) || '—');
    var not = document.createElement('small');
    not.style.display = 'block';
    not.textContent = 'Tünel adresi değişirse bilgisayardaki yeni QR\u2019ı okutun. Telefonda bekleyen kayıtlar ve ayarlar adres değişince de korunur.';
    var d = document.createElement('button');
    d.type = 'button';
    d.className = 'tam';
    d.style.marginTop = '10px';
    d.textContent = '📷 QR ile bağlan / sunucuyu değiştir';
    d.addEventListener('click', function () { guvenli(function () { A.qrBaglan(); }); });
    var s = document.createElement('small');
    s.style.cssText = 'display:block;margin-top:8px;color:var(--soluk)';
    s.textContent = 'Uygulama sürümü: ' + (guvenli(function () { return A.surum(); }) || '');
    kutu.appendChild(b); kutu.appendChild(adres); kutu.appendChild(not); kutu.appendChild(d); kutu.appendChild(s);
    var kapat = document.getElementById('ayarKapat');
    if (kapat && kapat.parentNode === panel) panel.insertBefore(kutu, kapat); else panel.appendChild(kutu);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ayarEkle); else ayarEkle();
})();
