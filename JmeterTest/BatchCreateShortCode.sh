#!/bin/bash

# 批量创建短码，产出 shortcodes.csv 供 JMeter 跳转压测使用。
#
# 用法：
#   MINIURL_TOKEN=<accessToken> bash BatchCreateShortCode.sh
#
# token 获取：POST /api/user/login，从响应里取 data.accessToken（有效期 30 分钟，
# 100 条约几秒跑完，通常不会中途过期）。
# token 不入库：它是有时效的凭证，写进脚本再提交等于把凭证留在仓库历史里。

# ===== 环境变量配置 =====
BASE_URL="${BASE_URL:-http://localhost:9191}"
TOKEN="${MINIURL_TOKEN:?未设置 MINIURL_TOKEN：请先登录获取 accessToken 后传入}"
# ========================

echo "shortCode" > shortcodes.csv

for i in $(seq 1 100); do
  RESP=$(curl -s -X POST "$BASE_URL/api/link/create" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"longUrl":"https://example.com/page/'$i'"}')
  CODE=$(echo $RESP | grep -o '"shortCode":"[^"]*"' | cut -d'"' -f4)
  echo "$CODE" >> shortcodes.csv
  echo "[$i/100] $CODE"
done

echo "✅ 完成！共生成 $(wc -l < shortcodes.csv) 条短码"