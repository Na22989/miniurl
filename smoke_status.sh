#!/bin/bash
# Day 5 冒烟测试：访问统计接口 GET /api/link/status/{linkId}（PV/UV/趋势）
# 用法: bash smoke_status.sh            # 默认连 localhost:9191
#       BASE=http://localhost:19191 bash smoke_status.sh   # 覆盖基址（CI self-hosted 隔离端口）
# 前置: 应用运行在 ${BASE:-localhost:9191}，MySQL + Redis 已启动
#
# 覆盖：正常统计（含 clickCount 30s 落库）/ 越权 42100 / 不存在 42100 / linkId<=0 40000 / 未登录 40100

set +e
BASE="${BASE:-http://localhost:9191}"
PASS=0; FAIL=0
RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'

# python 兼容：GitHub Actions runner 只有 python3；本地开发机可能是 python
if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo -e "${RED}未找到 python / python3，冒烟脚本需要其一解析 JSON${NC}" >&2
  exit 1
fi

# 从 stdin 读 JSON，按点分路径取值（如 data.pv）
json_get() {
  $PYTHON -c "
import sys, json
try:
    obj = json.load(sys.stdin)
except Exception:
    print('PARSE_ERROR'); sys.exit(0)
for k in '$1'.split('.'):
    if not isinstance(obj, dict): obj = None; break
    obj = obj.get(k)
    if obj is None: break
print(obj if obj is not None else '')
"
}

# 数组长度（非数组返回空）
json_len() {
  $PYTHON -c "
import sys, json
obj = json.load(sys.stdin)
v = obj
for k in '$1'.split('.'):
    v = v.get(k) if isinstance(v, dict) else None
    if v is None: break
print(len(v) if isinstance(v, list) else '')
"
}

assert_eq() {  # $1=描述 $2=实际 $3=期望
  if [ "$2" = "$3" ]; then echo -e " ${GREEN}PASS${NC}  $1 → $2"; ((PASS++));
  else echo -e " ${RED}FAIL${NC}  $1 → 期望 $3, 实际 $2"; ((FAIL++)); fi
}
assert_ge() {  # $1=描述 $2=实际 $3=下界
  if [ "$2" -ge "$3" ] 2>/dev/null; then echo -e " ${GREEN}PASS${NC}  $1 → $2 (≥$3)"; ((PASS++));
  else echo -e " ${RED}FAIL${NC}  $1 → $2, 期望 ≥$3"; ((FAIL++)); fi
}

# ─── 准备：两个测试用户（重复跑 register 报"已存在"无害） ───
curl -s -X POST "$BASE/api/user/register" -H "Content-Type: application/json" \
  -d '{"username":"stats_a","password":"123456"}' > /dev/null
curl -s -X POST "$BASE/api/user/register" -H "Content-Type: application/json" \
  -d '{"username":"stats_b","password":"123456"}' > /dev/null

TOKEN_A=$(curl -s -X POST "$BASE/api/user/login" -H "Content-Type: application/json" \
  -d '{"username":"stats_a","password":"123456"}' | json_get data.accessToken)
TOKEN_B=$(curl -s -X POST "$BASE/api/user/login" -H "Content-Type: application/json" \
  -d '{"username":"stats_b","password":"123456"}' | json_get data.accessToken)

if [ -z "$TOKEN_A" ] || [ -z "$TOKEN_B" ]; then
  echo -e "${RED}登录失败，取不到 accessToken${NC}"
  exit 1
fi
echo " stats_a / stats_b 登录成功，token 已就绪"

# ─── 创建短链 ───
CREATE=$(curl -s -X POST "$BASE/api/link/create" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{"longUrl":"https://www.baidu.com"}')
ID=$(echo "$CREATE" | json_get data.id)
CODE=$(echo "$CREATE" | json_get data.shortCode)
echo " 创建短链 id=$ID shortCode=$CODE"

if [ -z "$ID" ] || [ -z "$CODE" ]; then
  echo -e "${RED}创建短链失败：$CREATE${NC}"
  exit 1
fi

# ─── 重定向 3 次（生成访问日志 + Redis 计数） ───
echo "=== 重定向 3 次 ==="
for i in 1 2 3; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/s/$CODE")
  echo "  redirect#$i → HTTP $HTTP_CODE"
done

echo "=== 等 33s（异步日志落库 + clickCount 30s 定时同步） ==="
sleep 33

# ─── 正常统计 ───
echo "=== TC-01: 自己的短链 → 正常返回完整统计 ==="
R=$(curl -s "$BASE/api/link/status/$ID" -H "Authorization: Bearer $TOKEN_A")
echo "$R" | $PYTHON -m json.tool 2>/dev/null || echo "$R"
assert_eq "code" "$(echo "$R" | json_get code)" "20000"
assert_eq "linkId" "$(echo "$R" | json_get data.linkId)" "$ID"
assert_eq "shortCode" "$(echo "$R" | json_get data.shortCode)" "$CODE"
assert_ge "pv(总点击)" "$(echo "$R" | json_get data.pv)" 3
assert_ge "uv(总独立访客)" "$(echo "$R" | json_get data.uv)" 1
assert_ge "todayPv" "$(echo "$R" | json_get data.todayPv)" 3
assert_ge "todayUv" "$(echo "$R" | json_get data.todayUv)" 1
assert_ge "hourlyTrend 点数" "$(echo "$R" | json_len data.hourlyTrend)" 1

echo ""
echo "=== TC-02: 别人的短链 → 越权 LINK_NOT_FOUND ==="
R=$(curl -s "$BASE/api/link/status/$ID" -H "Authorization: Bearer $TOKEN_B")
assert_eq "code" "$(echo "$R" | json_get code)" "42100"

echo ""
echo "=== TC-03: 不存在的 linkId → LINK_NOT_FOUND ==="
R=$(curl -s "$BASE/api/link/status/99999999999999" -H "Authorization: Bearer $TOKEN_A")
assert_eq "code" "$(echo "$R" | json_get code)" "42100"

echo ""
echo "=== TC-04: linkId<=0 → BAD_REQUEST ==="
R=$(curl -s "$BASE/api/link/status/0" -H "Authorization: Bearer $TOKEN_A")
assert_eq "code" "$(echo "$R" | json_get code)" "40000"

echo ""
echo "=== TC-05: 未登录 → UNAUTHORIZED ==="
R=$(curl -s "$BASE/api/link/status/$ID")
assert_eq "code" "$(echo "$R" | json_get code)" "40100"

# ─── 汇总 ───
echo ""
echo "========================================="
echo -e "  通过: ${GREEN}$PASS${NC}  /  失败: ${RED}$FAIL${NC}  /  总计: $((PASS+FAIL))"
echo "========================================="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
