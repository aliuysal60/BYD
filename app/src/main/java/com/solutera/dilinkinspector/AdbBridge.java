package com.solutera.dilinkinspector;

import android.content.Context;
import android.os.Build;
import io.github.muntashirakon.adb.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONObject;

/** Application-private ADB identity. Pairing codes and private keys never enter reports. */
public final class AdbBridge {
    private final Context ctx;
    private PrivateKey privateKey;
    private Certificate certificate;
    private volatile AdbConnection connection;
    private volatile PairingConnectionCtx pairing;
    private volatile String endpoint="Bağlı değil";
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    public AdbBridge(Context ctx) { this.ctx=ctx.getApplicationContext(); }
    private void identity() throws Exception {
        if(privateKey!=null)return;
        File file=new File(ctx.getNoBackupFilesDir(),"adb-identity.p12");
        KeyStore store=KeyStore.getInstance("PKCS12");char[] password="local-adb".toCharArray();
        if(file.isFile()) {
            try(InputStream in=new FileInputStream(file)){store.load(in,password);}
            privateKey=(PrivateKey)store.getKey("adb",password);certificate=store.getCertificate("adb");
            if(privateKey==null||certificate==null)throw new IOException("ADB kimliği bozuk; uygulama verisini silmeden önce raporu dışarı aktarın.");
            return;
        }
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);
        java.security.KeyPair keys=gen.generateKeyPair();
        X500Name name=new X500Name("CN=DiLink Inspector Local ADB");
        BouncyCastleProvider bc=new BouncyCastleProvider();
        JcaX509v3CertificateBuilder builder=new JcaX509v3CertificateBuilder(name,new BigInteger(128,new SecureRandom()),
            new Date(System.currentTimeMillis()-86400000L),new Date(System.currentTimeMillis()+315360000000L),name,keys.getPublic());
        Certificate cert=new JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(keys.getPrivate())));
        store.load(null,password);store.setKeyEntry("adb",keys.getPrivate(),password,new Certificate[]{cert});
        File tmp=new File(file.getParentFile(),"adb-identity.tmp");
        try(OutputStream out=new FileOutputStream(tmp)){store.store(out,password);}
        if(!tmp.renameTo(file))throw new IOException("ADB kimliği kaydedilemedi");
        privateKey=keys.getPrivate();certificate=cert;
    }
    public boolean connected(){AdbConnection c=connection;return c!=null&&c.isConnected()&&c.isConnectionEstablished();}
    public String state(){return connected()?"ADB bağlı • "+endpoint:"ADB bağlı değil";}
    public void disconnect(){AdbConnection c=connection;connection=null;endpoint="Bağlı değil";if(c!=null)try{c.close();}catch(Exception ignored){} }
    public void cancel(){PairingConnectionCtx p=pairing;if(p!=null)p.cancel();disconnect();}
    public boolean connect(String host,int port)throws Exception {
        LocalNetwork.requireLocal(host);identity();disconnect();
        AdbConnection c=new AdbConnection.Builder(host,port).setApi(Build.VERSION.SDK_INT).setDeviceName("DiLinkInspector")
            .setPrivateKey(privateKey).setCertificate(certificate).build();connection=c;
        ScheduledFuture<?> deadline=timer.schedule(()->{try{c.close();}catch(Exception ignored){}},25,TimeUnit.SECONDS);
        try {
            if(!c.connect(23,TimeUnit.SECONDS,false))throw new IOException("ADB yetkilendirmesi tamamlanmadı. Cihazdaki RSA onayını veya kablosuz eşleştirmeyi kontrol edin.");
            endpoint=LocalNetwork.endpoint(host,port);return true;
        }catch(Exception e){disconnect();throw e;}finally{deadline.cancel(false);}
    }
    public void pair(String host,int port,String code)throws Exception {
        LocalNetwork.requireLocal(host);
        if(!code.matches("[0-9]{6}"))throw new IllegalArgumentException("Ekranda gösterilen 6 haneli eşleştirme kodunu girin.");
        identity();
        PairingConnectionCtx p=new PairingConnectionCtx(host,port,code.getBytes(StandardCharsets.UTF_8),privateKey,certificate,"DiLinkInspector");pairing=p;
        ScheduledFuture<?> deadline=timer.schedule(p::cancel,25,TimeUnit.SECONDS);
        try{p.start();}finally{deadline.cancel(false);p.close();pairing=null;}
    }
    public JSONObject shell(String command,int timeoutMs) throws Exception {
        AdbConnection c=connection;if(!connected())throw new IOException("Önce yerel ADB bağlantısı kurun.");
        String marker="__DILINK_EXIT_"+UUID.randomUUID().toString().replace("-","")+"__";
        String wrapped="sh -c "+LocalNetwork.quote(command)+"; printf '\\n"+marker+"%s\\n' \"$?\"";
        AtomicBoolean timed=new AtomicBoolean();
        ScheduledFuture<?> deadline=timer.schedule(()->{timed.set(true);try{c.close();}catch(Exception ignored){}},timeoutMs,TimeUnit.MILLISECONDS);
        ByteArrayOutputStream output=new ByteArrayOutputStream();boolean truncated=false;String failure=null;
        try(AdbStream stream=c.open("shell:"+wrapped);InputStream in=stream.openInputStream()) {
            byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1){int take=Math.min(n,Math.max(0,128000-output.size()));output.write(buf,0,take);if(take<n){truncated=true;break;}}
        }catch(Exception e){failure=ScanEngine.error(e);}finally{deadline.cancel(false);}
        String value=new String(output.toByteArray(),StandardCharsets.UTF_8);Integer exit=null;int at=value.lastIndexOf(marker);
        if(at>=0){try{exit=Integer.parseInt(value.substring(at+marker.length()).trim());value=value.substring(0,at);}catch(Exception ignored){}}
        return ScanEngine.obj("mode","adb_shell","command",command,"endpoint",endpoint,"exit",exit,"timed_out",timed.get(),"truncated",truncated,"output",ScanEngine.redact(value),"error",failure);
    }

    /** İkili (raw) çıktı döndüren shell: kamera karesi gibi büyük bayt akışları için.
     *  İşaretleyici/çıkış kodu eklenmez, çıktı kırpılmaz (güvenlik sınırına dek). */
    public byte[] shellBinary(String command, int timeoutMs) throws Exception {
        AdbConnection c = connection;
        if (!connected()) throw new IOException("Önce yerel ADB bağlantısı kurun.");
        AtomicBoolean timed = new AtomicBoolean();
        ScheduledFuture<?> deadline = timer.schedule(() -> { timed.set(true); try { c.close(); } catch (Exception ignored) {} }, timeoutMs, TimeUnit.MILLISECONDS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (AdbStream stream = c.open("shell:" + command); InputStream in = stream.openInputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > 12 * 1024 * 1024) break; // güvenlik sınırı
            }
        } catch (Exception e) {
            if (!timed.get()) throw e;
        } finally {
            deadline.cancel(false);
        }
        return out.toByteArray();
    }
}
