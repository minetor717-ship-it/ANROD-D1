const fs = require('fs'), vm = require('vm'), assert = require('assert');
const kod = fs.readFileSync(__dirname + '/../app/src/main/assets/bridge.js', 'utf8');
let ok = 0, fail = 0;
async function t(ad, f) { try { await f(); ok++; console.log('  OK  ', ad); } catch (e) { fail++; console.log('  FAIL', ad, '\n      ', e && e.message); } }

function el(tag) {
  const o = { tag, style: {}, children: [], listeners: {}, _cls: new Set(), textContent: '', className: '', id: '',
    classList: { contains: c => o._cls.has(c), add: c => o._cls.add(c), remove: c => o._cls.delete(c) },
    appendChild(c) { c.parentNode = o; o.children.push(c); return c; },
    insertBefore(c, ref) { c.parentNode = o; const i = o.children.indexOf(ref); o.children.splice(i < 0 ? o.children.length : i, 0, c); return c; },
    addEventListener(t_, f) { (o.listeners[t_] = o.listeners[t_] || []).push(f); },
    getContext() { return { drawImage(src, x, y, w, h) { o._cizim = [w, h]; } }; },
    toDataURL() { return 'data:image/jpeg;base64,QUJD'; } };
  return o;
}

function ortam(app, ekstra) {
  const ayarPanel = el('section'); ayarPanel.id = 'ayarPanel';
  const ayarKapat = el('button'); ayarKapat.id = 'ayarKapat'; ayarPanel.appendChild(ayarKapat);
  ayarPanel._cls.add('gizli');
  const gorsel = el('div'); gorsel._cls.add('gizli');
  const ogeler = { ayarPanel, ayarKapat, gorsel };
  const body = el('body');
  const doc = { readyState: 'complete', body, createElement: el, getElementById: id => ogeler[id] || null, addEventListener() {} };
  const nav = {};
  const win = { SayimApp: app };
  const sb = Object.assign({ window: win, document: doc, navigator: nav, setTimeout, clearTimeout, Promise, JSON, Math, console }, ekstra || {});
  win.document = doc; win.navigator = nav;
  vm.createContext(sb);
  vm.runInContext(kod, sb);
  return { win, doc, nav, sb, ogeler, body };
}

(async () => {
  console.log('== APK dışında (SayimApp yok)');
  await t('hiçbir şey eklenmez', () => { const o = ortam(undefined); assert.strictEqual(o.win.BarcodeDetector, undefined); assert.strictEqual(o.win.webkitSpeechRecognition, undefined); });

  console.log('== Sesli komut');
  const cagri = [];
  const app = { srVar: () => true, srBaslat: (...a) => cagri.push(['bas', ...a]), srDurdur: (...a) => cagri.push(['dur', ...a]),
    barkodOku: (...a) => cagri.push(['bd', ...a]), panoOku: () => 'https://sbu2.saglik.gov.tr/QR/QR.aspx?kno=1021250670', panoYaz: t_ => cagri.push(['yaz', t_]),
    sunucu: () => 'x.trycloudflare.com', surum: () => '1.0', qrBaglan: () => cagri.push(['qr']) };
  const o = ortam(app);
  const SR = o.win.webkitSpeechRecognition;
  await t('webkitSpeechRecognition ve SpeechRecognition tanımlı', () => { assert.ok(SR); assert.strictEqual(o.win.SpeechRecognition, SR); });
  const r = new SR();
  const olaylar = [];
  r.lang = 'tr-TR'; r.continuous = true; r.maxAlternatives = 3;
  r.onstart = () => olaylar.push('start');
  r.onresult = e => olaylar.push(['result', e.resultIndex, e.results.length, e.results[0].isFinal, e.results[0].length, e.results[0][0].transcript, e.results[0][1].transcript]);
  r.onerror = e => olaylar.push(['error', e.error]);
  r.onend = () => olaylar.push('end');
  await t('start native srBaslat(id, dil, alternatif) çağırır', () => { r.start(); assert.deepStrictEqual(cagri[0].slice(0, 1).concat(cagri[0].slice(2)), ['bas', 'tr-TR', 3]); });
  await t('ikinci start InvalidStateError (Chrome ile aynı)', () => { assert.throws(() => r.start(), e => e.name === 'InvalidStateError'); });
  const id = cagri[0][1];
  await t('native sonuç → onresult (isFinal, alternatifler)', () => {
    o.win.__sayimSR.basladi(id);
    o.win.__sayimSR.sonuc(id, JSON.stringify(['kaydet', 'kayıt et']));
    assert.deepStrictEqual(olaylar[0], 'start');
    assert.deepStrictEqual(olaylar[1], ['result', 0, 1, true, 2, 'kaydet', 'kayıt et']);
  });
  await t('hata ve bitiş: onerror, onend; sonra yeniden start serbest', () => {
    o.win.__sayimSR.hata(id, 'not-allowed'); o.win.__sayimSR.bitti(id);
    assert.deepStrictEqual(olaylar.slice(2), [['error', 'not-allowed'], 'end']);
    r.start(); assert.strictEqual(cagri[cagri.length - 1][0], 'bas');
  });
  await t('stop/abort native durdurmayı çağırır', () => { r.stop(); assert.deepStrictEqual(cagri[cagri.length - 1].slice(0, 1).concat(cagri[cagri.length - 1].slice(2)), ['dur', false]); r.abort(); assert.strictEqual(cagri[cagri.length - 1][2], true); });
  await t('srVar false ise sesli komut tanımlanmaz (sayfa kendi mesajını gösterir)', () => { const o2 = ortam(Object.assign({}, app, { srVar: () => false })); assert.strictEqual(o2.win.webkitSpeechRecognition, undefined); });

  console.log('== BarcodeDetector');
  await t("'BarcodeDetector' in window ve biçimler", async () => {
    assert.ok('BarcodeDetector' in o.win);
    const f = await o.win.BarcodeDetector.getSupportedFormats();
    assert.ok(f.includes('qr_code') && f.includes('ean_13') && f.includes('code_128'));
  });
  await t('detect(video): 1280 px tuvale küçültür, JPEG (öneksiz base64) native’e gider, sonuç rawValue/format', async () => {
    const det = new o.win.BarcodeDetector({ formats: ['qr_code', 'ean_13'] });
    const p = det.detect({ videoWidth: 1920, videoHeight: 1080 });
    await new Promise(r_ => setTimeout(r_, 10));
    const c = cagri.filter(x => x[0] === 'bd').pop();
    assert.strictEqual(c[2], 'QUJD');
    o.win.__sayimBD.sonuc(c[1], JSON.stringify([{ raw: 'https://x.y/z', format: 'qr_code' }, { raw: '123', format: 'code_128' }]));
    const s = await p;
    assert.strictEqual(s.length, 1);            // code_128 istenen biçimlerde değil: süzüldü
    assert.strictEqual(s[0].rawValue, 'https://x.y/z'); assert.strictEqual(s[0].format, 'qr_code');
  });
  await t('boş sonuç → []', async () => {
    const det = new o.win.BarcodeDetector();
    const p = det.detect({ width: 640, height: 480 });
    await new Promise(r_ => setTimeout(r_, 10));
    const c = cagri.filter(x => x[0] === 'bd').pop();
    o.win.__sayimBD.sonuc(c[1], '[]');
    assert.strictEqual(JSON.stringify(await p), '[]');
  });
  await t('boyutsuz kaynak → [] (native çağrılmaz)', async () => {
    const n = cagri.length; const s = await new o.win.BarcodeDetector().detect({ videoWidth: 0, videoHeight: 0 });
    assert.strictEqual(JSON.stringify(s), '[]'); assert.strictEqual(cagri.length, n);
  });

  console.log('== Pano');
  await t('navigator.clipboard.readText native panoyu okur', async () => { assert.strictEqual(await o.nav.clipboard.readText(), 'https://sbu2.saglik.gov.tr/QR/QR.aspx?kno=1021250670'); });

  console.log('== Geri tuşu');
  const kapandi = [];
  const o3 = ortam(app, { gorselKapat: () => kapandi.push('gorsel'), barkodKapat: () => kapandi.push('tarama'), ayarAc: x => kapandi.push('ayar' + x), tumAcikMi: () => false, tumKapat: () => kapandi.push('tum') });
  await t('hiçbir ekran açık değilse false', () => { assert.strictEqual(o3.win.__sayimGeri(), false); assert.strictEqual(JSON.stringify(kapandi), '[]'); });
  await t('fotoğraf görüntüleyici açıkken onu kapatır', () => { o3.ogeler.gorsel._cls.clear(); assert.strictEqual(o3.win.__sayimGeri(), true); assert.strictEqual(JSON.stringify(kapandi), '["gorsel"]'); });
  await t('tarayıcı (body.tarama) açıkken kapatır', () => { o3.ogeler.gorsel._cls.add('gizli'); o3.body._cls.add('tarama'); assert.strictEqual(o3.win.__sayimGeri(), true); assert.strictEqual(kapandi.slice(-1)[0], 'tarama'); });
  await t('ayar paneli açıkken kapatır', () => { o3.body._cls.delete('tarama'); o3.ogeler.ayarPanel._cls.delete('gizli'); assert.strictEqual(o3.win.__sayimGeri(), true); assert.strictEqual(kapandi.slice(-1)[0], 'ayarfalse'); });

  console.log('== Ayarlar bölümü');
  await t('“Sunucu bağlantısı” bölümü Tamam düğmesinden önce eklenir, innerHTML kullanılmaz', () => {
    const p = o.ogeler.ayarPanel;
    assert.strictEqual(p.children.length, 2);
    assert.strictEqual(p.children[0].id, 'apkBaglanti'); assert.strictEqual(p.children[1].id, 'ayarKapat');
    const txt = JSON.stringify(p.children[0].children.map(c => c.textContent));
    assert.ok(txt.includes('x.trycloudflare.com') && txt.includes('1.0') && txt.includes('QR ile bağlan'));
    assert.ok(!kod.includes('innerHTML'));
  });
  await t('QR düğmesi native qrBaglan çağırır', () => {
    const d = o.ogeler.ayarPanel.children[0].children.find(c => c.tag === 'button');
    d.listeners.click[0](); assert.strictEqual(cagri[cagri.length - 1][0], 'qr');
  });
  console.log('\nbridge.js: ' + ok + ' geçti, ' + fail + ' başarısız');
  process.exit(fail ? 1 : 0);
})();
