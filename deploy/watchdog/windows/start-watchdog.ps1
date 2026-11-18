[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_.:\[\]-]+$')][string]$Serial,
    [ValidateSet('com.studyontv.tv','com.studyontv.mobile','com.studyontv.tv.debug','com.studyontv.mobile.debug')]
    [string]$Package = 'com.studyontv.tv',
    [ValidateRange(10,3600)][int]$Interval = 30,
    [string]$AdbPath = 'adb',
    [string]$PythonPath = 'python',
    [string]$LogFile = '',
    [ValidateSet('watch','ensure','status')][string]$Mode = 'watch',
    [switch]$RepairAccessibility
)
$ErrorActionPreference = 'Stop'
$repoRoot = Join-Path (Join-Path (Join-Path $PSScriptRoot '..') '..') '..'
$watchdogScript = [IO.Path]::GetFullPath((Join-Path (Join-Path $repoRoot 'scripts') 'device-watchdog.py'))
$watchdogArgs = @($watchdogScript, $Mode, '--serial', $Serial, '--package', $Package,
    '--interval', [string]$Interval, '--adb', $AdbPath)
if ($LogFile) { $watchdogArgs += @('--log-file', $LogFile) }
if ($RepairAccessibility) { $watchdogArgs += '--repair-accessibility' }
& $PythonPath @watchdogArgs
exit $LASTEXITCODE
