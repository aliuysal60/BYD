package com.solutera.dilinkinspector;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Bounded DEX string evidence, not a decompiler or proof that a code path is reachable. */
public final class ApkResearch {
    public static JSONObject inspect(Context ctx,String pkg)throws Exception {
        ApplicationInfo app=ctx.getPackageManager().getApplicationInfo(pkg,0);
        ArrayList<String> paths=new ArrayList<>();paths.add(app.sourceDir);if(app.splitSourceDirs!=null)paths.addAll(Arrays.asList(app.splitSourceDirs));
        JSONArray findings=new JSONArray(),errors=new JSONArray();int files=0;long consumed=0;boolean limited=false;
        outer:for(String path:paths)try(ZipFile zip=new ZipFile(path)){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                ZipEntry entry=entries.nextElement();if(!entry.getName().matches("classes[0-9]*\\.dex"))continue;
                if(entry.getSize()>32*1024*1024||consumed+entry.getSize()>64*1024*1024){limited=true;continue;}
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=zip.getInputStream(entry)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(bytes.size()+n>32*1024*1024)throw new IOException("DEX boyut sınırı");bytes.write(buf,0,n);}}
                byte[] data=bytes.toByteArray();consumed+=data.length;files++;
                for(String value:strings(data)){
                    findings.put(ScanEngine.obj("apk",new File(path).getName(),"dex",entry.getName(),"string",value));
                    if(findings.length()>=300){limited=true;break outer;}
                }
            }
        }catch(Exception e){errors.put(ScanEngine.error(e));}
        return ScanEngine.obj("package",pkg,"dex_files",files,"bytes_read",consumed,"limited",limited,"findings",findings,"errors",errors,
            "note","Bunlar DEX içindeki metin/sınıf ipuçlarıdır. Kodun çağrıldığını veya menünün açılabildiğini kanıtlamaz. Ayrıntılı akış incelemesi için APK dışarı aktarılabilir.");
    }
    static List<String> strings(byte[] data)throws IOException {
        ArrayList<String> out=new ArrayList<>();
        if(data.length<112||data[0]!='d'||data[1]!='e'||data[2]!='x')throw new IOException("Desteklenmeyen DEX");
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);int size=b.getInt(56),offset=b.getInt(60);
        if(size<0||offset<0||(long)offset+(long)size*4>data.length)throw new IOException("DEX string tablosu geçersiz");
        for(int i=0;i<size&&out.size()<300;i++){
            int pos=b.getInt(offset+i*4);if(pos<0||pos>=data.length)continue;int n=0;
            while(pos<data.length&&(data[pos++]&128)!=0&&++n<5){}
            int end=pos;while(end<data.length&&data[end]!=0&&end-pos<2048)end++;
            if(end>=data.length||end-pos>=2048)continue;
            String s=new String(data,pos,end-pos,StandardCharsets.UTF_8);String lower=s.toLowerCase(Locale.ROOT);
            // Broad hunt for hidden developer/engineering entry points, including OEM secret-code and Chinese-language (BYD) triggers.
            if(lower.matches(".*(development.?settings|adb_enabled|adb_wifi|no_debugging|write_secure_settings|usbdebug|usb_debug|debugging|buildnumber|build_number|engineering|engineermode|engineer.?mode|factory.?mode|factorymode|factorytest|factory_test|settings\\$|secret.?code|android_secret_code|\\*#|#\\*|hidden|backdoor|unlock|superuser|\\.factory\\.|\\.engineer|developeroption|工程|调试|开发|隐藏|后门|密码|出厂|工厂).*"))out.add(ScanEngine.redact(s));
        }
        return out;
    }
}
