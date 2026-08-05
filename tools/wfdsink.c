/*
 * wfdsink —— Wi-Fi Display Sink 的 RTSP 握手探针（一次性诊断工具，不属于 App）
 *
 * 已经实测确认：WFD Source（Windows）才是 RTSP 监听方，Sink 必须主动连过去。
 * 本工具手工走完 M1–M7 全流程，目的是试出 Windows 能接受的能力集参数，
 * 再据此重写 app 里的 WfdServer / WfdSessionHandler。
 *
 * 消息序列：
 *   M1  Source → Sink   OPTIONS          本工具回 200 + Public
 *   M2  Sink   → Source OPTIONS          本工具主动发
 *   M3  Source → Sink   GET_PARAMETER    本工具回能力集
 *   M4  Source → Sink   SET_PARAMETER    选定参数，回 200
 *   M5  Source → Sink   SET_PARAMETER    wfd_trigger_method: SETUP，回 200
 *   M6  Sink   → Source SETUP            本工具主动发，带 client_port
 *   M7  Sink   → Source PLAY             本工具主动发
 *   之后 RTP 流入本地 UDP 端口
 *
 * 编译（NDK）：
 *   aarch64-linux-android21-clang wfdsink.c -o wfdsink
 * 用法：
 *   ./wfdsink <source_ip> [rtsp_port] [rtp_port] [video_formats]
 *   ./wfdsink 192.168.49.163 7236 19000
 */
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define BUFSZ 16384

static int rtsp_fd = -1;
static int rtp_fd = -1;
static int out_cseq = 0;               /* 我们主动发起的请求用的 CSeq */
static char session_id[128] = "";
static char presentation_url[512] = "";
static char rtp_port_str[16];

/* 可通过命令行覆盖的能力集 */
static const char *video_formats =
    "00 00 02 04 0001DEFF 00000000 00000000 00 0000 0000 00 none none";
static const char *audio_codecs = "AAC 00000001 00";

/* ─── 工具函数 ──────────────────────────────────────────────────────── */

static void msleep(int ms)
{
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long) (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static void show(const char *dir, const char *msg)
{
    const char *p;
    printf("%s ", dir);
    for (p = msg; *p; p++) {
        if (*p == '\r')
            continue;
        putchar(*p);
        if (*p == '\n' && *(p + 1) && *(p + 1) != '\r' && *(p + 1) != '\n')
            printf("%s ", dir);
    }
    printf("\n");
    fflush(stdout);
}

static int send_msg(const char *msg)
{
    show("<<", msg);
    if (send(rtsp_fd, msg, strlen(msg), 0) < 0) {
        fprintf(stderr, "send 失败: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

/* 读一条完整的 RTSP 消息（头部 + 按 Content-Length 读 body） */
static int read_msg(char *buf, int cap)
{
    int len = 0;
    char *hdr_end = NULL;
    int content_len = 0, header_len = 0;
    char *cl;

    while (len < cap - 1) {
        ssize_t n = recv(rtsp_fd, buf + len, cap - 1 - len, 0);
        if (n <= 0) {
            if (len == 0)
                return n == 0 ? 0 : -1;
            break;
        }
        len += (int) n;
        buf[len] = '\0';

        if (!hdr_end)
            hdr_end = strstr(buf, "\r\n\r\n");
        if (!hdr_end)
            continue;

        header_len = (int) (hdr_end - buf) + 4;
        cl = strcasestr(buf, "Content-Length:");
        content_len = cl ? atoi(cl + 15) : 0;
        if (len >= header_len + content_len)
            break;
    }
    buf[len] = '\0';
    show(">>", buf);
    return len;
}

static int get_cseq(const char *msg)
{
    const char *p = strcasestr(msg, "CSeq:");
    return p ? atoi(p + 5) : 0;
}

/*
 * 从消息里抽取某个头部/参数的值，写入 out。
 * stop_at_space 用于 wfd_presentation_URL —— 它带两个值（URL0 URL1），
 * 例如 "rtsp://.../streamid=0 none"，只能取第一个，否则拼出来的
 * SETUP 请求行会多一段，Windows 会当成畸形请求直接断链。
 */
static int extract2(const char *msg, const char *key, char *out, int cap,
                    int stop_at_space)
{
    const char *p = strcasestr(msg, key);
    const char *end;
    int n;

    if (!p)
        return 0;
    p += strlen(key);
    while (*p == ' ' || *p == ':')
        p++;
    end = p;
    while (*end && *end != '\r' && *end != '\n' && *end != ';' &&
           !(stop_at_space && *end == ' '))
        end++;
    n = (int) (end - p);
    if (n >= cap)
        n = cap - 1;
    memcpy(out, p, n);
    out[n] = '\0';
    return 1;
}

static int extract(const char *msg, const char *key, char *out, int cap)
{
    return extract2(msg, key, out, cap, 0);
}

/* ─── M1 / M3 / M4 / M5：响应 Source 发来的请求 ──────────────────────── */

static void respond_options(int cseq)
{
    char msg[512];
    snprintf(msg, sizeof(msg),
             "RTSP/1.0 200 OK\r\n"
             "CSeq: %d\r\n"
             "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n"
             "\r\n",
             cseq);
    send_msg(msg);
}

static void respond_get_parameter(int cseq, const char *req)
{
    char body[2048] = "";
    char msg[4096];

    /* Source 问什么就答什么 —— 少答一项都可能导致它直接放弃 */
    if (strstr(req, "wfd_video_formats")) {
        strcat(body, "wfd_video_formats: ");
        strcat(body, video_formats);
        strcat(body, "\r\n");
    }
    if (strstr(req, "wfd_audio_codecs")) {
        strcat(body, "wfd_audio_codecs: ");
        strcat(body, audio_codecs);
        strcat(body, "\r\n");
    }
    if (strstr(req, "wfd_client_rtp_ports")) {
        /* 关键：告诉 Source 往哪个 UDP 端口发 RTP */
        strcat(body, "wfd_client_rtp_ports: RTP/AVP/UDP;unicast ");
        strcat(body, rtp_port_str);
        strcat(body, " 0 mode=play\r\n");
    }
    if (strstr(req, "wfd_content_protection"))
        strcat(body, "wfd_content_protection: none\r\n");
    if (strstr(req, "wfd_display_edid"))
        strcat(body, "wfd_display_edid: none\r\n");
    if (strstr(req, "wfd_coupled_sink"))
        strcat(body, "wfd_coupled_sink: none\r\n");
    if (strstr(req, "wfd_uibc_capability"))
        strcat(body, "wfd_uibc_capability: none\r\n");
    if (strstr(req, "wfd_connector_type"))
        strcat(body, "wfd_connector_type: 05\r\n");
    if (strstr(req, "wfd_standby_resume_capability"))
        strcat(body, "wfd_standby_resume_capability: none\r\n");
    if (strstr(req, "wfd_3d_video_formats"))
        strcat(body, "wfd_3d_video_formats: none\r\n");

    snprintf(msg, sizeof(msg),
             "RTSP/1.0 200 OK\r\n"
             "CSeq: %d\r\n"
             "Content-Type: text/parameters\r\n"
             "Content-Length: %d\r\n"
             "\r\n"
             "%s",
             cseq, (int) strlen(body), body);
    send_msg(msg);
}

static void respond_ok(int cseq)
{
    char msg[256];
    snprintf(msg, sizeof(msg), "RTSP/1.0 200 OK\r\nCSeq: %d\r\n\r\n", cseq);
    send_msg(msg);
}

/* ─── M2 / M6 / M7：我们主动发起的请求 ───────────────────────────────── */

static void send_options(void)
{
    char msg[512];
    snprintf(msg, sizeof(msg),
             "OPTIONS * RTSP/1.0\r\n"
             "CSeq: %d\r\n"
             "Require: org.wfa.wfd1.0\r\n"
             "\r\n",
             ++out_cseq);
    send_msg(msg);
}

static void send_setup(void)
{
    char msg[1024];
    snprintf(msg, sizeof(msg),
             "SETUP %s RTSP/1.0\r\n"
             "CSeq: %d\r\n"
             "Transport: RTP/AVP/UDP;unicast;client_port=%s\r\n"
             "\r\n",
             presentation_url, ++out_cseq, rtp_port_str);
    send_msg(msg);
}

static void send_play(void)
{
    char msg[1024];
    snprintf(msg, sizeof(msg),
             "PLAY %s RTSP/1.0\r\n"
             "CSeq: %d\r\n"
             "Session: %s\r\n"
             "\r\n",
             presentation_url, ++out_cseq, session_id);
    send_msg(msg);
}

/* ─── RTP 接收统计 ──────────────────────────────────────────────────── */

static int open_rtp(int port)
{
    struct sockaddr_in a;
    rtp_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (rtp_fd < 0)
        return -1;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    a.sin_port = htons((unsigned short) port);
    if (bind(rtp_fd, (struct sockaddr *) &a, sizeof(a)) < 0) {
        fprintf(stderr, "RTP 端口 %d 绑定失败: %s\n", port, strerror(errno));
        return -1;
    }
    printf("[RTP] 已监听 UDP %d\n\n", port);
    return 0;
}

static void watch_rtp(int seconds)
{
    char buf[2048];
    time_t start = time(NULL);
    long packets = 0, bytes = 0;
    struct timeval tv;

    tv.tv_sec = 1;
    tv.tv_usec = 0;
    setsockopt(rtp_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    printf("\n[RTP] 开始统计 %d 秒...\n", seconds);
    fflush(stdout);

    while (time(NULL) - start < seconds) {
        ssize_t n = recv(rtp_fd, buf, sizeof(buf), 0);
        if (n > 0) {
            packets++;
            bytes += n;
            if (packets == 1) {
                printf("\n*** 收到第一个 RTP 包！%d 字节，负载类型 0x%02x ***\n",
                       (int) n, (unsigned char) buf[1] & 0x7f);
                fflush(stdout);
            }
        }
    }
    printf("\n[RTP] %d 秒内收到 %ld 个包，共 %ld 字节（%.1f kbps）\n",
           seconds, packets, bytes, bytes * 8.0 / seconds / 1000.0);
}

/* ─── 主流程 ────────────────────────────────────────────────────────── */

int main(int argc, char *argv[])
{
    const char *ip;
    int rtsp_port = 7236, rtp_port = 19000;
    struct sockaddr_in addr;
    char buf[BUFSZ];
    time_t start;
    int attempt = 0, played = 0;
    struct timeval tv;

    if (argc < 2) {
        fprintf(stderr,
                "用法: %s <source_ip> [rtsp_port] [rtp_port] [video_formats]\n",
                argv[0]);
        return 2;
    }
    ip = argv[1];
    if (argc > 2)
        rtsp_port = atoi(argv[2]);
    if (argc > 3)
        rtp_port = atoi(argv[3]);
    if (argc > 4)
        video_formats = argv[4];
    snprintf(rtp_port_str, sizeof(rtp_port_str), "%d", rtp_port);
    snprintf(presentation_url, sizeof(presentation_url),
             "rtsp://%s/wfd1.0/streamid=0", ip);

    if (open_rtp(rtp_port) < 0)
        return 1;

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short) rtsp_port);
    inet_pton(AF_INET, ip, &addr.sin_addr);

    printf("等待并连接 Source %s:%d ...\n", ip, rtsp_port);
    fflush(stdout);
    start = time(NULL);
    while (time(NULL) - start < 90) {
        attempt++;
        rtsp_fd = socket(AF_INET, SOCK_STREAM, 0);
        if (connect(rtsp_fd, (struct sockaddr *) &addr, sizeof(addr)) == 0) {
            printf("已连接（第 %d 次尝试）\n\n", attempt);
            break;
        }
        close(rtsp_fd);
        rtsp_fd = -1;
        msleep(500);
    }
    if (rtsp_fd < 0) {
        printf("连不上 Source，先在 Windows 上发起投屏再运行本工具\n");
        return 1;
    }

    tv.tv_sec = 30;
    tv.tv_usec = 0;
    setsockopt(rtsp_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    /* RTSP 主循环：请求就响应，响应就推进状态机 */
    for (;;) {
        int n = read_msg(buf, sizeof(buf));
        if (n <= 0) {
            printf("\n连接结束（%s）\n", n == 0 ? "对端关闭" : strerror(errno));
            break;
        }

        if (strncmp(buf, "RTSP/1.0", 8) == 0) {
            /* 这是对我们请求的响应 */
            if (strstr(buf, "Session:") && session_id[0] == '\0') {
                extract(buf, "Session:", session_id, sizeof(session_id));
                printf("[会话] Session = %s，发送 M7 PLAY\n\n", session_id);
                send_play();
                played = 1;
            } else if (played) {
                printf("[会话] PLAY 已确认，等待 RTP\n");
                watch_rtp(15);
                break;
            }
            continue;
        }

        /* 这是 Source 发来的请求 */
        {
            int cseq = get_cseq(buf);
            if (strncmp(buf, "OPTIONS", 7) == 0) {
                respond_options(cseq);
                printf("[流程] M1 已响应，发送 M2\n\n");
                send_options();
            } else if (strncmp(buf, "GET_PARAMETER", 13) == 0) {
                if (strstr(buf, "wfd_")) {
                    printf("[流程] M3 能力查询\n");
                    respond_get_parameter(cseq, buf);
                } else {
                    respond_ok(cseq); /* keep-alive */
                }
            } else if (strncmp(buf, "SET_PARAMETER", 13) == 0) {
                char url[512];
                if (extract2(buf, "wfd_presentation_URL:", url, sizeof(url), 1) &&
                    strncmp(url, "rtsp://", 7) == 0) {
                    snprintf(presentation_url, sizeof(presentation_url), "%s", url);
                    printf("[流程] M4 拿到 presentation URL: %s\n", presentation_url);
                }
                respond_ok(cseq);
                if (strstr(buf, "wfd_trigger_method: SETUP")) {
                    printf("[流程] M5 收到 SETUP 触发，发送 M6\n\n");
                    send_setup();
                }
            } else {
                respond_ok(cseq);
            }
        }
    }

    if (rtsp_fd >= 0)
        close(rtsp_fd);
    if (rtp_fd >= 0)
        close(rtp_fd);
    return 0;
}
