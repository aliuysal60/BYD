package com.solutera.dilinkinspector;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * NV12 kareleri donanım H.264 encoder'ıyla (Venus) MP4'e yazar.
 * Hedef: MediaStore Movies/DiLinkCam — Dosyalar uygulamasından erişilebilir.
 *
 * Güvenlik katmanları (uygulama kayıt ortasında ölürse):
 *  1) ACTION_SHUTDOWN alıcısı finalizeActive() ile kaydı anında düzgün kapatır.
 *  2) Her kodlanmış kare uygulama içi KURTARMA GÜNLÜĞÜNE de yazılır; düzgün
 *     kapanamayan kayıt açılışta recoverPending() ile günlükten yeniden inşa edilir.
 *  3) Kurtarma verisi de yoksa (uygulama verisi silinmişse) bozuk satır temizlenir.
 */
public final class Mp4Recorder {
    private static final String MIME_HEVC = "video/hevc";
    private static final String MIME_AVC = "video/avc";
    private String mime = MIME_HEVC; // HEVC yoksa AVC'ye düşer (start'ta)
    private final int width, height;

    private MediaCodec encoder;
    private MediaMuxer muxer;
    private ParcelFileDescriptor pfd;
    private int trackIndex = -1;
    private boolean muxerStarted;
    private long startNs;
    private long frames;
    public volatile String filePath; // MediaStore URI'si veya yerel dosya yolu
    public Uri uri;

    /** Kurtarma günlüğü: uygulama özel dizininde ham örnek akışı. */
    private DataOutputStream journal;
    private File journalFile;
    private static final int JOURNAL_MAGIC = 0x43414A31;    // "CAJ1" (eski: codec yok → AVC varsayılır)
    private static final int JOURNAL_MAGIC_V2 = 0x43414A32; // "CAJ2" (codec/MIME başlıkta)

    public Mp4Recorder(int width, int height) { this.width = width; this.height = height; }

    /** Kaydı başlat: MediaStore'a yeni MP4 oluştur, encoder + muxer + günlük hazırla.
     *  H.265/HEVC önceliklidir (H.264'ün ~yarısı boyut, aynı kalite); encoder
     *  yoksa H.264'e düşer. 4 kamera eşzamanlı kaydı için bitrate orta seviyede. */
    public synchronized void start(Context ctx) throws Exception {
        if (encoder != null) throw new IllegalStateException(ctx.getString(R.string.rec_err_already_open));
        MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, pickColorFormat());
        // HEVC ~700 kbps ≈ H.264 1.4 Mbps görsel kalite. I-frame 2 sn (akıcı seek).
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 700_000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        try {
            encoder = MediaCodec.createEncoderByType(mime);
        } catch (Exception e) {
            mime = MIME_AVC; // HEVC encoder yoksa H.264'e düş
            fmt = MediaFormat.createVideoFormat(mime, width, height);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, pickColorFormat());
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 800_000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            encoder = MediaCodec.createEncoderByType(mime);
        }
        encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        String name = "DiLinkCam_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= 26) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DiLinkCam");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = ctx.getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new IllegalStateException(ctx.getString(R.string.rec_err_mediastore));
            // MediaStore ad çakışmasında " (1)", " (2)" ekler (4 kamera aynı
            // saniyede başlar). Kurtarma journal'ının GERÇEK display_name ile
            // eşleşmesi için adı geri oku — yoksa journal "...213408.bin" ama
            // dosya "...213408 (1).mp4" olur, remux journal'ı bulamaz, kayıt
            // kurtarılamaz ve pending kalır.
            try {
                android.database.Cursor dc = ctx.getContentResolver().query(
                        uri, new String[]{MediaStore.Video.Media.DISPLAY_NAME}, null, null, null);
                if (dc != null) {
                    try { if (dc.moveToFirst()) { String real = dc.getString(0); if (real != null && real.endsWith(".mp4")) name = real; } }
                    finally { dc.close(); }
                }
            } catch (Exception ignored) {}
            pfd = ctx.getContentResolver().openFileDescriptor(uri, "rw");
            if (pfd == null) throw new IllegalStateException(ctx.getString(R.string.rec_err_mp4_open));
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            filePath = "Movies/DiLinkCam/" + name;
        } else {
            File dir = new File(ctx.getExternalFilesDir(null), "DiLinkCam");
            dir.mkdirs();
            File f = new File(dir, name);
            muxer = new MediaMuxer(f.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            filePath = f.getAbsolutePath();
        }
        trackIndex = -1;
        muxerStarted = false;
        startNs = System.nanoTime();
        frames = 0;

        // kurtarma günlüğü
        journalFile = new File(ctx.getFilesDir(), "cam_journal_" + name.substring(0, name.length() - 4) + ".bin");
        try {
            journal = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(journalFile)));
            journal.writeInt(JOURNAL_MAGIC_V2);   // v2: başlıkta codec/MIME de var
            journal.writeInt(width);
            journal.writeInt(height);
            journal.writeUTF(mime);               // "video/avc" veya "video/hevc"
            journal.flush();
        } catch (IOException e) {
            journal = null; // günlüksüz devam: kurtarma devre dışı
        }
    }

    /** Son seçilen renk formatı — teşhis için (ParkCamService durum.txt'de gösterir). */
    public static volatile int LAST_COLOR_FORMAT = -1;

    private int pickColorFormat() {
        for (int candidate : new int[]{MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                0x7FA30C00 /* COLOR_QCOM_FormatYUV420SemiPlanar */,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar}) {
            try {
                MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
                fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, candidate);
                MediaCodec test = MediaCodec.createEncoderByType(mime);
                test.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                test.stop(); test.release();
                LAST_COLOR_FORMAT = candidate;
                return candidate;
            } catch (Exception ignored) {}
        }
        LAST_COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    }

    /**
     * Bir NV12 kareyi kodlayıcıya ver. blocking=false iken kodlayıcı doluysa kare atlanır.
     * @return kabul edildiyse true
     */
    public synchronized boolean feed(byte[] nv12, boolean blocking) {
        if (encoder == null) return false;
        long ptsUs = (System.nanoTime() - startNs) / 1000L;
        int inIndex = encoder.dequeueInputBuffer(blocking ? 50 : 0);
        if (inIndex >= 0) {
            boolean wrote = false;
            // COLOR_FormatYUV420Flexible: kodlayıcı düzlem yerleşimini (stride,
            // pixelStride) KENDİ belirler ve Image ile bildirir. Ham byte kopyası
            // (buf.put) UV'yi yanlış hizalar → renk kaybı (gri video). Doğru yol:
            // getInputImage ile Image'in Y/U/V düzlemlerine stride'a uyarak yazmak.
            if (LAST_COLOR_FORMAT == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                try {
                    android.media.Image img = encoder.getInputImage(inIndex);
                    if (img != null) {
                        fillImageFromNv12(img, nv12);
                        int size = width * height * 3 / 2;
                        encoder.queueInputBuffer(inIndex, 0, size, ptsUs, 0);
                        frames++; wrote = true;
                    }
                } catch (Exception ignored) {}
            }
            if (!wrote) {
                // Somut format (NV12/I420 SemiPlanar/Planar): ham byte kopyası.
                ByteBuffer buf = encoder.getInputBuffer(inIndex);
                if (buf != null && buf.capacity() >= nv12.length) {
                    buf.clear();
                    buf.put(nv12);
                    encoder.queueInputBuffer(inIndex, 0, nv12.length, ptsUs, 0);
                    frames++;
                } else {
                    encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, 0); // sığmadı: boş kare atla
                }
            }
        } else if (!blocking) {
            drain();
            return false; // kodlayıcı meşgul — kare düştü (normal, kaynak ~1-2fps)
        }
        drain();
        return inIndex >= 0;
    }

    /** NV12 kaynağı (Y düzlemi + UV interleaved) → Image'in Y/U/V düzlemlerine,
     *  her düzlemin rowStride/pixelStride'ına uyarak kopyala. Image semi-planar
     *  (U/V aynı tamponda, pixelStride=2) veya planar olabilir; API ikisini de
     *  bu yolla doğru doldurur. */
    private void fillImageFromNv12(android.media.Image img, byte[] nv12) {
        int w = width, h = height;
        android.media.Image.Plane[] p = img.getPlanes();
        // --- Y düzlemi ---
        ByteBuffer yb = p[0].getBuffer();
        int yRowStride = p[0].getRowStride();
        int yPix = p[0].getPixelStride();
        if (yRowStride == w && yPix == 1) {
            yb.put(nv12, 0, w * h);
        } else {
            for (int row = 0; row < h; row++) {
                int src = row * w;
                int dst = row * yRowStride;
                if (yPix == 1) {
                    yb.position(dst); yb.put(nv12, src, w);
                } else {
                    for (int col = 0; col < w; col++) yb.put(dst + col * yPix, nv12[src + col]);
                }
            }
        }
        // --- U/V düzlemleri --- NV12 UV interleaved: uvBase'den U=even, V=odd
        int uvBase = w * h;
        ByteBuffer ub = p[1].getBuffer();
        ByteBuffer vb = p[2].getBuffer();
        int uRowStride = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRowStride = p[2].getRowStride(), vPix = p[2].getPixelStride();
        int cw = w / 2, chh = h / 2;
        for (int row = 0; row < chh; row++) {
            int src = uvBase + row * w;           // bu UV satırı (OUT_W bayt = cw çift U,V)
            for (int col = 0; col < cw; col++) {
                byte U = nv12[src + col * 2];
                byte V = nv12[src + col * 2 + 1];
                ub.put(row * uRowStride + col * uPix, U);
                vb.put(row * vRowStride + col * vPix, V);
            }
        }
    }

    /** Kodlanmış paketleri muxer'a taşı + günlüğe yaz. */
    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int out = encoder.dequeueOutputBuffer(info, 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = encoder.getOutputFormat();
                trackIndex = muxer.addTrack(of);
                muxer.start();
                muxerStarted = true;
                journalCsd(of);
                continue;
            }
            if (out < 0) continue;
            ByteBuffer buf = encoder.getOutputBuffer(out);
            if (buf != null && muxerStarted && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                muxer.writeSampleData(trackIndex, buf, info);
                journalSample(buf, info);
            }
            encoder.releaseOutputBuffer(out, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }

    private void journalCsd(MediaFormat of) {
        if (journal == null) return;
        try {
            ByteBuffer csd0 = of.getByteBuffer("csd-0");
            ByteBuffer csd1 = of.getByteBuffer("csd-1");
            if (csd0 != null) journalRecord(1, 0, 0, csd0);
            if (csd1 != null) journalRecord(1, 1, 0, csd1);
        } catch (Exception ignored) { journal = null; }
    }

    private void journalSample(ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (journal == null) return;
        try {
            ByteBuffer d = buf.duplicate();
            d.position(info.offset).limit(info.offset + info.size);
            journalRecord(2, info.flags, info.presentationTimeUs, d);
        } catch (Exception ignored) { journal = null; }
    }

    private void journalRecord(int type, int flags, long pts, ByteBuffer data) throws IOException {
        int len = data.remaining();
        journal.writeInt(type);
        journal.writeInt(flags);
        journal.writeLong(pts);
        journal.writeInt(len);
        byte[] tmp = new byte[len];
        data.get(tmp);
        journal.write(tmp);
        journal.flush();
    }

    public synchronized long frameCount() { return frames; }

    /** Kaydı bitir: EOS → kalan paketler → muxer kapanış → MediaStore pending kaldır → günlük sil. */
    public synchronized void stop(Context ctx) {
        if (encoder == null) return;
        try {
            int in = encoder.dequeueInputBuffer(50);
            if (in >= 0) {
                encoder.queueInputBuffer(in, 0, 0, (System.nanoTime() - startNs) / 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            drain();
        } catch (Exception ignored) {}
        try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) {}
        try { muxer.release(); } catch (Exception ignored) {}
        try { encoder.stop(); } catch (Exception ignored) {}
        try { encoder.release(); } catch (Exception ignored) {}
        if (pfd != null) { try { pfd.close(); } catch (Exception ignored) {} }
        if (journal != null) { try { journal.close(); } catch (Exception ignored) {} }
        if (journalFile != null && muxerClosedOk()) journalFile.delete(); // günlük artık gereksiz
        if (uri != null && ctx != null && Build.VERSION.SDK_INT >= 26) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, v, null, null);
            } catch (Exception ignored) {}
        }
        encoder = null; muxer = null; pfd = null; muxerStarted = false;
        journal = null; journalFile = null;
    }

    private boolean muxerClosedOk() { return muxerStarted; }

    /* ================= depolama yönetimi ================= */

    private static final String REL = "Movies/DiLinkCam";

    private static Uri filesUri() {
        return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    /**
     * Düzgün kapanmamış (IS_PENDING) DiLinkCam kayıtlarını kurtar:
     * günlük varsa örnek akışından yeni oynatılabilir MP4 inşa eder; yoksa bozuk satırı siler.
     * @return insan okur sonuç raporu (kurtarılan/silinen sayısı)
     */
    public static String recoverPending(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return "";
        int recovered = 0, dropped = 0;
        try {
            String sel = MediaStore.Video.Media.RELATIVE_PATH + "='" + REL + "' AND "
                    + MediaStore.Video.Media.IS_PENDING + "=1";
            android.database.Cursor c = ctx.getContentResolver().query(filesUri(), null, sel, null, null);
            if (c == null) return "";
            java.util.ArrayList<android.net.Uri> pend = new java.util.ArrayList<>();
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            try {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                while (c.moveToNext()) {
                    pend.add(android.content.ContentUris.withAppendedId(filesUri(), c.getLong(idCol)));
                    names.add(c.getString(nameCol));
                }
            } finally { c.close(); }
            for (int i = 0; i < pend.size(); i++) {
                if (remux(ctx, names.get(i))) recovered++;
                else dropped++;
                try { ctx.getContentResolver().delete(pend.get(i), null, null); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        if (recovered == 0 && dropped == 0) return null;
        return dropped > 0 ? ctx.getString(R.string.rec_recovered_dropped, recovered, dropped)
                           : ctx.getString(R.string.rec_recovered_msg, recovered);
    }

    /** Günlükten yeni MP4 üret; başarılıysa günlüğü sil ve true döndür. */
    private static boolean remux(Context ctx, String displayName) {
        if (displayName == null) return false;
        String base = displayName.endsWith(".mp4") ? displayName.substring(0, displayName.length() - 4) : displayName;
        File jf = new File(ctx.getFilesDir(), "cam_journal_" + base + ".bin");
        // Geriye uyumluluk: eski kayıtlarda MediaStore " (N)" ekliyordu ama
        // journal parantezsiz addaydı → journal'ı " (N)" soyulmuş adla da dene.
        if (!jf.isFile()) {
            String stripped = base.replaceFirst("\\s*\\(\\d+\\)$", "");
            File alt = new File(ctx.getFilesDir(), "cam_journal_" + stripped + ".bin");
            if (alt.isFile()) jf = alt;
        }
        if (!jf.isFile()) return false;
        Uri out = null;
        ParcelFileDescriptor pfd = null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(jf), 1 << 16));
            int magic = in.readInt();
            if (magic != JOURNAL_MAGIC && magic != JOURNAL_MAGIC_V2) return false;
            int w = in.readInt(), h = in.readInt();
            // v2 başlığında codec/MIME var; v1'de yok → bu araçta kayıt AVC olduğu
            // için AVC varsay (remux eskiden HEVC varsayıyordu ve bu yüzden
            // kurtarma SESSİZCE başarısız oluyordu — pending dosyalar kalıyordu).
            String recMime = (magic == JOURNAL_MAGIC_V2) ? in.readUTF() : MIME_AVC;

            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, REL);
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            out = ctx.getContentResolver().insert(filesUri(), v);
            if (out == null) return false;
            pfd = ctx.getContentResolver().openFileDescriptor(out, "rw");
            if (pfd == null) throw new IOException("fd");
            MediaMuxer m = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            MediaFormat fmt = MediaFormat.createVideoFormat(recMime, w, h);
            java.io.ByteArrayOutputStream csd0 = new java.io.ByteArrayOutputStream(), csd1 = new java.io.ByteArrayOutputStream();
            while (true) {
                int type, flags, len; long pts;
                try { type = in.readInt(); flags = in.readInt(); pts = in.readLong(); len = in.readInt(); }
                catch (java.io.EOFException eof) { break; }
                if (len < 0 || len > 16 * 1024 * 1024) break; // bozuk kayıt: buraya kadar
                byte[] data = new byte[len];
                try { in.readFully(data); }
                catch (IOException partial) { break; } // son kayıt yarım: önceki örnekler geçerli
                if (type == 1) {
                    if (flags == 0) csd0.write(data); else csd1.write(data);
                } else if (type == 2) {
                    if (track < 0) {
                        if (csd0.size() == 0) break; // örnek geldi ama CSD yok: inşa edilemez
                        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.toByteArray()));
                        if (csd1.size() > 0) fmt.setByteBuffer("csd-1", ByteBuffer.wrap(csd1.toByteArray()));
                        track = m.addTrack(fmt);
                        m.start();
                    }
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    info.offset = 0; info.size = len;
                    info.presentationTimeUs = pts;
                    info.flags = flags & ~MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
                    m.writeSampleData(track, ByteBuffer.wrap(data), info);
                }
            }
            if (track < 0) throw new IOException(ctx != null ? ctx.getString(R.string.rec_err_no_sample) : "no sample");
            m.stop(); m.release(); pfd.close(); pfd = null;
            ContentValues done = new ContentValues(); done.put(MediaStore.Video.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(out, done, null, null);
            jf.delete();
            return true;
        } catch (Exception e) {
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            if (out != null) try { ctx.getContentResolver().delete(out, null, null); } catch (Exception ignored) {}
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }

    /** Yarım kalmış kayıtları günlüksüz temizler (eski davranış — yalnız kurtarılamayanlar). */
    public static int cleanupPending(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return 0;
        try {
            String sel = MediaStore.Video.Media.RELATIVE_PATH + "='" + REL + "' AND "
                    + MediaStore.Video.Media.IS_PENDING + "=1";
            return ctx.getContentResolver().delete(filesUri(), sel, null);
        } catch (Exception e) { return 0; }
    }

    /**
     * Depolamada en az minFreeBytes boş kalana kadar EN ESKİ DiLinkCam MP4'lerini siler.
     * @return silinen dosya sayısı
     */
    public static int ensureFreeSpace(Context ctx, long minFreeBytes) {
        if (Build.VERSION.SDK_INT < 26) return 0;
        int deleted = 0;
        try {
            StatFs st = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            while (st.getAvailableBytes() < minFreeBytes) {
                String sel = MediaStore.Video.Media.RELATIVE_PATH + "='" + REL + "' AND "
                        + MediaStore.Video.Media.IS_PENDING + "=0";
                android.database.Cursor c = ctx.getContentResolver().query(filesUri(), null, sel, null,
                        MediaStore.Video.Media.DATE_ADDED + " ASC LIMIT 1");
                if (c == null) break;
                Uri oldest = null;
                try {
                    if (c.moveToNext()) {
                        oldest = android.content.ContentUris.withAppendedId(filesUri(), c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)));
                    }
                } finally { c.close(); }
                if (oldest == null) break; // silinecek kaydımız kalmadı
                ctx.getContentResolver().delete(oldest, null, null);
                deleted++;
                st.restat(Environment.getExternalStorageDirectory().getAbsolutePath());
            }
        } catch (Exception ignored) {}
        return deleted;
    }

    /** Movies/DiLinkCam altındaki bizim kayıtların toplam boyutu (bayt). */
    public static long usedBytes(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return 0;
        long total = 0;
        try {
            String sel = MediaStore.Video.Media.RELATIVE_PATH + "='" + REL + "'";
            android.database.Cursor c = ctx.getContentResolver().query(filesUri(), null, sel, null, null);
            if (c == null) return 0;
            try {
                int sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                while (c.moveToNext()) total += c.getLong(sizeCol);
            } finally { c.close(); }
        } catch (Exception ignored) {}
        return total;
    }
}
