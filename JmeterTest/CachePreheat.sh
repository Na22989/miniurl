while IFS= read -r code; do
    status=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9191/s/$code)
    echo "短码: $code → HTTP $status"
done < <(tail -n +2 shortcodes_15k.csv)