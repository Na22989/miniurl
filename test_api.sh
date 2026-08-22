#!/bin/bash
# MiniURL API 自动化测试脚本
# 用法: bash test_api.sh
# 前置条件: 应用运行在 localhost:9191

set +e

BASE="http://localhost:9191"
PASS=0
FAIL=0
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

# 准备测试用户
echo "=== 准备测试数据 ==="

# pmtest 用户
curl -s -X POST "$BASE/api/user/register" -H "Content-Type: application/json" \
  -d '{"username":"pmtest","password":"123456"}' > /dev/null 2>&1 || true

TOKEN_A=$(curl -s -X POST "$BASE/api/user/login" -H "Content-Type: application/json" \
  -d '{"username":"pmtest","password":"123456"}' | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# test004 用户
curl -s -X POST "$BASE/api/user/register" -H "Content-Type: application/json" \
  -d '{"username":"test004","password":"123456"}' > /dev/null 2>&1 || true

TOKEN_B=$(curl -s -X POST "$BASE/api/user/login" -H "Content-Type: application/json" \
  -d '{"username":"test004","password":"123456"}' | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# 记录基线总数
BASELINE=$(curl -s -X POST "$BASE/api/link/list" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{}' \
  | python -c "import sys,json; print(json.load(sys.stdin)['data']['total'])")

echo "  基线总数: $BASELINE"

# 给 pmtest 创建 3 条短链
ID1=$(curl -s -X POST "$BASE/api/link/create" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{"longUrl":"https://www.baidu.com"}' \
  | python -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

ID2=$(curl -s -X POST "$BASE/api/link/create" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{"longUrl":"https://github.com"}' \
  | python -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

curl -s -X POST "$BASE/api/link/create" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{"longUrl":"https://stackoverflow.com"}' > /dev/null

EXPECTED_TOTAL=$((BASELINE + 3))
EXPECTED_AFTER_DELETE=$((EXPECTED_TOTAL - 1))

echo " TOKEN_A  (pmtest) : ${TOKEN_A:0:30}..."
echo " TOKEN_B  (test004): ${TOKEN_B:0:30}..."
echo " ID1=$ID1  ID2=$ID2"
echo " 期望创建后总数=$EXPECTED_TOTAL, 删除后=$EXPECTED_AFTER_DELETE"
echo ""

# ─── 断言函数 ───
assert_code() {  # $1=描述 $2=实际响应 $3=期望code $4=期望http状态
  local actual_code=$(echo "$2" | python -c "import sys,json; print(json.load(sys.stdin)['code'])" 2>/dev/null || echo "PARSE_ERROR")
  if [ "$actual_code" = "$3" ]; then
    echo -e " ${GREEN}PASS${NC}  $1  → code=$actual_code"
    ((PASS++))
  else
    echo -e " ${RED}FAIL${NC}  $1  → 期望 code=$3, 实际 code=$actual_code"
    echo "       响应: $2"
    ((FAIL++))
  fi
}

assert_count() {  # $1=描述 $2=响应 $3=JSON字段名 $4=期望值
  local actual=$(echo "$2" | python -c "import sys,json; print(json.load(sys.stdin)['data']['$3'])" 2>/dev/null || echo "PARSE_ERROR")
  if [ "$actual" = "$4" ]; then
    echo -e " ${GREEN}PASS${NC}  $1  → $3=$actual"
    ((PASS++))
  else
    echo -e " ${RED}FAIL${NC}  $1  → 期望 $3=$4, 实际 $3=$actual"
    ((FAIL++))
  fi
}

# ─── 测试用例 ───
echo "=== TC-01: 分页列表（默认参数，预期3条） ==="
R=$(curl -s -X POST "$BASE/api/link/list" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{}')
assert_code "TC-01" "$R" "20000"
assert_count "TC-01 总数" "$R" "total" "$EXPECTED_TOTAL"

echo ""
echo "=== TC-02: 分页列表（每页2条） ==="
R=$(curl -s -X POST "$BASE/api/link/list" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{"size":2}')
assert_code "TC-02" "$R" "20000"
# 每页2条，总数 EXPECTED_TOTAL 条 → 页数至少 2
PAGES=$(echo "$R" | python -c "import sys,json; print(json.load(sys.stdin)['data']['pages'])" 2>/dev/null)
if [ "$PAGES" -ge 2 ] 2>/dev/null; then
  echo -e " ${GREEN}PASS${NC}  TC-02 页数  → pages=$PAGES (≥2)"
  ((PASS++))
else
  echo -e " ${RED}FAIL${NC}  TC-02 页数  → pages=$PAGES, 期望 ≥2"
  ((FAIL++))
fi

echo ""
echo "=== TC-03: 分页列表（第2页） ==="
R=$(curl -s -X POST "$BASE/api/link/list" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{"current":2,"size":2}')
assert_code "TC-03" "$R" "20000"
assert_count "TC-03 当前页" "$R" "current" "2"

echo ""
echo "=== TC-04: 分页列表（current=0，校验失败） ==="
R=$(curl -s -X POST "$BASE/api/link/list" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{"current":0}')
assert_code "TC-04" "$R" "40000"

echo ""
echo "=== TC-05: 分页列表（size=100，超限） ==="
R=$(curl -s -X POST "$BASE/api/link/list" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{"size":100}')
assert_code "TC-05" "$R" "40000"

echo ""
echo "=== TC-06: 分页列表（未登录） ==="
R=$(curl -s -X POST "$BASE/api/link/list" -H "Content-Type: application/json" -d '{}')
assert_code "TC-06" "$R" "40100"

echo ""
echo "=== TC-07: 短链详情（自己的） ==="
R=$(curl -s "$BASE/api/link/detail/$ID1" -H "Authorization: Bearer $TOKEN_A")
assert_code "TC-07" "$R" "20000"

echo ""
echo "=== TC-08: 短链详情（不存在的 ID） ==="
R=$(curl -s "$BASE/api/link/detail/99999999999999" -H "Authorization: Bearer $TOKEN_A")
assert_code "TC-08" "$R" "42100"

echo ""
echo "=== TC-09: 短链详情（别人的 ID，安全测试） ==="
R=$(curl -s "$BASE/api/link/detail/$ID1" -H "Authorization: Bearer $TOKEN_B")
assert_code "TC-09" "$R" "42100"

echo ""
echo "=== TC-10: 短链详情（linkId ≤ 0） ==="
R=$(curl -s "$BASE/api/link/detail/-1" -H "Authorization: Bearer $TOKEN_A")
assert_code "TC-10" "$R" "40000"

echo ""
echo "=== TC-11: 短链详情（未登录） ==="
R=$(curl -s "$BASE/api/link/detail/$ID1")
assert_code "TC-11" "$R" "40100"

echo ""
echo "=== TC-12: 删除短链（正常） ==="
R=$(curl -s -X DELETE "$BASE/api/link/delete" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d "{\"id\":$ID1}")
assert_code "TC-12" "$R" "20000"

echo ""
echo "=== TC-13: 删除后列表确认只剩2条 ==="
R=$(curl -s -X POST "$BASE/api/link/list" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{}')
assert_code "TC-13" "$R" "20000"
assert_count "TC-13 总数" "$R" "total" "$EXPECTED_AFTER_DELETE"

echo ""
echo "=== TC-14: 删除不存在的短链 ==="
R=$(curl -s -X DELETE "$BASE/api/link/delete" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{"id":99999999999999}')
assert_code "TC-14" "$R" "42100"

echo ""
echo "=== TC-15: 删除别人的短链（安全测试） ==="
R=$(curl -s -X DELETE "$BASE/api/link/delete" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_B" -d "{\"id\":$ID2}")
assert_code "TC-15" "$R" "42100"

echo ""
echo "=== TC-16: 删除（id=0，校验失败） ==="
R=$(curl -s -X DELETE "$BASE/api/link/delete" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" -d '{"id":0}')
assert_code "TC-16" "$R" "40000"

echo ""
echo "=== TC-17: 删除（未登录） ==="
R=$(curl -s -X DELETE "$BASE/api/link/delete" -H "Content-Type: application/json" -d "{\"id\":$ID2}")
assert_code "TC-17" "$R" "40100"

# ─── 结果汇总 ───
echo ""
echo "========================================="
echo -e "  通过: ${GREEN}$PASS${NC}  /  失败: ${RED}$FAIL${NC}  /  总计: $((PASS+FAIL))"
echo "========================================="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
