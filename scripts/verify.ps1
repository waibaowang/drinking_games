$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    & mvn verify
    if ($LASTEXITCODE -ne 0) { throw '后端验证失败' }
    Push-Location (Join-Path $root 'poker-web')
    try {
        & pnpm install --frozen-lockfile
        if ($LASTEXITCODE -ne 0) { throw '前端依赖校验失败' }
        & pnpm build
        if ($LASTEXITCODE -ne 0) { throw '前端类型检查或生产构建失败' }
    } finally { Pop-Location }
} finally { Pop-Location }

Write-Host '全部验证通过。'
