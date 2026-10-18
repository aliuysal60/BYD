package com.solutera.dilinkinspector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Araç açıldığında (Android boot tamamlandığında):
 *  1. Kablosuz ADB zincirini KENDİLİĞİNDEN yeniden kurar (TS köprüsü + OTA)
 *  2. ParkCamService'i otomatik başlatır (Electro gibi — kullanıcı hiçbir
 *     düğmeye basmaz; araç açılır açılmaz kamera + WiFi koruması devrede)
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context context, Intent intent) {
        final String action = intent != null && intent.getAction() != null ? intent.getAction() : "";
        // BOOT_COMPLETED / QUICKBOOT: araç açılışı.
        // MY_PACKAGE_REPLACED: uygulama güncellendi — servis açılıp keeper/qcam'i
        //   sürüm kontrollü yeniler (eski süreçler öldürülür). Yeniden başlatma
        //   beklenmeden güncelleme hemen devreye girer.
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        // 1) Servisi hemen başlat (bildirim + wakelock).
        //    "setup" action: servis ZATEN canlıysa bile bootstrap'i (ADB→keeper→
        //    qcam, sürüm kontrollü yenileme) tetikler. Güncelleme sonrası uygulama
        //    süreci kill edilip yeni onCreate çalışsa da, bu çift güvence sağlar.
        try {
            Intent svc = new Intent(context, ParkCamService.class).setAction("setup");
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {}

        // 2) ADB zincirini arka planda yeniden aç (servis kendi de dener ama
        //    sistem servisleri hazır olmayabilir; çift güvence)
        new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException ignored) { return; }
            for (int attempt = 1; attempt <= 4; attempt++) {
                try {
                    try {
                        OtaTestCommand.call(context, svc -> OtaTestCommand.y(svc, 31, 77), 8000);
                    } catch (Exception ignored) {}
                    try {
                        TsPropBridge.enableWirelessAdb(context);
                    } catch (Exception ignored) {}
                    // doğrula
                    java.net.Socket s = null;
                    try {
                        s = new java.net.Socket();
                        s.connect(new java.net.InetSocketAddress(
                                java.net.InetAddress.getLoopbackAddress(), 5555), 3000);
                        s.close();
                        return; // 5555 açık — tamam
                    } catch (Exception e) {
                    } finally { try { if (s != null) s.close(); } catch (Exception ignored) {} }
                } catch (Exception ignored) {}
                try { Thread.sleep(6000); } catch (InterruptedException ignored) { return; }
            }
        }, "boot-adb").start();
    }
}
