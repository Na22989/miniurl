# Metrics.ps1 — MiniURL 压测指标快照 / 增量工具（Windows PowerShell 5.1）
#
# 为什么需要它：Micrometer 计数器是「进程内单调累计」，没有 run 维度。
# 唯一暴露的读数端点是 /actuator/prometheus（application.yml 的
# management.endpoints.web.exposure.include 只放了 health,info,prometheus，
# /actuator/metrics 没暴露）。所以记账法只能是「实验前后各拍一次快照，做减法」。
#
# 用法：
#   . .\Metrics.ps1                                  # dot-source 载入函数
#   Save-MiniurlMetrics -Tag R1-before
#   ...跑压测...
#   Save-MiniurlMetrics -Tag R1-after
#   Compare-MiniurlMetrics -Before .\metrics\R1-before.txt -After .\metrics\R1-after.txt
#
# 注意：从未被 increment 过的计数器在 /actuator/prometheus 里「根本不出现」，
#       本脚本把缺席一律当 0 处理（不是 bug，是 Micrometer 惰性建表的语义）。

$script:MiniurlBaseUrl = "http://localhost:9191"

function Save-MiniurlMetrics {
    param(
        [Parameter(Mandatory = $true)][string]$Tag,
        [string]$BaseUrl = $script:MiniurlBaseUrl,
        [string]$OutDir = (Join-Path $PSScriptRoot "metrics")
    )
    if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
    $out = Join-Path $OutDir "$Tag.txt"

    # 用 curl.exe，不用 curl 别名（PS 5.1 里 curl = Invoke-WebRequest，参数完全不同）
    $raw = & curl.exe -s "$BaseUrl/actuator/prometheus"
    if (-not $raw) { throw "抓取失败：$BaseUrl/actuator/prometheus 无响应，应用没起来？" }

    # 只留业务指标 + 少量关心的 JVM/HTTP 指标；# HELP / # TYPE 注释行丢弃
    $lines = $raw | Where-Object {
        $_ -notmatch '^#' -and (
            $_ -like 'miniurl_*' -or
            $_ -like 'http_server_requests_seconds_count*' -or
            $_ -like 'jvm_threads_live_threads*' -or
            $_ -like 'executor_*'
        )
    }
    $lines | Sort-Object | Set-Content -Path $out -Encoding utf8
    "快照已写入 $out（$($lines.Count) 行）"
}

function Compare-MiniurlMetrics {
    param(
        [Parameter(Mandatory = $true)][string]$Before,
        [Parameter(Mandatory = $true)][string]$After
    )

    function Read-Snapshot([string]$path) {
        $map = @{}
        foreach ($line in (Get-Content $path)) {
            $i = $line.LastIndexOf(' ')
            if ($i -le 0) { continue }
            $key = $line.Substring(0, $i)
            $val = $line.Substring($i + 1)
            $num = 0.0
            if ([double]::TryParse($val, [ref]$num)) { $map[$key] = $num }
        }
        return $map
    }

    $b = Read-Snapshot $Before
    $a = Read-Snapshot $After

    $keys = @($a.Keys) + @($b.Keys) | Sort-Object -Unique
    $rows = foreach ($k in $keys) {
        $bv = if ($b.ContainsKey($k)) { $b[$k] } else { 0.0 }   # 缺席 = 0（惰性建表）
        $av = if ($a.ContainsKey($k)) { $a[$k] } else { 0.0 }
        $d = $av - $bv
        if ($d -ne 0) {
            [pscustomobject]@{ Metric = $k; Before = $bv; After = $av; Delta = $d }
        }
    }
    $rows | Sort-Object Metric | Format-Table -AutoSize
}

function Get-MiniurlRedirectDelta {
    # 只打印判据最常用的那几个 counter 的增量，便于直接抄进结果表
    param(
        [Parameter(Mandatory = $true)][string]$Before,
        [Parameter(Mandatory = $true)][string]$After
    )
    Compare-MiniurlMetrics -Before $Before -After $After |
        Out-String -Stream |
        Where-Object { $_ -match 'miniurl_redirect|miniurl_redis_degraded|miniurl_link_created|^Metric|^---' }
}
