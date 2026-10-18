# İlerleme Kaydı

---

## [2026-10-18] — Çoklu dil (i18n) altyapısı + İngilizce/Türkçe

**Faz:** 1-2 — Altyapı + Java bağlama
**Durum:** Tamamlandı (İngilizce+Türkçe); İspanyolca/Fransızca/Almanca/Rusça çeviri sürüyor

### Yapılanlar
- Android string resource altyapısı kuruldu: values/, values-tr/es/fr/de/ru/
- 115 string anahtarı tanımlandı (36'sı format'lı: %1$s, %1$02d vb.)
- MainActivity, ParkCamService, CameraEngine, Mp4Recorder'daki tüm kullanıcı-görünür
  Türkçe metinler getString(R.string.x) ile değiştirildi
- recoverPending() null döndürecek şekilde düzeltildi ("0 kayıt" prefix karşılaştırması kaldırıldı)
- CAM_NAMES statik dizisi MainActivity'de getString ile oluşturuluyor
- values/strings.xml (İngilizce varsayılan) + values-tr/strings.xml (Türkçe) yazıldı
- Derleme doğrulandı (aapt2 çoklu locale otomatik derliyor, R.string çözülüyor)

### Oluşturulan/Değiştirilen Dosyalar
- `app/src/main/res/values/strings.xml` — İngilizce varsayılan (115 anahtar)
- `app/src/main/res/values-tr/strings.xml` — Türkçe
- `app/src/main/java/.../MainActivity.java` — UI metinleri → getString
- `app/src/main/java/.../ParkCamService.java` — bildirim/durum/log → getString
- `app/src/main/java/.../CameraEngine.java` — kurulum log → getString
- `app/src/main/java/.../Mp4Recorder.java` — hata/kurtarma → getString

### Kararlar
- Kapsam: kullanıcı-görünür sınıflar (yukarıdaki 4). ResearchLab/ScanEngine/
  ApkResearch/LocalDiscovery/LocalNetwork/DiLinkPassword dahili → kapsam dışı
  (UI'da gösterilmiyor).
- Android standart i18n: sistem dili → otomatik locale seçimi, build değişmez.

### Sonraki Adım
- 4 dilin (es/fr/de/ru) çevirisi tamamlanınca anahtar tutarlılığı doğrula + derle + test

---

## [2026-10-18] — 6 dil çevirisi tamamlandı + derleme doğrulandı

**Faz:** 3-4 — Çeviri + Doğrulama
**Durum:** Tamamlandı (araç testi hariç — araç erişilemez)

### Yapılanlar
- İspanyolca, Fransızca, Almanca, Rusça strings.xml paralel subagent'larla çevrildi
- Her dilde 115 anahtar, format belirteçleri (%1$s, %1$02d vb.) birebir korundu
- Doğrulama scripti: 5 dilin tümü İngilizce referansla OK (eksik/fazla anahtar yok,
  format uyumsuzluğu yok)
- Derleme: aapt2 5 dili (tr/es/fr/de/ru + varsayılan en) resources.arsc'ye derledi;
  aapt2 dump ile locale listesi doğrulandı
- R.string referansları çözüldü, APK imzalandı

### Oluşturulan/Değiştirilen Dosyalar
- `app/src/main/res/values-es/strings.xml` — İspanyolca
- `app/src/main/res/values-fr/strings.xml` — Fransızca
- `app/src/main/res/values-de/strings.xml` — Almanca
- `app/src/main/res/values-ru/strings.xml` — Rusça

### Sorunlar / Notlar
- Araç testi yapılamadı (WiFi düştü, araç erişilemez). Android standart i18n
  olduğu için cihaz testi olmadan da sistem diline göre otomatik seçim garantili.
  Araç açıldığında test edilebilir.

### Sonraki Adım
- (Opsiyonel) Araçta sistem dili değiştirip görsel doğrulama

---

## [2026-10-18] — Kesilen kayıt kurtarma + ADB kararlılığı + "Son kaydı kaydet" butonu

**Faz:** Hata düzeltme + özellik
**Durum:** Tamamlandı (buton araç testi bekliyor)

### Yapılanlar
- Journal kurtarma düzeltildi: remux HEVC yerine kayıt codec'ini (AVC) kullanıyor
  (journal v2 başlıkta MIME); journal adı MediaStore gerçek display_name ile
  eşleşiyor (" (N)" dahil). Araçta doğrulandı: kesilen kayıt 59 sn geçerli mp4
  olarak kurtarıldı.
- ADB kararsızlığı: BootReceiver boot-adb thread önce 5555 açık mı bakıyor,
  açıksa property yazmıyor (adbd gereksiz restart → bağlantı kopması önlendi).
- "Son kaydı kaydet" butonu: kayıt süresi satırının yanında, yalnız kayıt
  sürerken görünür. Basınca flushSegments() 4 kameranın o anki segmentini hemen
  kapatır (oynatılabilir mp4) + kesintisiz yeni dosyada devam. Olay anında
  kaydı 10 dk beklemeden izleyebilmek için.
- 6 dile 4 yeni string (btn_save_now, toast_save_now, log_flush_*).

### Oluşturulan/Değiştirilen Dosyalar
- `.../Mp4Recorder.java` — journal v2 + codec + display_name eşleşmesi
- `.../BootReceiver.java` — 5555 önce kontrol
- `.../ParkCamService.java` — flushSegments() + saveNow action
- `.../MainActivity.java` — "Son kaydı kaydet" butonu
- `app/src/main/res/values*/strings.xml` — 4 yeni anahtar x6 dil

### Sonraki Adım
- Araç açılınca: kur, kayıt sürerken butona bas, o anki segment oynatılabilir mp4 oluyor mu test et

---

## [2026-10-18] — Performans: 4 kamera kayıtta yavaşlama (Öncelik 1)

**Faz:** 5 — Performans (Öncelik 1)
**Görev:** FPS düşür + C istemci sızıntısı + arka plan önizleme
**Durum:** Tamamlandı (kod + derleme); araç testi bekliyor

### Bağlam
Başka bir modelin 11 maddelik performans önerisi gerçek kodla satır satır
doğrulandı. Çoğu gerçek; birkaçı eski/çözülmüş mimariyi tarif ettiği için
reddedildi (madde 3/5: pullerLoop zaten kısa-ömürlü işçi + watchdog). Madde 1'in
"dosya yolunu kaldır" çözümü imkânsız (qcam_patched kapalı kaynak qcarcam_test);
gerçekçi kazanç FPS düşürmek. Kullanıcı Öncelik 1 + 6 fps seçti.

### Yapılanlar
- qcam_loop8.c: g_dumpframe 3→5 (10→6 fps). qcam_patched'e -dumpFrame=5 geçer;
  ham .raw yazma/rename + diskten geri okuma + TCP yükü ~%40 azalır. Bu, 4 kamera
  kayıtta sistemi yavaşlatan ASIL darboğazdı (saniyede ~157 MB dosya trafiği).
- qcam_loop8.c: QDF/QFPS env okuması spawn_tool()'dan ÖNCEYE taşındı — eskiden
  ilk süreç varsayılan değerle başlayıp env ayarını kaçırıyordu (madde 7).
- qcam_loop8.c: reap_dead_clients() eklendi; broadcast_frames ölü istemcileri
  (alive=0) listeden çıkarıp free etmiyordu → Java ~8 sn'de bir yeniden
  bağlandığından liste sınırsız büyüyordu (madde 10). Her broadcast sonrası +
  her 300 turda (kare gelmese de) temizlenir.
- QCAM_VERSION 10→11, HELPER_VER 10→11 (senkron — cihazdaki binary MD5+sürüm
  ile yenilenir).
- MainActivity: onStop()'ta WANT_LIVE=false, onStart()'ta liveOn ise geri aç.
  Görünmeyen önizleme (başka uygulama/ekran kapalı) 250 ms'de bir tam çözünürlük
  YUV→RGB + yeni Bitmap üretmesin (madde 9). KAYIT bundan ETKİLENMEZ.
- Sürüm 4.15.1→4.15.2, versionCode 81→82.

### Oluşturulan/Değiştirilen Dosyalar
- `tools/native/qcam_loop8.c` — fps 6, QDF erken okuma, istemci reaper, v11
- `app/src/main/java/.../CameraEngine.java` — HELPER_VER 11
- `app/src/main/java/.../MainActivity.java` — onStart/onStop WANT_LIVE yönetimi
- `build.py` — VERSION 4.15.2
- `app/src/main/AndroidManifest.xml` — versionCode 82, versionName 4.15.2
- `dist/DiLink-Inspector-4.15.2.apk` — SHA-256 6019eb502002c1586ca223d36dbccb068b0b4c4751ae0104e81bfbbd7de22ccb

### Alınan Kararlar
- FPS 6 (dumpFrame=5): dashcam için yeterli, plaka/olay okunur, yük ~%40 azalır.
- Madde 1'in dosya yolunu kaldırma kısmı YAPILMADI: qcam_patched kapalı kaynak,
  belleğe yazdırılamaz. FPS düşürmek pratik eşdeğeri.
- Madde 2 (Flexible YUV kopyalama) ERTELENDİ: muhtemelen aktif değil (cihazda
  QCOM SemiPlanar seçiliyor olmalı). durum.txt COLOR_FORMAT ile doğrulanacak.
- Madde 3/5 REDDEDİLDİ: mevcut mimari (kısa-ömürlü işçi + watchdog) zaten çözmüş.

### Sorunlar / Notlar
- C'de reap, client_thread'in detached olması ve bitişte alive=0 set edip c'ye
  dokunmadan dönmesine dayanıyor — reaper güvenle free eder.
- Test edilecek: FPS gerçekten 6 mı (RD_FRAME artış hızı), sistem akıcılığı,
  önizleme arka plan/geri dönüş, kayıt oynatılabilirlik, COLOR_FORMAT.

### Sonraki Adım
- Araç açıldığında 4.15.2'yi kur, yukarıdaki test listesini uygula. Öncelik 1
  yeterli gelirse Faz 6 (Öncelik 2/3) opsiyonel kalır.
