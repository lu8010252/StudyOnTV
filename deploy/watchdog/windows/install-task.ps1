[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_.:\[\]-]+$')][string]$Serial = '',
    [ValidateSet('com.studyontv.tv','com.studyontv.mobile','com.studyontv.tv.debug','com.studyontv.mobile.debug')]
    [string]$Package = 'com.studyontv.tv',
    [ValidateRange(10,3600)][int]$Interval = 30,
    [string]$AdbPath = 'adb',
    [string]$PythonPath = 'python',
    [switch]$RepairAccessibility,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'
$taskName = 'StudyOnTV-Watchdog'
if ($Uninstall) {
    $existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($existingTask) {
        Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    }
    Write-Output 'StudyOnTV watchdog task removed; ADB keys are preserved.'
    exit 0
}
if ([string]::IsNullOrWhiteSpace($Serial)) { throw '-Serial is required when installing the task.' }
# Scheduled tasks have a restricted PATH: store absolute executable paths.
$pythonExecutable = (Get-Command $PythonPath -CommandType Application -ErrorAction Stop).Source
$adbExecutable = (Get-Command $AdbPath -CommandType Application -ErrorAction Stop).Source
$launcher = Join-Path $PSScriptRoot 'start-watchdog.ps1'
$logFile = Join-Path $env:LOCALAPPDATA 'StudyOnTV/watchdog.log'
foreach ($taskPath in @($pythonExecutable, $adbExecutable, $launcher, $logFile)) {
    if ($taskPath -match '["`\r\n]') { throw 'Executable paths cannot contain quotes, backticks or newlines.' }
}
$arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -File "{0}" -Serial "{1}" -Package "{2}" -Interval {3} -AdbPath "{4}" -PythonPath "{5}" -LogFile "{6}"' -f $launcher,$Serial,$Package,$Interval,$adbExecutable,$pythonExecutable,$logFile
if ($RepairAccessibility) { $arguments += ' -RepairAccessibility' }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Argument $arguments
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $identity
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description 'Restore StudyOnTV on an authorized TV while it is awake.' -Force | Out-Null
Start-ScheduledTask -TaskName $taskName
Write-Output 'Installed StudyOnTV-Watchdog for the current user. It runs after this user signs in.'
