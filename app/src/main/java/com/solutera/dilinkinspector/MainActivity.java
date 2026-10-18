package com.solutera.dilinkinspector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * ParkCam 4.9 — menü tabanlı sade arayüz.
 *
 * Sol üstteki ☰ ikonu sol menüyü açar:
 *   • Ana Sayfa      → durum panosu (araç, kayıt, kurulum günlüğü)
 *   • Canlı Kamera   → "CANLI YAYINI AÇ" ile akış
 *   • ⚙ Ayarlar      → "Aracı Hazırla" + "Kaldırmaya Hazırla" düğmeleri
 *
 * Tüm ağır iş serviste (ParkCamService): kurulum, kamera, otomatik kayıt.
 * Activity yalnız servisin durumunu gösterir ve istek gönderir.
 */
public final class MainActivity extends Activity {
    private final int BG=Color.rgb(13,23,33),CARD=Color.rgb(25,39,51),TEXT=Color.rgb(231,240,245),
        MUTED=Color.rgb(158,180,193),ACCENT=Color.rgb(90,224,186),RED=Color.rgb(240,110,110);

    private LinearLayout menuPanel, pageHome, pageCamera;
    private TextView stateLine, vehLine, recLine, liveStat, setupLog;
    private ImageView liveView;
    private Button liveBtn;
    private Spinner camSpin;
    private Handler ui;
    private volatile boolean liveOn;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui=new Handler(Looper.getMainLooper());

        // Servisi başlat — her şeyi o yönetir
        try {
            Intent svc=new Intent(this,ParkCamService.class);
            if (android.os.Build.VERSION.SDK_INT>=26) startForegroundService(svc); else startService(svc);
        } catch (Exception ignored) {}

        buildUi();
        pollStatus(); // durum + canlı kare döngüsü
    }

    @Override protected void onDestroy() {
        ParkCamService.WANT_LIVE=false; // sayfa kapandı, akış talebini bırak
        super.onDestroy();
    }

    /* ================= UI ================= */

    private void buildUi() {
        FrameLayout rootFrame=new FrameLayout(this);
        rootFrame.setBackgroundColor(BG);

        // ---- içerik (dikey kaydırılabilir) ----
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14),dp(10),dp(14),dp(8));
        scroll.addView(content);
        rootFrame.addView(scroll);

        // üst bar: ☰ + başlık
        LinearLayout bar=new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView burger=text("☰",26,ACCENT); burger.setPadding(dp(6),dp(4),dp(14),dp(4));
        burger.setOnClickListener(v->toggleMenu(true));
        bar.addView(burger);
        TextView title=text("ParkCam",20,TEXT); title.setTypeface(null,Typeface.BOLD);
        bar.addView(title);
        content.addView(bar);

        // ============ ANA SAYFA ============
        pageHome=new LinearLayout(this); pageHome.setOrientation(LinearLayout.VERTICAL);
        content.addView(pageHome);

        LinearLayout statusCard=card(pageHome);
        statusCard.addView(text("Durum",18,ACCENT));
        stateLine=text("durum: kontrol ediliyor…",14,MUTED); statusCard.addView(stateLine);
        vehLine=text("araç: ?",15,TEXT); statusCard.addView(vehLine);
        recLine=text("kayıt: kapalı",15,TEXT); statusCard.addView(recLine);
        statusCard.addView(text("Kayıtlar Movies/DiLinkCam altına yazılır. Araç AÇIK olunca kayıt otomatik başlar, kapandıktan 2 dk sonra durur.",11,MUTED));

        LinearLayout logCard=card(pageHome);
        logCard.addView(text("Kurulum / Olay Günlüğü",14,ACCENT));
        setupLog=text("",11,TEXT); setupLog.setTypeface(Typeface.MONOSPACE);
        setupLog.setTextIsSelectable(true);
        logCard.addView(setupLog);

        // ============ CANLI KAMERA SAYFASI ============
        pageCamera=new LinearLayout(this); pageCamera.setOrientation(LinearLayout.VERTICAL);
        pageCamera.setVisibility(View.GONE);
        content.addView(pageCamera);

        LinearLayout camCard=card(pageCamera);
        camCard.addView(text("Canlı Kamera",18,ACCENT));

        LinearLayout row=new LinearLayout(this); camCard.addView(row);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(text("Canlı kamera: ",14,TEXT));
        camSpin=new Spinner(this);
        ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,CameraEngine.CAM_NAMES);
        camSpin.setAdapter(ad);
        int saved=getSharedPreferences("parkcam",0).getInt("live_cam",0);
        ParkCamService.LIVE_CAM=saved;
        for (int i=0;i<CameraEngine.CAM_IDS.length;i++) if (CameraEngine.CAM_IDS[i]==saved) camSpin.setSelection(i);
        // seçim değişince kaydet + servise bildir (kayıt HER ZAMAN 4 kamera, bu yalnız canlı izleme)
        camSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id) {
                int camId=CameraEngine.CAM_IDS[Math.max(0,Math.min(pos,CameraEngine.CAM_IDS.length-1))];
                ParkCamService.LIVE_CAM=camId;
                getSharedPreferences("parkcam",0).edit().putInt("live_cam",camId).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        row.addView(camSpin,new LinearLayout.LayoutParams(0,dp(52),1));

        liveView=new ImageView(this);
        liveView.setBackgroundColor(Color.rgb(0,10,6));
        liveView.setAdjustViewBounds(true);
        liveView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        camCard.addView(liveView,new LinearLayout.LayoutParams(-1,dp(380)));

        liveBtn=button("▶ CANLI YAYINI AÇ",this::toggleLive);
        camCard.addView(liveBtn);
        liveStat=text("canlı: kapalı",12,MUTED); camCard.addView(liveStat);
        camCard.addView(text("AKIŞTA 4 KAMERA BİRDEN: araç açıkken dördü aynı anda kayda alınır (Movies/DiLinkCam). Burada seçtiğin kamera canlı izlemede gösterilir.",11,MUTED));

        // ---- sol menü (çekmece) ----
        menuPanel=new LinearLayout(this);
        menuPanel.setOrientation(LinearLayout.VERTICAL);
        menuPanel.setBackgroundColor(Color.rgb(16,28,38));
        menuPanel.setPadding(dp(14),dp(24),dp(14),dp(14));
        FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(280),-1);
        mp.gravity=android.view.Gravity.START;
        menuPanel.setLayoutParams(mp);
        menuPanel.setVisibility(View.GONE);
        menuPanel.addView(text("ParkCam",18,ACCENT));
        menuPanel.addView(text(" ",8,MUTED));

        Button home=menuItem("🏠  Ana Sayfa",()->{ showPage(0); toggleMenu(false); });
        menuPanel.addView(home);
        Button cam=menuItem("📷  Canlı Kamera",()->{ showPage(1); toggleMenu(false); });
        menuPanel.addView(cam);
        menuPanel.addView(text(" ",8,MUTED));
        menuPanel.addView(text("⚙  Ayarlar",15,MUTED));

        Button prep=menuItem("★  Aracı Hazırla",()->{
            toggleMenu(false);
            showPage(0);
            send("setup");
            toast("Kurulum başladı — günlüğü anasayfada izleyin");
        });
        menuPanel.addView(prep);
        Button clean=menuItem("✕  Kaldırmaya Hazırla",()->confirmCleanup());
        menuPanel.addView(clean);

        rootFrame.addView(menuPanel);
        setContentView(rootFrame);
    }

    /** Ayar menüsündeki kaldırma hazırlığı — onay ister */
    private void confirmCleanup() {
        toggleMenu(false);
        showPage(0);
        new AlertDialog.Builder(this).setTitle("Kaldırma hazırlığı")
            .setMessage("Yardımcı süreçler durdurulur, /data/local/tmp dosyaları silinir ve kablosuz ADB kapatılır. Uygulamayı kaldırmadan önce kullan. (Kayıtların Movies/DiLinkCam'de kalır.)")
            .setNegativeButton("Vazgeç",null)
            .setPositiveButton("Temizle",(d,w)->{
                send("cleanup");
                toast("Temizlik başladı — günlüğü anasayfada izleyin");
            }).show();
    }

    private void send(String action) {
        try {
            Intent i=new Intent(this,ParkCamService.class).setAction(action);
            startService(i);
        } catch (Exception e) { toast("Gönderilemedi: "+e.getMessage()); }
    }

    private void showPage(int which) {
        pageHome.setVisibility(which==0?View.VISIBLE:View.GONE);
        pageCamera.setVisibility(which==1?View.VISIBLE:View.GONE);
        if (which!=1 && liveOn) { liveOn=false; ParkCamService.WANT_LIVE=false; liveBtn.setText("▶ CANLI YAYINI AÇ"); liveStat.setText("canlı: kapalı"); }
    }

    private void toggleMenu(boolean show) {
        menuPanel.setVisibility(show?View.VISIBLE:View.GONE);
    }

    /* ================= canlı izleme ================= */

    private void toggleLive() {
        liveOn=!liveOn;
        ParkCamService.WANT_LIVE=liveOn;
        liveBtn.setText(liveOn?"■ YAYINI DURDUR":"▶ CANLI YAYINI AÇ");
        liveStat.setText(liveOn?"canlı: bekleniyor…":"canlı: kapalı");
    }

    /** 2 sn'de bir durum; canlı açıkken 300 ms'de bir kare */
    private void pollStatus() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()||isDestroyed()) return;
                if (!ParkCamService.IS_RUNNING) {
                    stateLine.setText("durum: servis bekleniyor…");
                } else {
                    stateLine.setText("durum: "+ParkCamService.STATUS);
                }
                vehLine.setText("araç: "+ParkCamService.VEHICLE);
                if (ParkCamService.RECORDING) {
                    long sec=(System.currentTimeMillis()-ParkCamService.REC_START_MS)/1000;
                    recLine.setText(String.format(Locale.US,"kayıt: ● KAYITTA %02d:%02d • %s",sec/60,sec%60,ParkCamService.REC_FILE));
                    recLine.setTextColor(RED);
                } else {
                    recLine.setText("kayıt: kapalı");
                    recLine.setTextColor(TEXT);
                }
                setupLog.setText(ParkCamService.SERVICE_LOG);
                if (liveOn) {
                    android.graphics.Bitmap b=ParkCamService.LIVE_BMP;
                    if (b!=null) {
                        liveView.setImageBitmap(b);
                        liveStat.setText(String.format(Locale.US,"canlı: kamera %d • %dx%d",ParkCamService.LIVE_CAM,CameraEngine.OUT_W,CameraEngine.OUT_H));
                    }
                }
                ui.postDelayed(this, liveOn?300:2000);
            }
        },1500);
    }

    /* ================= ui yardımcıları ================= */

    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
    private TextView text(String v,int size,int color){ TextView t=new TextView(this); t.setText(v); t.setTextSize(size); t.setTextColor(color); t.setPadding(dp(3),dp(5),dp(3),dp(5)); return t; }
    private GradientDrawable bg(int color){ GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(12)); return g; }
    private Button button(String value,Runnable job){ Button b=new Button(this); b.setText(value); b.setAllCaps(false); b.setTextColor(TEXT); b.setTextSize(14); b.setOnClickListener(v->job.run()); return b; }
    private Button menuItem(String value,Runnable job){
        Button b=new Button(this); b.setText(value); b.setAllCaps(false); b.setTextColor(TEXT); b.setTextSize(15);
        b.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));
        lp.bottomMargin=dp(2);
        b.setLayoutParams(lp);
        b.setOnClickListener(v->job.run());
        return b;
    }
    private LinearLayout card(LinearLayout parent){
        LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14),dp(10),dp(14),dp(12)); c.setBackground(bg(CARD));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(12);
        parent.addView(c,lp); return c;
    }
}
