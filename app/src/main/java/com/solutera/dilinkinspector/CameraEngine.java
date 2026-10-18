package com.solutera.dilinkinspector;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * Kamera altyapısı: araca kendi kendine kurulum + kare okuma + UYVY dönüşümleri.
 *
 * Kurulum zinciri (tümü uygulama içinden, dış ADB gerektirmez):
 *   1. TS Settings arka kapısı / OTA servisi ile kablosuz ADB (5555) açılır
 *   2. AdbBridge ile 127.0.0.1:5555'e bağlanılır (kendi RSA kimliğımız)
 *   3. Asset'lerdeki qcam_patched + qcam_loop + qcarcam_config.xml araca yazılır
 *   4. qcam_loop yardımcısı başlatılır → /data/local/tmp/snap_<kamera>.raw üretir
 *   5. Uygulama snap dosyalarını doğrudan okur (dizin 0771, dosyalar 0666)
 *
 * Kare formatı: UYVY (YUV 4:2:2 paket) 1920×1024; alt 64 satır dolgu →
 * aktif alan 1920×960.
 */
public final class CameraEngine {
    public static final int W = 1920, H_FRAME = 1024;          // ham kare tamponu
    public static final int OUT_W = 1280, OUT_H = 960;         // etkin alan: sol üst 1280x960
    public static final int FRAME_BYTES = W * H_FRAME * 2;     // 3.932.160
    public static final int NV12_BYTES = OUT_W * OUT_H * 3 / 2;
    public static final int[] CAM_IDS = {0, 1, 2, 3, 5};
    // Etiketler araçta ölçüldü: indeks 2 = SAĞ gövde kamerası, indeks 3 = SOL
    public static final String[] CAM_NAMES = {"Front", "Rear", "Right", "Left", "DVR (external)"};

    private final Context ctx;
    private final ResearchLab lab;
    public volatile String lastError;

    /** TCP kare kaynağı (yardımcı 127.0.0.1:7777). SELinux dosya kısıtını aşar. */
    private Socket src;
    private java.io.BufferedInputStream srcIn;
    private OutputStream srcOut;
    private volatile int wantedCam = 0;

    public interface Step { void run(String line); }

    /** Yardımcı protokol sürümü — qcam_loop7.c içindeki QCAM_VERSION ile eşleşmeli.
     *  v7: KAMERA BAŞINA AYRI ÖRNEK — Ön/Arka/Sağ/Sol aynı anda akar.
     *  frame_%d<cam>%i.raw yamalı qcam_patched_c<cam> varyantları, kamera başına
     *  cam_demand_<cam> talep dosyası ve 7800+cam TCP portu. */
    public static final int HELPER_VER = 10;
    public static final int[] MULTI_CAMS = {0, 1, 2, 3};

    /** Son başlatılan yardımcı kamerası (yardımcı tek kamera ile çalışır). */
    private volatile int helperCam = -1;

    /** Uzakta çalışan/yazılı yardımcının sürümü (adb ile); okunamazsa -1. */
    public int remoteHelperVersion() {
        try {
            JSONObject r = lab.adb.shell("cat /data/local/tmp/qcam_loop_0.ver 2>/dev/null || cat /data/local/tmp/qcam_loop.ver 2>/dev/null || echo -1", 6000);
            return Integer.parseInt(r.optString("output", "-1").trim());
        } catch (Exception e) { return -1; }
    }

    /** Yardımcı süreci canlı mı (pidfile + kill -0; adb bağlantısı gerekir)? */
    public boolean helperRunning() {
        try {
            JSONObject st = lab.adb.shell("kill -0 $(cat /data/local/tmp/qcam_loop_0.pid 2>/dev/null) 2>/dev/null && echo yasiyor || echo olu", 6000);
            return st.optString("output").contains("yasiyor");
        } catch (Exception e) { return false; }
    }

    public CameraEngine(ResearchLab lab) {
        this.lab = lab;
        this.ctx = lab.engine.ctx;
    }

    /* ================= kurulum ================= */

    /** 1) Kablosuz ADB'yi hazır-la. ÖNEMLİ: bağlantı zaten çalışıyorsa HİÇBİRŞEY yazma
     *  (property/OTA yazımı adbd'yi yeniden başlatır ve TLS modunu değiştirebilir —
     *  çalışan bağlantıyı kendimiz kırmayalım). */
    public boolean connectAdb(Step log) {
        if (lab.adb.connected()) {
            log.run(ctx.getString(R.string.ce_adb_already_open));
            return true;
        }
        if (tryConnect()) {
            log.run(ctx.getString(R.string.ce_adb_already_listening));
            return true;
        }

        log.run(ctx.getString(R.string.ce_step1));
        try {
            String r = TsPropBridge.enableWirelessAdb(ctx);
            log.run(r);
        } catch (Exception e) { log.run(ctx.getString(R.string.ce_ts_bridge, ScanEngine.error(e))); }

        // NOT: y(31,99) KULLANILMAZ — adb_wifi_enabled=1 adbd'yi TLS moduna alır ve
        // klasik (TLS'siz) bağlantıyı engeller. Yalnız adb_enabled=1 gerekir.
        log.run(ctx.getString(R.string.ce_step2));
        try {
            String r1 = OtaTestCommand.call(ctx, svc -> OtaTestCommand.y(svc, 31, 77), 10000);
            log.run("y(31,77) → " + r1);
        } catch (Exception e) { log.run("OTA servisi: " + ScanEngine.error(e)); }

        log.run(ctx.getString(R.string.ce_step3));
        sleep(4000);
        if (tryConnect()) { log.run(ctx.getString(R.string.ce_connected)); return true; }

        log.run(ctx.getString(R.string.ce_tls_fallback));
        closeTls(log);
        if (tryConnect()) { log.run(ctx.getString(R.string.ce_connected_classic)); return true; }

        log.run(ctx.getString(R.string.ce_connect_failed));
        return false;
    }

    private boolean tryConnect() {
        try { return lab.adb.connect("127.0.0.1", 5555); }
        catch (Exception e) { lastError = ScanEngine.error(e); return false; }
    }

    /** "Klasik moda dön (TLS kapat)" akışı — otomatikleştirilmiş hali. */
    public void closeTls(Step log) {
        try {
            log.run("adb_wifi=0 → " + OtaTestCommand.call(ctx, svc -> OtaTestCommand.y(svc, 31, 88), 10000));
        } catch (Exception e) { log.run("y(31,88): " + ScanEngine.error(e)); }
        if (lab.adb.connected()) {
            try { lab.adb.shell("settings put global adb_wifi_enabled 0", 6000); } catch (Exception ignored) {}
        }
        sleep(500);
        try { log.run("wiress=0 → " + TsPropBridge.setSingle(ctx, "sys.connect.adb.wiress", "0")); } catch (Exception e) { log.run(ScanEngine.error(e)); }
        sleep(1000);
        try { log.run("wiress=1 → " + TsPropBridge.setSingle(ctx, "sys.connect.adb.wiress", "1")); } catch (Exception e) { log.run(ScanEngine.error(e)); }
        sleep(2500);
    }

    /** 2) Asset'lerdeki yardımcı dosyaları araca yaz (idempotent: boyut eşitse atlar). */
    public boolean installHelpers(Step log) throws Exception {
        if (!lab.adb.connected()) { log.run(ctx.getString(R.string.ce_adb_not_connected_install)); return false; }
        String[] names = {"qcam_patched_c0", "qcam_patched_c1", "qcam_patched_c2", "qcam_patched_c3", "qcam_loop8", "qcarcam_config.xml"};
        boolean allOk = true;
        for (String name : names) allOk &= pushAsset(name, log);
        lab.adb.shell("chmod 755 /data/local/tmp/qcam_loop8 /data/local/tmp/qcam_patched_c0 /data/local/tmp/qcam_patched_c1 /data/local/tmp/qcam_patched_c2 /data/local/tmp/qcam_patched_c3", 8000);
        return allOk;
    }

    /** Asset'i araca yazar; uzak dosyanın MD5 karması eşitse atlar (boyut değil
     *  içerik kontrolü — aynı boyutlu farklı sürümler doğru şekilde güncellenir). */
    private boolean pushAsset(String name, Step log) throws Exception {
        byte[] data = readAsset("bin/" + name);
        String localMd5 = md5(data);
        JSONObject hash = lab.adb.shell("md5sum /data/local/tmp/" + name + " 2>/dev/null | cut -d' ' -f1", 8000);
        String remote = hash.optString("output", "").trim().toLowerCase(Locale.US);
        if (remote.length() == 32 && remote.equals(localMd5)) {
            log.run(ctx.getString(R.string.ce_asset_current, name, data.length, localMd5.substring(0, 8)));
            return true;
        }
        log.run(ctx.getString(R.string.ce_asset_writing, name, data.length));
        lab.adb.shell("rm -f /data/local/tmp/" + name, 8000);
        int chunk = 18 * 1024; // base64 sonrası ~24KB — adb shell komut sınırı içinde
        for (int off = 0; off < data.length; off += chunk) {
            int len = Math.min(chunk, data.length - off);
            String piece = android.util.Base64.encodeToString(java.util.Arrays.copyOfRange(data, off, off + len), android.util.Base64.NO_WRAP);
            lab.adb.shell("echo " + piece + " | base64 -d >> /data/local/tmp/" + name, 15000);
        }
        JSONObject after = lab.adb.shell("md5sum /data/local/tmp/" + name + " 2>/dev/null | cut -d' ' -f1", 8000);
        boolean ok = after.optString("output", "").trim().toLowerCase(Locale.US).equals(localMd5);
        log.run(ok ? ctx.getString(R.string.ce_asset_verified, name) : ctx.getString(R.string.ce_asset_not_verified, name));
        if (!ok) lastError = ctx.getString(R.string.ce_asset_hash_failed, name);
        return ok;
    }

    private static String md5(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private byte[] readAsset(String path) throws IOException {
        AssetManager am = ctx.getAssets();
        try (InputStream in = am.open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** 3) Yardımcıyı hazırla — istenen KAMERA ile (v5: tek kamera oturumu).
     *  Süreç canlı, sürüm VE kamera eşleşiyorsa dokunma; değilse yenile.
     *  Kare akışı 60 sn içinde doğrulanır (TCP yoksa ADB yolu). */
    /** 3) Yardımcıları hazırla — v7: 4 KAMERA AYNI ANDA (0=Ön 1=Arka 2=Sağ 3=Sol).
     *  Sürümler eşleşiyorsa ve örnek yaşıyorsa dokunma; değilse hepsini yenile.
     *  Kare akışı 60 sn içinde doğrulanır (TCP yoksa ADB yolu). */
    /** Cihazdaki yardımcı binary'ler APK asset'leriyle AYNI mı? (içerik/MD5).
     *  Herhangi biri farklı/eksikse false → startHelper yeniden kurar.
     *  Hata/okunamama durumunda güvenli taraf: false (yeniden kur). */
    private boolean helpersBinaryCurrent() {
        String[] names = {"qcam_loop8", "qcam_patched_c0", "qcam_patched_c1", "qcam_patched_c2", "qcam_patched_c3"};
        try {
            for (String name : names) {
                String localMd5 = md5(readAsset("bin/" + name));
                JSONObject h = lab.adb.shell("md5sum /data/local/tmp/" + name + " 2>/dev/null | cut -d' ' -f1", 8000);
                String remote = h.optString("output", "").trim().toLowerCase(Locale.US);
                if (!remote.equals(localMd5)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean startHelper(Step log, int cam) {
        if (!lab.adb.connected()) { log.run(ctx.getString(R.string.ce_adb_not_connected)); return false; }
        try {
            int remote = remoteHelperVersion();
            boolean alive = helperRunning();
            // keeper ile aynı mantık: sürüm damgası eşit olsa bile cihazdaki
            // binary APK'dekiyle AYNI değilse (QCAM_VERSION değişmeden kod
            // düzeltilmişse) yenile. Yoksa MD5 farkı sessizce yutulurdu.
            boolean binCurrent = helpersBinaryCurrent();
            if (alive && remote == HELPER_VER && binCurrent) {
                log.run(ctx.getString(R.string.ce_helpers_current, remote));
            } else {
                log.run(!binCurrent && alive && remote == HELPER_VER
                        ? ctx.getString(R.string.ce_helper_binary_changed)
                        : (alive ? ctx.getString(R.string.ce_helper_diff_version, remote) : ctx.getString(R.string.ce_helpers_closed)));
                lab.adb.shell("pkill qcam_loop8; pkill qcam_loop; pkill qcam_patched; sleep 1; rm -f /data/local/tmp/qcam_loop_*.pid /data/local/tmp/qcam_loop_*.ver /data/local/tmp/qcam_loop.pid /data/local/tmp/qcam_loop.ver; true", 10000);
                installHelpers(log);
                touchDemand();
                StringBuilder launch = new StringBuilder("cd /data/local/tmp && ");
                for (int i = 0; i < MULTI_CAMS.length; i++) {
                    int c = MULTI_CAMS[i];
                    launch.append("(setsid nohup ./qcam_loop8 ").append(c)
                          .append(" > q8_").append(c).append(".log 2>&1 < /dev/null &)");
                    if (i < MULTI_CAMS.length - 1) launch.append(' ');
                }
                lab.adb.shell(launch.toString(), 8000);
                log.run(ctx.getString(R.string.ce_helpers_started));
            }
            helperCam = cam;
            return waitFirstFrame(log, cam, 60_000);
        } catch (Exception e) {
            lastError = ScanEngine.error(e);
            log.run("Hata: " + lastError);
            return false;
        }
    }

    /** Tek örneği yeniden başlat (yanlış-kaynak / kare kesilmesi durumunda). */
    public void restartHelperCam(int cam) {
        try {
            lab.adb.shell("kill $(cat /data/local/tmp/qcam_loop_" + cam + ".pid) 2>/dev/null; sleep 1; "
                + "cd /data/local/tmp && (setsid nohup ./qcam_loop8 " + cam
                + " > q8_" + cam + ".log 2>&1 < /dev/null &)", 8000);
        } catch (Exception ignored) {}
    }

    /** Yardımcının açtığı kamera (kayit dosyasindan; yoksa -1). */
    public int remoteHelperCam() {
        try {
            JSONObject r = lab.adb.shell("ls /data/local/tmp/qcam_config_*.xml 2>/dev/null | tail -1", 6000);
            String out = r.optString("output", "").trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("qcam_config_(\\d+)\\.xml").matcher(out);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception e) { return -1; }
    }

    /** Talep kalp atışı: yardımcılar yalnız bunu gördüğünde döküm yapar (disk koruma).
     *  v7: kamera başına talep dosyası — hepsi touch'lanır. */
    public void touchDemand() {
        try {
            StringBuilder cmd = new StringBuilder();
            for (int i = 0; i < MULTI_CAMS.length; i++) {
                if (i > 0) cmd.append(" && ");
                cmd.append("touch /data/local/tmp/cam_demand_").append(MULTI_CAMS[i]);
            }
            lab.adb.shell(cmd.toString(), 6000);
        } catch (Exception ignored) {}
    }

    /** Kare akışını doğrula: toplam ms içinde istenen kameradan ilk kareyi bekle. */
    private boolean waitFirstFrame(Step log, int cam, long totalMs) throws Exception {
        long deadline = System.currentTimeMillis() + totalMs;
        long lastProgress = 0;
        while (System.currentTimeMillis() < deadline) {
            touchDemand();
            byte[] f = nextFrameFor(cam, 1000);
            if (f != null) {
                log.run(ctx.getString(R.string.ce_helper_ready, cam, remoteHelperVersion()));
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastProgress > 12000) {
                lastProgress = now;
                JSONObject l = lab.adb.shell("tail -2 /data/local/tmp/qcam_loop.log 2>/dev/null", 6000);
                log.run(ctx.getString(R.string.ce_waiting_helper_log, l.optString("output").trim().replace("\n", " | ")));
            }
            sleep(400);
        }
        lastError = ctx.getString(R.string.ce_frame_stream_failed);
        log.run(ctx.getString(R.string.ce_frame_stream_not_started));
        return false;
    }

    /** Belirli kamera için kare oku (TCP -> dosya -> ADB ikili). */
    public byte[] nextFrameFor(int cam, int timeoutMs) {
        int save = wantedCam;
        wantedCam = cam;
        try { return nextFrame(timeoutMs); }
        finally { wantedCam = save; }
    }

    /** Yardımcıyı durdur (kamera akışı kapanır). */
    public void stopHelper() {
        if (!lab.adb.connected()) return;
        try {
            lab.adb.shell("pkill qcam_loop8; pkill qcam_loop; pkill qcam_patched; rm -f /data/local/tmp/qcam_loop_*.pid /data/local/tmp/qcam_loop.pid; true", 8000);
        } catch (Exception ignored) {}
    }

    /**
     * Kaldırma hazırlığı: yardımcı süreçlerini öldürür, araca kurduğumuz TÜM
     * dosyaları siler ve (closeAdb=true ise) kablosuz ADB'yi kapatır.
     * Android uninstall sırasında uygulamaya kod çalıştırma şansı vermediği için
     * kullanıcı uygulamayı kaldırmadan ÖNCE bu düğmeyi kullanır.
     */
    public boolean uninstallPrep(Step log, boolean closeAdb) {
        try {
            if (!lab.adb.connected()) {
                log.run(ctx.getString(R.string.ce_adb_connecting));
                tryConnectQuiet();
            }
            if (!lab.adb.connected()) { log.run(ctx.getString(R.string.ce_adb_connect_failed_files)); return false; }
            log.run(ctx.getString(R.string.ce_stopping_helpers));
            lab.adb.shell("kill $(cat /data/local/tmp/qcam_loop.pid) 2>/dev/null; pkill qcam_loop; pkill qcam_patched; sleep 1; true", 10000);
            log.run(ctx.getString(R.string.ce_deleting_files));
            lab.adb.shell("cd /data/local/tmp && rm -f qcam_loop.pid qcam_loop.ver qcam_loop.log qcam_loop qcam_patched qcarcam_config.xml snap_*.raw frame_*.raw; true", 10000);
            JSONObject left = lab.adb.shell("ls /data/local/tmp/ | grep -cE 'qcam|snap' || true", 6000);
            log.run(ctx.getString(R.string.ce_remaining_files, left.optString("output", "?").trim()));
            if (closeAdb) {
                log.run(ctx.getString(R.string.ce_closing_adb));
                try { OtaTestCommand.call(ctx, svc -> OtaTestCommand.y(svc, 31, 66), 8000); } catch (Exception ignored) {}
                try { TsPropBridge.setSingle(ctx, "sys.connect.adb.wiress", "0"); } catch (Exception ignored) {}
                lab.adb.disconnect();
            }
            log.run(ctx.getString(R.string.ce_cleanup_done));
            return true;
        } catch (Exception e) {
            lastError = ScanEngine.error(e);
            log.run("Hata: " + lastError);
            return false;
        }
    }

    private boolean tryConnectQuiet() {
        try { return lab.adb.connect("127.0.0.1", 5555); } catch (Exception e) { return false; }
    }

    /** Yardımcının şu an açtığı kamera. */
    public int getHelperCam() { return helperCam; }

    /**
     * Yanlış-kaynak koruması (kamera 0): ön kameranın sağ bandı (x 1400-1800)
     * sıfır dolgudur. AVM oturum çaprazlanınca gelen bileşik/DVR karelerinde bu
     * bant dolu olur (yeşil dolgu + PIP önizlemeler). Böyle kareler atılır;
     * art arda gelirse çağıran yardımcıyı yenileyebilir.
     */
    public boolean looksLikeWrongSource(byte[] frame) {
        if (frame == null || frame.length != FRAME_BYTES) return false;
        int samples = 0, nonzero = 0;
        for (int y = 100; y < 900; y += 16) {
            int row = y * W * 2;
            for (int x = 1400; x < 1800; x += 16) {
                int u = frame[row + x * 2] & 0xFF;
                int v = frame[row + x * 2 + 2] & 0xFF;
                int lum = frame[row + x * 2 + 1] & 0xFF;
                samples++;
                if (u != 0 || v != 0 || lum != 0) nonzero++;
            }
        }
        return samples > 0 && nonzero * 100 / samples > 20;
    }

    /* ================= TCP kare kaynağı ================= */

    /** Yardımcıya hızlı PING/PONG — çalışıyor mu? */
    public boolean ping() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 7777), 1200);
            s.setSoTimeout(1200);
            OutputStream o = s.getOutputStream();
            o.write("PING\n".getBytes("US-ASCII"));
            o.flush();
            byte[] buf = new byte[8];
            int n = s.getInputStream().read(buf);
            return n >= 4 && new String(buf, 0, n, "US-ASCII").startsWith("PONG");
        } catch (Exception e) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) {}
        }
    }

    /** Kalıcı kare bağlantısını kur ve istenen kamerayı seç. */
    public synchronized boolean selectCamera(int cam) {
        wantedCam = cam;
        if (src != null && !src.isClosed() && src.isConnected()) {
            try { return true; }
            catch (Exception ignored) { closeSource(); }
        }
        try {
            src = new Socket();
            // v7: kamera başına port (7800+cam)
            src.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 7800 + cam), 1500);
            src.setTcpNoDelay(true);
            srcIn = new java.io.BufferedInputStream(src.getInputStream(), 1 << 16);
            srcOut = src.getOutputStream();
            return true;
        } catch (Exception e) {
            lastError = ScanEngine.error(e);
            closeSource();
            return false;
        }
    }

    private void closeSource() {
        try { if (src != null) src.close(); } catch (Exception ignored) {}
        src = null; srcIn = null; srcOut = null;
    }

    /**
     * Sonraki kareyi oku (bloklayıcı, timeoutMs ile sınırlı).
     * Boşta zaman aşımı (kare gelmedi) temizdir: bağlantı korunur.
     * Yarım okuma (desync riski) bağlantıyı yeniler. TCP yoksa dosyadan dener.
     */
    public synchronized byte[] nextFrame(int timeoutMs) {
        for (int attempt = 0; attempt < 3 && src != null && !src.isClosed(); attempt++) {
            try {
                src.setSoTimeout(timeoutMs);
                // önce 1 bayt: hiç kare yokken temiz zaman aşımı (bağlantıyı koru)
                int first = srcIn.read();
                if (first < 0) { closeSource(); break; }
                if (first != 'F') { closeSource(); break; }
                byte[] rest = readFully(srcIn, 15);
                if (rest == null) { closeSource(); break; }
                byte[] hdr = new byte[16];
                hdr[0] = (byte) first;
                System.arraycopy(rest, 0, hdr, 1, 15);
                if (hdr[1] != 'R' || hdr[2] != 'M' || hdr[3] != 'E') { closeSource(); break; }
                int cam = int32(hdr, 4);
                int len = int32(hdr, 12);
                if (len <= 0 || len > 8 * 1024 * 1024) { closeSource(); break; }
                // qcam v10+ NV12 (NV12_BYTES) yollar; eski qcam UYVY (FRAME_BYTES).
                // İkisini de kabul et (bu yol ağırlıkla ilk-kare doğrulaması için;
                // çağıran gelen baytın boyutundan formatı anlar).
                if (cam != wantedCam || (len != FRAME_BYTES && len != NV12_BYTES)) {
                    // istenmeyen kamera/boyut: gövdeyi atla, sonraki kareyi dene
                    if (!skipFully(srcIn, len)) { closeSource(); break; }
                    continue;
                }
                byte[] data = readFully(srcIn, len);
                if (data == null) { closeSource(); break; }
                return data;
            } catch (java.net.SocketTimeoutException e) {
                return null; // temiz boşta zaman aşımı
            } catch (Exception e) {
                lastError = ScanEngine.error(e);
                closeSource();
                break;
            }
        }
        // TCP yok/koptu → dosya fallback (SELinux izin veriyorsa çalışır)
        if (frameStamp(wantedCam) > 0) return readFrame(wantedCam);
        // Son çare: kareyi uygulamanın KENDİ ADB bağlantısı üzerinden ikili aktar
        // (shell dosyayı okur — kendi dosyası; adbd uygulamaya taşır; SELinux'e takılmaz)
        if (lab.adb.connected()) {
            try {
                byte[] data = lab.adb.shellBinary("cat /data/local/tmp/snap_" + wantedCam + ".raw", 12000);
                if (data.length == FRAME_BYTES) return data;
                lastError = "adb kare boyutu: " + data.length;
            } catch (Exception e) {
                lastError = ScanEngine.error(e);
            }
        }
        return null;
    }

    private boolean skipFully(InputStream in, int len) {
        byte[] buf = new byte[8192];
        try {
            int remaining = len;
            while (remaining > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) return false;
                remaining -= n;
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private static int int32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int done = 0;
        while (done < len) {
            int n = in.read(out, done, len - done);
            if (n < 0) return null;
            done += n;
        }
        return out;
    }

    /* ================= kare okuma ================= */

    public File snapFile(int cam) { return new File("/data/local/tmp/snap_" + cam + ".raw"); }

    /** snap dosyasını oku; boyut tam değilse (yarım yazım) null döner. */
    public byte[] readFrame(int cam) {
        File f = snapFile(cam);
        long mtime = f.lastModified();
        long size = f.length();
        if (size != FRAME_BYTES) return null;
        byte[] data = new byte[FRAME_BYTES];
        try (FileInputStream in = new FileInputStream(f)) {
            int done = 0;
            while (done < FRAME_BYTES) {
                int n = in.read(data, done, FRAME_BYTES - done);
                if (n < 0) return null;
                done += n;
            }
        } catch (Exception e) { lastError = ScanEngine.error(e); return null; }
        // okuma sırasında yeniden yazılmışsa (rename atomik olduğundan mtime değişir) at
        if (f.lastModified() != mtime || f.length() != FRAME_BYTES) return null;
        return data;
    }

    /** Yeni kare mi? (mtime değişimi) */
    public long frameStamp(int cam) {
        File f = snapFile(cam);
        return f.isFile() ? f.lastModified() : 0;
    }

    /* ================= dönüşümler ================= */

    /**
     * Atıl kare filtresi: iki UYVY kare arasında anlamlı fark var mı?
     * Etkin alanı (sol 1280x960) seyrek örnekler; 12+ parlaklık farkı olan örnek
     * oranı %0,4'ün altındaysa sahne statik sayılır → kare atlanır.
     */
    public boolean changedEnough(byte[] prev, byte[] cur) {
        if (prev == null || cur == null || prev.length != FRAME_BYTES || cur.length != FRAME_BYTES) return true;
        int samples = 0, changed = 0;
        for (int y = 0; y < OUT_H; y += 8) {
            int row = y * W * 2;
            for (int x = 0; x < OUT_W; x += 8) {
                int off = row + x * 2 + 1; // Y
                int d = (prev[off] & 0xFF) - (cur[off] & 0xFF);
                if (d < 0) d = -d;
                if (d > 12) changed++;
                samples++;
            }
        }
        if (samples == 0) return true;
        return changed * 1000L >= samples * 4; // değişen örnek ≥ %0,4
    }

    /** UYVY → ARGB8888 Bitmap. Yalnız etkin alan (sol 1280x960): yeşil dolgu
     *  bandı ve aracın PIP önizleme kareleri kırpılır. */
    public Bitmap toBitmap(byte[] uyvy) {
        return toBitmap(uyvy, null);
    }

    /** UYVY → ARGB8888 Bitmap. {@code scratch} verilirse (uzunluk ≥ OUT_W*OUT_H)
     *  piksel ara-belleği olarak tekrar kullanılır; null ise yeni ayrılır.
     *  Bitmap nesnesi her çağrıda YENİdir — createBitmap pikselleri kopyalar,
     *  böylece scratch hemen tekrar kullanılabilir ve UI thread'i etkilenmez. */
    public Bitmap toBitmap(byte[] uyvy, int[] scratch) {
        int[] pixels = (scratch != null && scratch.length >= OUT_W * OUT_H)
                ? scratch : new int[OUT_W * OUT_H];
        int base = 0;
        for (int y = 0; y < OUT_H; y++) {
            int row = y * W * 2;
            for (int x = 0; x < OUT_W; x += 2) {
                int u = uyvy[row + x * 2] & 0xFF;
                int y0 = uyvy[row + x * 2 + 1] & 0xFF;
                int v = uyvy[row + x * 2 + 2] & 0xFF;
                int y1 = uyvy[row + x * 2 + 3] & 0xFF;
                pixels[base + x] = yuvPixel(y0, u, v);
                pixels[base + x + 1] = yuvPixel(y1, u, v);
            }
            base += OUT_W;
        }
        return Bitmap.createBitmap(pixels, 0, OUT_W, OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
    }

    /** NV12 (OUT_W x OUT_H) → ARGB8888 Bitmap. qcam v10'da kare TCP'den zaten
     *  NV12 geldiği için canlı izleme bunu kullanır (UYVY yolu gerekmez).
     *  NV12: ySize = OUT_W*OUT_H Y baytı; ardından her 2x2 blok için (U,V) çifti. */
    public Bitmap nv12ToBitmap(byte[] nv12, int[] scratch) {
        int[] pixels = (scratch != null && scratch.length >= OUT_W * OUT_H)
                ? scratch : new int[OUT_W * OUT_H];
        int ySize = OUT_W * OUT_H;
        for (int y = 0; y < OUT_H; y++) {
            int yRow = y * OUT_W;
            int uvRow = ySize + (y >> 1) * OUT_W;   // (OUT_W/2) çift * 2 bayt = OUT_W
            for (int x = 0; x < OUT_W; x++) {
                int yy = nv12[yRow + x] & 0xFF;
                int uvOff = uvRow + (x & ~1);        // çift x hizası: U sonra V
                int u = nv12[uvOff] & 0xFF;
                int v = nv12[uvOff + 1] & 0xFF;
                pixels[yRow + x] = yuvPixel(yy, u, v);
            }
        }
        return Bitmap.createBitmap(pixels, 0, OUT_W, OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
    }

    private static int yuvPixel(int y, int u, int v) {
        int c = y - 16, d = u - 128, e = v - 128;
        int r = clip((298 * c + 409 * e + 128) >> 8);
        int g = clip((298 * c - 100 * d - 208 * e + 128) >> 8);
        int b = clip((298 * c + 516 * d + 128) >> 8);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clip(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** UYVY → NV12 (1280x960 etkin alan): Y düzlemi + araya eklenmiş U/V (4:2:0).
     *  Yeni dizi ayırır — her kare için allokasyon yapar. Sıcak yolda (kayıt)
     *  GC baskısını önlemek için {@link #toNv12(byte[], byte[])} tercih edilir. */
    public byte[] toNv12(byte[] uyvy) {
        byte[] out = new byte[NV12_BYTES];
        toNv12(uyvy, out);
        return out;
    }

    /** UYVY → NV12, çağıranın sağladığı {@code out} dizisine yazar (yeniden
     *  kullanılabilir buffer). {@code out} en az {@link #NV12_BYTES} olmalı.
     *  Kayıt döngüsünde kamera başına tek buffer tekrar kullanılır → sıfır
     *  allokasyon, GC baskısı yok. */
    public void toNv12(byte[] uyvy, byte[] out) {
        int ySize = OUT_W * OUT_H;
        int uvBase = ySize;
        int uvRow = 0;
        for (int y = 0; y < OUT_H; y++) {
            int row = y * W * 2;
            int yRow = y * OUT_W;
            for (int x = 0; x < OUT_W; x++) out[yRow + x] = uyvy[row + x * 2 + 1];
            if ((y & 1) == 0) {
                int uOff = uvBase + uvRow * OUT_W;
                for (int x = 0; x < OUT_W; x += 2) {
                    out[uOff + (x >> 1)] = uyvy[row + x * 2];               // U
                    out[uOff + (OUT_W >> 1) + (x >> 1)] = uyvy[row + x * 2 + 2]; // V
                }
                uvRow++;
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
