package com.solutera.dilinkinspector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ParkCam Ana Servis (v4.9) — HER ŞEY SERVİSTE:
 *
 *  1. Kurulum: ADB aç (TS köprüsü + OTA) → keeper kur → kamera yardımcısı başlat
 *  2. CAN nabzı: 10 sn'de bir getPowerLevel/wakeUpMcu/qcomWakeup + vites + hız
 *     → bildirim + bulut ping
 *  3. OTOMATİK KAYIT: araç AÇIK algılanınca kayıt başlar; araç KAPALI
 *     algılandıktan 2 dk sonra durur. Araç 2 dk dolmadan uyursa kayıt günlükle
 *     kalır → sonraki açılışta recoverPending() düzeltir.
 *  4. Kare pompası: kayıt/canlı istendiği sürece kareleri çeker, kodlar;
 *     canlı izleme için son kareyi LIVE_BMP'ye bırakır (Activity oradan okur).
 *
 * Activity kendi ADB bağlantısı KURMAZ (race) — her şeyi servisten ister.
 */
public final class ParkCamService extends Service {
    private static final String CHANNEL = "parkcam";
    private static final String TAG = "ParkCamService";
    /** Beklenen keeper sürümü. PowerKeeper.KEEPER_VER ile AYNI olmalı (elle senkron).
     *  Cihazdaki /data/local/tmp/keeper.ver bu değerden küçükse/farklıysa eski
     *  keeper öldürülüp yeni keeper.jar ile yeniden başlatılır. */
    private static final int KEEPER_VER = 17;
    private static final long SEGMENT_MS = 10 * 60 * 1000L;       // 10 dk'lık dosyalar
    private static final long MAX_GAP_MS = 30 * 1000L;            // en fazla 30 sn boşluk
    private static final long MIN_FREE = 20L * 1024 * 1024 * 1024; // ≥20 GB boş
    private static final long TAIL_MS = 2 * 60 * 1000L;           // araç kapandıktan sonra kayıt kuyruğu

    // ---- Activity'nin okuduğu durum ----
    public static volatile boolean IS_RUNNING = false;
    public static volatile String STATUS = null; /* set in onCreate via getString */
    public static volatile String VEHICLE = "?";      // "ACIK P1 0 km/h" gibi
    public static volatile String SERVICE_LOG = "";   // kurulum/temizlik günlüğü
    public static volatile boolean RECORDING = false;
    public static volatile String REC_FILE = "";
    public static volatile long REC_START_MS = 0;
    public static volatile boolean WANT_LIVE = false;  // canlı izleme talebi
    public static volatile int LIVE_CAM = 0;
    // okuyucu teşhis sayaçları (durum.txt'e yazılır)
    public static final long[] RD_CONN = new long[4];    // bağlanma denemesi
    public static final long[] RD_OK = new long[4];      // başarılı bağlantı
    public static final long[] RD_FRAME = new long[4];   // alınan kare
    public static final String[] RD_ERR = new String[4]; // son hata
    public static volatile Bitmap LIVE_BMP;           // son canlı kare

    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private Thread heartbeat;
    private volatile boolean running;
    private CameraEngine cam;
    private final Mp4Recorder[] recorders = new Mp4Recorder[4];  // kamera başına
    private final long[] segStart = new long[4];
    // Kamera başına yeniden kullanılan buffer'lar (GC baskısını önler — her kare
    // new byte[] yerine aynı diziye yazılır). pull-w-<cam> thread'leri kamera
    // başına ayrı olduğundan senkronizasyon gerekmez; her buffer'a tek thread dokunur.
    private final byte[][] frameBuf = new byte[4][];   // ham UYVY alım (eski qcam, FRAME_BYTES)
    private final byte[][] nv12Recv = new byte[4][];   // NV12 alım (qcam v10+, NV12_BYTES)
    private final byte[][] nv12Buf  = new byte[4][];   // UYVY→NV12 dönüşüm çıktısı (eski yol)
    private final int[][]  bmpScratch = new int[4][];  // toBitmap/nv12ToBitmap piksel ara-belleği
    private final long[] lastFeed = new long[4];
    private volatile long closedSinceMs;
    private volatile long lastMarkerMs;     // deneme işareti mtime koruması
    private Handler uiHandler;

    @Override public void onCreate() {
        super.onCreate();
        IS_RUNNING = true;
        if (STATUS == null) STATUS = getString(R.string.svc_status_starting);
        uiHandler = new Handler(Looper.getMainLooper());

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ParkCam", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(1, buildNotification(getString(R.string.svc_starting)));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "parkcam:service");
        cpuLock.setReferenceCounted(false);
        cpuLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "parkcam:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        running = true;
        cam = new CameraEngine(ResearchLab.get(this));
        STATUS = getString(R.string.svc_status_service_started);

        // Yarım kayıtları kurtar (araç uyurken kesilen son kayıt dahil) — arka planda
        new Thread(() -> {
            try {
                String rec = Mp4Recorder.recoverPending(this);
                if (rec != null) log(getString(R.string.log_recover, rec));
            } catch (Throwable ignored) {}
        }, "parkcam-recover").start();

        // Kalp atışı: 15 sn'de bir bildirim yenile
        heartbeat = new Thread(() -> {
            while (running) {
                try { Thread.sleep(15000); } catch (InterruptedException e) { break; }
                updateNotification(notifyText());
            }
        }, "parkcam-heartbeat");
        heartbeat.start();

        // Kurulum zinciri: ADB → keeper → kamera
        new Thread(this::bootstrap, "parkcam-bootstrap").start();

        // CAN nabzı + otomatik kayıt kararı — 10 sn
        new Thread(this::canPulse, "parkcam-can").start();

        // Kare pompası — kayıt/canlı sürdüğü sürece
        new Thread(this::pumpLoop, "parkcam-pump").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent != null ? intent.getAction() : "";
        if ("setup".equals(action)) {
            log(getString(R.string.log_setup_manual));
            new Thread(this::bootstrap, "parkcam-setup").start();
        } else if ("cleanup".equals(action)) {
            log(getString(R.string.log_cleanup_begin));
            new Thread(this::cleanupAll, "parkcam-cleanup").start();
        } else if ("stopRec".equals(action)) {
            stopRecording(getString(R.string.reason_manual_stop));
        } else if ("saveNow".equals(action)) {
            // "Son kaydı kaydet": o anki segmenti hemen oynatılabilir yap
            new Thread(this::flushSegments, "parkcam-flush").start();
        } else if ("recTest".equals(action)) {
            // 60 sn'lik deneme kaydı — aracı açmadan boru hattını doğrulamak için
            log(getString(R.string.log_test_recording));
            startRecording(getString(R.string.reason_test));
            new Thread(() -> {
                try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
                stopRecording(getString(R.string.reason_test_elapsed));
            }, "rec-test").start();
        }
        return START_STICKY;
    }

    // ================= kurulum / temizlik =================

    private void bootstrap() {
        // WiFi/standby kırpışmalarına dayanacak şekilde: başarılı olana dek tekrar dener
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                CameraEngine.Step log = this::log;
                if (attempt == 1) log(getString(R.string.log_setup_begin));
                boolean adbOk = cam.connectAdb(log);
                if (adbOk) {
                    deployKeeper(log);
                    boolean camOk = cam.startHelper(log, 0); // v8: 4 kamera örneği
                    if (camOk) {
                        STATUS = getString(R.string.svc_status_ready);
                        log(getString(R.string.log_ready, attempt));
                        return; // başarı — döngüden çık
                    }
                    STATUS = getString(R.string.svc_status_camera_failed);
                } else {
                    STATUS = getString(R.string.svc_status_adb_off);
                }
            } catch (Exception e) {
                STATUS = getString(R.string.svc_status_error, String.valueOf(e.getMessage()));
                log(getString(R.string.log_setup_error, attempt, String.valueOf(e)));
            }
            try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
        }
    }

    /** Kaldırma hazırlığı: yardımcıları durdur, dosyaları temizle */
    private void cleanupAll() {
        try {
            stopRecording(getString(R.string.reason_cleanup));
            cam.uninstallPrep(this::log, true);
            STATUS = getString(R.string.svc_status_cleaned);
            log(getString(R.string.log_cleaned));
        } catch (Exception e) {
            log(getString(R.string.log_cleanup_error, String.valueOf(e)));
        }
        updateNotification(notifyText());
    }

    /** keeper.jar'ı APK asset'inden araca kur ve shell uid'de başlat */
    private void deployKeeper(CameraEngine.Step log) {
        try {
            ResearchLab lab = ResearchLab.get(this);
            java.io.InputStream in = getAssets().open("bin/keeper.jar");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] jarData = bos.toByteArray();

            String localMd5 = md5(jarData);
            JSONObject remote = lab.adb.shell("md5sum /data/local/tmp/keeper.jar 2>/dev/null | cut -d' ' -f1", 6000);
            String remoteMd5 = remote.optString("output", "").trim().toLowerCase();
            boolean jarCurrent = remoteMd5.equals(localMd5);

            // Çalışan keeper'ın sürümü (damga dosyasından). Yoksa/okunamazsa -1.
            int remoteVer = -1;
            try {
                JSONObject rv = lab.adb.shell("cat /data/local/tmp/keeper.ver 2>/dev/null || echo -1", 5000);
                remoteVer = Integer.parseInt(rv.optString("output", "-1").trim());
            } catch (Exception ignored) {}

            // Keeper süreci canlı mı? cmdline'ı TAM "keeper" olan süreç say
            // (öldürme ile aynı ölçüt — gatekeeper ve başka adlar elenir).
            JSONObject ps = lab.adb.shell("n=0; for p in $(ls /proc | grep -E '^[0-9]+$'); do c=$(cat /proc/$p/cmdline 2>/dev/null | tr '\\0' ' ' | sed 's/ *$//'); if [ \"$c\" = keeper ]; then n=$((n+1)); fi; done; echo $n", 8000);
            int keeperCount = 0;
            try { keeperCount = Integer.parseInt(ps.optString("output", "0").trim()); } catch (Exception ignored) {}
            boolean running = keeperCount >= 1;

            // qcam ile aynı mantık: güncel sürüm CANLI çalışıyorsa dokunma.
            if (running && remoteVer == KEEPER_VER && jarCurrent) {
                log.run(getString(R.string.log_keeper_current, remoteVer));
                return;
            }

            // Buraya düştüysek: eski sürüm, ölü ya da jar değişmiş → öldür + yenile.
            log.run(running
                ? getString(R.string.log_keeper_refresh, remoteVer, KEEPER_VER)
                : getString(R.string.log_keeper_closed));

            // 1) Çalışan (eski) keeper'ı kesin öldür.
            //    ÖNEMLİ: app_process64 ile başlatılan süreçte /proc/pid/comm "main",
            //    --nice-name ise yalnız cmdline'ı "keeper" yapar. Bu yüzden ne
            //    "pkill -x keeper" (comm=main → eşleşmez) ne "pkill -f PowerKeeper"
            //    (cmdline'da PowerKeeper yok → eşleşmez) işe yarar. Güvenilir tek
            //    yol cmdline'ı TAM "keeper" olan pid'leri bulup kill etmek; bu
            //    gatekeeper'ı (cmdline farklı) ve başka uid'leri (kill sessizce
            //    başarısız) doğal olarak eler.
            lab.adb.shell("for p in $(ls /proc | grep -E '^[0-9]+$'); do c=$(cat /proc/$p/cmdline 2>/dev/null | tr '\\0' ' ' | sed 's/ *$//'); if [ \"$c\" = keeper ]; then kill -9 $p 2>/dev/null; fi; done; rm -f /data/local/tmp/keeper.ver; true", 8000);
            Thread.sleep(1500);

            // 2) Jar değiştiyse yeniden yaz.
            if (!jarCurrent) {
                log.run(getString(R.string.log_keeper_writing, jarData.length));
                lab.adb.shell("rm -f /data/local/tmp/keeper.jar", 5000);
                String b64 = android.util.Base64.encodeToString(jarData, android.util.Base64.NO_WRAP);
                for (int off = 0; off < b64.length(); off += 18000) {
                    String part = b64.substring(off, Math.min(b64.length(), off + 18000));
                    lab.adb.shell("echo " + part + " | base64 -d >> /data/local/tmp/keeper.jar", 10000);
                }
                lab.adb.shell("chmod 644 /data/local/tmp/keeper.jar", 3000);
            }

            // 3) Yeni keeper'ı başlat (CLASSPATH zorunlu — app_process64 sınıfı bulsun).
            lab.adb.shell("cd /data/local/tmp && (CLASSPATH=/data/local/tmp/keeper.jar " +
                "LD_LIBRARY_PATH=/system/lib64 setsid nohup /system/bin/app_process64 " +
                "/system/bin --nice-name=keeper PowerKeeper > keeper_out.log 2>&1 < /dev/null &)", 8000);
            Thread.sleep(4000);

            // 4) Doğrula: süreç ayakta mı ve yeni sürüm damgası yazıldı mı?
            JSONObject check = lab.adb.shell("n=0; for p in $(ls /proc | grep -E '^[0-9]+$'); do c=$(cat /proc/$p/cmdline 2>/dev/null | tr '\\0' ' ' | sed 's/ *$//'); if [ \"$c\" = keeper ]; then n=$((n+1)); fi; done; echo $n", 8000);
            int cnt = 0;
            try { cnt = Integer.parseInt(check.optString("output", "0").trim()); } catch (Exception ignored) {}
            int newVer = -1;
            try {
                JSONObject nv = lab.adb.shell("cat /data/local/tmp/keeper.ver 2>/dev/null || echo -1", 5000);
                newVer = Integer.parseInt(nv.optString("output", "-1").trim());
            } catch (Exception ignored) {}
            log.run(cnt > 0
                ? getString(R.string.log_keeper_running, (newVer > 0 ? String.valueOf(newVer) : "?"))
                : getString(R.string.log_keeper_failed));
        } catch (Exception e) {
            log.run(getString(R.string.log_keeper_error, String.valueOf(e.getMessage())));
        }
    }

    private String md5(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // ================= CAN nabzı + otomatik kayıt =================

    private void canPulse() {
        Object powerDev = null, gearDev = null, speedDev = null;
        Class<?> powerCls = null, gearCls = null, speedCls = null;
        dalvik.system.PathClassLoader loader = null;
        Context bydCx = null;
        int pulseCount = 0;
        String loadErr = "";
        long startMs = System.currentTimeMillis();
        boolean wasOpen = false, firstRead = true;

        while (true) {
            try {
                Thread.sleep(10000);
                pulseCount++;
                long elapsed = (System.currentTimeMillis() - startMs) / 1000;

                if (powerDev == null || gearDev == null || speedDev == null) {
                    try {
                        if (loader == null) {
                            loader = new dalvik.system.PathClassLoader(
                                "/system/app/BydAutoService/BydAutoService.apk",
                                "/system/lib64", getClassLoader());
                            bydCx = createBydBypass();
                        }
                        if (powerDev == null) {
                            Class<?> cls1 = Class.forName("android.hardware.bydauto.power.BYDAutoPowerDevice", true, loader);
                            powerDev = cls1.getMethod("getInstance", Context.class).invoke(null, bydCx);
                            powerCls = powerDev != null ? powerDev.getClass() : null;
                        }
                        if (gearDev == null) {
                            Class<?> cls2 = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", true, loader);
                            gearDev = cls2.getMethod("getInstance", Context.class).invoke(null, bydCx);
                            gearCls = gearDev != null ? gearDev.getClass() : null;
                        }
                        if (speedDev == null) {
                            Class<?> cls3 = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice", true, loader);
                            speedDev = cls3.getMethod("getInstance", Context.class).invoke(null, bydCx);
                            speedCls = speedDev != null ? speedDev.getClass() : null;
                        }
                    } catch (Throwable t) {
                        loadErr = errTag(t);
                        if (pulseCount <= 3) {
                            String st = android.util.Log.getStackTraceString(t).replace('\n', ' ');
                            android.util.Log.w(TAG, "BYD yukleme hatasi #" + pulseCount + ": "
                                + st.substring(0, Math.min(400, st.length())));
                        }
                    }
                }

                String powerStr = "?", gearStr = "C?", speedStr = "C?";
                if (powerCls != null && powerDev != null) {
                    try {
                        Object pl = powerCls.getMethod("getPowerLevel").invoke(powerDev);
                        powerStr = pl instanceof Number ? String.valueOf(((Number)pl).intValue()) : String.valueOf(pl);
                        powerCls.getMethod("wakeUpMcu").invoke(powerDev);
                        powerCls.getMethod("qcomWakeup", int.class).invoke(powerDev, 0);
                    } catch (Throwable ignored) {}
                }
                // Vites (KALİBRE): 1=P 2=R 3=N 4=D — harf+ham değer birlikte
                if (gearCls != null && gearDev != null) {
                    try {
                        Object gear = gearCls.getMethod("getGear").invoke(gearDev);
                        int g = gear instanceof Number ? ((Number)gear).intValue() : -1;
                        String letter = g == 1 ? "P" : g == 2 ? "R" : g == 3 ? "N" : g == 4 ? "D" : "?";
                        gearStr = letter + g;
                    } catch (Throwable t) { gearStr = errTag(t); }
                }
                if (speedCls != null && speedDev != null) {
                    try {
                        Object spd = speedCls.getMethod("getCurrentSpeed").invoke(speedDev);
                        speedStr = spd instanceof Number ? String.format(Locale.US, "%.0f", ((Number)spd).doubleValue()) : "?";
                    } catch (Throwable t) { speedStr = errTag(t); }
                }

                String aracDurum = "?";
                try {
                    int pl = Integer.parseInt(powerStr);
                    aracDurum = pl == 0 ? "KAPALI" : pl == 2 ? "ACIK" : "PL" + pl;
                } catch (NumberFormatException ignored) {}

                VEHICLE = aracDurum + " " + gearStr + " " + speedStr + "km/h";
                updateNotification(notifyText());

                // Teşhis dosyası: kabuktan okunabilir uygulama içi durum
                try {
                    java.io.File diag = new java.io.File(getExternalFilesDir(null), "durum.txt");
                    StringBuilder d = new StringBuilder();
                    d.append("zaman: ").append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())).append('\n');
                    d.append("STATUS: ").append(STATUS).append('\n');
                    d.append("VEHICLE: ").append(VEHICLE).append('\n');
                    d.append("RECORDING: ").append(RECORDING).append(" file=").append(REC_FILE).append('\n');
                    d.append("WANT_LIVE: ").append(WANT_LIVE).append('\n');
                    d.append("COLOR_FORMAT: ").append(String.format("0x%08X", Mp4Recorder.LAST_COLOR_FORMAT)).append('\n');
                    for (int rc = 0; rc < 4; rc++) {
                        d.append("RD").append(rc).append(": conn=").append(RD_CONN[rc])
                         .append(" ok=").append(RD_OK[rc]).append(" kare=").append(RD_FRAME[rc])
                         .append(" err=").append(RD_ERR[rc]).append('\n');
                    }
                    d.append("LOG:\n").append(SERVICE_LOG).append('\n');
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(diag);
                    fo.write(d.toString().getBytes("UTF-8"));
                    fo.close();
                } catch (Throwable ignored) {}

                // Bulut ping
                try {
                    String data = java.net.URLEncoder.encode(
                        VEHICLE + "_#" + pulseCount + "_+" + elapsed + "s"
                        + (RECORDING ? "_REC" : ""), "UTF-8");
                    java.net.URL u = new java.net.URL(
                        "http://byd-ping.cloud.solucharger.com/ping?id=parkcam&data=" + data);
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    c.getResponseCode();
                    c.disconnect();
                } catch (Exception ignored) {}

                // Harici deneme kaydı tetikleyicisi: /sdcard/parkcam_rectest
                // Yalnız YENİ işaret tetikler (mtime koruması — dosya silinemese
                // bile her turda yeniden başlamaz)
                try {
                    java.io.File marker = new java.io.File(
                        android.os.Environment.getExternalStorageDirectory(), "parkcam_rectest");
                    if (marker.isFile() && marker.lastModified() != lastMarkerMs) {
                        lastMarkerMs = marker.lastModified();
                        marker.delete();
                        log(getString(R.string.log_test_recording));
                        startRecording(getString(R.string.reason_test_file_trigger));
                        new Thread(() -> {
                            try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
                            stopRecording(getString(R.string.reason_test_elapsed));
                        }, "rec-test").start();
                    }
                } catch (Throwable ignored) {}

                // ---- OTOMATİK KAYIT KARARI ----
                boolean open = "ACIK".equals(aracDurum);
                if (open) {
                    closedSinceMs = 0;
                    if (!RECORDING) startRecording(getString(R.string.reason_vehicle_on));
                } else if ("KAPALI".equals(aracDurum)) {
                    if (RECORDING) {
                        if (closedSinceMs == 0) {
                            closedSinceMs = System.currentTimeMillis();
                            log(getString(R.string.log_vehicle_off_tail, TAIL_MS/60000));
                        } else if (System.currentTimeMillis() - closedSinceMs >= TAIL_MS) {
                            stopRecording(getString(R.string.reason_vehicle_off_tail, TAIL_MS/60000));
                        }
                    }
                }
                firstRead = false; wasOpen = open;

            } catch (InterruptedException e) { break; }
            catch (Exception ignored) {}
        }
    }

    /** BYDAuto izin bypass — getApplicationContext wrapper'ı soymasın diye override */
    private Context createBydBypass() {
        return new android.content.ContextWrapper(this) {
            @Override public Context getApplicationContext() { return this; }
            @Override public int checkCallingOrSelfPermission(String permission) {
                if (permission != null && permission.startsWith("android.permission.BYDAUTO_")) return 0;
                return super.checkCallingOrSelfPermission(permission);
            }
            @Override public void enforceCallingOrSelfPermission(String permission, String message) {
                if (permission != null && permission.startsWith("android.permission.BYDAUTO_")) return;
                super.enforceCallingOrSelfPermission(permission, message);
            }
        };
    }

    private static String errTag(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String n = c.getClass().getSimpleName();
        return "E:" + (n.length() > 12 ? n.substring(0, 12) : n);
    }

    // ================= kayıt (4 kamera) =================

    private synchronized void startRecording(String reason) {
        if (RECORDING) return;
        // Kayıt başladı: "araç kapalı" geri sayımını SIFIRLA. Aksi halde araç uzun
        // süredir kapalıyken başlatılan kayıt (ör. deneme kaydı) canPulse'ın eski
        // closedSinceMs'i yüzünden +2dk dolmuş sayılıp anında durabiliyordu.
        closedSinceMs = 0;
        try {
            int freed = Mp4Recorder.ensureFreeSpace(this, MIN_FREE);
            if (freed > 0) log(getString(R.string.log_old_rec_deleted, freed));
            String recov = Mp4Recorder.recoverPending(this);
            if (recov != null) log(getString(R.string.log_recovered, recov));

            int opened = 0;
            for (int i = 0; i < 4; i++) {
                try {
                    recorders[i] = new Mp4Recorder(CameraEngine.OUT_W, CameraEngine.OUT_H);
                    recorders[i].start(this);
                    segStart[i] = System.currentTimeMillis();
                    opened++;
                } catch (Exception e) {
                    log(getString(R.string.log_cam_open_failed, i, String.valueOf(e)));
                    recorders[i] = null;
                }
            }
            RECORDING = opened > 0;
            REC_START_MS = System.currentTimeMillis();
            REC_FILE = opened + " cam";
            log(getString(R.string.log_rec_started, reason, opened));
        } catch (Exception e) {
            log(getString(R.string.rec_err_mp4_open) + ": " + e);
        }
        updateNotification(notifyText());
    }

    private synchronized void stopRecording(String reason) {
        if (!RECORDING) return;
        RECORDING = false;
        long frames = 0;
        for (int i = 0; i < 4; i++) {
            if (recorders[i] != null) {
                frames += recorders[i].frameCount();
                try { recorders[i].stop(this); } catch (Exception ignored) {}
                recorders[i] = null;
            }
        }
        log(getString(R.string.log_rec_stopped, reason, frames));
        REC_FILE = "";
        updateNotification(notifyText());
    }

    /** Bir kameranın 10 dk'lık segmentini döndür */
    private void rotateSegment(int i, long now) {
        if (!RECORDING || recorders[i] == null) return;
        if (now - segStart[i] < SEGMENT_MS) return;
        try {
            recorders[i].stop(this);
            Mp4Recorder.ensureFreeSpace(this, MIN_FREE);
            recorders[i] = new Mp4Recorder(CameraEngine.OUT_W, CameraEngine.OUT_H);
            recorders[i].start(this);
            segStart[i] = now;
        } catch (Exception e) {
            log(getString(R.string.log_cam_segment_error, i, String.valueOf(e)));
            recorders[i] = null;
        }
    }

    /** "Son kaydı kaydet" düğmesi: kayıt sürerken 4 kameranın O ANKİ segmentini
     *  HEMEN kapatır (10 dk beklemeden) → o ana kadarki görüntü oynatılabilir MP4
     *  olarak Movies/DiLinkCam'e yazılır; kayıt kesintisiz yeni dosyada sürer.
     *  Olay anında kullanıcının kaydı hemen izleyebilmesi için. */
    synchronized void flushSegments() {
        if (!RECORDING) { log(getString(R.string.log_flush_not_recording)); return; }
        long now = System.currentTimeMillis();
        int saved = 0;
        for (int i = 0; i < 4; i++) {
            if (recorders[i] == null) continue;
            try {
                recorders[i].stop(this);           // o anki dosyayı düzgün kapat (moov yazılır → oynatılabilir)
                Mp4Recorder.ensureFreeSpace(this, MIN_FREE);
                recorders[i] = new Mp4Recorder(CameraEngine.OUT_W, CameraEngine.OUT_H);
                recorders[i].start(this);          // kayıt kesintisiz yeni dosyada devam
                segStart[i] = now;
                saved++;
            } catch (Exception e) {
                log(getString(R.string.log_cam_segment_error, i, String.valueOf(e)));
                recorders[i] = null;
            }
        }
        log(getString(R.string.log_flush_saved, saved));
    }

    // ================= kare pompası (v9: DONMA ÖNLEYİCİ) =================

    /** Her kameranın güncel çekici soketi — watchdog zorla kapatmak için */
    private final java.net.Socket[] pullSock = new java.net.Socket[4];
    private final long[] lastLiveMs = new long[4];

    /** Mimari: kalıcı okuyucu thread YOK (donuyorlardı). Her tur TAZE kısa
     *  ömürlü işçi: bağlan → "D" → ~8 sn kare çek → besle → kapat. İşçi 15 sn'de
     *  bitmezse watchdog soketi zorla kapatarak engeli kaldırır ve işçiyi bırakır
     *  — donma yapısal olarak imkânsız. */
    private void pumpLoop() {
        // Yedek talep dokunuşçusu (ADB köprüsü çalışıyorsa dosya yolunu da tazeler)
        new Thread(() -> {
            while (true) {
                try {
                    if ((RECORDING || WANT_LIVE) && cam != null) cam.touchDemand();
                    Thread.sleep(5000);
                } catch (InterruptedException e) { return; }
            }
        }, "pump-demand").start();
        // Kamera başına çekici döngüsü
        for (int i = 0; i < 4; i++) {
            final int camId = i;
            new Thread(() -> pullerLoop(camId), "pull-" + i).start();
        }
    }

    /** Çekici: aktifken taze işçi başlat + 15 sn watchdog ile denetle */
    private void pullerLoop(int camId) {
        while (true) {
            try {
                if (!(RECORDING || (WANT_LIVE && LIVE_CAM == camId))) {
                    Thread.sleep(1000);
                    continue;
                }
                RD_CONN[camId]++;
                Thread w = new Thread(() -> pullFrames(camId), "pull-w-" + camId);
                w.setDaemon(true);
                w.start();
                long deadline = System.currentTimeMillis() + 15000;
                while (w.isAlive() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(150);
                }
                if (w.isAlive()) {
                    java.net.Socket s = pullSock[camId];
                    if (s != null) try { s.close(); } catch (Exception ignored) {}
                    w.join(2000);
                    RD_ERR[camId] = "watchdog-kes";
                    RD_CONN[camId]++; // watchdog turu da deneme sayılır (canlılık teyidi)
                }
                Thread.sleep(200);
            } catch (InterruptedException e) { return; }
        }
    }

    /** Tek kısa ömürlü tur: bağlan → D → kare çek/besle (8 sn bütçe) → kapat */
    private void pullFrames(int camId) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            pullSock[camId] = s;
            s.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 7800 + camId), 2000);
            RD_OK[camId]++;
            RD_ERR[camId] = null;
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000);
            java.io.OutputStream out = s.getOutputStream();
            java.io.InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 1 << 18);
            byte[] hdr = new byte[16];
            long budgetEnd = System.currentTimeMillis() + 8000;
            while ((RECORDING || (WANT_LIVE && LIVE_CAM == camId)) && System.currentTimeMillis() < budgetEnd) {
                out.write("D\n".getBytes("US-ASCII"));
                out.flush();
                readFully(in, hdr, 16);
                if (hdr[0] != 'F' || hdr[1] != 'R' || hdr[2] != 'M' || hdr[3] != 'E') throw new java.io.IOException("desync");
                int fcam = leInt(hdr, 4);
                int len = leInt(hdr, 12);
                // İki format: NV12 (qcam v10+, cihazda dönüştürülmüş, 1280x960) veya
                // UYVY (eski qcam, 1920x1024 ham). len ile ayırt edilir.
                boolean isNv12 = (len == CameraEngine.NV12_BYTES);
                boolean isUyvy = (len == CameraEngine.FRAME_BYTES);
                if ((!isNv12 && !isUyvy) || fcam != camId) {
                    skipFully(in, len);
                    continue;
                }
                // Gövdeyi yeniden kullanılan buffer'a oku (new byte[] YOK).
                byte[] body;
                if (isNv12) {
                    body = nv12Recv[camId];
                    if (body == null) { body = new byte[CameraEngine.NV12_BYTES]; nv12Recv[camId] = body; }
                } else {
                    body = frameBuf[camId];
                    if (body == null) { body = new byte[CameraEngine.FRAME_BYTES]; frameBuf[camId] = body; }
                }
                readFully(in, body, len);
                RD_FRAME[camId]++;
                long now = SystemClockMs();

                if (RECORDING) {
                    rotateSegment(camId, now);
                    if (recorders[camId] != null) {
                        // HER kare beslenir (atıl filtre gece açlığa sokuyordu;
                        // HEVC/AVC durağan kareyi zaten ~1 kbps'e sıkıştırır).
                        byte[] nv12;
                        if (isNv12) {
                            // qcam zaten NV12 yolladı — doğrudan kodlayıcıya, dönüşüm YOK.
                            nv12 = body;
                        } else {
                            // Eski UYVY — Java tarafında NV12'ye çevir (yeniden kullanılan buffer).
                            nv12 = nv12Buf[camId];
                            if (nv12 == null) { nv12 = new byte[CameraEngine.NV12_BYTES]; nv12Buf[camId] = nv12; }
                            cam.toNv12(body, nv12);
                        }
                        // feed() NV12'yi kodlayıcıya kopyalar, dönüş sonrası buffer tekrar kullanılabilir.
                        recorders[camId].feed(nv12, false);
                        lastFeed[camId] = now;
                    }
                }
                if (WANT_LIVE && LIVE_CAM == camId && now - lastLiveMs[camId] >= 250) {
                    int[] scratch = bmpScratch[camId];
                    if (scratch == null) { scratch = new int[CameraEngine.OUT_W * CameraEngine.OUT_H]; bmpScratch[camId] = scratch; }
                    LIVE_BMP = isNv12 ? cam.nv12ToBitmap(body, scratch) : cam.toBitmap(body, scratch);
                    lastLiveMs[camId] = now;
                }
            }
        } catch (Throwable t) {
            RD_ERR[camId] = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) {}
            if (pullSock[camId] == s) pullSock[camId] = null;
        }
    }

    private static long SystemClockMs() { return System.currentTimeMillis(); }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void readFully(java.io.InputStream in, byte[] buf, int len) throws java.io.IOException {
        int done = 0;
        while (done < len) {
            int n = in.read(buf, done, len - done);
            if (n < 0) throw new java.io.IOException("EOF");
            done += n;
        }
    }

    private static void skipFully(java.io.InputStream in, long len) throws java.io.IOException {
        byte[] buf = new byte[8192];
        while (len > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, len));
            if (n < 0) throw new java.io.IOException("EOF");
            len -= n;
        }
    }

    // ================= bildirim / yaşam döngüsü =================

    private String notifyText() {
        String t = VEHICLE;
        if (RECORDING) {
            long sec = (System.currentTimeMillis() - REC_START_MS) / 1000;
            t += " • KAYIT " + String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
        }
        return t;
    }

    private void log(String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String prev = SERVICE_LOG;
        // son ~25 satır tut
        String[] lines = (prev + "\n" + stamp + "  " + line).split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - 25); i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        SERVICE_LOG = sb.toString();
        android.util.Log.i(TAG, line);
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL)
                    .setContentTitle("ParkCam").setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build();
        }
        return new Notification.Builder(this)
                .setContentTitle("ParkCam").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(1, buildNotification(text));
    }

    @Override public void onDestroy() {
        running = false;
        IS_RUNNING = false;
        if (RECORDING) stopRecording(getString(R.string.reason_cleanup));
        if (heartbeat != null) heartbeat.interrupt();
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
