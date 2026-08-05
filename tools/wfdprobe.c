/*
 * wfdprobe —— wpa_supplicant 控制接口探针（一次性诊断工具，不属于 App）
 *
 * 用途：验证这台手机的 wpa_supplicant 能否被设置成 Wi-Fi Display Sink，
 * 也就是 Windows「连接到无线显示器」发现设备所依赖的 WFD IE 广播。
 * ROM 没有随包提供 wpa_cli，所以这里直接和控制 socket 对话。
 *
 * 协议：控制接口是 UNIX datagram socket，发纯文本命令、收纯文本回复。
 * 客户端自己的 socket 必须建在 wpa_supplicant 能写回的位置，所以放在
 * 它自己的 socket 目录下（继承 wifi_data_file 的 SELinux 标签），
 * 并 chown 给 wifi 用户。
 *
 * 编译（NDK）：
 *   aarch64-linux-android21-clang wfdprobe.c -o wfdprobe
 * 用法（需 root）：
 *   ./wfdprobe <ctrl_socket> [命令...]
 *   ./wfdprobe /data/misc/wifi/sockets/p2p0 PING "SET wifi_display 1"
 */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define WIFI_UID 1010 /* AID_WIFI */

static int ctrl_fd = -1;
static char local_path[108];

static int ctrl_open(const char *server_path)
{
    struct sockaddr_un local, dest;
    char *dir_end;
    char dir[108];

    ctrl_fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (ctrl_fd < 0) {
        fprintf(stderr, "socket() 失败: %s\n", strerror(errno));
        return -1;
    }

    /* 客户端 socket 与服务端放在同一目录，才能拿到 wpa_supplicant 可写的 SELinux 标签 */
    snprintf(dir, sizeof(dir), "%s", server_path);
    dir_end = strrchr(dir, '/');
    if (dir_end)
        *dir_end = '\0';
    snprintf(local_path, sizeof(local_path), "%s/wfdprobe_%d", dir, (int) getpid());

    memset(&local, 0, sizeof(local));
    local.sun_family = AF_UNIX;
    snprintf(local.sun_path, sizeof(local.sun_path), "%s", local_path);
    unlink(local_path);
    if (bind(ctrl_fd, (struct sockaddr *) &local, sizeof(local)) < 0) {
        fprintf(stderr, "bind(%s) 失败: %s\n", local_path, strerror(errno));
        return -1;
    }
    /* wpa_supplicant 以 wifi 用户运行，回包要能写进来 */
    if (chown(local_path, WIFI_UID, WIFI_UID) < 0)
        fprintf(stderr, "警告: chown 失败: %s\n", strerror(errno));
    if (chmod(local_path, 0770) < 0)
        fprintf(stderr, "警告: chmod 失败: %s\n", strerror(errno));

    memset(&dest, 0, sizeof(dest));
    dest.sun_family = AF_UNIX;
    snprintf(dest.sun_path, sizeof(dest.sun_path), "%s", server_path);
    if (connect(ctrl_fd, (struct sockaddr *) &dest, sizeof(dest)) < 0) {
        fprintf(stderr, "connect(%s) 失败: %s\n", server_path, strerror(errno));
        return -1;
    }
    return 0;
}

static void ctrl_close(void)
{
    if (ctrl_fd >= 0)
        close(ctrl_fd);
    if (local_path[0])
        unlink(local_path);
}

static int ctrl_request(const char *cmd)
{
    char reply[4096];
    struct timeval tv;
    ssize_t n;

    printf("→ %s\n", cmd);
    if (send(ctrl_fd, cmd, strlen(cmd), 0) < 0) {
        fprintf(stderr, "← send 失败: %s\n\n", strerror(errno));
        return -1;
    }

    tv.tv_sec = 3;
    tv.tv_usec = 0;
    setsockopt(ctrl_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    n = recv(ctrl_fd, reply, sizeof(reply) - 1, 0);
    if (n < 0) {
        fprintf(stderr, "← recv 失败: %s\n\n", strerror(errno));
        return -1;
    }
    reply[n] = '\0';
    printf("← %s\n", reply);
    if (n == 0 || reply[n - 1] != '\n')
        printf("\n");
    return 0;
}

int main(int argc, char *argv[])
{
    int i, rc = 0;

    if (argc < 3) {
        fprintf(stderr, "用法: %s <ctrl_socket> <命令> [命令...]\n", argv[0]);
        return 2;
    }

    if (ctrl_open(argv[1]) < 0) {
        ctrl_close();
        return 1;
    }
    printf("已连接: %s (本地 %s)\n\n", argv[1], local_path);

    for (i = 2; i < argc; i++) {
        if (ctrl_request(argv[i]) < 0)
            rc = 1;
    }

    ctrl_close();
    return rc;
}
