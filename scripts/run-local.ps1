param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$java = (Get-Command java.exe -ErrorAction Stop).Source
$node = (Get-Command node.exe -ErrorAction Stop).Source
$vite = Join-Path $root 'poker-web\node_modules\vite\bin\vite.js'
$logDirectory = Join-Path $root 'data\local-logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
if (-not (Test-Path -LiteralPath $vite)) { throw '请先在 poker-web 目录运行 pnpm install。' }

function Wait-LocalService($url, $process, $logName, [switch]$Backend) {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        if ($process -and $process.HasExited) { break }
        try {
            if ($Backend) {
                $healthy = (Invoke-RestMethod -Uri $url -TimeoutSec 2).status -eq 'UP'
            } else {
                $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2
                $healthy = $response.StatusCode -eq 200 -and $response.Content -match 'id="app"'
            }
            if ($healthy) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Get-ChildItem -LiteralPath $logDirectory -Filter "$logName.*.log" | ForEach-Object {
        Get-Content -LiteralPath $_.FullName -Tail 30
    }
    throw "服务未能启动：$url。日志位于 $logDirectory"
}

$backend = $null
$frontend = $null
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
    if (-not $SkipBuild) {
        Push-Location $root
        try {
            & mvn -q -pl poker-server -am package -DskipTests
            if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
        } finally { Pop-Location }
    }
    $jar = Get-ChildItem -LiteralPath (Join-Path $root 'poker-server\target') -Filter 'poker-server-*.jar' |
        Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
    if (-not $jar) { throw '没有后端 JAR，请去掉 -SkipBuild 后重试。' }
    $backend = Start-Process -WindowStyle Hidden -FilePath $java -ArgumentList '-jar', ('"{0}"' -f $jar.FullName) `
        -WorkingDirectory $root -RedirectStandardOutput (Join-Path $logDirectory 'backend.out.log') `
        -RedirectStandardError (Join-Path $logDirectory 'backend.err.log') -PassThru
}
Wait-LocalService 'http://127.0.0.1:8080/actuator/health' $backend 'backend' -Backend

if (-not (Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue)) {
    $frontend = Start-Process -WindowStyle Hidden -FilePath $node `
        -ArgumentList ('"{0}" --host :: --port 5173 --strictPort' -f $vite) `
        -WorkingDirectory (Join-Path $root 'poker-web') `
        -RedirectStandardOutput (Join-Path $logDirectory 'frontend.out.log') `
        -RedirectStandardError (Join-Path $logDirectory 'frontend.err.log') -PassThru
}
Wait-LocalService 'http://127.0.0.1:5173' $frontend 'frontend'
Write-Host '网页：http://localhost:5173'
Write-Host '后端健康检查：http://localhost:8080/actuator/health'
Write-Host "服务已在后台运行，日志：$logDirectory"
