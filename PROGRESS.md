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
