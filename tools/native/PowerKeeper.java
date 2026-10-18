import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * PowerKeeper v12 — Çekirdek + KAMERA NÖBETÇİSİ
 *
 * YENİ (v12): 4 kamera örneğini (qcam_loop8 0-3) her 20 sn'de bir denetler,
 * ölmüşse yeniden başlatır. Kamera boru hattı böylece uygulamanın ADB köprüsünden
 * BAĞIMSIZ yaşar (video TCP 7800-7803 üzerinden akar; talep de artık "D" komutu
 * ile TCP'den — dosya dokunuşu yedek olarak duruyor).
 *
 * Diğer görevler: PARTIAL_WAKE_LOCK, WiFi savaşı, ParkCam diriltme,
 * komut sunucusu 7778, bulut ping, güç teşhisi.
 */
public final class PowerKeeper {
    /** Keeper protokol/sürüm damgası. Her anlamlı değişiklikte ARTIR.
     *  ParkCamService.KEEPER_VER ile AYNI olmalı (elle senkron) — uygulama bu
     *  değerle cihazdaki çalışan keeper'ın güncel olup olmadığını anlar ve
     *  eskiyse öldürüp yeniden başlatır. */
    static final int KEEPER_VER = 17;
    /** Çalışan sürümün damgası — uygulamanın (ParkCamService) okuduğu dosya. */
    static final String VERFILE = "/data/local/tmp/keeper.ver";
    static final String LOG = "/data/local/tmp/keeper_alive.log";
    static final int CMD_PORT = 7778;
    static final String PING_URL = "http://byd-ping.cloud.solucharger.com/ping";
    static final String PKG = "com.solutera.dilinkinspector";

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        try {
            Context cx = getSystemContext();
            PowerManager pm = (PowerManager) cx.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "keeper:cpu");
            cpu.setReferenceCounted(false);
            cpu.acquire();
            // Sürüm damgasını diske yaz: uygulama bununla güncelliği anlar.
            writeVersion();
            log("v" + KEEPER_VER + " kamera-nobetcisi basladi");

            startCameraWatch();
            startCmdServer();
            startWifiWatch(cx);
            startMainLoop(cx);

            while (true) { Thread.sleep(60000); }

        } catch (Throwable t) {
            log("HATA: " + t);
        }
    }

    /** Kamera nöbetçisi: qcam_loop8 0-3 örnekleri ölmüşse yeniden başlat */
    private static void startCameraWatch() {
        Thread t = new Thread(() -> {
            while (true) {
                int beats = 0;
                while (true) try {
                    if (++beats % 30 == 1) log("kamera nobeti tarama #" + beats);
                    // YETIM HASATICISI: döngüsü ölmüş kamera araçları varsa öldür
                    // (fırtına koruması — araç yalnız CANLI döngüsüyle döküm yapar)
                    String[] orph = sh("for c in 0 1 2 3; do if ! kill -0 $(cat /data/local/tmp/qcam_loop_$c.pid 2>/dev/null) 2>/dev/null; then pkill -9 -x qcam_patched_$c; pkill -9 -x qcam_patched_c$c; fi; done; true");
                    if (!orph[1].trim().isEmpty()) log("yetim temizlik: " + orph[1].trim());
                    for (int c = 0; c <= 3; c++) {
                        String[] r = sh("kill -0 $(cat /data/local/tmp/qcam_loop_" + c + ".pid 2>/dev/null) 2>/dev/null && echo y");
                        boolean alive = r[1].trim().equals("y");
                        if (!alive) {
                            // binary var mı? yoksa bu turu atla (uygulama kurar)
                            String[] b = sh("test -x /data/local/tmp/qcam_loop8 && echo v");
                            if (!b[1].trim().equals("v")) break;
                            log("kamera " + c + " ornegi olu -> yeniden baslatiliyor");
                            sh("cd /data/local/tmp && rm -f qcam_loop_" + c + ".pid && (setsid nohup ./qcam_loop8 " + c
                                + " > q8_" + c + ".log 2>&1 < /dev/null &)");
                        }
                    }
                    Thread.sleep(20000);
                } catch (Throwable t2) {
                    log("kamera nobeti hata: " + t2);
                    try { Thread.sleep(20000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "keeper-camera");
        t.setDaemon(true);
        t.start();
    }

    /** Ana döngü — 10 sn'de bir: ParkCam dirilt + ping */
    private static void startMainLoop(Context cx) {
        Thread t = new Thread(() -> {
            int count = 0; int resurrectionCount = 0;
            long start = System.currentTimeMillis();
            while (true) {
                // Diriltme döngüsü ASLA ölmemeli: kesinti gelse bile döngüden
                // çıkma, bayrağı temizleyip devam et.
                try { Thread.sleep(10000); } catch (InterruptedException e) { Thread.interrupted(); continue; }
                count++;
                long elapsed = (System.currentTimeMillis() - start) / 1000;

                // Not: keeper.jar güncelliği kontrolünü APK'den deploy eden taraf
                // (ParkCamService.deployKeeper) MD5 + sürüm damgasıyla yapar;
                // burada tekrar hesaplamaya gerek yok.

                // ParkCam uygulaması yaşıyor mu?
                String[] ps = sh("ps -A | grep " + PKG + " | grep -v grep");
                boolean appAlive = !ps[1].trim().isEmpty();

                // ÖLDÜYSE DİRILT
                if (!appAlive) {
                    resurrectionCount++;
                    log("PARKCAM-OLDU → diriltme #" + resurrectionCount);
                    sh("am start-foreground-service -n " + PKG + "/.ParkCamService 2>/dev/null " +
                       "; am start -n " + PKG + "/.MainActivity 2>/dev/null");
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.interrupted(); }
                    // Tekrar kontrol
                    String[] ps2 = sh("ps -A | grep " + PKG + " | grep -v grep");
                    appAlive = !ps2[1].trim().isEmpty();
                }

                // Her 3. turda güç teşhisi (uyku öncesi son durumu yakala)
                if (count % 3 == 0) {
                    String[] pw = sh("dumpsys power | grep -E 'mWakefulness=|mStayOn=|mHoldingDisplay' | head -3");
                    String pwInfo = pw[1].trim().replaceAll("\\s+", " ");
                    if (pwInfo.length() > 160) pwInfo = pwInfo.substring(0, 160);
                    appendFile("/data/local/tmp/keeper_power.log",
                        stampNow() + " " + pwInfo + "\n");
                }

                // WiFi durumu
                String status = (appAlive ? "APP✓" : "APP✗") + " dirilt=" + resurrectionCount;
                try {
                    WifiManager wm = (WifiManager) cx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) status += " WiFi=" + (wm.isWifiEnabled() ? "acik" : "KAPALI");
                } catch (Throwable ignored) {}

                // Bulut ping
                boolean pingOk = false;
                try {
                    String data = URLEncoder.encode(status + " +" + elapsed + "s #" + count, "UTF-8");
                    URL u = new URL(PING_URL + "?id=keeper&data=" + data);
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    pingOk = (c.getResponseCode() == 200);
                    c.disconnect();
                } catch (Exception ignored) {}

                String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                log(String.format(Locale.US, "[%s] #%d %s %s +%ds",
                    stamp, count, pingOk ? "PING✓" : "PING✗", status, elapsed));
                appendFile("/data/local/tmp/keeper_ping.log",
                    String.format("%s #%d %s %s dirilt=%d\n", stamp, count, pingOk?"OK":"FAIL", status, resurrectionCount));
            }
        }, "keeper-main");
        t.setDaemon(true);
        t.start();
    }

    /** WiFi nöbeti */
    private static void startWifiWatch(Context cx) {
        Thread t = new Thread(() -> {
            WifiManager wm = (WifiManager) cx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            boolean wasOff = false;
            while (true) {
                try {
                    Thread.sleep(2000);
                    if (!wm.isWifiEnabled()) {
                        if (!wasOff) log("WIFI-KAPANDI → svc wifi enable");
                        wasOff = true;
                        sh("svc wifi enable");
                    } else {
                        if (wasOff) log("WIFI-GERI-GELDI");
                        wasOff = false;
                    }
                } catch (Throwable ignored) { return; }
            }
        }, "keeper-wifi");
        t.setDaemon(true);
        t.start();
    }

    /** Komut sunucusu 7778 */
    private static void startCmdServer() {
        Thread t = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket(CMD_PORT, 4, InetAddress.getByName("0.0.0.0"));
                log("komut sunucusu 0.0.0.0:" + CMD_PORT);
                while (true) {
                    Socket s = ss.accept();
                    s.setSoTimeout(30000);
                    Thread w = new Thread(() -> handleCmd(s));
                    w.setDaemon(true);
                    w.start();
                }
            } catch (Throwable ignored) {}
        }, "keeper-cmd");
        t.setDaemon(true);
        t.start();
    }

    private static void handleCmd(Socket s) {
        try (Socket sock = s; BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             OutputStream out = sock.getOutputStream()) {
            sock.setTcpNoDelay(true);
            String cmd;
            while ((cmd = in.readLine()) != null) {
                if (cmd.trim().isEmpty()) continue;
                log("KOMUT: " + cmd);
                String[] r = sh(cmd.length() > 512 ? cmd.substring(0, 512) : cmd);
                out.write(r[1].getBytes("UTF-8"));
                out.write("\n#OK\n".getBytes("UTF-8"));
                out.flush();
            }
        } catch (Throwable ignored) {}
    }

    /** Yardımcılar */
    static String[] sh(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l; while ((l = br.readLine()) != null) sb.append(l).append('\n');
            }
            StringBuilder eb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String l; while ((l = br.readLine()) != null) eb.append(l).append('\n');
            }
            p.waitFor();
            return new String[]{String.valueOf(p.exitValue()), sb.toString() + eb.toString()};
        } catch (Throwable t) { return new String[]{"-1", ""}; }
    }

    static boolean portListening5555() { return listenIn("/proc/net/tcp") || listenIn("/proc/net/tcp6"); }

    private static boolean listenIn(String path) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(path)))) {
            String l;
            while ((l = br.readLine()) != null)
                if (l.contains(":15B3") && l.toUpperCase(Locale.US).contains(" 0A ")) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static Context getSystemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(thread);
    }

    /** Sürüm damgasını diske yaz (qcam_loop_N.ver ile aynı mantık). */
    static void writeVersion() {
        try {
            File f = new File(VERFILE);
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write((KEEPER_VER + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
    }

    static void log(String s) {
        try {
            File f = new File(LOG);
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write((stamp + "  " + s + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
    }

    static void appendFile(String path, String s) {
        try {
            File f = new File(path);
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(s.getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
    }

    static String stampNow() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }
}
