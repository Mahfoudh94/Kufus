param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("up", "down", "status")]
    [string]$Action,

    [Parameter(Position = 1)]
    [int]$SizeMb = 128
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$WorkerBin = if ($env:KUFUS_WORKER_BIN) {
    $env:KUFUS_WORKER_BIN
} else {
    Join-Path $RepoRoot "worker-cli\build\bin\mingwX64\debugExecutable\kufus-worker.exe"
}
$VhdxPath = Join-Path $env:TEMP "kufus-virtual.vhdx"

function Invoke-WorkerList {
    if (Test-Path $WorkerBin) {
        Write-Host "Worker output ($WorkerBin --list):"
        & $WorkerBin --list
    } else {
        Write-Host "Worker binary not built yet; build it with: .\gradlew.bat :worker-cli:linkDebugExecutableMingwX64"
    }
}

if (-not (Get-Command New-VHD -ErrorAction SilentlyContinue)) {
    Write-Error "Hyper-V VHD cmdlets (New-VHD / Mount-VHD) are unavailable. Enable Hyper-V Management Tools or create a VHDX in Disk Management."
}

switch ($Action) {
    "up" {
        if (Test-Path $VhdxPath) {
            Dismount-VHD -Path $VhdxPath -ErrorAction SilentlyContinue
            Remove-Item -Force $VhdxPath
        }
        $sizeBytes = [uint64]$SizeMb * 1MB
        New-VHD -Path $VhdxPath -SizeBytes $sizeBytes -Dynamic | Out-Null
        $mounted = Mount-VHD -Path $VhdxPath -NoDriveLetter -Passthru
        Write-Host "Mounted virtual disk PhysicalDrive$($mounted.DiskNumber) ($VhdxPath)"
        Invoke-WorkerList
    }
    "down" {
        Dismount-VHD -Path $VhdxPath -ErrorAction SilentlyContinue
        Remove-Item -Force -ErrorAction SilentlyContinue $VhdxPath
        Write-Host "Detached virtual VHDX."
    }
    "status" {
        Get-VHD -Path $VhdxPath -ErrorAction SilentlyContinue
        Invoke-WorkerList
    }
}
