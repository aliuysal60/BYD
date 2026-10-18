package com.solutera.dilinkinspector;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Runs under the release app UID, never grants privileges to the diagnostic APK. */
public final class IntegrationTests extends Instrumentation {
    private int passed;
    private Bundle args;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    private void check(boolean condition,String label){if(!condition)throw new AssertionError(label);passed++;Bundle b=new Bundle();b.putString("stream","PASS "+label+"\n");sendStatus(0,b);}
    private void fails(ScanEngine.Operation operation,String label)throws Exception{boolean rejected=false;try{operation.run();}catch(Exception expected){rejected=true;}check(rejected,label);}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Context ctx=getTargetContext();ScanEngine engine=ScanEngine.get(ctx);ResearchLab lab=ResearchLab.get(ctx);
            if(args!=null&&args.containsKey("pair_port")){
                lab.pair("127.0.0.1",Integer.parseInt(args.getString("pair_port")),args.getString("pair_code"));
                check(!lab.transcript().contains(args.getString("pair_code")),"successful pairing code excluded from report");
                lab.connect("127.0.0.1",Integer.parseInt(args.getString("tls_port")));
                check(lab.adb.connected(),"paired TLS ADB connection established");
                check(lab.execute(1,"id",6000).optString("output").contains("uid=2000(shell)"),"TLS ADB shell command executes");
                lab.adb.disconnect();result.putString("stream","PASS: TLS pairing and "+passed+" assertions\n");finish(Activity.RESULT_OK,result);return;
            }
            check(ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")!=PackageManager.PERMISSION_GRANTED,"app begins without privileged settings permission");
            check(LocalNetwork.requireLocal("127.0.0.1").isLoopbackAddress(),"IPv4 loopback accepted");
            check(LocalNetwork.requireLocal("::1").isLoopbackAddress(),"IPv6 loopback accepted");
            fails(()->LocalNetwork.requireLocal("192.0.2.15"),"remote peer rejected");
            fails(()->LocalNetwork.requireLocal("example.com"),"hostname/DNS peer rejected");
            fails(()->LocalNetwork.port("0"),"invalid port rejected");
            fails(()->LocalNetwork.port("65536"),"overflow port rejected");
            JSONObject id=lab.execute(0,"id",3000);check(id.optString("output").contains("uid="+android.os.Process.myUid()+"("),"app shell retains app UID");
            JSONObject timeout=engine.command(new String[]{"/system/bin/sh","-c","sleep 2"},150,1024);check(timeout.optBoolean("timed_out_or_cancelled"),"local command timeout recorded");
            String previous=Settings.Global.getString(ctx.getContentResolver(),"adb_enabled");
            lab.enable(false,false);
            check(Objects.equals(previous,Settings.Global.getString(ctx.getContentResolver(),"adb_enabled")),"unprivileged experiment does not change protected setting");
            check(lab.transcript().contains("SecurityException"),"write denial retained in research evidence");
            check(lab.snapshot().has("restore_baseline"),"restore baseline persisted");
            check(lab.snapshot().optJSONArray("restore_keys").length()==2,"only attempted keys enter restore list");
            check(ResearchLab.diff(ScanEngine.obj("settings",ScanEngine.obj("x",0)),ScanEngine.obj("settings",ScanEngine.obj("x",1))).getJSONObject("settings").has("x"),"before/after changed setting detected");
            check(ScanEngine.relevant("com.vendor.hidden.EngineerMode"),"additional OEM discovery keywords");
            check(ScanEngine.risky("FactoryResetActivity"),"reset not auto-launched");
            check(lab.routes().size()>=12,"standard and explicit menu fallbacks available");
            fails(()->lab.execute(1,"id",1000),"ADB commands require actual connection");
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                Thread responder=new Thread(()->{try(Socket s=server.accept()){
                    byte[] req=new byte[31];new DataInputStream(s.getInputStream()).readFully(req);
                    ByteBuffer packet=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);packet.putInt(LocalNetwork.AUTH).putInt(1).putInt(0).putInt(20).putInt(0).putInt(~LocalNetwork.AUTH).put(new byte[20]);s.getOutputStream().write(packet.array());
                }catch(Exception ignored){}});responder.start();
                check("adb_auth_required".equals(LocalNetwork.identify("127.0.0.1",server.getLocalPort())),"ADB AUTH is identified without sending key or command");responder.join(2000);
            }
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                Thread responder=new Thread(()->{try(Socket s=server.accept()){s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n".getBytes(StandardCharsets.US_ASCII));}catch(Exception ignored){}});responder.start();
                fails(()->LocalNetwork.identify("127.0.0.1",server.getLocalPort()),"non-ADB listener not reported as ADB");responder.join(2000);
            }
            engine.start(false);long until=SystemClock.elapsedRealtime()+90000;while(engine.running&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
            check(!engine.running&&"complete".equals(engine.snapshot().optString("scan_status")),"full inventory completes");
            check(engine.snapshot().optJSONObject("device").has("soc_model"),"SoC fields reported");
            check(engine.snapshot().optJSONObject("network").optJSONArray("addresses").length()>0,"local network addresses reported");
            JSONObject apk=ApkResearch.inspect(ctx,ctx.getPackageName());check(apk.optInt("dex_files")>0&&apk.optJSONArray("findings").length()>0,"installed APK DEX hints extracted");
            lab.discover(false);check(lab.snapshot().has("discovery"),"bounded local discovery report produced");
            File zip=engine.reportZip();try(ZipFile z=new ZipFile(zip)){check(z.getEntry("report.json")!=null&&z.getEntry("summary.txt")!=null,"report ZIP contains JSON and text");}
            File text=engine.reportText();check(text.isFile()&&text.length()>0,"standalone text report produced");
            File[] history=new File(ctx.getFilesDir(),"exports").listFiles((d,n)->n.endsWith(".zip"));check(history!=null&&history.length>=2,"previous report automatically archived");
            if(args!=null&&args.containsKey("adb_port")){
                int port=Integer.parseInt(args.getString("adb_port"));lab.connect("127.0.0.1",port);
                check(lab.adb.connected(),"real localhost ADB connection established");
                JSONObject adbId=lab.execute(1,"id",5000);check(adbId.optString("output").contains("uid=2000(shell)"),"real ADB command executes as shell UID");
                JSONObject unicode=lab.execute(1,"printf 'geliştirici ✓'; exit 7",5000);
                check(unicode.optString("output").contains("geliştirici ✓")&&unicode.optInt("exit",-1)==7,"ADB preserves Unicode and nonzero exit status");
                JSONObject bounded=lab.execute(1,"sleep 10",300);check(bounded.optBoolean("timed_out"),"ADB command deadline closes hung transport");
                lab.adb.disconnect();lab.connect("127.0.0.1",port);check(lab.adb.connected(),"ADB reconnect reuses persistent identity");lab.adb.disconnect();
            }
            // Pairing failure exercises native TLS load plus partially initialized cleanup.
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                Thread responder=new Thread(()->{try(Socket s=server.accept()){s.close();}catch(Exception ignored){}});responder.start();
                fails(()->lab.adb.pair("127.0.0.1",server.getLocalPort(),"123456"),"pairing failure reports cleanly with TLS native provider");responder.join(2000);
            }
            check(!lab.transcript().contains("123456"),"pairing code not written to report");
            result.putString("stream","PASS: "+passed+" integration assertions\n");result.putInt("passed",passed);finish(Activity.RESULT_OK,result);
        }catch(Throwable t){result.putString("stream","FAIL after "+passed+" assertions: "+t+"\n"+android.util.Log.getStackTraceString(t));finish(Activity.RESULT_CANCELED,result);}
    }
}
