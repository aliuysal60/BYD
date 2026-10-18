package com.solutera.dilinkinspector;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import org.json.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded on-device discovery. mDNS peers are ignored unless an address belongs to this device. */
public final class LocalDiscovery {
    public interface Control { boolean cancelled(); void status(String value); }
    private final Context ctx;
    private final Control control;
    private final List<JSONObject> found=Collections.synchronizedList(new ArrayList<>());
    private final List<String> errors=Collections.synchronizedList(new ArrayList<>());
    private final Set<String> seen=Collections.synchronizedSet(new HashSet<>());
    private final Queue<NsdServiceInfo> resolveQueue=new ConcurrentLinkedQueue<>();
    private final Object resolveLock=new Object();
    private volatile boolean stopped,resolving;
    private NsdManager nsd;
    public LocalDiscovery(Context c,Control control){ctx=c;this.control=control;}
    public JSONArray candidates(){synchronized(found){return new JSONArray(found);}}
    private void add(String host,int port,String type,String source){
        if(stopped||control.cancelled()||port<1||port>65535)return;
        try{LocalNetwork.requireLocal(host);}catch(Exception ignored){return;}
        String key=LocalNetwork.endpoint(host,port)+"/"+type;
        if(seen.add(key))found.add(ScanEngine.obj("host",host,"port",port,"kind",type,"source",source));
    }
    public JSONObject discover(boolean deep,JSONObject properties)throws Exception {
        long start=SystemClock.elapsedRealtime();
        List<NsdManager.DiscoveryListener> listeners=new ArrayList<>();WifiManager.MulticastLock multicast=null;
        try {
            nsd=(NsdManager)ctx.getSystemService(Context.NSD_SERVICE);
            try{WifiManager wifi=(WifiManager)ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);multicast=wifi.createMulticastLock("dilink-adb-discovery");multicast.setReferenceCounted(false);multicast.acquire();}catch(Exception e){errors.add(ScanEngine.error(e));}
            if(nsd!=null)for(String type:new String[]{"_adb._tcp.","_adb-tls-connect._tcp.","_adb-tls-pairing._tcp."}){
                NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
                    public void onDiscoveryStarted(String t){}
                    public void onDiscoveryStopped(String t){}
                    public void onStartDiscoveryFailed(String t,int code){errors.add(t+" discovery error="+code);}
                    public void onStopDiscoveryFailed(String t,int code){}
                    public void onServiceLost(NsdServiceInfo service){}
                    public void onServiceFound(NsdServiceInfo service){if(!stopped&&!control.cancelled()){resolveQueue.add(service);resolveNext();}}
                };
                try{nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener);listeners.add(listener);}catch(Exception e){errors.add(ScanEngine.error(e));}
            }
            control.status("Yerel ADB: sistem portları ve mDNS aranıyor…");
            Set<Integer> ports=new LinkedHashSet<>(Arrays.asList(5555,5556,5557,5558));
            if(properties!=null)for(String key:new String[]{"service.adb.tcp.port","persist.adb.tcp.port","service.adb.tls.port"}){
                JSONObject p=properties.optJSONObject(key);if(p!=null)try{ports.add(LocalNetwork.port(p.optString("output")));}catch(Exception ignored){}
            }
            LinkedHashSet<String> hosts=new LinkedHashSet<>(Arrays.asList("127.0.0.1","::1"));
            for(InetAddress a:LocalNetwork.addresses())if(!a.isLoopbackAddress()&&!a.isLinkLocalAddress())hosts.add(a.getHostAddress());
            for(String host:hosts)for(int port:ports){if(control.cancelled())break;probe(host,port);}
            if(deep&&!control.cancelled())scanAllLoopback();
            while(!control.cancelled()&&SystemClock.elapsedRealtime()-start<12000)Thread.sleep(100);
        }finally{
            stopped=true;resolveQueue.clear();
            if(nsd!=null)for(NsdManager.DiscoveryListener listener:listeners)try{nsd.stopServiceDiscovery(listener);}catch(Exception ignored){}
            if(multicast!=null&&multicast.isHeld())multicast.release();
        }
        JSONArray errorSnapshot;synchronized(errors){errorSnapshot=new JSONArray(new ArrayList<>(errors));}
        return ScanEngine.obj("at",ScanEngine.time(),"scope","this_device_only","deep",deep,"cancelled",control.cancelled(),
            "elapsed_ms",SystemClock.elapsedRealtime()-start,"candidates",candidates(),"errors",errorSnapshot,
            "note","tcp_open_unidentified ADB kanıtı değildir. mDNS eşleştirme portu ile bağlantı portu farklı olabilir. Derin tarama loopback ile sınırlıdır; bütün ağ arayüzlerini kapsamaz.");
    }
    private void resolveNext(){
        synchronized(resolveLock){if(resolving||stopped)return;NsdServiceInfo next=resolveQueue.poll();if(next==null)return;resolving=true;
            try{nsd.resolveService(next,new NsdManager.ResolveListener(){
                public void onResolveFailed(NsdServiceInfo service,int code){errors.add("mDNS resolve error="+code);done();}
                public void onServiceResolved(NsdServiceInfo service){InetAddress host=service.getHost();if(host!=null)add(host.getHostAddress(),service.getPort(),service.getServiceType().contains("pairing")?"mdns_pairing":"mdns_connect","mDNS "+service.getServiceType());done();}
                private void done(){synchronized(resolveLock){resolving=false;}resolveNext();}
            });}catch(Exception e){resolving=false;errors.add(ScanEngine.error(e));}
        }
    }
    private void probe(String host,int port){
        if(stopped||control.cancelled())return;
        try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),100);}catch(Exception ignored){return;}
        String kind="tcp_open_unidentified";
        try{kind=LocalNetwork.identify(host,port);}catch(Exception ignored){}
        add(host,port,kind,"TCP + ADB handshake probe");
    }
    private void scanAllLoopback()throws InterruptedException {
        AtomicInteger next=new AtomicInteger(1),completed=new AtomicInteger();
        long end=SystemClock.elapsedRealtime()+120000;ExecutorService pool=Executors.newFixedThreadPool(16);
        CountDownLatch done=new CountDownLatch(16);
        try{
            for(int w=0;w<16;w++)pool.execute(()->{try{
                for(int p;(p=next.getAndIncrement())<=65535&&!control.cancelled()&&SystemClock.elapsedRealtime()<end;){
                    probe("127.0.0.1",p);probe("::1",p);completed.incrementAndGet();
                }
            }finally{done.countDown();}});
            while(!done.await(250,TimeUnit.MILLISECONDS)){
                control.status("Derin yerel tarama: "+completed.get()+" / 65535 port • "+found.size()+" aday");
                if(control.cancelled()||SystemClock.elapsedRealtime()>=end)break;
            }
            if(completed.get()<65535)errors.add("Kısmi derin tarama: "+completed.get()+" / 65535 port; iptal veya 120 saniye sınırı.");
        }finally{pool.shutdownNow();pool.awaitTermination(3,TimeUnit.SECONDS);}
    }
}
