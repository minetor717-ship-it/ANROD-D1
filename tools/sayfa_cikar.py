# -*- coding: utf-8 -*-
"""
sayim_programi.py içindeki telefon sayfasını (HTML) ve hizmet işçisini (SERVICE_WORKER) APK'nın assets klasörüne çıkarır.

Bunlar yalnızca YEDEK kopyadır: telefon sunucuya ulaşabildiğinde sayfayı hep sunucudan alır (böylece sunucu güncellenince APK'yı
yeniden derlemek gerekmez); sunucu hiç görülmediyse ya da kapalıysa bu yedek açılır. Sayfa değişince bir kez çalıştırmanız yeter:

    py tools/sayfa_cikar.py  ..\\sayim_programi\\sayim_programi.py
"""
import ast
import os
import sys

HEDEF = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    kaynak = sys.argv[1]
    with open(kaynak, "r", encoding="utf-8-sig") as f:
        agac = ast.parse(f.read())
    degerler = {}
    for d in agac.body:
        if isinstance(d, ast.Assign) and len(d.targets) == 1 and isinstance(d.targets[0], ast.Name):
            ad = d.targets[0].id
            if ad in ("HTML", "SERVICE_WORKER") and isinstance(d.value, ast.Constant) and isinstance(d.value.value, str):
                degerler[ad] = d.value.value
    eksik = [a for a in ("HTML", "SERVICE_WORKER") if a not in degerler]
    if eksik:
        print("Bulunamadı:", ", ".join(eksik))
        return 1
    os.makedirs(HEDEF, exist_ok=True)
    for ad, dosya in (("HTML", "sayfa.html"), ("SERVICE_WORKER", "sw.js")):
        yol = os.path.join(HEDEF, dosya)
        with open(yol, "w", encoding="utf-8", newline="\n") as f:
            f.write(degerler[ad])
        print("Yazıldı:", os.path.normpath(yol), len(degerler[ad]), "karakter")
    return 0


if __name__ == "__main__":
    sys.exit(main())
