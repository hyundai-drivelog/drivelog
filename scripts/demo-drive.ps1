<#
.SYNOPSIS
Drives the Pleos emulator along a real road so that a release build can record a real card.

.DESCRIPTION
A release build has no simulation, so position and gear are injected into the emulator:
Drive, then position fixes along scripts\demo-route.json at a changing speed, then Park.

Run it twice. -Prepare first moves the emulator to the start of the route. Then start the recording in the app,
which must happen before leaving P, and run it again without -Prepare. Without the first step the recording starts
from wherever the emulator was, the recorder rejects the jump to this route, and the drive stays at 0.0 km.
Nothing is installed and app data is not touched.

With -KmPerKwh the battery energy (EV_BATTERY_LEVEL) also falls with the distance driven, as if the car used that
much, so the app's efficiency from start and end energy can be tried. The emulator does not drain the battery when
driving, so without it the drive has no efficiency. The battery is left where the drive ended. These are injected
figures, not a measurement of any car.

.EXAMPLE
.\scripts\demo-drive.ps1 -Serial emulator-5556 -Prepare
.\scripts\demo-drive.ps1 -Serial emulator-5556
.\scripts\demo-drive.ps1 -Serial emulator-5556 -KmPerKwh 6.5
#>
param(
    [string]$Serial = "emulator-5556",
    [string]$Adb = "adb",
    # The recorder drops jumps above 250 km/h, and timing jitter between fixes raises the apparent speed.
    [ValidateRange(20, 200)][double]$MaxSpeedKmh = 180,
    [ValidateRange(5, 200)][double]$MinSpeedKmh = 60,
    [ValidateRange(1, 10)][int]$IntervalSeconds = 2,
    # 0 leaves the battery alone. Otherwise the injected efficiency, in km per kWh.
    [ValidateRange(0, 15)][double]$KmPerKwh = 0,
    [switch]$Prepare
)

$ErrorActionPreference = "Stop"
$console = "http://127.0.0.1:8088"
$culture = [System.Globalization.CultureInfo]::InvariantCulture
$route = (Get-Content (Join-Path $PSScriptRoot "demo-route.json") -Raw | ConvertFrom-Json).coordinates

function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb $args failed with exit code $LASTEXITCODE" }
}

function Set-Gear([string]$Gear) {
    Invoke-WebRequest -UseBasicParsing -Method Put -Uri "$console/vehicle/gear?gear=$Gear" | Out-Null
    Write-Host "Gear: $Gear"
}

function Set-BatteryKwh([double]$Kwh) {
    $value = [Math]::Max(0, $Kwh).ToString("0.000", $culture)
    # A missed step is caught up by the next one; it must not end the drive halfway.
    try { Invoke-WebRequest -UseBasicParsing -Method Put -Uri "$console/vehicle/ev-battery?field=battery_level&value=$value" | Out-Null }
    catch { Write-Warning "Battery not set to $value kWh: $($_.Exception.Message)" }
}

function Set-Position([double]$Longitude, [double]$Latitude) {
    Invoke-Adb emu geo fix $Longitude.ToString($culture) $Latitude.ToString($culture) | Out-Null
}

function Get-DistanceKm($From, $To) {
    $rad = [Math]::PI / 180
    $x = ($To[0] - $From[0]) * $rad * [Math]::Cos(($From[1] + $To[1]) / 2 * $rad)
    $y = ($To[1] - $From[1]) * $rad
    return [Math]::Sqrt($x * $x + $y * $y) * 6371
}

if ((& $Adb -s $Serial get-state 2>$null) -ne "device") { throw "Emulator $Serial is not connected." }

if ($Prepare) {
    Set-Position $route[0][0] $route[0][1]
    Write-Host "Emulator moved to the start of the route. Start the recording in the app, then run this again without -Prepare."
    exit 0
}

$legs = for ($i = 0; $i -lt $route.Count - 1; $i++) { Get-DistanceKm $route[$i] $route[$i + 1] }
$totalKm = ($legs | Measure-Object -Sum).Sum
Write-Host ("Route {0:N1} km. The recording must already be running in the app." -f $totalKm)

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

$batteryStart = $null
if ($KmPerKwh -gt 0) {
    $batteryStart = [double](Invoke-RestMethod -UseBasicParsing -Uri "$console/vehicle/ev-battery").current_energy_kwh
    Write-Host ("Battery {0:N3} kWh, falling at {1} km/kWh: {2:N3} kWh over the route." -f $batteryStart, $KmPerKwh, ($totalKm / $KmPerKwh))
}

try {
    Start-Sleep -Seconds 2
    Set-Gear "Drive"
    $leg = 0
    $intoLeg = 0.0
    $driven = 0.0
    while ($leg -lt $legs.Count) {
        Start-Sleep -Seconds $IntervalSeconds
        # Two slow stretches and two fast ones, so the card has speeds to colour.
        $wave = (1 - [Math]::Cos($driven / $totalKm * 4 * [Math]::PI)) / 2
        $speed = $MinSpeedKmh + ($MaxSpeedKmh - $MinSpeedKmh) * $wave
        $step = $speed * $IntervalSeconds / 3600
        $driven += $step
        $intoLeg += $step
        while ($leg -lt $legs.Count -and $intoLeg -ge $legs[$leg]) {
            $intoLeg -= $legs[$leg]
            $leg++
        }
        if ($null -ne $batteryStart) { Set-BatteryKwh ($batteryStart - [Math]::Min($driven, $totalKm) / $KmPerKwh) }
        if ($leg -ge $legs.Count) { Set-Position $route[-1][0] $route[-1][1]; break }
        $t = if ($legs[$leg] -gt 0) { $intoLeg / $legs[$leg] } else { 0 }
        Set-Position ($route[$leg][0] + ($route[$leg + 1][0] - $route[$leg][0]) * $t) ($route[$leg][1] + ($route[$leg + 1][1] - $route[$leg][1]) * $t)
    }
} finally {
    # Never leave the emulator in Drive: the app stays locked until it returns to P.
    try { Set-Gear "Park" } catch { Write-Warning "Could not return to Park. Set it in the Virtual Console: $_" }
    Stop-Job $session -ErrorAction SilentlyContinue
    Remove-Job $session -Force -ErrorAction SilentlyContinue
}
if ($null -ne $batteryStart) {
    Write-Host ("Battery now {0:N3} kWh." -f [double](Invoke-RestMethod -UseBasicParsing -Uri "$console/vehicle/ev-battery").current_energy_kwh)
}
Write-Host "Done. End the recording in the app if the stop screen is showing."
