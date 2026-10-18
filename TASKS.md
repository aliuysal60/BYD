# Görevler — Çoklu Dil (i18n) Desteği

Hedef: Tüm kullanıcıya görünen metinleri (UI, mesaj, buton, dialog, bildirim,
warning, hata, kurulum günlüğü) 6 dile çevir ve Android sistem diline göre
otomatik seç. Diller: Türkçe, İngilizce, İspanyolca, Fransızca, Almanca, Rusça.

## Faz 1: Altyapı ve string çıkarımı

> Hedef: Android string resource altyapısını kur, kullanıcı-görünür metinleri topla.

- [x] Kapsam belirle: kullanıcı-görünür sınıflar (MainActivity, ParkCamService,
      CameraEngine, Mp4Recorder) + hata/log mesajları. ResearchLab/ScanEngine/
      ApkResearch/LocalDiscovery/LocalNetwork/DiLinkPassword dahili → kapsam dışı
      (UI'da gösterilmiyor; yalnız gerekirse ScanEngine.error çıktısı).
- [x] `values/strings.xml` oluştur (varsayılan = İngilizce), tüm string'lere
      anlamlı anahtar ver (ör. `btn_prepare_vehicle`, `status_ready`).
- [x] Format'lı string'leri (`%d`, `%s`, `%.1f`) Android format sözdizimine
      çevir (`%1$d` vb. gerektiğinde).

## Faz 2: Java kodunu resource'a bağla

> Hedef: Hardcoded metinleri getString(R.string.x) ile değiştir.

- [x] MainActivity: tüm UI metinleri → getString (Activity context hazır).
- [x] ParkCamService: bildirim + STATUS + log mesajları → getString. Statik
      STATUS alanı sorunu: Context serviste var, getString(res) kullan; statik
      alanlar için anahtar sakla veya context'li yardımcı.
- [x] CameraEngine + Mp4Recorder: log/hata mesajları → getString (ctx mevcut).
- [x] Context erişimi olmayan yerler için strateji (yardımcı metot / resource id).

## Faz 3: 6 dile çeviri

> Hedef: strings.xml'i 6 dile çevir, dil klasörlerini oluştur.

- [x] `values/strings.xml` = İngilizce (varsayılan).
- [x] `values-tr/strings.xml` = Türkçe.
- [x] `values-es/strings.xml` = İspanyolca.
- [x] `values-fr/strings.xml` = Fransızca.
- [x] `values-de/strings.xml` = Almanca.
- [x] `values-ru/strings.xml` = Rusça.
- [x] Tüm dillerde aynı anahtar seti + format tutarlılığı (eksik anahtar yok).

## Faz 4: Derleme + doğrulama

> Hedef: APK derlensin, dil seçimi çalışsın.

- [x] `python build.py` hatasız (aapt2 çoklu locale'i otomatik derler).
- [x] Kaçırılan hardcoded Türkçe string kalmadığını doğrula (grep).
- [!] Araçta test: sistem dili ile açılışta doğru dil görünüyor mu (mümkünse).
