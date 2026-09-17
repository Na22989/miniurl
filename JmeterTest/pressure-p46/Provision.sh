#!/bin/bash
# Provision.sh — 压测造数：为某个实验注册专属用户并批量创建带 run 标记的短链
#
# 为什么每个实验一个用户：link 表不能改结构，user_id 是唯一可用的归属维度。
# 每条链接同时带第二个独立维度——long_url 里嵌 run 标记 https://example.com/pt/<RUNID>/<i>，
# 两个维度互为校验，事后可审计「这条数据属于哪次实验」。
#
# 造数必须在「限流全关」的临时实例上跑，否则用户滑动窗口 50 次/60s 会把造数拖成几十分钟：
#   java -jar target/miniurl-0.0.1-SNAPSHOT.jar \
#        --spring.data.redis.database=9 \
#        --rate-limit.enabled=false --rate-limit.user.enabled=false --rate-limit.global.enabled=false
#
# 用法（Git Bash）：
#   bash Provision.sh <RUNID> <username> <count> <out.csv> [expire_seconds]
# 例：
#   bash Provision.sh PT20260906-R4 pt_r4_0906 40 r4_victims.csv
#   bash Provision.sh PT20260906-R6 pt_r6_0906 10 r6_expiring.csv 180

set -u

BASE_URL="${BASE_URL:-http://localhost:9191}"
RUNID="${1:?用法: bash Provision.sh <RUNID> <username> <count> <out.csv> [expire_seconds]}"
USERNAME="${2:?缺少 username（4-20 字符）}"
COUNT="${3:?缺少 count}"
OUTCSV="${4:?缺少 out.csv}"
EXPIRE_SECONDS="${5:-}"
PASSWORD="${PT_PASSWORD:-pt_pass_123}"

echo "== 1/4 注册用户 $USERNAME（已存在会返回 41100，忽略）"
curl -s -X POST "$BASE_URL/api/user/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"nickname\":\"$RUNID\"}" | head -c 300
echo

echo "== 2/4 登录取 accessToken"
LOGIN_RESP=$(curl -s -X POST "$BASE_URL/api/user/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN_RESP" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
if [ -z "$TOKEN" ]; then
  echo "登录失败，响应：$LOGIN_RESP"
  exit 1
fi
echo "token 前 20 位：${TOKEN:0:20}..."

# 过期时间用「本地字面量」ISO 格式，不带时区偏移：后端字段是 LocalDateTime，
# 传 UTC 的 Z 结尾字符串会被按本地时间解析而整体偏 8 小时
EXPIRE_JSON=""
if [ -n "$EXPIRE_SECONDS" ]; then
  EXPIRE_AT=$(date -d "+${EXPIRE_SECONDS} seconds" +%Y-%m-%dT%H:%M:%S)
  EXPIRE_JSON=",\"expireTime\":\"$EXPIRE_AT\""
  echo "== 过期时间统一设为 $EXPIRE_AT"
fi

echo "== 3/4 创建 $COUNT 条短链，run 标记 $RUNID"
echo "shortCode" > "$OUTCSV"
OK=0
FAIL=0
for i in $(seq 1 "$COUNT"); do
  RESP=$(curl -s -X POST "$BASE_URL/api/link/create" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"longUrl\":\"https://example.com/pt/$RUNID/$i\"$EXPIRE_JSON}")
  CODE=$(echo "$RESP" | grep -o '"shortCode":"[^"]*"' | cut -d'"' -f4)
  if [ -n "$CODE" ]; then
    echo "$CODE" >> "$OUTCSV"
    OK=$((OK + 1))
  else
    FAIL=$((FAIL + 1))
    echo "  [失败 $i] $RESP"
  fi
  if [ $((i % 50)) -eq 0 ]; then echo "  [$i/$COUNT] ok=$OK fail=$FAIL"; fi
done

echo "== 4/4 完成：成功 $OK，失败 $FAIL，输出 $OUTCSV"
echo "审计口令（MySQL）："
echo "  select count(*) from miniurl_db.link l join miniurl_db.user u on u.id=l.user_id where u.username='$USERNAME';"
echo "  select count(*) from miniurl_db.link where long_url like 'https://example.com/pt/$RUNID/%';"
