<#
.SYNOPSIS
  一条命令跑完检索评测：出题 -> 执行 -> 验收 -> 基线对比。

.EXAMPLE
  $env:EVAL_USERNAME='admin'; $env:EVAL_PASSWORD='...'; .\scripts\eval-pipeline.ps1 -AutoStart

.EXAMPLE
  .\scripts\eval-pipeline.ps1 -Suite full -BaseUrl http://staging:8081
#>
[CmdletBinding()]
param(
    [ValidateSet('smoke', 'full')]
    [string]$Suite = 'smoke',
    [string]$BaseUrl = 'http://localhost:8081',
    [switch]$AutoStart,
    [int]$HealthTimeoutSec = 180,
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
}
if (-not $env:JAVA_HOME) {
    throw "未设置 JAVA_HOME；可通过 -JavaHome 'C:\path\to\jdk21' 指定"
}

$mvn = Join-Path $repoRoot 'mvnw.cmd'
if (-not (Test-Path $mvn)) {
    $mvn = Join-Path $repoRoot 'mvnw'
}
if (-not (Test-Path $mvn)) {
    throw '找不到 Maven Wrapper（mvnw / mvnw.cmd）'
}

if (-not $env:EVAL_TOKEN -and -not ($env:EVAL_USERNAME -and $env:EVAL_PASSWORD)) {
    throw '缺少认证信息：设置 EVAL_TOKEN，或同时设置 EVAL_USERNAME / EVAL_PASSWORD'
}

# 轮询健康检查，最多等 TimeoutSec 秒；只依赖白名单端点，不触发业务逻辑
function Wait-AppReady {
    param([string]$Url, [int]$TimeoutSec)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "$Url/actuator/health" -TimeoutSec 5
            if ($health.status -eq 'UP') {
                Write-Host "应用已就绪: $Url"
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "应用在 ${TimeoutSec}s 内未就绪: $Url/actuator/health"
}

$app = $null
$exitCode = 1
try {
    if ($AutoStart) {
        $logDir = Join-Path $repoRoot 'target\eval'
        New-Item -ItemType Directory -Force -Path $logDir | Out-Null
        Write-Host "启动应用，日志: $logDir\app.log"
        $app = Start-Process -FilePath $mvn -ArgumentList @('-B', 'spring-boot:run') `
            -RedirectStandardOutput (Join-Path $logDir 'app.log') `
            -RedirectStandardError (Join-Path $logDir 'app.err.log') `
            -PassThru -WindowStyle Hidden
        Wait-AppReady -Url $BaseUrl -TimeoutSec $HealthTimeoutSec
    }

    & $mvn -B '-Dtest=RetrievalEvalPipelineTest' '-Deval.enabled=true' `
        "-Deval.suite=$Suite" "-Deval.baseUrl=$BaseUrl" test
    $exitCode = $LASTEXITCODE
} finally {
    # 只停自己启动的进程树，不碰机器上已有的应用实例
    if ($app -and -not $app.HasExited) {
        Write-Host "停止应用进程 $($app.Id)"
        & taskkill /PID $app.Id /T /F | Out-Null
    }
}

Write-Host "评测产物目录: $repoRoot\target\eval"
exit $exitCode
