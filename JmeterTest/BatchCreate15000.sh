#!/bin/bash

# ===== 环境变量配置 =====
BASE_URL="http://localhost:9191"
TOKEN="${TOKEN}"   # 从环境变量读取

if [ -z "$TOKEN" ]; then
    echo "❌ 请先设置 TOKEN 环境变量：export TOKEN=<你的JWT>"
    exit 1
fi
# ========================

echo "开始批量创建 15000 个短链..."
echo "shortCode" > shortcodes_15k.csv

BATCH=50
SUCCESS=0
START_TIME=$(date +%s)

for i in $(seq 1 15000); do
    RESP=$(curl -s -X POST "$BASE_URL/api/link/create" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"longUrl":"https://example.com/page/'$i'"}')

    CODE=$(echo "$RESP" | grep -o '"shortCode":"[^"]*"' | cut -d'"' -f4)
    echo "$CODE" >> shortcodes_15k.csv
    SUCCESS=$((SUCCESS + 1))

    if [ $((i % BATCH)) -eq 0 ]; then
        ELAPSED=$(($(date +%s) - START_TIME))
        echo "[$i/15000] 已创建 $SUCCESS 条，耗时 ${ELAPSED}s"
    fi
done

ELAPSED=$(($(date +%s) - START_TIME))
echo "✅ 完成！共 $(wc -l < shortcodes_15k.csv) 行，耗时 ${ELAPSED}s"
