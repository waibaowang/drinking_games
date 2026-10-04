$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & mvn -q -pl poker-server -am install -DskipTests
    if ($LASTEXITCODE -ne 0) { throw '编译失败' }
    & mvn -q -pl poker-server dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw '无法生成运行时类路径' }
    $dependencies = Get-Content -Raw (Join-Path $root 'poker-server\target\runtime-classpath.txt')
    $classpath = (Join-Path $root 'poker-server\target\classes') + ';' + $dependencies.Trim()
    & java -cp $classpath cn.friendpoker.server.user.PasswordHashTool
} finally { Pop-Location }
