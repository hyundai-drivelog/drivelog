param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$SdkPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$resolvedApk = (Resolve-Path -LiteralPath $Apk).Path
if ([IO.Path]::GetFileName($resolvedApk) -notmatch '^[\p{L}\p{N}_-]+_DRIVELOG_release\.apk$') {
    throw '제출 파일명은 팀명_DRIVELOG_release.apk 형식이어야 합니다.'
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkPath 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$buildTools) { throw 'Android SDK build-tools를 찾을 수 없습니다.' }
$signer = Join-Path $buildTools.FullName 'apksigner.bat'
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
if (!(Test-Path -LiteralPath $signer) -or !(Test-Path -LiteralPath $aapt)) {
    throw 'apksigner 또는 aapt가 없습니다.'
}

& $signer verify --verbose $resolvedApk
if ($LASTEXITCODE -ne 0) { throw 'APK 서명 검증 실패. 서명 없는 release나 debug 서명 재사용을 제출 완료로 취급하지 마세요.' }
$badging = & $aapt dump badging $resolvedApk
if ($LASTEXITCODE -ne 0) { throw 'APK manifest 조회 실패.' }
if ($badging -match 'application-debuggable') { throw 'debuggable APK는 제출할 수 없습니다.' }
$package = $badging | Select-String "^package: name='com.drivelog.app'"
if (!$package) { throw 'DRIVELOG 앱 패키지가 아닙니다.' }

$hash = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$hashPath = $resolvedApk + '.sha256'
if (Test-Path -LiteralPath $hashPath) {
    $expected = ((Get-Content -LiteralPath $hashPath -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
    if ($hash -ne $expected) { throw 'SHA-256 파일과 APK가 일치하지 않습니다.' }
}
[pscustomobject]@{
    Apk = $resolvedApk
    Bytes = (Get-Item -LiteralPath $resolvedApk).Length
    Sha256 = $hash
    SignatureVerified = $true
    Debuggable = $false
    RuntimeVerified = $false
}

Write-Output '서명과 manifest 검사 완료. 실제 release 키의 인증서인지, 저장소 접근·기기 실행·기본 모드는 별도 확인하세요.'
