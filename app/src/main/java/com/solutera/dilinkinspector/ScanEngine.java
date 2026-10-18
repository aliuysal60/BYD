package com.solutera.dilinkinspector;

import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.*;
import android.hardware.usb.*;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Device inventory and persistent research evidence. Active operations live in ResearchLab. */
public final class ScanEngine {
    public static final String[] ACTIONS = {
        "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "android.settings.DEVICE_INFO_SETTINGS",
        "android.settings.SETTINGS", "android.settings.APPLICATION_SETTINGS",
        "android.settings.MANAGE_APPLICATIONS_SETTINGS", "android.settings.USB_SETTINGS",
        "android.settings.WIFI_SETTINGS", "android.settings.WIRELESS_DEBUGGING_SETTINGS"
    };
    private static ScanEngine instance;
    public static synchronized ScanEngine get(Context c) {
        if (instance == null) instance = new ScanEngine(c.getApplicationContext());
        return instance;
    }
    public final Context ctx;
    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    public volatile boolean running, cancelled;
    public volatile String status = "Taramaya hazır";
    public volatile int progress;
    public volatile Runnable listener;
    private JSONObject report = new JSONObject();
    private JSONArray events = new JSONArray();
    private final Object lock = new Object();
    private ScanEngine(Context c) {
        ctx = c;
        try {
            String raw = readFile(new File(c.getFilesDir(), "latest.json"), 16000000);
            report = new JSONObject(raw);
            events = report.optJSONArray("events");
            if (events == null) events = new JSONArray();
            if ("running".equals(report.optString("scan_status"))) {
                report.put("scan_status", "interrupted_process_ended");
                events.put(obj("at",time(),"type","recovery","detail","Önceki tarama tamamlanmadan uygulama işlemi sonlanmış; kısmi rapor yüklendi."));
            }
            status = "Önceki rapor yüklendi • " + report.optString("scan_status", "bilinmiyor");
        } catch (Exception ignored) { }
    }
    private void publish() { main.post(() -> { Runnable r = listener; if (r != null) r.run(); }); }
    public static String error(Throwable t) { return t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()); }
    public static JSONObject obj(Object... kv) {
        JSONObject j = new JSONObject();
        for (int i=0; i+1<kv.length; i+=2) try { j.put(String.valueOf(kv[i]), kv[i+1] == null ? JSONObject.NULL : kv[i+1]); } catch (Exception ignored) { }
        return j;
    }
    public static String time() { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()); }
    public synchronized void start(boolean extended) {
        if (running) return;
        try { if(report.has("created_at")||events.length()>0)reportZip(); }
        catch(Exception e){status="Önceki rapor arşivlenemedi: "+error(e);publish();return;}
        running = true; cancelled = false; progress = 0; status = "Tarama başlıyor";
        synchronized(lock) {
            JSONObject research=report.optJSONObject("research");
            report = obj("schema", 2, "app_version", "2.0.0", "created_at", time(), "scan_status", "running",
                "mode", "unprivileged_read_only", "extended", extended,
                "limitations", "Tarama salt okunurdur. Araştırma ve konsol işlemleri ayrıca kaydedilir. TCP açıklığı ADB kanıtı değildir. Üçüncü taraf uygulamalara dönen ayar değerleri filtrelenebilir.");
            if(research!=null)try{report.put("research",research);}catch(Exception ignored){}
            events = new JSONArray();
        }
        publish();
        worker.execute(() -> {
            try {
                section("device", "Cihaz ve Android sürümü", 8, () -> device());
                section("settings", "Geliştirici ve ADB ayarları", 18, () -> settings());
                section("restrictions", "Kullanıcı ve yönetim kısıtlamaları", 27, () -> restrictions());
                section("properties", "USB ve hata ayıklama özellikleri", 38, () -> properties());
                section("packages", "Uygulamalar, ekranlar ve izinler", 66, () -> packages());
                section("intent_routes", "Standart ayar ekranları", 76, () -> routes());
                section("usb", "USB bağlantı göstergeleri", 82, () -> usb());
                section("network", "Yerel IP ve ağ bilgisi", 85, () -> network());
                section("loopback", "Yalnızca cihaz içindeki 5555 portu", 88, () -> loopback());
                if (extended) section("commands", "Sınırlı salt okunur sistem sorguları", 96, () -> commands());
                synchronized(lock) { report.put("scan_status", cancelled ? "cancelled" : "complete"); report.put("finished_at", time()); }
                status = cancelled ? "Durduruldu • kısmi rapor hazır" : "Tarama tamamlandı • rapor hazır";
                progress = cancelled ? progress : 100;
            } catch (Exception e) {
                event("scan_error", error(e));
                synchronized(lock) { try { report.put("scan_status", "error"); } catch(Exception ignored) {} }
                status = "Tarama hatası • kısmi rapor korunuyor";
            } finally { running = false; save(); publish(); }
        });
    }
    interface Job { Object run() throws Exception; }
    private void section(String key, String title, int pct, Job job) {
        if (cancelled) return;
        status=title; publish();
        Object result;
        try { result=job.run(); } catch(Exception e) { result=obj("error",error(e)); }
        synchronized(lock) { try { report.put(key,result); } catch(Exception ignored) {} }
        progress=pct; save(); publish();
    }
    public void event(String type, String detail) {
        synchronized(lock) { events.put(obj("at",time(),"type",type,"detail",redact(detail))); }
        save(); publish();
    }
    public void record(String key,Object value){synchronized(lock){try{if(!report.has("created_at"))report.put("created_at",time());report.put(key,value);}catch(Exception ignored){}}save();publish();}
    public void updateStatus(String value){status=value;publish();}
    public interface Operation { void run() throws Exception; }
    public synchronized boolean operate(String title,Operation job){
        if(running)return false;running=true;cancelled=false;status=title;publish();
        worker.execute(()->{try{job.run();status=cancelled?"İşlem durduruldu":"İşlem tamamlandı";}
            catch(Exception e){event("operation_error",title+" • "+error(e));status=error(e);}
            finally{running=false;save();publish();}});return true;
    }
    public JSONObject state(){return obj("at",time(),"settings",settings(),"restrictions",restrictions(),"usb",usb(),"properties",properties());}
    public JSONObject snapshot() {
        synchronized(lock) {
            try {
                report.put("events", events);
                return new JSONObject(report.toString());
            } catch(Exception e) { return obj("error",error(e)); }
        }
    }
    private synchronized void save() {
        try {
            File tmp = new File(ctx.getFilesDir(), "latest.tmp");
            write(tmp, snapshot().toString(2));
            File dst = new File(ctx.getFilesDir(), "latest.json");
            if (!tmp.renameTo(dst)) throw new IOException("Report rename failed");
        } catch(Exception ignored) { }
    }
    public static void write(File file, String value) throws IOException {
        try(FileOutputStream o=new FileOutputStream(file)) { o.write(value.getBytes(StandardCharsets.UTF_8)); }
    }
    private JSONObject device() {
        return obj("manufacturer",Build.MANUFACTURER,"brand",Build.BRAND,"model",Build.MODEL,
            "device",Build.DEVICE,"product",Build.PRODUCT,"hardware",Build.HARDWARE,"board",Build.BOARD,
            "soc_manufacturer",Build.VERSION.SDK_INT>=31?Build.SOC_MANUFACTURER:null,"soc_model",Build.VERSION.SDK_INT>=31?Build.SOC_MODEL:null,
            "android",Build.VERSION.RELEASE,"sdk",Build.VERSION.SDK_INT,"security_patch",Build.VERSION.SECURITY_PATCH,
            "build_display",Build.DISPLAY,"build_id",Build.ID,"incremental",Build.VERSION.INCREMENTAL,
            "build_fingerprint",Build.FINGERPRINT,"build_type",Build.TYPE,"tags",Build.TAGS,
            "abis",new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)),"app_uid",android.os.Process.myUid(),
            "screen",ctx.getResources().getDisplayMetrics().toString(),"time_zone",TimeZone.getDefault().getID(),
            "note","VIN, IMEI, seri numarası, Android ID, hesap, konum ve Wi-Fi adı istenmez.");
    }
    private JSONObject settings() {
        JSONObject j=new JSONObject();
        String[][] groups={
            {"global","development_settings_enabled","adb_enabled","adb_wifi_enabled","adb_allowed_connection_time","verifier_verify_adb_installs","package_verifier_enable","device_provisioned"},
            {"secure","development_settings_enabled","adb_enabled","install_non_market_apps","user_setup_complete"}
        };
        for(String[] group:groups) {
            JSONObject values=new JSONObject();
            for(int i=1;i<group.length;i++) {
                String k=group[i];
                try {
                    String v=group[0].equals("global")?Settings.Global.getString(ctx.getContentResolver(),k):Settings.Secure.getString(ctx.getContentResolver(),k);
                    values.put(k,obj("status",v==null?"not_present_or_not_visible":"read","value",v));
                } catch(Exception e) { try { values.put(k,obj("status","denied_or_error","error",error(e))); } catch(Exception ignored) {} }
            }
            try { j.put(group[0],values); } catch(Exception ignored) {}
        }
        JSONObject perms=new JSONObject();
        for(String p:new String[]{"android.permission.WRITE_SECURE_SETTINGS","android.permission.DUMP","android.permission.READ_LOGS","android.permission.QUERY_ALL_PACKAGES","android.permission.INTERNET"})
            try { perms.put(p,ctx.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED); } catch(Exception ignored) {}
        try { j.put("this_app_permissions",perms);j.put("interpretation","0/1 üçüncü taraf uygulama görünümüdür; sürüme/OEM'e göre filtrelenebilir. Gerçek ADB bağlantısı ayrıca doğrulanmalı."); } catch(Exception ignored) {}
        return j;
    }
    private JSONObject restrictions() {
        JSONObject j=new JSONObject();
        try {
            UserManager u=(UserManager)ctx.getSystemService(Context.USER_SERVICE);
            Bundle b=u.getUserRestrictions(); JSONObject all=new JSONObject();
            for(String k:b.keySet()) all.put(k,String.valueOf(b.get(k)));
            j.put("current_user_restrictions",all);
            j.put("debugging_restricted",u.hasUserRestriction("no_debugging_features"));
            j.put("safe_boot_restricted",u.hasUserRestriction("no_safe_boot"));
            j.put("is_system_user",u.isSystemUser());j.put("user_profiles_count",u.getUserProfiles().size());
            if(Build.VERSION.SDK_INT>=24)j.put("is_user_unlocked",u.isUserUnlocked());
        } catch(Exception e) { try {j.put("user_error",error(e));}catch(Exception ignored){} }
        try {
            DevicePolicyManager d=(DevicePolicyManager)ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            List<ComponentName> admins=d.getActiveAdmins(); JSONArray a=new JSONArray();
            if(admins!=null) for(ComponentName n:admins) a.put(n.flattenToString());
            j.put("active_admin_components",a);
        } catch(Exception e) {try{j.put("policy_error",error(e));}catch(Exception ignored){} }
        return j;
    }
    private JSONObject properties() {
        JSONObject all=new JSONObject();
        String[] keys={"ro.build.version.release","ro.build.version.sdk","ro.build.version.security_patch",
            "ro.build.display.id","ro.build.type","ro.debuggable","ro.secure","ro.adb.secure",
            "persist.sys.usb.config","sys.usb.config","sys.usb.state","sys.usb.configfs","init.svc.adbd",
            "service.adb.tcp.port","persist.adb.tcp.port","service.adb.tls.port","persist.adb.tls_server.enable",
            "ro.boot.verifiedbootstate","ro.boot.flash.locked","ro.boot.vbmeta.device_state"};
        for(String k:keys) {
            if(cancelled) break;
            try { all.put(k,command(new String[]{"/system/bin/getprop",k},1200,4096)); } catch(Exception ignored) {}
        }
        return all;
    }
    public static boolean relevant(String value) {
        String s=value.toLowerCase(Locale.ROOT);
        return s.matches(".*(byd|dilink|settings|develop|debug|engineer|factory|diagnos|usb|adb|hidden|about|deviceinfo|mtk|launcher|service|test|开发|调试|工程|geliştir).*" );
    }
    public static boolean risky(String value) {
        return value.toLowerCase(Locale.ROOT).matches(".*(reset|wipe|format|erase|reboot|shutdown|calibrat|upgrade|updatefirmware|flashfirmware|masterclear|factorytest|重置|恢复出厂|擦除|校准|升级|刷机|关机|重启).*" );
    }
    public static int rank(String value) {
        String s=value.toLowerCase(Locale.ROOT);
        if(s.contains("develop")||s.contains("debug")||s.contains("adb")||s.contains("开发")||s.contains("调试")||s.contains("geliştir")) return 3;
        if(s.contains("usb")||s.contains("settings"))return 2;
        return relevant(s)?1:0;
    }
    private JSONObject packages() throws Exception {
        PackageManager pm=ctx.getPackageManager();
        List<PackageInfo> pkgs=pm.getInstalledPackages(PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|
            PackageManager.GET_RECEIVERS|PackageManager.GET_PROVIDERS|PackageManager.GET_PERMISSIONS|PackageManager.GET_META_DATA|PackageManager.MATCH_DISABLED_COMPONENTS);
        Collections.sort(pkgs,(a,b)->a.packageName.compareTo(b.packageName));
        JSONArray list=new JSONArray(); int count=0, candidates=0;
        for(PackageInfo p:pkgs) {
            if(cancelled) break;
            try {
                ApplicationInfo app=p.applicationInfo;
                if(app==null)continue;
                JSONObject out=obj("package",p.packageName,"label",String.valueOf(pm.getApplicationLabel(app)),
                    "version",p.versionName,"version_code",p.versionCode,"uid",app.uid,
                    "system",(app.flags&ApplicationInfo.FLAG_SYSTEM)!=0,"debuggable",(app.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0,
                    "enabled",app.enabled,"target_sdk",app.targetSdkVersion,"source",app.sourceDir,
                    "related",relevant(p.packageName),"shared_user_id",p.sharedUserId);
                JSONArray aa=new JSONArray();
                if(p.activities!=null) for(ActivityInfo a:p.activities) {
                    JSONObject item=component(a,app); String full=a.packageName+"/"+a.name+" "+item.optString("label");
                    item.put("rank",rank(full)); item.put("risky_name",risky(full));
                    item.put("permission",a.permission); item.put("permission_detail",permission(pm,a.permission));
                    item.put("accessible_candidate",item.optBoolean("effective_enabled")&&a.exported&&(a.permission==null||ctx.checkSelfPermission(a.permission)==PackageManager.PERMISSION_GRANTED));
                    item.put("alias_target",a.targetActivity); aa.put(item); count++;
                    if(rank(full)>0)candidates++;
                }
                out.put("activities",aa);
                JSONArray req=new JSONArray();
                if(p.requestedPermissions!=null) for(int i=0;i<p.requestedPermissions.length;i++) {
                    boolean granted=p.requestedPermissionsFlags!=null && i<p.requestedPermissionsFlags.length && (p.requestedPermissionsFlags[i]&PackageInfo.REQUESTED_PERMISSION_GRANTED)!=0;
                    req.put(obj("name",p.requestedPermissions[i],"granted",granted));
                }
                out.put("requested_permissions",req);
                if(relevant(p.packageName)||aa.toString().toLowerCase(Locale.ROOT).matches(".*(develop|debug|adb).*")) {
                    JSONArray services=new JSONArray(),receivers=new JSONArray(),providers=new JSONArray(),declared=new JSONArray();
                    if(p.services!=null)for(ServiceInfo s:p.services)services.put(component(s,app).put("permission",s.permission));
                    if(p.receivers!=null)for(ActivityInfo a:p.receivers)receivers.put(component(a,app).put("permission",a.permission));
                    if(p.providers!=null)for(ProviderInfo pr:p.providers)providers.put(component(pr,app).put("authority",pr.authority).put("read_permission",pr.readPermission).put("write_permission",pr.writePermission));
                    if(p.permissions!=null)for(PermissionInfo pi:p.permissions)declared.put(obj("name",pi.name,"protection_level",pi.protectionLevel));
                    out.put("services_metadata_only",services);out.put("receivers_metadata_only",receivers);out.put("providers_metadata_only",providers);out.put("declared_permissions",declared);
                }
                list.put(out);
            } catch(Exception e) {list.put(obj("package",p.packageName,"error",error(e)));}
        }
        return obj("visible_package_count",list.length(),"activity_count",count,"related_activity_count",candidates,
            "visibility_note","QUERY_ALL_PACKAGES istenmiştir; OEM ve kullanıcı profili kısıtlamaları yine geçerli olabilir.","items",list);
    }
    private JSONObject component(ComponentInfo a,ApplicationInfo app) {
        int state=PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        try {state=ctx.getPackageManager().getComponentEnabledSetting(new ComponentName(a.packageName,a.name));}catch(Exception ignored){}
        boolean effective=app.enabled && (state==PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (state==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT&&a.enabled));
        String label="";try{label=String.valueOf(a.loadLabel(ctx.getPackageManager()));}catch(Exception ignored){}
        JSONObject metadata=new JSONObject();if(a.metaData!=null)for(String key:a.metaData.keySet())try{metadata.put(key,redact(String.valueOf(a.metaData.get(key))));}catch(Exception ignored){}
        return obj("package",a.packageName,"name",a.name,"label",label,"exported",a.exported,"manifest_enabled",a.enabled,
            "component_state",state,"effective_enabled",effective,"process",a.processName,"metadata",metadata);
    }
    private JSONObject permission(PackageManager pm,String p) {
        if(p==null||p.isEmpty())return obj("required",false,"held_by_inspector",true);
        try {PermissionInfo pi=pm.getPermissionInfo(p,0);return obj("required",true,"protection_level",pi.protectionLevel,"owner",pi.packageName,"held_by_inspector",ctx.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED);}
        catch(Exception e){return obj("required",true,"error",error(e),"held_by_inspector",ctx.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED);}
    }
    private JSONArray routes() {
        JSONArray out=new JSONArray();
        for(String a:ACTIONS) {
            try {
                List<ResolveInfo> resolved=ctx.getPackageManager().queryIntentActivities(new Intent(a),PackageManager.MATCH_DEFAULT_ONLY);
                JSONArray options=new JSONArray();
                for(ResolveInfo r:resolved)if(r.activityInfo!=null)options.put(component(r.activityInfo,r.activityInfo.applicationInfo).put("permission",r.activityInfo.permission));
                out.put(obj("action",a,"resolvers",options));
            } catch(Exception e){out.put(obj("action",a,"error",error(e)));}
        }
        return out;
    }
    private JSONObject usb() {
        JSONObject out=new JSONObject();
        try {
            UsbManager manager=(UsbManager)ctx.getSystemService(Context.USB_SERVICE); JSONArray devs=new JSONArray();
            for(UsbDevice d:manager.getDeviceList().values())devs.put(obj("vendor_id",d.getVendorId(),"product_id",d.getProductId(),"device_class",d.getDeviceClass(),"interfaces",d.getInterfaceCount(),"permission_already_granted",manager.hasPermission(d)));
            out.put("host_devices_without_serials",devs);
            Intent i=ctx.registerReceiver(null,new IntentFilter("android.hardware.usb.action.USB_STATE"));
            JSONObject state=new JSONObject();
            if(i!=null)for(String key:new String[]{"connected","configured","adb","mtp","ptp","rndis","midi"})if(i.hasExtra(key))state.put(key,i.getBooleanExtra(key,false));
            out.put("sticky_usb_state",state);
            out.put("note","USB host listesinin boş olması USB hata ayıklamanın kapalı olduğunu kanıtlamaz.");
        }catch(Exception e){try{out.put("error",error(e));}catch(Exception ignored){}}
        return out;
    }
    private JSONArray loopback() {
        JSONArray out=new JSONArray();
        for(String host:new String[]{"127.0.0.1","::1"}) {
            try(Socket s=new Socket()) {
                s.connect(new InetSocketAddress(host,5555),650);
                out.put(obj("host",host,"port",5555,"tcp_accepts",true,"note","Yalnızca TCP bağlantısı; ADB protokolü ve yetkisi doğrulanmadı. Veri gönderilmedi."));
            }catch(Exception e){out.put(obj("host",host,"port",5555,"tcp_accepts",false,"error",error(e),"note","Diğer portlar ve ağ arayüzleri hakkında sonuç vermez."));}
        }
        return out;
    }
    private JSONObject network(){
        JSONObject out=new JSONObject();JSONArray ips=new JSONArray();
        try{for(InetAddress a:LocalNetwork.addresses())ips.put(obj("ip",a.getHostAddress(),"loopback",a.isLoopbackAddress(),"adb_tcp_example","adb connect "+LocalNetwork.endpoint(a.getHostAddress(),5555)));out.put("addresses",ips);
            android.net.ConnectivityManager cm=(android.net.ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo n=cm.getActiveNetworkInfo();out.put("connected",n!=null&&n.isConnected());out.put("transport",n==null?"unknown":n.getTypeName());
        }catch(Exception e){try{out.put("error",error(e));}catch(Exception ignored){}}return out;
    }
    private JSONArray commands() {
        JSONArray out=new JSONArray();
        String[][] cmds={{"/system/bin/id"},{"/system/bin/getenforce"},{"/system/bin/dumpsys","adb"},
            {"/system/bin/dumpsys","usb"},{"/system/bin/dumpsys","user"},{"/system/bin/dumpsys","device_policy"},
            {"/system/bin/service","list"},{"/system/bin/cmd","adb","is-wifi-supported"},{"/system/bin/cmd","adb","is-wifi-qr-supported"}};
        for(String[] cmd:cmds){if(cancelled)break;out.put(command(cmd,1800,50000));}
        if(!cancelled && Build.VERSION.SDK_INT>=24)out.put(command(new String[]{"/system/bin/logcat","-d","-v","brief","-t","120","--pid="+android.os.Process.myPid()},1800,24000));
        return out;
    }
    public JSONObject command(String[] args,int timeout,int cap) {
        java.lang.Process p=null;
        try {
            p=new ProcessBuilder(args).redirectErrorStream(true).start();
            final InputStream input=p.getInputStream(); final ByteArrayOutputStream output=new ByteArrayOutputStream();
            final boolean[] truncated={false};
            Thread reader=new Thread(()->{try{byte[] buf=new byte[2048];int n;while((n=input.read(buf))!=-1){synchronized(output){int take=Math.min(n,Math.max(0,cap-output.size()));if(take>0)output.write(buf,0,take);if(take<n)truncated[0]=true;}}}catch(IOException ignored){}},"bounded-output");
            reader.setDaemon(true);reader.start();
            long until=android.os.SystemClock.elapsedRealtime()+timeout; Integer exit=null;
            while(android.os.SystemClock.elapsedRealtime()<until&&!cancelled){try{exit=p.exitValue();break;}catch(IllegalThreadStateException e){Thread.sleep(25);}}
            boolean timed=exit==null;if(timed)p.destroy();
            reader.join(300);try{input.close();}catch(Exception ignored){}
            String value; synchronized(output){value=new String(output.toByteArray(),StandardCharsets.UTF_8);}
            return obj("argv",new JSONArray(Arrays.asList(args)),"exit",exit,"timed_out_or_cancelled",timed,"truncated",truncated[0],"output",redact(value));
        }catch(Exception e){return obj("argv",new JSONArray(Arrays.asList(args)),"error",error(e));}
        finally{if(p!=null)p.destroy();}
    }
    public static String redact(String in) {
        String s=in.replaceAll("(?im)^.*(?:serial(?:number|no|_number)?|imei|imsi|android_id|ssid|password|passwd|token|account(?:name)?|vin\\s*[:=]).*$","[hassas alan gizlendi]");
        s=s.replaceAll("(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b","[MAC gizlendi]");
        s=s.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","[e-posta gizlendi]");
        return s;
    }
    public static String summary(JSONObject r) {
        StringBuilder s=new StringBuilder("DiLink Inspector 2.0.0\n");
        s.append("Tarih: ").append(r.optString("created_at","—")).append("\nDurum: ").append(r.optString("scan_status","Henüz taranmadı")).append("\n\n");
        JSONObject d=r.optJSONObject("device"),p=r.optJSONObject("packages"),st=r.optJSONObject("settings"),restrict=r.optJSONObject("restrictions");
        if(d!=null)s.append(d.optString("manufacturer")).append(" ").append(d.optString("model")).append(" • Android ").append(d.optString("android")).append(" / API ").append(d.optInt("sdk")).append("\nDerleme: ").append(d.optString("build_display")).append("\n\n");
        if(st!=null){JSONObject g=st.optJSONObject("global");if(g!=null)for(String k:new String[]{"development_settings_enabled","adb_enabled","adb_wifi_enabled"}){JSONObject v=g.optJSONObject(k);s.append(k).append(": ").append(v==null?"bilinmiyor":v.optString("status")+" / "+v.optString("value")).append("\n");}}
        if(restrict!=null)s.append("Hata ayıklama kısıtlaması: ").append(restrict.has("debugging_restricted")?restrict.optBoolean("debugging_restricted"):"bilinmiyor").append("\n");
        if(p!=null)s.append("\nGörünen paket: ").append(p.optInt("visible_package_count")).append("\nEkran: ").append(p.optInt("activity_count")).append("\nİlgili ekran: ").append(p.optInt("related_activity_count")).append("\n");
        s.append("\nYORUM SINIRLARI\n• null / okunamadı = kapalı demek değildir.\n• adb_enabled=1 = bağlantı ve yetki doğrulandı demek değildir.\n• 5555 açık = yalnızca TCP dinleyicisi.\n• Permission denied, uygulamanın yetki sınırını gösterir.\n• Menü isteğinin kabul edilmesi ekranın açıldığı anlamına gelmez. Sonucu kullanıcı kaydeder.\n");
        s.append("\nSONRAKİ ADIM\nEkranlar sekmesindeki geliştirici/ADB/USB adaylarını tek tek inceleyin. Sonuçları kaydedin ve rapor ZIP dosyasını paylaşın. İlgili sistem APK'sı ayrıca dışarı aktarılabilir.\n");
        JSONArray ev=r.optJSONArray("events");if(ev!=null){s.append("\nDENEME GÜNLÜĞÜ\n");for(int i=0;i<ev.length();i++)s.append(ev.optJSONObject(i)).append("\n");}
        JSONObject lab=r.optJSONObject("research");if(lab!=null)s.append("\nARAŞTIRMA / KONSOL\n").append(lab.optString("transcript")).append("\n");
        return s.toString();
    }
    public File reportZip() throws Exception {
        JSONObject snap=snapshot();File out=exportFile("DiLink-report", ".zip");
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(out))){entry(z,"report.json",snap.toString(2));entry(z,"summary.txt",summary(snap));entry(z,"READ_ME.txt","Tarama salt okunurdur. Araştırma sekmesi ayar yazmayı, yerel ADB bağlantısını ve kullanıcı komutlarını destekler; gerçekleştirilen işlemler research/events alanındadır. Anahtarlar ve eşleştirme kodları dışarı aktarılmaz. IP/paket/derleme bilgileri ve komut çıktıları bulunabilir. Paylaşmadan önce inceleyin.\n");}
        return out;
    }
    public File reportText()throws Exception{File out=exportFile("DiLink-summary",".txt");write(out,summary(snapshot()));return out;}
    public File exportPackage(String pkg) throws Exception {
        PackageInfo pi=ctx.getPackageManager().getPackageInfo(pkg,0);ApplicationInfo a=pi.applicationInfo;
        File out=exportFile("APK-"+pkg.replaceAll("[^a-zA-Z0-9._-]","_"),".zip");
        JSONArray hashes=new JSONArray();
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(out))){
            List<String> paths=new ArrayList<>();paths.add(a.sourceDir);if(a.splitSourceDirs!=null)paths.addAll(Arrays.asList(a.splitSourceDirs));
            for(int i=0;i<paths.size();i++){
                File src=new File(paths.get(i));String n=i==0?"base.apk":"split-"+i+".apk";
                if(!src.isFile()||!src.canRead())throw new IOException("APK okunamıyor: "+src);
                z.putNextEntry(new ZipEntry(n));MessageDigest md=MessageDigest.getInstance("SHA-256");
                try(InputStream in=new FileInputStream(src)){byte[] buf=new byte[65536];int len;while((len=in.read(buf))!=-1){z.write(buf,0,len);md.update(buf,0,len);}}
                z.closeEntry();StringBuilder hex=new StringBuilder();for(byte b:md.digest())hex.append(String.format(Locale.US,"%02x",b&255));hashes.put(obj("file",n,"sha256",hex.toString()));
            }
            entry(z,"metadata.json",obj("package",pkg,"version",pi.versionName,"files",hashes,"note","Yalnızca yüklü APK dosyaları; özel uygulama verisi dahil değil.").toString(2));
        }catch(Exception e){out.delete();throw e;}
        event("apk_export",pkg);return out;
    }
    private File exportFile(String prefix,String suffix)throws IOException {
        File folder=new File(ctx.getFilesDir(),"exports");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Export directory unavailable");
        return new File(folder,prefix+"-"+new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(new Date())+suffix);
    }
    private static void entry(ZipOutputStream z,String name,String content)throws IOException {z.putNextEntry(new ZipEntry(name));z.write(content.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
    private static String readFile(File f,int cap)throws IOException {try(InputStream in=new FileInputStream(f);ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>cap)throw new IOException("Too large");b.write(buf,0,n);}return new String(b.toByteArray(),StandardCharsets.UTF_8);}}
}
