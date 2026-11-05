<#
.SYNOPSIS
Drives the Pleos emulator along a short route so that a release build can record a real card.

.DESCRIPTION
A release build has no simulation, so position and gear are injected into the emulator:
Drive, then position fixes at a speed the recorder accepts, then Park.
Start the recording in the app first, because the app locks once the gear leaves P.
Nothing is installed and app data is not touched.

.EXAMPLE
.\scripts\demo-drive.ps1 -Serial emulator-5556
#>
param(
    [string]$Serial = "emulator-5556",
    [string]$Adb = "adb",
    [double]$StartLongitude = 129.1186,
    [double]$StartLatitude = 35.1532,
    [double]$EndLongitude = 129.1604,
    [double]$EndLatitude = 35.1587,
    # The recorder drops jumps above 250 km/h. Timing jitter between fixes raises the apparent speed.
    [ValidateRange(10, 200)][double]$SpeedKmh = 150,
    [ValidateRange(1, 10)][int]$IntervalSeconds = 2
)

$ErrorActionPreference = "Stop"
$console = "http://127.0.0.1:8088"
$culture = [System.Globalization.CultureInfo]::InvariantCulture

function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb $args failed with exit code $LASTEXITCODE" }
}

function Set-Gear([string]$Gear) {
    Invoke-WebRequest -UseBasicParsing -Method Put -Uri "$console/vehicle/gear?gear=$Gear" | Out-Null
    Write-Host "Gear: $Gear"
}

function Set-Position([double]$Longitude, [double]$Latitude) {
    Invoke-Adb emu geo fix $Longitude.ToString($culture) $Latitude.ToString($culture) | Out-Null
}

function Get-DistanceKm([double]$Lon1, [double]$Lat1, [double]$Lon2, [double]$Lat2) {
    $rad = [Math]::PI / 180
    $x = ($Lon2 - $Lon1) * $rad * [Math]::Cos(($Lat1 + $Lat2) / 2 * $rad)
    $y = ($Lat2 - $Lat1) * $rad
    return [Math]::Sqrt($x * $x + $y * $y) * 6371
}

if ((& $Adb -s $Serial get-state 2>$null) -ne "device") { throw "Emulator $Serial is not connected." }

$distanceKm = Get-DistanceKm $StartLongitude $StartLatitude $EndLongitude $EndLatitude
$steps = [Math]::Max(2, [int][Math]::Ceiling($distanceKm / $SpeedKmh * 3600 / $IntervalSeconds))
Write-Host ("Route {0:N1} km, {1} fixes, about {2} s." -f $distanceKm, $steps, ($steps * $IntervalSeconds))
Write-Host "The recording must already be running in the app."

Invoke-Adb forward tcp:8088 tcp:8088 | Out-Null

# The Virtual Console ignores gear changes unless a browser session is kept alive once a second.
$client = [guid]::NewGuid().ToString("N")
$session = Start-Job -ArgumentList "$console/browser/session?client=$client" -ScriptBlock {
    param($uri)
    while ($true) {
        try { Invoke-WebRequest -UseBasicParsing -Method Post -Uri $uri | Out-Null } catch {}
        Start-Sleep -Seconds 1
    }
}

try {
    Start-Sleep -Seconds 2
    Set-Position $StartLongitude $StartLatitude
    Set-Gear "Drive"
    for ($i = 1; $i -le $steps; $i++) {
        Start-Sleep -Seconds $IntervalSeconds
        $t = $i / $steps
        # A gentle arc, so that the drawn route is not a ruler line.
        $bend = [Math]::Sin($t * [Math]::PI) * 0.003
        Set-Position ($StartLongitude + ($EndLongitude - $StartLongitude) * $t) ($StartLatitude + ($EndLatitude - $StartLatitude) * $t + $bend)
    }
} finally {
    # Never leave the emulator in Drive: the app stays locked until it returns to P.
    try { Set-Gear "Park" } catch { Write-Warning "Could not return to Park. Set it in the Virtual Console: $_" }
    Stop-Job $session -ErrorAction SilentlyContinue
    Remove-Job $session -Force -ErrorAction SilentlyContinue
}
Write-Host "Done. End the recording in the app if the stop screen is showing."
