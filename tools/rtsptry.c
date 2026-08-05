/*
 * rtsptry —— 反向 RTSP 连接探针（一次性诊断工具，不属于 App）
 *
 * 用途：验证 Wi-Fi Display 里 RTSP 的 TCP 连接方向。
 * 如果 WFD Source（Windows）才是监听方，那么从 Sink（手机）主动连它的
 * 7236 端口应该能连上，并收到 Source 发来的 M1（OPTIONS）。
 *
 * 编译（NDK）：
 *   aarch64-linux-android21-clang rtsptry.c -o rtsptry
 * 用法：
 *   ./rtsptry <source_ip> <port> <重试秒数>
 *   ./rtsptry 192.168.49.163 7236 90
 */
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static void msleep(int ms)
{
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long) (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static void dump(const char *tag, const char *buf, int len)
{
    int i;
    printf("%s (%d 字节):\n", tag, len);
    for (i = 0; i < len; i++)
        putchar(buf[i] == '\r' ? ' ' : buf[i]);
    printf("\n----\n");
    fflush(stdout);
}

int main(int argc, char *argv[])
{
    const char *ip;
    int port, deadline_s, fd = -1, attempt = 0;
    struct sockaddr_in addr;
    time_t start;
    char buf[8192];
    struct timeval tv;
    ssize_t n;

    if (argc < 4) {
        fprintf(stderr, "用法: %s <source_ip> <port> <重试秒数>\n", argv[0]);
        return 2;
    }
    ip = argv[1];
    port = atoi(argv[2]);
    deadline_s = atoi(argv[3]);

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short) port);
    if (inet_pton(AF_INET, ip, &addr.sin_addr) != 1) {
        fprintf(stderr, "IP 无效: %s\n", ip);
        return 2;
    }

    printf("持续尝试连接 %s:%d，最多 %d 秒...\n", ip, port, deadline_s);
    fflush(stdout);

    start = time(NULL);
    while (time(NULL) - start < deadline_s) {
        attempt++;
        fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) {
            fprintf(stderr, "socket 失败: %s\n", strerror(errno));
            return 1;
        }
        tv.tv_sec = 2;
        tv.tv_usec = 0;
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        if (connect(fd, (struct sockaddr *) &addr, sizeof(addr)) == 0) {
            printf("\n*** 连接成功！第 %d 次尝试，耗时 %ld 秒 ***\n",
                   attempt, (long) (time(NULL) - start));
            printf("=> Source 才是 RTSP 监听方，Sink 必须主动发起连接\n\n");
            fflush(stdout);
            break;
        }
        close(fd);
        fd = -1;
        msleep(500);
    }

    if (fd < 0) {
        printf("\n始终连不上 %s:%d（尝试 %d 次）\n", ip, port, attempt);
        printf("=> 说明 Source 没有在这个端口监听，连接方向另有其他\n");
        return 1;
    }

    /* 连上了：先看 Source 会不会主动发 M1 (OPTIONS) */
    tv.tv_sec = 8;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    n = recv(fd, buf, sizeof(buf) - 1, 0);
    if (n > 0) {
        buf[n] = '\0';
        dump("收到 Source 主动发来的报文", buf, (int) n);
    } else {
        printf("8 秒内 Source 没有主动发报文（%s）\n\n",
               n == 0 ? "对端关闭" : strerror(errno));
    }

    /* 主动发一条 M2 OPTIONS，看 Source 是否按 RTSP 回应 */
    {
        const char *req =
            "OPTIONS * RTSP/1.0\r\n"
            "CSeq: 1\r\n"
            "Require: org.wfa.wfd1.0\r\n"
            "\r\n";
        printf("发送 M2: OPTIONS * RTSP/1.0\n");
        fflush(stdout);
        if (send(fd, req, strlen(req), 0) < 0) {
            fprintf(stderr, "send 失败: %s\n", strerror(errno));
            close(fd);
            return 1;
        }
        n = recv(fd, buf, sizeof(buf) - 1, 0);
        if (n > 0) {
            buf[n] = '\0';
            dump("Source 的回应", buf, (int) n);
        } else {
            printf("没有收到回应（%s）\n", n == 0 ? "对端关闭" : strerror(errno));
        }
    }

    close(fd);
    return 0;
}
