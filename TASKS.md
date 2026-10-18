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

## Faz 5: Performans — 4 kamera kayıtta sistem yavaşlaması (Öncelik 1)

> Hedef: 4 kamera eşzamanlı kayıtta sistemi yavaşlatan ham kare disk trafiğini
> ve boşa giden yükleri azalt. (Başka bir modelin önerileri gerçek kodla
> doğrulandı; yüksek-etki/düşük-risk 3 madde seçildi.)

- [x] FPS 10→6: qcam_loop8.c g_dumpframe 3→5 (dosya yazma/rename/okuma + TCP
      yükü ~%40 azalır; dashcam için 6 fps yeterli). QDF/QFPS'yi spawn_tool'dan
      ÖNCE oku (ilk sürecin ayarı kaçırması hatası giderildi). QCAM_VERSION 10→11
      + HELPER_VER 10→11 senkron.
- [x] C istemci sızıntısı: broadcast_frames ölü istemcileri (alive=0) listeden
      çıkarmıyordu → reap_dead_clients() ile her broadcast + periyodik temizle.
- [x] Arka plan önizleme: MainActivity onStop'ta WANT_LIVE=false (görünmeyen
      önizleme 250 ms'de bir YUV→RGB + Bitmap üretmesin); onStart geri açar.
      KAYIT etkilenmez.
- [x] Derleme 4.15.2 (versionCode 82) — NDK ile qcam_loop8 yeniden derlendi.
- [ ] Araçta test: FPS ~6'ya düştü mü (durum.txt RD_FRAME artış hızı), sistem
      akıcılığı, canlı izleme arka plan/geri dönüş, kayıt oynatılabilir mi,
      COLOR_FORMAT değeri (madde 2 Flexible yolu aktif mi?).

## Faz 6: Performans — Öncelik 2/3 (araç testinden sonra değerlendirilecek)

> Hedef: İlk grup yeterli gelmezse veya onaylanırsa kalan doğrulanmış maddeler.

- [ ] "D" TCP talep protokolünü tamamla + kamera-bazlı talep (yalnız-canlı
      izlemede 3 kamera boşuna üretmesin) — madde 6.
- [ ] stop() EOS bekleme düzeltmesi (dequeueInputBuffer 50µs → drain döngüsü;
      "Son kaydı kaydet"/segment dönüşü son kareleri kaybetmesin) — ek bulgu.
- [ ] Segment dönüşlerini kameralar arası kaydır (4 encoder aynı anda yeniden
      kurulmasın) — madde 8 güvenli versiyon.
- [ ] pickColorFormat finally temizliği (madde 4) + boot çift-bootstrap guard
      (madde 11) — küçük sigortalar.
