param(
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

function Fail([string]$msg) {
    Write-Host "FAIL: $msg"
    exit 1
}

# 定位 java：优先 JAVA_HOME，其次 PATH。
$javaExe = $null
if ($JavaHome) {
    $candidate = Join-Path $JavaHome 'bin\java.exe'
    if (Test-Path -LiteralPath $candidate) { $javaExe = $candidate }
}
if (-not $javaExe) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $javaExe = $cmd.Source }
}
if (-not $javaExe) {
    Fail 'java not found: set JAVA_HOME to a JDK 17+ (e.g. Android Studio bundled jbr)'
}

$output = cmd /c "`"$javaExe`" -version 2>&1"
$output = ($output -join "`n")
if ($output -match 'version "1\.(\d+)') {
    $major = [int]$Matches[1]  # JDK 8 旧格式，一律低于 17
} elseif ($output -match 'version "(\d+)') {
    $major = [int]$Matches[1]
} else {
    Fail "cannot parse java version from: $output"
}

if ($major -lt 17) {
    Fail "java $major is too old: JDK 17+ required ($javaExe)"
}

Write-Host "java OK: major=$major ($javaExe)"

# SDK：优先 ANDROID_HOME，其次仓库内 .tools/android-sdk（local.properties 亦可指向他处）。
$sdk = $env:ANDROID_HOME
if (-not $sdk) {
    $repoSdk = Join-Path $PSScriptRoot '..\..\.tools\android-sdk'
    if (Test-Path -LiteralPath $repoSdk) { $sdk = $repoSdk }
}
if ($sdk) {
    Write-Host "android sdk: $sdk"
} else {
    Write-Host 'WARN: ANDROID_HOME not set and repo .tools/android-sdk missing; rely on local.properties sdk.dir'
}

Write-Host 'RESULT: PASS'
