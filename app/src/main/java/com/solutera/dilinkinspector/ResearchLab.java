package com.solutera.dilinkinspector;

import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.*;
import android.provider.Settings;
import org.json.*;
import java.util.*;

/** User-started experiments with persistent before/after evidence and explicit execution identity. */
public final class ResearchLab {
    private static ResearchLab instance;
    public static synchronized ResearchLab get(Context ctx){if(instance==null)instance=new ResearchLab(ScanEngine.get(ctx));return instance;}
    public final ScanEngine engine;
    public final AdbBridge adb;
    private JSONObject data;
    private JSONArray journal;
    private String transcript;
    public static final String[] MODES={"Uygulama shell (APK yetkisi)","Yerel ADB shell (bağlantı gerekir)","Root / su (cihazda varsa)"};
    private static final String PKG="com.solutera.dilinkinspector";
    /** Grant every permission the app declares, then open every runtime app-op for it. Runs only with real privilege (ADB/root); from the app shell each line is denied and simply logged. */
    private static final String GRANT_ALL=
        "for p in $(pm dump "+PKG+" | sed -n 's/.*\\(android.permission.[A-Z0-9_]*\\).*/\\1/p' | sort -u); do echo \"grant $p\"; pm grant "+PKG+" $p 2>&1; done; "
        +"for op in RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WRITE_SETTINGS SYSTEM_ALERT_WINDOW; do echo \"appops $op\"; appops set "+PKG+" $op allow 2>&1; done";
    public static final String[] PRESET_NAMES={"Kimliği göster","Geliştirici / USB ADB durumunu oku","Geliştirici + USB ADB aç (global)","Geliştirici + USB ADB aç (secure ad alanı)","Kablosuz ADB açmayı dene","TCP/IP ADB'yi aç (adbd'yi 5555'te başlat)","Destekleyici ADB ayarları (uyanık kal, doğrulama kapat)","USB yapılandırmasına adb ekle (root)","USB/adbd göstergeleri","USB komut desteğini göster","ADB için uygulama izinlerini ver","Bu uygulamaya tüm izinleri ver","Bu uygulamayı device owner yap","Geliştirici ekranını aç","Yönetim kısıtlamalarını oku"};
    public static final String[] PRESETS={"id",
        "settings get global development_settings_enabled; settings get global adb_enabled; settings get global adb_wifi_enabled; settings get secure development_settings_enabled; settings get secure adb_enabled",
        "settings put global development_settings_enabled 1 && settings put global adb_enabled 1",
        "settings put secure development_settings_enabled 1; settings put secure adb_enabled 1",
        "settings put global adb_wifi_enabled 1",
        "setprop service.adb.tcp.port 5555; settings put global adb_enabled 1; stop adbd; start adbd; getprop service.adb.tcp.port",
        "settings put global stay_on_while_plugged_in 7; settings put global verifier_verify_adb_installs 0; settings put global package_verifier_enable 0",
        "setprop persist.sys.usb.config adb; setprop sys.usb.config adb; getprop sys.usb.state",
        "getprop init.svc.adbd; getprop sys.usb.config; getprop sys.usb.state; getprop service.adb.tcp.port; getprop service.adb.tls.port",
        "svc usb; cmd usb help; cmd adb is-wifi-supported; cmd adb is-wifi-qr-supported",
        "pm grant "+PKG+" android.permission.WRITE_SECURE_SETTINGS && pm grant "+PKG+" android.permission.DUMP && pm grant "+PKG+" android.permission.READ_LOGS",
        GRANT_ALL,
        "dpm set-device-owner "+PKG+"/.AdminReceiver",
        "am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
        "dumpsys user; dumpsys device_policy"};
    private ResearchLab(ScanEngine engine){this.engine=engine;adb=new AdbBridge(engine.ctx);data=engine.snapshot().optJSONObject("research");if(data==null)data=new JSONObject();journal=data.optJSONArray("journal");if(journal==null)journal=new JSONArray();transcript=data.optString("transcript","");}
    public synchronized String transcript(){return transcript;}
    public synchronized JSONObject snapshot(){try{return new JSONObject(data.toString());}catch(Exception e){return new JSONObject();}}
    public synchronized void append(String title,Object result){
        String rendered=ScanEngine.redact(String.valueOf(result));
        transcript+="\n["+ScanEngine.time()+"] "+title+"\n"+rendered+"\n";
        if(transcript.length()>160000)transcript="[Eski ekran çıktısı kısaltıldı; önceki ZIP raporlarını inceleyin.]\n"+transcript.substring(transcript.length()-140000);
        journal.put(ScanEngine.obj("at",ScanEngine.time(),"operation",title,"result",result));
        if(journal.length()>80){JSONArray keep=new JSONArray();for(int i=journal.length()-60;i<journal.length();i++)keep.put(journal.opt(i));journal=keep;}
        try{data.put("journal",journal);data.put("transcript",transcript);}catch(Exception ignored){}
        engine.record("research",snapshot());
    }
    private synchronized void put(String key,Object value){try{data.put(key,value);}catch(Exception ignored){}engine.record("research",snapshot());}
    public void status(String text){engine.updateStatus(text);}
    public void cancel(){engine.cancelled=true;adb.cancel();status("İptal isteniyor…");}
    public boolean run(String title,ScanEngine.Operation job){return engine.operate(title,()->{try{job.run();}catch(Exception e){append(title,ScanEngine.obj("error",ScanEngine.error(e)));throw e;}});}
    public JSONObject capture(String label){JSONObject state=engine.state();put("latest_state",state);append(label,state);return state;}
    public void after(JSONObject before,String label){JSONObject after=capture(label);append("Önce / sonra farkı",diff(before,after));}
    static JSONObject diff(JSONObject before,JSONObject after){
        JSONObject out=new JSONObject();for(String key:new String[]{"settings","restrictions","usb","properties"}){
            JSONObject a=before==null?null:before.optJSONObject(key),b=after.optJSONObject(key);JSONObject changed=new JSONObject();
            if(b!=null){Iterator<String> keys=b.keys();while(keys.hasNext()){String k=keys.next();Object old=a==null?null:a.opt(k),now=b.opt(k);if(!String.valueOf(old).equals(String.valueOf(now)))try{changed.put(k,ScanEngine.obj("before",old,"after",now));}catch(Exception ignored){}}}
            try{out.put(key,changed);}catch(Exception ignored){}
        }return out;
    }
    public void enable(boolean wireless,boolean useRoot)throws Exception{
        JSONObject before=capture("Açma denemesi öncesi");put("restore_baseline",before);
        String[] keys=wireless?new String[]{"development_settings_enabled","adb_enabled","adb_wifi_enabled"}:new String[]{"development_settings_enabled","adb_enabled"};
        JSONArray attempted=new JSONArray();put("restore_keys",new JSONArray(Arrays.asList(keys)));
        for(String key:keys){if(engine.cancelled)break;
            try{boolean ok=Settings.Global.putInt(engine.ctx.getContentResolver(),key,1);attempted.put(ScanEngine.obj("key",key,"api_return",ok));}
            catch(Exception e){attempted.put(ScanEngine.obj("key",key,"error",ScanEngine.error(e)));}
        }
        append("Settings API yazma denemeleri",attempted);
        // Some OEMs read these from the `secure` namespace instead of `global`; attempt both via API too.
        JSONArray secure=new JSONArray();
        for(String key:new String[]{"development_settings_enabled","adb_enabled"}){if(engine.cancelled)break;
            try{boolean ok=Settings.Secure.putInt(engine.ctx.getContentResolver(),key,1);secure.put(ScanEngine.obj("key",key,"api_return",ok));}
            catch(Exception e){secure.put(ScanEngine.obj("key",key,"error",ScanEngine.error(e)));}
        }
        append("Settings API (secure ad alanı) yazma denemeleri",secure);
        for(String key:keys){if(engine.cancelled)break;append("Uygulama shell yazma",execute(0,"settings put global "+key+" 1",7000));}
        if(adb.connected()&&!engine.cancelled)for(String key:keys){if(engine.cancelled)break;append("ADB üzerinden yazma",execute(1,"settings put global "+key+" 1",7000));}
        if(useRoot&&!engine.cancelled)for(String key:keys){if(engine.cancelled)break;append("Root üzerinden yazma",execute(2,"settings put global "+key+" 1",10000));}
        after(before,"Açma denemesi sonrası");append("Yorum",assessment());
    }
    /** Best-effort enable of developer options before a menu launch, so the Settings screen does not reject with "enable developer options first". Tries the Settings API, the app shell, and an authorized ADB shell if connected. Returns true if development_settings_enabled reads back as 1. */
    public boolean prepareDeveloper()throws Exception{
        JSONArray attempts=new JSONArray();
        try{boolean ok=Settings.Global.putInt(engine.ctx.getContentResolver(),"development_settings_enabled",1);attempts.put(ScanEngine.obj("via","settings_api","api_return",ok));}
        catch(Exception e){attempts.put(ScanEngine.obj("via","settings_api","error",ScanEngine.error(e)));}
        if(!developerEnabled()&&!engine.cancelled)attempts.put(ScanEngine.obj("via","app_shell","result",execute(0,"settings put global development_settings_enabled 1",7000)));
        if(!developerEnabled()&&adb.connected()&&!engine.cancelled)attempts.put(ScanEngine.obj("via","adb_shell","result",execute(1,"settings put global development_settings_enabled 1",7000)));
        boolean enabled=developerEnabled();
        append("Geliştirici seçeneklerini açma denemesi",ScanEngine.obj("enabled",enabled,"attempts",attempts));
        return enabled;
    }
    private boolean developerEnabled(){try{return Settings.Global.getInt(engine.ctx.getContentResolver(),"development_settings_enabled",0)==1;}catch(Exception e){return false;}}
    private boolean hasSecureSettings(){return engine.ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")==PackageManager.PERMISSION_GRANTED;}
    /** Grant WRITE_SECURE_SETTINGS to this app over the authorized ADB shell, then prove it worked: re-check the runtime grant and perform a real Settings.Global write that only succeeds with the permission. Writes the full evidence (before grant, grant output, after grant, write return) to the report. */
    public void grantAndProve(int mode)throws Exception{
        JSONObject evidence=new JSONObject();
        evidence.put("adb_connected",adb.connected());
        evidence.put("mode",MODES[mode]);
        evidence.put("granted_before",hasSecureSettings());
        if(mode==1&&!adb.connected()){append("İzni ver ve kanıtla",evidence.put("error","Yerel ADB bağlantısı yok. Önce bilgisayardan yetkilendirilmiş ADB bağlantısı kurun."));return;}
        if(mode==0)evidence.put("not_via_app_shell","Uygulama shell'i pm grant çalıştıramaz; gerçek yetki için ADB veya root modu seçin.");
        evidence.put("pm_grant",execute(mode,"pm grant "+PKG+" android.permission.WRITE_SECURE_SETTINGS",8000));
        boolean granted=hasSecureSettings();evidence.put("granted_after",granted);
        if(granted){
            // Proof: a write that is only permitted with WRITE_SECURE_SETTINGS.
            boolean before=developerEnabled();
            boolean apiReturn;String apiError=null;
            try{apiReturn=Settings.Global.putInt(engine.ctx.getContentResolver(),"development_settings_enabled",1);}
            catch(Exception e){apiReturn=false;apiError=ScanEngine.error(e);}
            evidence.put("proof",ScanEngine.obj("wrote_setting","development_settings_enabled=1","was",before,"api_return",apiReturn,"api_error",apiError,"reads_back",developerEnabled()));
            evidence.put("verdict",apiReturn?"KANITLANDI: WRITE_SECURE_SETTINGS verildi ve ayar yazma başarılı.":"İzin verildi görünüyor fakat yazma doğrulanamadı.");
        }else evidence.put("verdict","İzin VERİLMEDİ. "+(mode==1?"ADB shell'in GRANT_RUNTIME_PERMISSIONS yetkisi yok ya da bağlantı yetkisiz.":"Bu modda pm grant uygulanamaz."));
        append("İzni ver ve kanıtla",evidence);
    }
    /** Single diagnostic pass that tries every in-app way to unlock developer options and reports exactly why each one does or does not work: device/build identity, hard blockers (management restriction, provisioning), then each enable technique (global + secure API, app shell, ADB if connected) with full error capture, finishing with a verdict and the recommended next step. */
    public void unlockDiagnostic()throws Exception{
        JSONObject r=new JSONObject();
        JSONObject state=engine.state();
        JSONObject dev=state.optJSONObject("properties");
        if(dev!=null)r.put("device",ScanEngine.obj("manufacturer",dev.opt("manufacturer"),"brand",dev.opt("brand"),"model",dev.opt("model"),
            "android",dev.opt("android"),"sdk",dev.opt("sdk"),"build_type",dev.opt("build_type"),"build_fingerprint",dev.opt("build_fingerprint"),"app_uid",dev.opt("app_uid")));
        // Hard blockers: if these are set, in-app enabling cannot succeed and the user needs an external path.
        JSONObject restrictions=state.optJSONObject("restrictions");
        boolean debuggingRestricted=restrictions!=null&&restrictions.optBoolean("debugging_restricted");
        JSONObject settings=state.optJSONObject("settings");
        JSONObject g=settings==null?null:settings.optJSONObject("global");
        r.put("blockers",ScanEngine.obj(
            "debugging_restricted",debuggingRestricted,
            "device_provisioned",g==null?null:String.valueOf(g.opt("device_provisioned")),
            "write_secure_settings_granted",hasSecureSettings(),
            "adb_connected",adb.connected()));
        append("Geliştirici kilidi tanılaması • cihaz ve engeller",r);
        if(debuggingRestricted){
            append("Tanılama kararı","HARD BLOCK: no_debugging_features kısıtlaması etkin. Bir yönetim politikası (device owner / profil) hata ayıklamayı yasaklıyor. Uygulama içinden açılamaz; kısıtlamayı koyan yönetici kaldırmadan geliştirici seçenekleri/ADB etkinleşmez.");
            return;
        }
        // Technique 1: Settings API, both namespaces.
        if(!engine.cancelled){
            boolean before=developerEnabled();JSONArray api=new JSONArray();
            for(String ns:new String[]{"global","secure"}){
                try{boolean ok="global".equals(ns)?Settings.Global.putInt(engine.ctx.getContentResolver(),"development_settings_enabled",1):Settings.Secure.putInt(engine.ctx.getContentResolver(),"development_settings_enabled",1);
                    api.put(ScanEngine.obj("namespace",ns,"api_return",ok,"reads_back",developerEnabled()));}
                catch(Exception e){api.put(ScanEngine.obj("namespace",ns,"error",ScanEngine.error(e)));}
            }
            append("Yöntem 1 • Settings API yazma",ScanEngine.obj("was",before,"attempts",api,"now",developerEnabled()));
        }
        // Technique 2: app shell (expected to be denied; proves the permission boundary in this build).
        if(!engine.cancelled)append("Yöntem 2 • Uygulama shell yazma",execute(0,"settings put global development_settings_enabled 1; settings put secure development_settings_enabled 1; settings get global development_settings_enabled",7000));
        // Technique 3: authorized ADB shell, if a connection exists.
        if(adb.connected()&&!engine.cancelled)append("Yöntem 3 • ADB shell yazma",execute(1,"settings put global development_settings_enabled 1; settings put global adb_enabled 1; settings get global development_settings_enabled",8000));
        else if(!engine.cancelled)append("Yöntem 3 • ADB shell yazma","Atlandı: yetkili yerel ADB bağlantısı yok.");
        // Technique 4: enable any disabled, exported DevelopmentSettings component via PackageManager.
        if(!engine.cancelled)enableComponents(0);
        boolean enabled=developerEnabled();
        String verdict;
        if(enabled)verdict="BAŞARILI: development_settings_enabled artık 1. Ekranlar > Geliştirici menüsü ile açmayı deneyin.";
        else if(hasSecureSettings())verdict="İzin var ama değer 1 olmadı: OEM bu ayarı başka bir anahtardan okuyor olabilir. APK statik incelemesi önerilir.";
        else verdict="İN-APP AÇILAMADI: WRITE_SECURE_SETTINGS yok ve yetkili ADB yok. Tek çıkış yolları: (1) bilgisayardan USB-ADB ile pm grant, (2) OEM mühendislik/gizli kodu, (3) aracın Settings APK'sını dışa aktarıp statik inceleme. Rapordaki cihaz modeli ve Android sürümüyle bu yollar araştırılmalı.";
        append("Tanılama kararı",verdict);
    }
    /** Promote this app to device owner via dpm over the authorized shell, then set the component as an active admin. Device owner can be set only before any account is added; failure (already provisioned / account present) is captured, not thrown. */
    public void grantDeviceOwner(int mode)throws Exception{
        String admin=PKG+"/.AdminReceiver";
        JSONObject evidence=new JSONObject();evidence.put("mode",MODES[mode]);evidence.put("admin",admin);
        if(mode==1&&!adb.connected()){append("Device owner denemesi",evidence.put("error","Yerel ADB bağlantısı yok."));return;}
        if(mode==0)evidence.put("not_via_app_shell","dpm uygulama shell'inden çalışmaz; ADB veya root modu gerekir.");
        evidence.put("set_device_owner",execute(mode,"dpm set-device-owner "+admin,10000));
        evidence.put("set_active_admin",execute(mode,"dpm set-active-admin "+admin,10000));
        DevicePolicyManager dpm=(DevicePolicyManager)engine.ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        boolean owner=dpm!=null&&dpm.isDeviceOwnerApp(PKG);evidence.put("is_device_owner",owner);
        evidence.put("verdict",owner?"KANITLANDI: uygulama device owner oldu.":"Device owner olunamadı. Genellikle cihazda zaten bir hesap/kurulum var; bu durumda yalnızca fabrika ayarları sonrası ilk kurulumda mümkündür.");
        append("Device owner denemesi",evidence);
    }
    public JSONObject execute(int mode,String command,int timeout)throws Exception {
        if(command.trim().isEmpty())throw new IllegalArgumentException("Komut boş.");
        if(command.length()>16000)throw new IllegalArgumentException("Komut fazla uzun.");
        JSONObject result;
        if(mode==1)result=adb.shell(command,timeout);
        else{result=engine.command(mode==2?new String[]{"su","-c",command}:new String[]{"/system/bin/sh","-c",command},timeout,128000);result.put("mode",mode==2?"root_requested_not_assumed":"app_shell");}
        return result;
    }
    public void console(int mode,String command)throws Exception{
        JSONObject before=capture("Komut öncesi");append(MODES[mode]+" • "+command,execute(mode,command,25000));after(before,"Komut sonrası");
    }
    public void restore(int mode)throws Exception{
        JSONObject saved=snapshot(),base=saved.optJSONObject("restore_baseline");JSONArray keys=saved.optJSONArray("restore_keys");
        if(base==null||keys==null)throw new IllegalStateException("Geri alınabilir bir açma denemesi yok.");
        JSONObject settings=base.optJSONObject("settings"),values=settings==null?null:settings.optJSONObject("global");
        JSONObject before=capture("Geri alma öncesi");
        for(int i=keys.length()-1;i>=0&&!engine.cancelled;i--){String key=keys.getString(i);JSONObject value=values==null?null:values.optJSONObject(key);
            if(value==null||!"read".equals(value.optString("status"))||value.isNull("value")){append("Geri alma atlandı",key+": önceki değer güvenilir biçimde okunamadı");continue;}
            String old=value.getString("value");if(!old.matches("[01]")){append("Geri alma atlandı",key+": beklenmeyen eski değer");continue;}
            if(mode==0){try{append("API geri alma",ScanEngine.obj("key",key,"value",old,"return",Settings.Global.putString(engine.ctx.getContentResolver(),key,old)));}catch(Exception e){append("API geri alma",ScanEngine.error(e));}}
            else append("Shell geri alma",execute(mode,"settings put global "+key+" "+old,7000));
        }after(before,"Geri alma sonrası");
    }
    public void discover(boolean deep)throws Exception {
        JSONObject state=engine.state();
        LocalDiscovery discovery=new LocalDiscovery(engine.ctx,new LocalDiscovery.Control(){public boolean cancelled(){return engine.cancelled;}public void status(String s){ResearchLab.this.status(s);}});
        JSONObject result=discovery.discover(deep,state.optJSONObject("properties"));put("discovery",result);append("Yerel ADB keşfi",result);
    }
    public void connect(String host,int port)throws Exception{append("ADB bağlantısı",ScanEngine.obj("endpoint",LocalNetwork.endpoint(host,port),"connected",adb.connect(host,port)));append("ADB kimlik doğrulaması",adb.shell("id",6000));}
    public void pair(String host,int port,String code)throws Exception{adb.pair(host,port,code);append("Kablosuz ADB eşleştirme","Başarılı. Bağlantı portunu seçip Bağlan düğmesini kullanın; eşleştirme portu bağlantı portu değildir.");}
    public void automatic(boolean root)throws Exception {
        enable(false,root);if(engine.cancelled)return;enableComponents(0);if(root&&!engine.cancelled)enableComponents(2);if(engine.cancelled)return;discover(false);if(engine.cancelled)return;
        JSONObject discovery=snapshot().optJSONObject("discovery");JSONArray candidates=discovery==null?null:discovery.optJSONArray("candidates");int tried=0;
        if(!adb.connected()&&candidates!=null)for(int i=0;i<candidates.length()&&tried<3&&!engine.cancelled;i++){
            JSONObject c=candidates.getJSONObject(i);String kind=c.optString("kind");if(!kind.startsWith("adb_")&&!"mdns_connect".equals(kind))continue;
            tried++;try{connect(c.getString("host"),c.getInt("port"));break;}catch(Exception e){append("Otomatik ADB bağlantı sonucu",ScanEngine.obj("endpoint",c,"error",ScanEngine.error(e)));}
        }
        if(adb.connected()&&!engine.cancelled){
            append("ADB ile USB/geliştirici açma",execute(1,PRESETS[2],10000));enableComponents(1);capture("ADB denemesi sonrası");
        }
        append("Araştırma sonucu",assessment());
    }
    public void enableComponents(int mode)throws Exception{
        PackageManager pm=engine.ctx.getPackageManager();JSONObject originals=snapshot().optJSONObject("component_baseline");if(originals==null)originals=new JSONObject();int count=0;
        for(PackageInfo pkg:pm.getInstalledPackages(PackageManager.GET_ACTIVITIES|PackageManager.MATCH_DISABLED_COMPONENTS)){
            if(pkg.activities==null||pkg.applicationInfo==null||!pkg.applicationInfo.enabled)continue;
            for(ActivityInfo a:pkg.activities){
                String lower=a.name.toLowerCase(Locale.ROOT);
                if(engine.cancelled)return;
                if(!a.exported||a.enabled||!lower.contains("developmentsettings")||lower.contains("disabled")||ScanEngine.risky(a.name))continue;
                if(++count>16){append("Bileşen denemesi","16 aday sınırı");return;}
                ComponentName component=new ComponentName(a.packageName,a.name);String flat=component.flattenToString();
                if(!originals.has(flat))originals.put(flat,pm.getComponentEnabledSetting(component));put("component_baseline",originals);
                if(mode==0){try{pm.setComponentEnabledSetting(component,PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP);append("Geliştirici ekranını etkinleştir",flat+" • API isteği kabul edildi");}catch(Exception e){append("Geliştirici ekranını etkinleştir",flat+" • "+ScanEngine.error(e));}}
                else append("Geliştirici ekranını etkinleştir",execute(mode,"pm enable --user current "+LocalNetwork.quote(flat),7000));
            }
        }
        if(count==0)append("Geliştirici ekranı etkinliği","Devre dışı, exported DevelopmentSettings bileşeni bulunamadı.");
    }
    public void restoreComponents(int mode)throws Exception{
        JSONObject originals=snapshot().optJSONObject("component_baseline");if(originals==null)throw new IllegalStateException("Kayıtlı bileşen değişikliği yok.");
        Iterator<String> keys=originals.keys();while(keys.hasNext()&&!engine.cancelled){String flat=keys.next();ComponentName c=ComponentName.unflattenFromString(flat);int state=originals.getInt(flat);
            if(c==null)continue;
            if(mode==0){try{engine.ctx.getPackageManager().setComponentEnabledSetting(c,state,PackageManager.DONT_KILL_APP);append("Bileşeni geri al",flat);}catch(Exception e){append("Bileşeni geri al",ScanEngine.error(e));}}
            else{String action=state==0?"default-state":state==1?"enable":state==3?"disable-user":state==4?"disable-until-used":"disable";append("Bileşeni geri al",execute(mode,"pm "+action+" --user current "+LocalNetwork.quote(flat),7000));}
        }
    }
    public String assessment(){
        JSONObject state=snapshot().optJSONObject("latest_state");JSONObject restrictions=state==null?null:state.optJSONObject("restrictions");
        StringBuilder s=new StringBuilder(adb.state()+"\n");
        if(restrictions!=null&&restrictions.optBoolean("debugging_restricted"))s.append("no_debugging_features etkin: yönetim politikası engeli var.\n");
        s.append("API/shell izin reddi APK'nın yetki sınırıdır. Ayarın 1 olması USB bağlantısını kanıtlamaz; 0 değeri de sürüme göre filtrelenebilir.\n");
        s.append("Menü yollarını deneyin; yoksa Ekranlar > Paket seçenekleri > APK ipuçlarını incele / dışarı aktar.\n");
        s.append("Kablosuz eşleştirme ekranı erişilebiliyorsa kod ve eşleştirme portuyla eşleştirin.\n");
        s.append("USB doğrulaması: veri bağlantısı ve araçtaki onaydan sonra bilgisayarda adb devices -l, ardından adb shell id.\n");
        return s.toString();
    }
    public static final class Route {public final Intent intent;public final String label;Route(Intent i,String l){intent=i;label=l;}}
    public List<Route> routes(){
        ArrayList<Route> out=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        for(String action:new String[]{"android.settings.APPLICATION_DEVELOPMENT_SETTINGS","com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS","android.settings.DEVICE_INFO_SETTINGS","android.settings.USB_SETTINGS","android.settings.WIRELESS_DEBUGGING_SETTINGS"})addRoute(out,seen,new Intent(action),action);
        for(String name:new String[]{".DevelopmentSettings",".DevelopmentSettingsDashboardActivity",".Settings$DevelopmentSettingsActivity",".Settings$DevelopmentSettingsDashboardActivity",".Settings$MyDeviceInfoActivity",".deviceinfo.aboutphone.MyDeviceInfoActivity",".DeviceInfoSettings"}){
            ComponentName c=new ComponentName("com.android.settings","com.android.settings"+name);addRoute(out,seen,new Intent().setComponent(c),c.flattenToShortString());
        }
        JSONObject packages=engine.snapshot().optJSONObject("packages");JSONArray items=packages==null?null:packages.optJSONArray("items");
        if(items!=null)for(int i=0;i<items.length();i++){JSONArray aa=items.optJSONObject(i).optJSONArray("activities");if(aa==null)continue;
            for(int j=0;j<aa.length();j++){JSONObject a=aa.optJSONObject(j);if(a==null)continue;String name=a.optString("name"),label=a.optString("label"),all=name+" "+label;
                if(!a.optBoolean("accessible_candidate")||ScanEngine.risky(all)||!all.toLowerCase(Locale.ROOT).matches(".*(develop|debug|adb|usb|deviceinfo|about|开发|调试).*"))continue;
                ComponentName c=new ComponentName(a.optString("package"),name);addRoute(out,seen,new Intent().setComponent(c),c.flattenToShortString());
            }
        }return out;
    }
    private static void addRoute(List<Route> out,Set<String> seen,Intent i,String label){if(seen.add(i.toUri(0)))out.add(new Route(i,label));}
}
