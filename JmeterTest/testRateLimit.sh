for i in $(seq 1 110); do
  echo -n "$i: "
  curl -s -o /dev/null -w "%{http_code}" http://localhost:9191/s/m5mwH5wy
  echo
done