/*
 * qcam_loop v8 - BYD kamera yakalayici: KAMERA BASINA AYRI ORNEK (coklu kamera)
 * Kullanim: qcam_loop8 <kamera_id>          (her kamera icin ayri surec)
 * Surum damgasi: QCAM_VERSION (asagida) — uygulama HELPER_VER ile eslesmeli.
 *
 * v7 degisiklikleri (v6'dan):
 *  - PIDFILE   -> qcam_loop_<cam>.pid   (kilit kamera basina; 4 ornek ayni anda yasayabilir)
 *  - VERFILE   -> qcam_loop_<cam>.ver
 *  - DEMAND    -> cam_demand_<cam>       (uygulama o kameranin talebini touch'lar)
 *  - TCP port  -> 7800 + cam             (7778 keeper'da oldugu icin kaydirildi)
 *
 * v6 davranisi korunur: talep kapisi (talep yoksa disk yazilmaz), oto-yeniden-dogus,
 * frame_<cam>_*.raw -> snap_<cam>.raw atomik tasinma, First Frame bekleyisi.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netdb.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <termios.h>
#include <string.h>
#include <dirent.h>
#include <signal.h>
#include <pthread.h>
#include <sys/wait.h>
#include <time.h>
#include <errno.h>
#include <sys/prctl.h>

#define DIRPATH "/data/local/tmp"
#define FRAME_SIZE 3932160L
#define QCAM_VERSION 10
#define BASE_PORT 7800

/* Kaynak UYVY: 1920x1024. Uygulamanin kullandigi etkin alan: sol-ust 1280x960. */
#define SRC_W 1920
#define SRC_H 1024
#define OUT_W 1280
#define OUT_H 960
/* NV12 (1280x960): Y duzlemi + araya eklenmis U/V (4:2:0) = W*H*3/2 */
#define NV12_SIZE ((long)OUT_W * OUT_H * 3 / 2)

#if defined(__aarch64__)
#include <arm_neon.h>
#define HAVE_NEON 1
#endif

static int master = -1;
static int child_in = -1; /* aracin stdin pipe'i: zarif cikis icin */
static pid_t child = -1;
static volatile int running = 1;
static long g_counter = 0;
static int g_cam = 0;
static long g_min_us = 83333;
static volatile time_t g_tcp_demand = 0; /* "D" komutu ile tazeLENIR */
static int g_burst = 1;       /* QBURST: tek yazışta kuyruklanan '21' sayısı */
static int g_dumpframe = 3;   /* QDF: her N'inci kare dökülür → 30/N fps */

static char pidfile[96], verfile[96], demandfile[96], snapfile[96];

static void on_term(int sig) { (void)sig; running = 0; }

static int move_snaps(void); /* spawn_tool içinde ilk-kare beklerken kullanılır */

static void kill_child(void) {
    if (child > 0) { kill(child, 9); waitpid(child, NULL, 0); child = -1; }
}

/* talep taze mi? (uygulama cam_demand_<cam>'i touch'liyor) */
static int demand_fresh(void) {
    struct stat st;
    if (time(NULL) < g_tcp_demand) return 1; /* TCP "D" talebi */
    if (stat(demandfile, &st) != 0) return 0;
    return (time(NULL) - st.st_mtime) <= 10;
}

/* tek kamera config'i yaz */
static int write_config(int cam) {
    char path[128];
    snprintf(path, sizeof(path), DIRPATH "/qcam_config_%d.xml", cam);
    FILE* f = fopen(path, "w");
    if (!f) return -1;
    fprintf(f,
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        "<qcarcam_inputs>\n"
        "    <input_device>\n"
        "        <properties input_id=\"%d\"/>\n"
        "        <output_setting width=\"1920\" height=\"1024\" pixelformat=\"V4L2_PIX_FMT_UYVY\"/>\n"
        "    </input_device>\n"
        "</qcarcam_inputs>\n", cam);
    fclose(f);
    chmod(path, 0666);
    return 0;
}

/* cocugu sh -c "setsid ... &" ile baslat (fork+exec varyanti kod=0 olup oluyordu;
 * ayni komut kabuktan %100 calisiyor — yontemi kabuga esitle).
 * Donus: ilk kare dosyasi gorunduyse 1. Cocuk pid'i pgrep ile izlenir. */

static int spawn_tool(void) {
    char cmd[512];
    char logpath[96];
    snprintf(logpath, sizeof(logpath), DIRPATH "/q8_tool_%d.log", g_cam);
    child = fork();
    if (child == 0) {
        prctl(PR_SET_PDEATHSIG, SIGKILL); /* ebeveyn olurse ben de olurum */
        int devnull = open("/dev/null", O_RDONLY);
        int out = open(logpath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (devnull >= 0) { dup2(devnull, 0); if (devnull > 2) close(devnull); }
        if (out >= 0) { dup2(out, 1); dup2(out, 2); if (out > 2) close(out); }
        setsid();
        chdir(DIRPATH);
        char cfgarg[96], dfarg[24], name[32], exe[96];
        snprintf(cfgarg, sizeof(cfgarg), "-config=qcam_config_%d.xml", g_cam);
        snprintf(dfarg, sizeof(dfarg), "-dumpFrame=%d", g_dumpframe);
        snprintf(name, sizeof(name), "qcam_patched_%d", g_cam);
        snprintf(exe, sizeof(exe), DIRPATH "/qcam_patched_c%d", g_cam);
        char* argv2[] = { name, cfgarg, dfarg, NULL };
        char* env[] = {
            "LD_LIBRARY_PATH=/vendor/lib64:/system/lib64:/system/apex/com.android.art.release/lib64",
            "PATH=/vendor/bin:/system/bin:/system/xbin",
            "HOME=" DIRPATH,
            NULL
        };
        execve(exe, argv2, env);
        _exit(127);
    }
    /* ilk kare dosyasini bekle (en cok 30 sn) */
    time_t start = time(NULL);
    while (time(NULL) - start < 30 && running) {
        if (move_snaps() > 0) return 1;
        /* cocuk erken oldu mu? (execve basarisiz / arac cikti) — bos bekleme */
        if (child > 0 && waitpid(child, NULL, WNOHANG) == child) { child = -1; return 0; }
        usleep(200000);
    }
    /* ilk kare gelmedi: cocuk bos yere disk/CPU yemesin, oldur ki ana dongu
     * tool_ok=0 ile temiz yeniden-dogus yapsin. */
    kill_child();
    return 0;
}

/* frame_<desen><sayac>.raw -> snap_<cam>.raw (yalniz TAM boyut)
 * cam 0: frame_0_<n>.raw (orijinal), cam N: frame_0N<n>.raw (yamali varyant) */
static int move_snaps(void) {
    char pattern[64], src[512];
    if (g_cam == 0) snprintf(pattern, sizeof(pattern), "frame_0_");
    else snprintf(pattern, sizeof(pattern), "frame_0%d", g_cam);
    DIR* d = opendir(DIRPATH);
    if (!d) return 0;
    int n = 0;
    struct dirent* e;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, pattern, strlen(pattern)) != 0) continue;
        char* dot = strrchr(e->d_name, '.');
        if (!dot || strcmp(dot, ".raw") != 0) continue;
        snprintf(src, sizeof(src), DIRPATH "/%s", e->d_name);
        struct stat st;
        if (stat(src, &st) != 0 || st.st_size != FRAME_SIZE) continue;
        unlink(snapfile);
        if (rename(src, snapfile) == 0) { chmod(snapfile, 0666); n++; }
    }
    closedir(d);
    return n;
}

/* ---------- TCP (yalniz PC teshisi; port 7800+cam) ---------- */
struct client { int fd; volatile int alive; pthread_t thread; struct client* next; };
static struct client* clients;
static pthread_mutex_t clients_lock = PTHREAD_MUTEX_INITIALIZER;

static void* client_thread(void* arg) {
    struct client* c = (struct client*)arg;
    char buf[64];
    while (running && c->alive) {
        ssize_t n = read(c->fd, buf, sizeof(buf) - 1);
        if (n == 0) break;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) continue;
        if (n < 0) break;
        buf[n] = 0;
        if (strncmp(buf, "PING", 4) == 0) { if (write(c->fd, "PONG\n", 5) < 0) break; }
    }
    close(c->fd);
    c->alive = 0;
    return NULL;
}

static void* acceptor_thread(void* arg) {
    int listen_fd = *(int*)arg;
    while (running) {
        int fd = accept(listen_fd, NULL, NULL);
        if (fd < 0) {
            if (errno == EINTR || errno == EAGAIN) { usleep(300000); continue; }
            usleep(500000); continue;
        }
        struct timeval tv = { 30, 0 }; /* yavas istemciler (WiFi/adb-forward) icin */
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        fcntl(fd, F_SETFD, FD_CLOEXEC);
        struct client* c = calloc(1, sizeof(*c));
        if (!c) { close(fd); continue; }
        c->fd = fd; c->alive = 1;
        pthread_mutex_lock(&clients_lock);
        c->next = clients; clients = c;
        pthread_mutex_unlock(&clients_lock);
        pthread_create(&c->thread, NULL, client_thread, c);
        pthread_detach(c->thread);
    }
    return NULL;
}

/* UYVY (SRC_W x SRC_H) -> NV12 (OUT_W x OUT_H, sol-ust kirpma).
 * NV12: once OUT_W*OUT_H Y baytlari, sonra her 2x2 blok icin (U,V) cifti.
 * NEON yolu: 16 UYVY baytini (8 piksel) tek seferde isler; Y'leri ayirir,
 * cift satirlarda U/V'yi alir. Skaler fallback her platformda calisir.
 * nv12 tamponu en az NV12_SIZE olmali. */
static void uyvy_to_nv12(const unsigned char* uyvy, unsigned char* nv12) {
    const int src_stride = SRC_W * 2;            /* UYVY: 2 bayt/piksel */
    unsigned char* yplane = nv12;
    unsigned char* uvplane = nv12 + (OUT_W * OUT_H);
    for (int y = 0; y < OUT_H; y++) {
        const unsigned char* srow = uyvy + (size_t)y * src_stride;
        unsigned char* yrow = yplane + (size_t)y * OUT_W;
        int x = 0;
#ifdef HAVE_NEON
        /* 8 piksel/iter: 16 UYVY baytini yukle (U0 Y0 V0 Y1 U2 Y2 V2 Y3 ...) */
        for (; x + 8 <= OUT_W; x += 8) {
            uint8x16_t v = vld1q_u8(srow + (size_t)x * 2);
            /* UYVY'de Y'ler tek indislerde (1,3,5,...), U/V cift indislerde */
            uint8x8x2_t de = vuzp_u8(vget_low_u8(v), vget_high_u8(v));
            /* de.val[0] = U0 V0 U2 V2 ... (cift konumlar: kroma)
             * de.val[1] = Y0 Y1 Y2 Y3 ... (tek konumlar: luma) */
            vst1_u8(yrow + x, de.val[1]);
        }
#endif
        for (; x < OUT_W; x++) {
            yrow[x] = srow[(size_t)x * 2 + 1];   /* Y tek indiste */
        }
        /* UV: yalniz cift satirlarda (4:2:0 dikey alt-ornekleme) */
        if ((y & 1) == 0) {
            unsigned char* uvrow = uvplane + (size_t)(y >> 1) * OUT_W; /* (OUT_W/2)*2 = OUT_W */
            for (int xx = 0; xx < OUT_W; xx += 2) {
                /* makropiksel baslangici: UYVY'de her 4 bayt = 2 piksel */
                const unsigned char* mp = srow + (size_t)xx * 2;
                uvrow[xx]     = mp[0];            /* U */
                uvrow[xx + 1] = mp[2];            /* V */
            }
        }
    }
}

static void broadcast_frames(void) {
    static unsigned char* frame_buf = NULL;
    static unsigned char* nv12_buf = NULL;
    if (!frame_buf) frame_buf = malloc(FRAME_SIZE);
    if (!nv12_buf) nv12_buf = malloc(NV12_SIZE);
    if (!frame_buf || !nv12_buf) return;
    struct stat st;
    if (stat(snapfile, &st) != 0 || st.st_size != FRAME_SIZE) return;
    FILE* f = fopen(snapfile, "rb");
    if (!f) return;
    size_t got = fread(frame_buf, 1, FRAME_SIZE, f);
    fclose(f);
    if (got != FRAME_SIZE) return;
    /* v10: UYVY'yi burada (cihaz tarafinda) NV12'ye cevir ve TCP'de NV12 yolla.
     * Kazanc: uygulamada Java toNv12 kalkar + TCP yuku 3.9MB -> 1.8MB (hem
     * format hem 1280x960 kirpma). snap dosyasi UYVY kalir (ADB/dosya fallback
     * ve nextFrame yolu icin). Baslik len=NV12_SIZE; uygulama bu boyuttan
     * NV12 oldugunu anlar (eski len=FRAME_SIZE UYVY ile geriye uyumlu). */
    uyvy_to_nv12(frame_buf, nv12_buf);
    pthread_mutex_lock(&clients_lock);
    for (struct client* c = clients; c; c = c->next) {
        if (!c->alive) continue;
        unsigned char hdr[16];
        memcpy(hdr, "FRME", 4);
        *(int*)(hdr + 4) = g_cam;
        *(int*)(hdr + 8) = (int)g_counter;
        *(int*)(hdr + 12) = (int)NV12_SIZE;
        ssize_t w1 = send(c->fd, hdr, 16, MSG_NOSIGNAL);
        ssize_t w2 = w1 == 16 ? send(c->fd, nv12_buf, NV12_SIZE, MSG_NOSIGNAL) : -1;
        if (w1 != 16 || w2 != NV12_SIZE) { c->alive = 0; shutdown(c->fd, SHUT_RDWR); }
    }
    pthread_mutex_unlock(&clients_lock);
}

int main(int argc, char** argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { printf("kullanim: qcam_loop8 <kamera_id>\n"); return 1; }
    g_cam = atoi(argv[1]);
    if (g_cam < 0 || g_cam > 15) { printf("gecersiz kamera\n"); return 1; }

    snprintf(pidfile, sizeof(pidfile), DIRPATH "/qcam_loop_%d.pid", g_cam);
    snprintf(verfile, sizeof(verfile), DIRPATH "/qcam_loop_%d.ver", g_cam);
    snprintf(demandfile, sizeof(demandfile), DIRPATH "/cam_demand_%d", g_cam);
    snprintf(snapfile, sizeof(snapfile), DIRPATH "/snap_%d.raw", g_cam);

    FILE* pf = fopen(pidfile, "r");
    if (pf) {
        int old = 0;
        if (fscanf(pf, "%d", &old) == 1 && old > 0 && old != getpid() && kill(old, 0) == 0) {
            printf("kamera %d zaten calisiyor pid=%d\n", g_cam, old);
            fclose(pf);
            return 0;
        }
        fclose(pf);
    }
    {   /* Bu kameranin yamali araci kurulu mu? Sufikssiz "qcam_patched" DEGIL —
         * uygulama yalnizca qcam_patched_c0..c3 kurar (CameraEngine.installHelpers). */
        char exe[96];
        snprintf(exe, sizeof(exe), DIRPATH "/qcam_patched_c%d", g_cam);
        if (access(exe, X_OK) != 0) { printf("qcam_patched_c%d yok\n", g_cam); return 2; }
    }
    {  /* yetim temizligi: onceki oturumdan kalan bu kamera araclari olsun */
        char cmdk[128];
        snprintf(cmdk, sizeof(cmdk), "pkill -9 -x qcam_patched_%d 2>/dev/null; pkill -9 -x qcam_patched_c%d 2>/dev/null; true", g_cam, g_cam);
        int rc2 = system(cmdk); (void)rc2;
    }

    signal(SIGTERM, on_term);
    signal(SIGINT, on_term);
    signal(SIGPIPE, SIG_IGN);

    if (write_config(g_cam) != 0) { printf("config yazilamadi\n"); return 3; }

    int listen_fd = -1;
    /* Saf IPv4 127.0.0.1 — cekirdek v6-esleme kabul etmiyordu; uygulama da
       artik acik 127.0.0.1 kullaniyor */
    listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd >= 0) {
        int one = 1;
        setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        fcntl(listen_fd, F_SETFD, FD_CLOEXEC);
        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        addr.sin_port = htons(BASE_PORT + g_cam);
        if (bind(listen_fd, (struct sockaddr*)&addr, sizeof(addr)) == 0 && listen(listen_fd, 4) == 0)
            printf("tcp: 127.0.0.1:%d acik (teshis)\n", BASE_PORT + g_cam);
        else { close(listen_fd); listen_fd = -1; }
    }

    pf = fopen(pidfile, "w");
    if (pf) { fprintf(pf, "%d\n", (int)getpid()); fclose(pf); }
    pf = fopen(verfile, "w");
    if (pf) { fprintf(pf, "%d\n", QCAM_VERSION); fclose(pf); }
    printf("qcam_loop8 pid=%d kamera=%d surum=%d\n", (int)getpid(), g_cam, QCAM_VERSION);

    pthread_t acceptor = 0;
    if (listen_fd >= 0) { pthread_create(&acceptor, NULL, acceptor_thread, &listen_fd); pthread_detach(acceptor); }

    int tool_ok = spawn_tool();
    printf(tool_ok ? "hazir: kamera %d akiyor\n" : "uyari: ilk kare alinamadi, yeniden denenecek\n", g_cam);
    move_snaps();

    time_t last_dump = time(NULL);
    long loops = 0, respawns = 0;
    time_t demand_last_fresh = time(NULL);
    /* fps: QDF (her N'inci kare, 30/N fps). Öntanımlı N=3 → 10 fps. */
    const char* dfenv = getenv("QDF");
    if (dfenv) { int v = atoi(dfenv); if (v >= 1 && v <= 30) g_dumpframe = v; }
    const char* fpsenv = getenv("QFPS");
    long fps_cap = 30 / g_dumpframe; if (fps_cap < 1) fps_cap = 1;
    if (fpsenv) { long v = atol(fpsenv); if (v >= 1 && v <= 30) fps_cap = v; }
    g_min_us = 1000000L / fps_cap;
    printf("mod: otonom döküm (-%d. kare) hedef %.0f fps\n", g_dumpframe, 1000000.0 / g_min_us);
    while (running) {
        usleep(30000); /* 30 ms döngü */
        int demanded = demand_fresh();
        if (demanded) demand_last_fresh = time(NULL);

        /* Talep yönetimi: istek varken çocuk yaşar (otonom döker); istek 15 sn
         * süredir yoksa çocuğu öldür (disk yazmasın). İstek dönünce yeniden doğar. */
        if (demanded) {
            int wst; pid_t wd = waitpid(child, &wst, WNOHANG);
            if (child <= 0 || wd == child) {
                if (++respawns > 10) { printf("hizli yeniden dogus yavaslatiliyor\n"); sleep(10); respawns = 5; }
                kill_child();
                tool_ok = spawn_tool();
                last_dump = time(NULL);
                continue;
            }
        } else if (child > 0 && time(NULL) - demand_last_fresh > 25) {
            printf("talep yok → araç durduruluyor (disk korunur)\n");
            kill_child();
            continue;
        }

        /* Çocuk yaşıyor: dosyalar kendiliğinden birikir → taşı + yayınla */
        if (child > 0 && tool_ok) {
            int moved = move_snaps();
            if (moved > 0) { g_counter += moved; last_dump = time(NULL); broadcast_frames(); }
            else if (time(NULL) - last_dump > 30) {
                printf("30 sn döküm yok -> oturum yenileniyor\n");
                kill_child(); tool_ok = spawn_tool(); last_dump = time(NULL);
            }
        }
        if (++loops % 300 == 0) printf("dongu %ld tasi %ld\n", loops, g_counter);
    }

    printf("kapanis\n");
    kill_child();
    unlink(pidfile);
    return 0;
}
