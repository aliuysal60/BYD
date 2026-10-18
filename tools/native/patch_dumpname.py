#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
qcam_patched (qcarcam_test yamalı kopyası) → kamera başına qcam_patched_c0..c3 üretir.

Neden gerekli: qcarcam_test dökümü "frame_%d_%i.raw" adıyla yazar; %d KAMERA ID'si
değil, cihaz DİZİ indeksidir ve tek girişli config'te her zaman 0'dır. Dört örnek
aynı ada yazıp birbirini ezer. Bu betik, format dizisindeki '_' ayraç karakterini
kamera rakamıyla değiştirir (aynı uzunlukta bayt yaması):
    frame_%d_%i.raw  →  frame_%d1%i.raw   (kamera 1)
Böylece her kamera kendi dosya adına döker: frame_0<n>*.raw

Kullanım:
    python patch_dumpname.py /yol/qcam_patched          # c0..c3 üretir
    python patch_dumpname.py /yol/qcam_patched -o /yol  # çıktı dizini seçer

Ürettikten sonra: app/src/main/assets/bin/ altına kopyalayın (adları
qcam_patched_c0..c3 olmalı) ve araçta chmod 755 yapılmasını kurulum yapmasına
bırakın (installHelpers her kurulumda 755'e çeker).
"""
import os
import sys

PAT = b"/data/local/tmp/frame_%d_%i.raw"


def patch(data: bytes, cam: int) -> bytes:
    i = data.find(PAT)
    if i < 0:
        raise SystemExit("hata: döküm format dizisi bulunamadı (farklı binary mi?)")
    sep = i + PAT.index(b"%d_") + 2  # '_' karakterinin ofseti
    out = bytearray(data)
    if cam:
        out[sep] = ord(str(cam))
    return bytes(out)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(1)
    src = sys.argv[1]
    outdir = sys.argv[sys.argv.index("-o") + 1] if "-o" in sys.argv else os.path.dirname(os.path.abspath(src)) or "."
    data = open(src, "rb").read()
    for cam in (0, 1, 2, 3):
        dst = os.path.join(outdir, "qcam_patched_c%d" % cam)
        open(dst, "wb").write(patch(data, cam))
        print("yazildi:", dst)


if __name__ == "__main__":
    main()
