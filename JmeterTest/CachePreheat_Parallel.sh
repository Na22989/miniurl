#!/bin/bash

# ===== 并行预热：50并发，15000条约30秒 =====
BASE_URL="http://localhost:9191"
CSV_FILE="${1:-shortcodes_15k.csv}"   # 默认读 shortcodes_15k.csv，也可传参
MAX_PARALLEL=50

if [ ! -f "$CSV_FILE" ]; then
    echo "❌ $CSV_FILE 不存在"
    echo "用法: bash CachePreheat_Parallel.sh [csv文件名]"
    exit 1
fi

TOTAL=$(tail -n +2 "$CSV_FILE" | wc -l)
echo "开始并行预热 $TOTAL 个短码（${MAX_PARALLEL}并发）..."
START_TIME=$(date +%s)
COUNT=0

# 用 < <(...) 而非管道，保证 COUNT 在主 shell 中更新
while IFS= read -r code; do
    curl -s -o /dev/null "$BASE_URL/s/$code" &
    COUNT=$((COUNT + 1))

    if [ $((COUNT % MAX_PARALLEL)) -eq 0 ]; then
        wait
        ELAPSED=$(($(date +%s) - START_TIME))
        echo "  [$COUNT/$TOTAL] 耗时 ${ELAPSED}s"
    fi
done < <(tail -n +2 "$CSV_FILE")

wait  # 最后一批
ELAPSED=$(($(date +%s) - START_TIME))
echo "✅ 预热完成！共 $TOTAL 条，耗时 ${ELAPSED}s"

# 验证缓存
redis-cli -a root DBSIZE 2>/dev/null
HEAD_CODE=$(head -2 "$CSV_FILE" | tail -1)
echo "抽样: GET /s/$HEAD_CODE → $(curl -s -o /dev/null -w '%{http_code}' $BASE_URL/s/$HEAD_CODE)"
