param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK not found: $ApkPath"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

$suspiciousNames = @(
    'nga_config',
    'config.ini',
    'keystore',
    'local.properties',
    'ngaPassportCid',
    'ngaPassportUid'
)

$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath))
try {
    $found = $false
    foreach ($entry in $zip.Entries) {
        $name = $entry.FullName
        foreach ($pattern in $suspiciousNames) {
            if ($name.IndexOf($pattern, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                Write-Host "FOUND entry: $name (pattern: $pattern)"
                $found = $true
            }
        }
        $isText = $false
        foreach ($ext in @('.ini', '.properties', '.xml', '.json', '.txt', '.md', '.html', '.js', '.css')) {
            if ($name.EndsWith($ext, [System.StringComparison]::OrdinalIgnoreCase)) {
                $isText = $true
                break
            }
        }
        if ($entry.Length -gt 0 -and $entry.Length -lt 2MB -and $isText) {
            $reader = New-Object System.IO.StreamReader($entry.Open())
            try {
                $text = $reader.ReadToEnd()
                # 只匹配“键=非空值”的真实凭据形态；代码里的字段名（裸键）不算。
                if ($text -match 'ngaPassport(Cid|Uid)\s*=\s*[^\s"''=]') {
                    Write-Host "FOUND credential content in: $name"
                    $found = $true
                }
            } finally {
                $reader.Dispose()
            }
        }
    }
    $file = Get-Item -LiteralPath $ApkPath
    $hash = Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256
    Write-Host "APK: $($file.FullName)"
    Write-Host "Size: $($file.Length) bytes"
    Write-Host "SHA256: $($hash.Hash)"
    if ($found) {
        Write-Host "RESULT: FAIL - suspicious content found."
        exit 1
    }
    Write-Host "RESULT: PASS - no suspicious entries found."
} finally {
    $zip.Dispose()
}
