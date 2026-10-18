package com.solutera.dilinkinspector;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * com.byd.ota OtaUiService (exported, izinsiz) üzerinden BYD'nin kendi
 * IOtaTestCommand arayüzünü çağırır.
 *
 * Firmware kaynağı: BydOtaUpdate.apk -> OtaTestCommandStub.y(cmd, val):
 *   y(31, 77) -> Settings.Global adb_enabled=1        (66 -> 0)
 *   y(31, 99) -> Settings.Global adb_wifi_enabled=1   (88 -> 0)
 * Descriptor ve transaction kodu firmware'deki IOtaTestCommand.java'dan birebir:
 *   descriptor "com.byd.sdkupdate.inner.IOtaTestCommand", y = transact kodu 3.
 * Servis: com.byd.ota/.view.OtaUiService, action "com.intent.action.OTA_UPGRADE_TEST".
 */
public final class OtaTestCommand {
    public static final String DESCRIPTOR = "com.byd.sdkupdate.inner.IOtaTestCommand";
    public static final String PACKAGE = "com.byd.ota";
    public static final String SERVICE = "com.byd.ota.view.OtaUiService";
    public static final String ACTION = "com.intent.action.OTA_UPGRADE_TEST";

    public interface Job {
        String run(IBinder service) throws RemoteException;
    }

    private OtaTestCommand() {}

    /** Servise bağlanır, job'ı binder üzerinden çalıştırır, sonucu döndürür ve bağlantıyı keser. */
    public static String call(Context ctx, Job job, long timeoutMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final IBinder[] holder = new IBinder[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                holder[0] = binder;
                latch.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        Intent intent = new Intent(ACTION).setComponent(new ComponentName(PACKAGE, SERVICE));
        boolean bound;
        try {
            bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            throw new IllegalStateException("OtaUiService bind çağrısı başarısız: " + ScanEngine.error(e));
        }
        if (!bound) throw new IllegalStateException("OtaUiService bind reddedildi (servis yok veya exported değil).");
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("OtaUiService bağlantısı zaman aşımına uğradı.");
            }
            if (holder[0] == null) throw new IllegalStateException("OtaUiService binder boş döndü.");
            return job.run(holder[0]);
        } finally {
            try { ctx.unbindService(connection); } catch (Exception ignored) {}
        }
    }

    /** IOtaTestCommand.y(cmd, val) — transaction kodu 3, (int, int) parametre, String dönüş. */
    public static String y(IBinder service, int cmd, int val) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(cmd);
            data.writeInt(val);
            if (!service.transact(3, data, reply, 0)) {
                throw new RemoteException("OtaUiService transact(3) başarısız — servis yanıt vermedi.");
            }
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
