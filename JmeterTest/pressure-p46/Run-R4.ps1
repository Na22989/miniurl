# Run-R4.ps1 — 场景 R4（缓存击穿并发）单次试验编排器
#
# 为什么需要编排器：R4 的前置条件极其脆弱——victim 短码必须同时满足
#   ① L2（Redis）里没有它的 key；
#   ② 当前 JVM 生命周期内从未被访问过（否则 Caffeine L1 命中，压根走不到锁）。
# 手工按顺序敲命令很容易漏掉一步，测出来的「击穿」其实是 L1 命中。
# 本脚本把 DEL → 校验 → 前快照 → 压测 → 后快照 → 算增量 串成一条，全部有回显可审计。
#
# 用法（PowerShell，在 JmeterTest\pressure-p46 目录下）：
#   .\Run-R4.ps1 -Victim m6ZIz3mp -Threads 200 -RedisDb 14
#
# 注意：同一个 victim 只能用一次。第二次跑必须换一个从未访问过的短码
#       （L1 无法从进程外清除；要复用同一个码，只能重启应用）。

param(
    [Parameter(Mandatory = $true)][string]$Victim,
    [int]$Threads = 200,
    [int]$RedisDb = 14,
    [string]$BaseUrl = "http://localhost:9191",
    [string]$RedisCli = "redis-cli",      # 需在 PATH 中，否则用 -RedisCli 指定完整路径
    [string]$RedisPass = "root",          # 与 .env.example 的 REDIS_PASSWORD 默认值一致
    [string]$JMeter = "jmeter",           # 需在 PATH 中，否则用 -JMeter 指定 jmeter.bat
    [string]$Label = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Metrics.ps1")

if (-not $Label) { $Label = "R4-t$Threads-$Victim" }
$outDir = Join-Path $PSScriptRoot "results\$Label"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

function Redis([string[]]$redisArgs) {
    & $RedisCli -a $RedisPass -n $RedisDb --no-auth-warning @redisArgs
}

Write-Host "== 0/6 环境指纹" -ForegroundColor Cyan
Write-Host "  victim=$Victim threads=$Threads redis-db=$RedisDb 输出=$outDir"

Write-Host "== 1/6 删掉 victim 的 L2 key，制造「热点失效」" -ForegroundColor Cyan
$delCount = Redis @("DEL", "shortlink:code:$Victim")
Write-Host "  DEL shortlink:code:$Victim -> $delCount（0 表示本来就不存在，也 OK）"

# 顺带清掉可能残留的重建锁，避免上一轮崩溃留下的锁把本轮全推进自旋分支
$lockDel = Redis @("DEL", "shortlink:rebuild:lock:$Victim")
Write-Host "  DEL shortlink:rebuild:lock:$Victim -> $lockDel（必须是 0；是 1 说明上轮没释放干净）"

Write-Host "== 2/6 校验 L2 确实为空" -ForegroundColor Cyan
$exists = Redis @("EXISTS", "shortlink:code:$Victim")
if ("$exists" -ne "0") { throw "L2 key 仍存在，前置条件不满足，终止" }
Write-Host "  EXISTS -> 0 ✅"

Write-Host "== 3/6 抓压测前指标快照" -ForegroundColor Cyan
Save-MiniurlMetrics -Tag "$Label-before" -BaseUrl $BaseUrl -OutDir $outDir

Write-Host "== 4/6 跑 JMeter（$Threads 线程集合后同时打一枪）" -ForegroundColor Cyan
$jmx = Join-Path $PSScriptRoot "场景4-缓存击穿并发.jmx"
$jtl = Join-Path $outDir "r4.jtl"
if (Test-Path $jtl) { Remove-Item $jtl }
& $JMeter -n -t $jmx -l $jtl -e -o (Join-Path $outDir "report") `
    "-Jvictim=$Victim" "-Jr4_threads=$Threads" | Tee-Object -FilePath (Join-Path $outDir "jmeter.log")

Write-Host "== 5/6 抓压测后指标快照" -ForegroundColor Cyan
Save-MiniurlMetrics -Tag "$Label-after" -BaseUrl $BaseUrl -OutDir $outDir

Write-Host "== 6/6 指标增量（判据：source=db 增量应 ≈ 1，不是 $Threads）" -ForegroundColor Cyan
Compare-MiniurlMetrics -Before (Join-Path $outDir "$Label-before.txt") `
                       -After  (Join-Path $outDir "$Label-after.txt")

Write-Host ""
Write-Host "手工复核（把下面两条抄进结果表）：" -ForegroundColor Yellow
Write-Host "  1) miniurl_redirect_total_total{source=`"db`"} 增量 = ? （期望 1）"
Write-Host "  2) miniurl_redirect_total_total{source=`"l2`"} 增量 = ? （期望 ≈ $Threads - 1 - L1命中数）"
Write-Host "  3) 应用日志里本 victim 的 SQL 执行次数（log-impl=StdOutImpl）："
Write-Host "     Select-String -Path ..\..\miniurl-backend\miniurl\logs\miniurl.log -Pattern '$Victim' | Measure-Object"
