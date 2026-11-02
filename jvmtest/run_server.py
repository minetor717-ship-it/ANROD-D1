import sys, os, time, tempfile
sys.path.insert(0, '/home/claude/src/sayim_programi')
import test_sayim as T          # tkinter/qrcode taklidini yapar ve gerçek sayim_programi.py'yi yükler
m = T.m
port = int(sys.argv[1])
tmp = tempfile.mkdtemp()
m.LOG_YOLU = os.path.join(tmp, 'log.txt'); m.log_kur()
depo = m.Depo(os.path.join(tmp, 'veri'))
ayar = dict(m.VARSAYILAN_AYAR); ayar.update(pin="483920", gizli_anahtar="k" * 64)
app = m.uygulama_olustur(depo, ayar)
s = m.Sunucu(); s.baslat(app, '127.0.0.1', port)
print("READY", tmp, flush=True)
time.sleep(3600)
