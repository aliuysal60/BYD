package com.solutera.dilinkinspector;

import android.content.Context;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Yeni "ADB Otomatik" sekmesinin mantığı. Mevcut sınıfları yalnızca çağırır, değiştirmez.
 * Tüm yöntemler, uygulamanın zaten bildirdiği yetkilerle sınırlıdır; her denemenin
 * sonucu kanıt olarak JSON'a yazılır.
 */
public final class AutoAdb {
    private static final String PKG = "com.solutera.dilinkinspector";
    private static final Pattern IMEIISH = Pattern.compile("\\d{10,}");

    private AutoAdb() {}

    /** 1) ADB/geliştirici ayarlarını her kanaldan açmayı dener; her denemenin çıktısını döndürür. */
    public static JSONObject enableAttempts(ResearchLab lab, boolean root) throws Exception {
        JSONArray steps = new JSONArray();
        String[] keys = {"development_settings_enabled", "adb_enabled", "adb_wifi_enabled"};
        // 1a. Settings API (global + secure)
        for (String ns : new String[]{"global", "secure"}) {
            for (String key : keys) {
                JSONObject attempt = ScanEngine.obj("via", "settings_api_" + ns, "key", key);
                try {
                    boolean ok = "global".equals(ns)
                            ? Settings.Global.putInt(lab.engine.ctx.getContentResolver(), key, 1)
                            : Settings.Secure.putInt(lab.engine.ctx.getContentResolver(), key, 1);
                    attempt.put("api_return", ok);
                } catch (Exception e) {
                    attempt.put("error", ScanEngine.error(e));
                }
                steps.put(attempt);
            }
        }
        // 1b. Uygulama shell
        for (String key : new String[]{"development_settings_enabled", "adb_enabled"}) {
            if (lab.engine.cancelled) break;
            steps.put(ScanEngine.obj("via", "app_shell").put("result",
                    lab.execute(0, "settings put global " + key + " 1", 7000)));
        }
        // 1c. Root (yalnızca istenirse)
        if (root && !lab.engine.cancelled) {
            for (String key : new String[]{"development_settings_enabled", "adb_enabled"}) {
                steps.put(ScanEngine.obj("via", "su").put("result",
                        lab.execute(2, "settings put global " + key + " 1", 10000)));
            }
            steps.put(ScanEngine.obj("via", "su_adbd_tcp").put("result",
                    lab.execute(2, "setprop service.adb.tcp.port 5555; stop adbd; start adbd; getprop service.adb.tcp.port", 10000)));
        }
        // 1d. Yetkili yerel ADB bağlantısı varsa oradan mühürle
        if (lab.adb.connected() && !lab.engine.cancelled) {
            steps.put(ScanEngine.obj("via", "adb_shell").put("result",
                    lab.execute(1, "settings put global development_settings_enabled 1; settings put global adb_enabled 1", 8000)));
        }
        return ScanEngine.obj("readback", readBack(lab), "attempts", steps);
    }

    /** Ayarların güncel okuma sonucu. */
    public static JSONObject readBack(ResearchLab lab) {
        Context ctx = lab.engine.ctx;
        JSONObject out = new JSONObject();
        for (String key : new String[]{"development_settings_enabled", "adb_enabled", "adb_wifi_enabled"}) {
            try {
                out.put("global." + key, Settings.Global.getString(ctx.getContentResolver(), key));
            } catch (Exception e) {
                try { out.put("global." + key, "hata: " + ScanEngine.error(e)); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    /** 2) Şifre anahtarını (TBox IMEI son 6) bulmayı dener: telefon, uygulama shell getprop, ADB getprop. */
    public static JSONObject detectKey(ResearchLab lab) throws Exception {
        LinkedHashSet<String> numbers = new LinkedHashSet<>();
        JSONArray sources = new JSONArray();
        try {
            TelephonyManager tm = (TelephonyManager) lab.engine.ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String imei = null;
                try { imei = tm.getImei(); } catch (Exception ignored) {}
                if (imei == null) try { imei = tm.getDeviceId(); } catch (Exception ignored) {}
                if (imei != null) { numbers.add(imei); sources.put(ScanEngine.obj("source", "telephony", "value", imei)); }
            }
        } catch (Exception e) {
            sources.put(ScanEngine.obj("source", "telephony", "error", ScanEngine.error(e)));
        }
        JSONObject shell = lab.execute(0, "getprop | grep -iE 'imei|meid|tbox' ; getprop ro.serialno", 7000);
        sources.put(ScanEngine.obj("source", "app_shell_getprop", "output", shell.optString("output")));
        collect(numbers, shell.optString("output"));
        if (lab.adb.connected() && !lab.engine.cancelled) {
            JSONObject adb = lab.execute(1, "getprop | grep -iE 'imei|meid|tbox'", 8000);
            sources.put(ScanEngine.obj("source", "adb_getprop", "output", adb.optString("output")));
            collect(numbers, adb.optString("output"));
        }
        JSONArray keys = new JSONArray();
        for (String n : numbers) {
            String key = DiLinkPassword.keyFromImei(n);
            if (key != null) keys.put(key);
        }
        return ScanEngine.obj("numbers", new JSONArray(numbers.toArray()), "keys", keys, "sources", sources);
    }

    private static void collect(LinkedHashSet<String> into, String text) {
        if (text == null) return;
        Matcher m = IMEIISH.matcher(text);
        while (m.find()) into.add(m.group());
    }

    /** 3) Hızlı yerel keşif: localhost/5555-5558 + mDNS. */
    public static JSONObject quickDiscover(ResearchLab lab) throws Exception {
        LocalDiscovery discovery = new LocalDiscovery(lab.engine.ctx, new LocalDiscovery.Control() {
            public boolean cancelled() { return lab.engine.cancelled; }
            public void status(String s) { lab.engine.updateStatus(s); }
        });
        JSONObject properties = lab.engine.state().optJSONObject("properties");
        JSONObject result = discovery.discover(false, properties);
        result.put("candidates", discovery.candidates());
        return result;
    }

    /** 4) Bağlantı kur: önce 127.0.0.1:5555, sonra keşif adayları. Her deneme kaydedilir. */
    public static JSONObject connectBest(ResearchLab lab, JSONObject discovery) throws Exception {
        JSONArray tried = new JSONArray();
        LinkedHashSet<String> endpoints = new LinkedHashSet<>();
        endpoints.add("127.0.0.1:5555");
        JSONArray candidates = discovery == null ? null : discovery.optJSONArray("candidates");
        if (candidates != null) for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c == null) continue;
            String kind = c.optString("kind");
            if (kind.startsWith("adb_") || "mdns_connect".equals(kind)) {
                endpoints.add(LocalNetwork.endpoint(c.optString("host"), c.optInt("port")));
            }
        }
        for (String endpoint : endpoints) {
            if (lab.engine.cancelled) break;
            int colon = endpoint.lastIndexOf(':');
            String host = endpoint.substring(0, colon);
            int port = Integer.parseInt(endpoint.substring(colon + 1));
            try {
                boolean ok = lab.adb.connect(host, port);
                tried.put(ScanEngine.obj("endpoint", endpoint, "connected", ok));
                return ScanEngine.obj("connected", ok, "endpoint", endpoint, "tried", tried);
            } catch (Exception e) {
                tried.put(ScanEngine.obj("endpoint", endpoint, "error", ScanEngine.error(e)));
            }
        }
        return ScanEngine.obj("connected", false, "tried", tried);
    }

    /** 5) Bağlantı kurulduktan sonra kanıt + mühürleme + IMEI araması. */
    public static JSONObject afterConnect(ResearchLab lab) throws Exception {
        if (!lab.adb.connected()) return ScanEngine.obj("connected", false);
        JSONObject id = lab.execute(1, "id", 8000);
        JSONObject seal = lab.execute(1, "settings put global development_settings_enabled 1; settings put global adb_enabled 1; "
                + "settings get global adb_enabled; getprop service.adb.tcp.port", 8000);
        JSONObject imei = lab.execute(1, "getprop | grep -iE 'imei|meid|tbox'", 8000);
        return ScanEngine.obj("connected", true, "id", id, "seal", seal,
                "imei_search", imei.optString("output"), "readback", readBack(lab));
    }
}
