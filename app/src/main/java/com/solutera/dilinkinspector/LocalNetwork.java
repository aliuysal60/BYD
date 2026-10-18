package com.solutera.dilinkinspector;

import java.net.*;
import java.util.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Numeric local endpoints only. Never resolve arbitrary hostnames or scan peers. */
public final class LocalNetwork {
    private LocalNetwork() {}
    public static List<InetAddress> addresses() throws SocketException {
        ArrayList<InetAddress> out = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) while (interfaces.hasMoreElements()) {
            NetworkInterface n = interfaces.nextElement();
            if (!n.isUp()) continue;
            Enumeration<InetAddress> a = n.getInetAddresses();
            while (a.hasMoreElements()) out.add(a.nextElement());
        }
        return out;
    }
    public static InetAddress requireLocal(String host) throws IOException {
        String value = host.trim();
        if ("localhost".equalsIgnoreCase(value)) value = "127.0.0.1";
        if (!value.matches("[0-9a-fA-F:.%_a-zA-Z0-9-]+") || (!value.contains(":") && !value.matches("[0-9.]+")))
            throw new IOException("Yalnızca bu cihazın sayısal IP adresi veya localhost kullanılabilir.");
        InetAddress a = InetAddress.getByName(value);
        if (a.isLoopbackAddress()) return a;
        for (InetAddress own : addresses()) if (own.equals(a)) return a;
        throw new IOException("Adres bu Android cihazına ait değil: " + host);
    }
    public static int port(String value) {
        int p;
        try { p = Integer.parseInt(value.trim()); } catch (Exception e) { throw new IllegalArgumentException("Port 1–65535 arasında olmalı."); }
        if (p < 1 || p > 65535) throw new IllegalArgumentException("Port 1–65535 arasında olmalı.");
        return p;
    }
    public static String endpoint(String host, int port) { return (host.contains(":") ? "["+host+"]" : host)+":"+port; }
    public static String quote(String command) { return "'" + command.replace("'", "'\\''") + "'"; }
    static final int CNXN=0x4e584e43, AUTH=0x48545541, STLS=0x534c5453;
    /** A valid ADB reply, not simply an accepting socket. No key or command is sent. */
    public static String identify(String host, int port) throws IOException {
        InetAddress address = requireLocal(host);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(address,port),300);
            s.setSoTimeout(550);
            byte[] payload="host::\0".getBytes(StandardCharsets.UTF_8);
            int sum=0; for(byte b:payload)sum+=b&255;
            ByteBuffer request=ByteBuffer.allocate(24+payload.length).order(ByteOrder.LITTLE_ENDIAN);
            request.putInt(CNXN).putInt(0x01000000).putInt(4096).putInt(payload.length).putInt(sum).putInt(CNXN ^ -1).put(payload);
            s.getOutputStream().write(request.array());
            DataInputStream in=new DataInputStream(s.getInputStream());byte[] header=new byte[24];in.readFully(header);
            ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int cmd=h.getInt(),arg0=h.getInt();h.getInt();int length=h.getInt(),checksum=h.getInt(),magic=h.getInt();
            if(magic!=(cmd ^ -1)||length<0||length>1024*1024)throw new IOException("ADB olmayan veya geçersiz protokol yanıtı");
            byte[] body=new byte[length];in.readFully(body);int actual=0;for(byte b:body)actual+=b&255;
            if(checksum!=0&&checksum!=actual)throw new IOException("ADB yanıtı checksum hatası");
            if(cmd==AUTH&&arg0==1)return "adb_auth_required";
            if(cmd==CNXN)return "adb_banner_received";
            if(cmd==STLS)return "adb_tls_pairing_may_be_required";
            return "unrecognized_adb_packet";
        }
    }
}
