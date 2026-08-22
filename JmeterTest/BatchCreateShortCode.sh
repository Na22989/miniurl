#!/bin/bash

# ===== 环境变量配置 =====
BASE_URL="http://localhost:9191"
TOKEN="eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjgsInN1YiI6IjgiLCJpYXQiOjE3ODYxODU2MTcsImV4cCI6MTc4Njc5MDQxN30.nmr0vA3I4li4EIRoSBatv8ZmfcW8ngOK1mIY-IHO4oo"
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