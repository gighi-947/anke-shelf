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
        if ($entry.Length -gt 0 -and $entry.Length -lt 2MB) {
            $reader = New-Object System.IO.StreamReader($entry.Open())
            try {
                $text = $reader.ReadToEnd()
                if ($text -match 'ngaPassport(Cid|Uid)') {
                    Write-Host "FOUND credential content in: $name"
                    $found = $true
                }
            } finally {
                $reader.Dispose()
            }
        }
    }
    if ($found) {
        Write-Host "RESULT: FAIL - suspicious content found."
        exit 1
    }
    Write-Host "RESULT: PASS - no suspicious entries found."
} finally {
    $zip.Dispose()
}
