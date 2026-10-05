$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    & mvn verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' }
    Push-Location (Join-Path $root 'poker-web')
    try {
        & pnpm install --frozen-lockfile
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency check failed' }
        & pnpm test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend coordinate tests failed' }
        & pnpm build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend type check or production build failed' }
    } finally { Pop-Location }
} finally { Pop-Location }

Write-Host 'All checks passed.'
