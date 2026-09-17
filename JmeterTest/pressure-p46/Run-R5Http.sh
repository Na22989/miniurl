#!/bin/bash
# Run-R5Http.sh — 场景 R5 的 HTTP 端到端部分（创建接口并发正确性）
#
# 定位：**这不是生成器吞吐测试**。创建接口前面挂着全局令牌桶（100/s，桶容量 100）
# 和用户滑动窗口（50 次/60s），HTTP 层压出来的上限是限流器的上限，不是 Snowflake 的。
# 生成器本身的并发唯一性与吞吐请跑 IdGenBench.java（JVM 内，脱离 HTTP）。
#
# 本脚本要回答的是三个「端到端」问题：
#   ① 被限流拒绝的请求是不是干净地返回 43100（而不是 500 / 连接重置）；
#   ② 返回成功（20000）的请求，是不是每一条都在 DB 里落了一行、且 short_code 无重复；
#   ③ 成功数是否与令牌桶容量+速率的理论值吻合（验证限流器行为，不是验证生成器）。
#
# 用法（Git Bash）：
#   bash Run-R5Http.sh <username> <并发数> <每线程请求数> <RUNID>
# 例：
#   bash Run-R5Http.sh pt_r5_0906 50 10 PT20260906-R5

set -u
BASE_URL="${BASE_URL:-http://localhost:9191}"
USERNAME="${1:?用法: bash Run-R5Http.sh <username> <并发数> <每线程请求数> <RUNID>}"
PAR="${2:-50}"
PER="${3:-10}"
RUNID="${4:-PT-R5}"
PASSWORD="${PT_PASSWORD:-pt_pass_123}"
OUT="r5_http_$(date +%H%M%S).txt"

curl -s -X POST "$BASE_URL/api/user/register" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"nickname\":\"$RUNID\"}" > /dev/null

TOKEN=$(curl -s -X POST "$BASE_URL/api/user/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
[ -z "$TOKEN" ] && { echo "登录失败"; exit 1; }

TOTAL=$((PAR * PER))
echo "== 并发 $PAR × 每线程 $PER = $TOTAL 次创建，RUNID=$RUNID"
export BASE_URL TOKEN RUNID

# 每个 worker 独立发一次创建，整行响应写入 $OUT。用 xargs -P 做并发，
# 每行输出很短（< 4KB），O_APPEND 下基本原子；跑完会校验行数，不足则说明有丢行。
: > "$OUT"
seq 1 "$TOTAL" | xargs -P "$PAR" -I{} sh -c '
  curl -s -X POST "$BASE_URL/api/link/create" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"longUrl\":\"https://example.com/pt/'"$RUNID"'/{}\"}"
  echo
' >> "$OUT"

LINES=$(grep -c . "$OUT")
OK=$(grep -c '"code":20000' "$OUT")
LIMITED=$(grep -c '"code":43100' "$OUT")
OTHER=$((LINES - OK - LIMITED))

echo
echo "======== R5 HTTP 结果 ========"
echo "响应行数      : $LINES / $TOTAL   ← 少于总数说明有请求没拿到响应体"
echo "成功 20000    : $OK"
echo "限流 43100    : $LIMITED"
echo "其他响应      : $OTHER            ← 判据：必须为 0（不能有 500 / 空响应）"
[ "$OTHER" -ne 0 ] && grep -v -e '"code":20000' -e '"code":43100' "$OUT" | head -5

echo
echo "DB 侧复核（Git Bash 里跑）："
echo "  MYSQL='/d/software/mysql8.0/mysql8/bin/mysql'"
echo "  \$MYSQL -uroot -proot -N -e \"select count(*), count(distinct short_code) from miniurl_db.link where long_url like 'https://example.com/pt/$RUNID/%';\""
echo "  ↑ 两个数必须相等，且等于上面的「成功 20000」= $OK"
echo "原始响应留档：$OUT"
