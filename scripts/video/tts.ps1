<#
.SYNOPSIS
Makes the demo video's narration with the Windows Korean voice.

.DESCRIPTION
Reads id|text lines (UTF-8) and writes <OutDir>\<id>.wav for each, using Microsoft Heami.
Write numbers and English the way they should be read (드라이브로그, 피, 쉰여섯).

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\video\tts.ps1 -Lines scripts\video\narration.txt -OutDir .local-qa\video-2026-11-13\audio
#>
param(
    [Parameter(Mandatory)][string]$Lines,
    [Parameter(Mandatory)][string]$OutDir,
    [ValidateRange(-10, 10)][int]$Rate = 1
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech
New-Item -ItemType Directory -Force $OutDir | Out-Null
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice("Microsoft Heami Desktop")
$s.Rate = $Rate
Get-Content -Encoding UTF8 $Lines | Where-Object { $_ -match '\|' } | ForEach-Object {
    $p = $_.Split("|", 2)
    $s.SetOutputToWaveFile((Join-Path $OutDir ($p[0].Trim() + ".wav")))
    $s.Speak($p[1].Trim())
}
$s.SetOutputToNull()
$s.Dispose()
