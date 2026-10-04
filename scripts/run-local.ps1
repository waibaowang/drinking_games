$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$nodeBin = 'C:\Users\中国队长\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\fallback'

Push-Location $root
try {
    & mvn -q -pl poker-server -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
} finally {
    Pop-Location
}
$jar = Get-ChildItem -LiteralPath (Join-Path $root 'poker-server\target') -Filter 'poker-server-*.jar' |
    Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
Start-Process -WindowStyle Hidden -FilePath 'java' -ArgumentList '-jar', $jar.FullName -WorkingDirectory $root
Start-Process -WindowStyle Hidden -FilePath (Join-Path $nodeBin 'pnpm.cmd') -ArgumentList 'dev' -WorkingDirectory (Join-Path $root 'poker-web')
Write-Host '后端与前端已启动。请打开 http://localhost:5173'

