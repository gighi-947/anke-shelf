# AnkeShelf documentation drift scan helper
#
# Purpose: turn the manual "doc drift check" into a semi-automatic scan.
# It prints current repository facts (HEAD / branch / worktree / CI list)
# and high-drift document snapshots for human review. It never edits docs
# and is not a CI gate.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/check-doc-drift.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/check-doc-drift.ps1 -RunTests
#
# Companion rules: AGENTS.md section 5 "Doc drift check".

param(
  [switch]$RunTests,
  [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Section([string]$title) {
  if (-not $Quiet) { Write-Host "`n=== $title ===" -ForegroundColor Cyan }
}

Section 'Git state'
git rev-parse HEAD
git branch --show-current
git status --short --branch

Section 'CI workflow list'
Get-ChildItem '.github/workflows' -Filter '*.yml' -File |
  Sort-Object Name | ForEach-Object { $_.Name }

Section 'AnkeShelf_DevLog.md section 1 (first 22 lines)'
Get-Content 'AnkeShelf_DevLog.md' -TotalCount 30 -Encoding UTF8 |
  Select-Object -First 22

Section 'docs/ARCHITECTURE_ROADMAP.md section 2.1 key rows'
Get-Content 'docs/ARCHITECTURE_ROADMAP.md' -Encoding UTF8 |
  Select-String -Pattern '^\| 主干状态 |^\| Windows Python 单测 |^\| JS 契约测试 |^\| Android JVM 单测 ' |
  ForEach-Object { $_.Line }

Section 'docs/ARCHITECTURE_ROADMAP.md section 2.2 line-count table (first 14 rows)'
$roadmap = Get-Content 'docs/ARCHITECTURE_ROADMAP.md' -Encoding UTF8
$start = ($roadmap | Select-String -Pattern '^### 2.2 代码规模热点').LineNumber
if ($start) {
  $roadmap[($start - 1)..([Math]::Min($start + 14, $roadmap.Count - 1))]
}

Section 'docs/MAINTENANCE_GUIDE.md section 7 key rows'
Get-Content 'docs/MAINTENANCE_GUIDE.md' -Encoding UTF8 |
  Select-String -Pattern '^\| Windows Python |^\| JS 契约 |^\| Android JVM |^\| 真机 |^\| UI harness ' |
  ForEach-Object { $_.Line }

Section 'Governance wiring check'
$wiring = @(
  @{ File = 'AGENTS.md'; Needle = 'scripts/check-doc-drift.ps1' },
  @{ File = 'CONTRIBUTING.md'; Needle = 'scripts/check-doc-drift.ps1' },
  @{ File = 'docs/MAINTENANCE_GUIDE.md'; Needle = 'scripts/check-doc-drift.ps1' }
)
foreach ($w in $wiring) {
  $hit = Select-String -Path $w.File -Pattern $w.Needle -SimpleMatch -Quiet
  if ($hit) {
    Write-Host ("OK   {0} -> {1}" -f $w.File, $w.Needle)
  } else {
    Write-Host ("MISS {0} missing reference to {1}" -f $w.File, $w.Needle) -ForegroundColor Yellow
  }
}

if ($RunTests) {
  Section 'Live test counts (Windows Python + JS contracts)'
  python -m unittest discover tests 2>&1 |
    Select-String -Pattern '^Ran \d+ tests|^OK|^FAILED'
  node contracts/tests/api-contract.test.js
  node contracts/tests/textpos.test.js
}

Section 'Done'
if (-not $Quiet) {
  Write-Host 'Snapshot above. Compare against AGENTS.md section 5 checklist manually.' -ForegroundColor Yellow
}
