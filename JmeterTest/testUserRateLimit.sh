#!/bin/bash
# MiniURL 用户级滑动窗口限流测试脚本
# 用法: bash testUserRateLimit.sh
# 前置条件: 应用运行在 localhost:9191，Redis 运行中
# 说明: 每次运行注册一个全新用户（rl_时间戳），保证限流 key 从零开始，不受上次运行干扰

set +e

BASE="http://localhost:9191"
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'
PASS=0
FAIL=0

# 与 application.yml 的 rate-limit.user.max-requests 保持一致
MAX_REQUESTS=50

echo "=== 准备测试用户（全新用户，限流状态从零开始）==="
TEST_USER="rl_$(date +%s)"
curl -s -X POST "$BASE/api/user/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$TEST_USER\",\"password\":\"123456\"}" > /dev/null 2>&1 || true

TOKEN=$(curl -s -X POST "$BASE/api/user/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$TEST_USER\",\"password\":\"123456\"}" \
  | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)

if [ -z "$TOKEN" ] || [ "$TOKEN" = "None" ]; then
  echo -e " ${RED}FAIL${NC}  登录失败，无法获取 token（检查应用和 MySQL/Redis 是否运行）"
  exit 1
fi

echo " 用户: $TEST_USER"
echo " 限流阈值: max-requests=$MAX_REQUESTS"
echo ""

# ─── 测试 1: 前 MAX_REQUESTS 次请求全部放行 ───
echo "=== 测试 1: 前 $MAX_REQUESTS 次请求全部放行 ==="
REJECTED=0
for i in $(seq 1 $MAX_REQUESTS); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/link/create" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"longUrl":"https://example.com"}')
  if [ "$CODE" = "429" ]; then
    REJECTED=$((REJECTED + 1))
  fi
done

if [ "$REJECTED" = "0" ]; then
  echo -e " ${GREEN}PASS${NC}  全部放行，0 次被拒"
  ((PASS++))
else
  echo -e " ${RED}FAIL${NC}  期望 0 次被拒，实际 $REJECTED 次被拒（是否误用已有用户或阈值不一致？）"
  ((FAIL++))
fi

# ─── 测试 2: 第 MAX_REQUESTS+1 次请求被限流 (HTTP 429) ───
echo ""
echo "=== 测试 2: 第 $((MAX_REQUESTS+1)) 次请求被限流 ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/link/create" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"longUrl":"https://example.com"}')

if [ "$HTTP_CODE" = "429" ]; then
  echo -e " ${GREEN}PASS${NC}  正确返回 HTTP 429 Too Many Requests"
  ((PASS++))
else
  echo -e " ${RED}FAIL${NC}  期望 429，实际 $HTTP_CODE"
  ((FAIL++))
fi

# ─── 测试 3: 响应体包含限流错误码 43100 ───
echo ""
echo "=== 测试 3: 响应体 code=43100 (RATE_LIMIT) ==="
BODY=$(curl -s -X POST "$BASE/api/link/create" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"longUrl":"https://example.com"}')
echo " 响应: $BODY"
CODE_FIELD=$(echo "$BODY" | python -c "import sys,json; print(json.load(sys.stdin)['code'])" 2>/dev/null)

if [ "$CODE_FIELD" = "43100" ]; then
  echo -e " ${GREEN}PASS${NC}  code=43100 (RATE_LIMIT)"
  ((PASS++))
else
  echo -e " ${RED}FAIL${NC}  期望 code=43100，实际 $CODE_FIELD"
  ((FAIL++))
fi

# ─── 测试 4: Retry-After 响应头存在且 ≥ 1 ───
echo ""
echo "=== 测试 4: Retry-After 响应头存在 ==="
RETRY_AFTER=$(curl -s -D - -o /dev/null -X POST "$BASE/api/link/create" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"longUrl":"https://example.com"}' \
  | grep -i "retry-after" | awk '{print $2}' | tr -d '\r')

if [ -n "$RETRY_AFTER" ] && [ "$RETRY_AFTER" -ge 1 ] 2>/dev/null; then
  echo -e " ${GREEN}PASS${NC}  Retry-After: $RETRY_AFTER 秒"
  ((PASS++))
else
  echo -e " ${RED}FAIL${NC}  Retry-After 头缺失或无效"
  ((FAIL++))
fi

# ─── 结果汇总 ───
echo ""
echo "========================================="
echo -e "  通过: ${GREEN}$PASS${NC}  /  失败: ${RED}$FAIL${NC}  /  总计: $((PASS+FAIL))"
echo "========================================="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
