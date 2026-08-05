#!/system/bin/sh
# wfdrun.sh —— 自动等待 Windows 入组后发起 WFD 握手（一次性诊断脚本）
#
# 轮询 p2p0 的邻居表，一旦发现活跃的客户端就对它跑 wfdsink。
# 这样不用掐时间窗口，可以先启动脚本，再慢慢从 Windows 发起投屏。
#
# 用法（需 root）：sh /data/local/tmp/wfdrun.sh [总时长秒数]

DEADLINE=${1:-600}
START=$(date +%s)
LAST_IP=""

echo "等待 Windows 接入 P2P 组，最长 ${DEADLINE} 秒..."

while [ $(( $(date +%s) - START )) -lt "$DEADLINE" ]; do
    # 排除 FAILED / INCOMPLETE 的陈旧表项
    # 注意：这台设备的 toybox 没有 awk，只能用 cut 取第一列
    IP=$(ip neigh show dev p2p0 2>/dev/null \
         | grep -vE "FAILED|INCOMPLETE" \
         | cut -d' ' -f1 | head -1)

    if [ -n "$IP" ]; then
        if [ "$IP" != "$LAST_IP" ]; then
            echo ""
            echo "=== 发现客户端 $IP，开始 WFD 握手 ==="
            LAST_IP="$IP"
        fi
        # wfdsink 自身会重试连接；握手跑完（成功或对端关闭）就返回
        /data/local/tmp/wfdsink "$IP" 7236 19000
        echo "=== 本轮握手结束，继续等待下一次连接 ==="
        LAST_IP=""
    fi
    sleep 2
done

echo "等待超时结束"
