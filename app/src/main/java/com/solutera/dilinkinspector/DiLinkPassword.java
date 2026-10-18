package com.solutera.dilinkinspector;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.util.Base64;

/**
 * BYD DiLink dinamik mühendislik/inspection şifresi (CipherManager).
 * Kaynak: 51.1.4.2605140.1 firmware, BydSecretCode.apk içindeki rf.java birebir portu.
 * Anahtar: TBox IMEI'nin son 6 hanesi (IMEI yoksa BYD tarafında "112233" kullanılıyor).
 * Her anahtar+saat için 5 aday üretilir: şu an ve ±1/±2 saat (GMT+8 Pekin saati).
 * Algoritma bağımsız bir Java referans implementasyonuyla doğrulanmıştır.
 */
public final class DiLinkPassword {
    private static final int[] OFFSETS = {-2, -1, 0, 1, 2};
    private static final TimeZone TZ = TimeZone.getTimeZone("GMT+08:00");
    private static final String IV = "1995020213467928";
    private static final Pattern DIGITS = Pattern.compile("\\d{6,}");

    private DiLinkPassword() {}

    /** BYD kontrolüyle aynı: anahtar 6 haneli sayı olmalı. */
    public static boolean validKey(String key) {
        return key != null && key.length() == 6 && key.matches("\\d{6}");
    }

    /** IMEI/benzeri dizgeden anahtar: son 6 hane. */
    public static String keyFromImei(String imei) {
        if (imei == null) return null;
        Matcher m = DIGITS.matcher(imei);
        String last = null;
        while (m.find()) last = m.group();
        if (last == null || last.length() < 6) return null;
        return last.substring(last.length() - 6);
    }

    /**
     * O an geçerli 5 aday şifre üretir (şu an, -1h, -2h, +1h, +2h sırasıyla).
     * turkish=true: Android'in Türkçe locale toUpperCase davranışı (i → İ).
     */
    public static String[] candidates(String key, Date now, boolean turkish) throws Exception {
        if (!validKey(key)) throw new IllegalArgumentException("Anahtar 6 haneli sayı olmalı (IMEI son 6 hane).");
        String[] out = new String[OFFSETS.length];
        for (int i = 0; i < OFFSETS.length; i++) out[i] = candidate(key, offset(now, OFFSETS[i]), turkish);
        return out;
    }

    private static Date offset(Date now, int hours) {
        Calendar c = Calendar.getInstance(TZ, Locale.US);
        c.setTime(now);
        c.add(Calendar.HOUR, hours);
        return c.getTime();
    }

    private static String candidate(String key, Date time, boolean turkish) throws Exception {
        String mmddyyyyhh = format(time, "MMddyyyyHH");
        String yyyyddmmhh = format(time, "yyyyddMMHH");
        String mmddhh = format(time, "MMddHH");
        // rf.b(): AES anahtarı = key + MMddyyyyHH ; düz metin = yyyyddMMHH + key
        byte[] seed = (key + mmddyyyyhh).getBytes(StandardCharsets.UTF_8);
        byte[] plain = (yyyyddmmhh + key).getBytes(StandardCharsets.UTF_8);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(seed, "AES"),
                new javax.crypto.spec.IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
        String b64 = Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP);
        // rf.a(): picks[i]=b64[key[i]] ; xors[i]=b64[key[i]+MMddHH[i]] ; XOR → base64 → alnum → 6 → upper
        StringBuilder picks = new StringBuilder(), xors = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            picks.append(b64.charAt(key.charAt(i) - '0'));
            xors.append(b64.charAt(key.charAt(i) - '0' + mmddhh.charAt(i) - '0'));
        }
        byte[] xored = new byte[6];
        for (int i = 0; i < 6; i++) xored[i] = (byte) (picks.charAt(i) ^ xors.charAt(i));
        String encoded = Base64.encodeToString(xored, Base64.NO_WRAP);
        StringBuilder alnum = new StringBuilder();
        for (int i = 0; i < encoded.length() && alnum.length() < 6; i++) {
            char ch = encoded.charAt(i);
            if (Character.isDigit(ch) || Character.isLetter(ch)) alnum.append(ch);
        }
        while (alnum.length() < 6) alnum.append('B');
        return upper(alnum.substring(0, 6), turkish);
    }

    private static String upper(String value, boolean turkish) {
        if (!turkish) return value.toUpperCase(Locale.US);
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            out.append(ch == 'i' ? '\u0130' : Character.toUpperCase(ch));
        }
        return out.toString();
    }

    private static String format(Date time, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TZ);
        return format.format(time);
    }
}
