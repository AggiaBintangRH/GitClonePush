param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [string]$SdkPath = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' })
)

$ErrorActionPreference = 'Stop'
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkPath 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'RELEASE_001_SDK_TOOLS_MISSING: Install Android SDK Build-Tools.' }
$signer = Join-Path $buildTools.FullName 'apksigner.bat'
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$signature = & $signer verify --verbose --print-certs $apk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'RELEASE_002_INVALID_SIGNATURE: Generate a signed release APK with your existing release key.' }
if (($signature -join "`n") -match '(?i)CN=Android Debug') { throw 'RELEASE_003_DEBUG_KEY: Do not distribute an APK signed with the Android debug key.' }

$badging = & $aapt dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'RELEASE_004_INVALID_APK: APK metadata could not be read.' }
if (($badging -join "`n") -notmatch "(?m)^package: name='com\.threeastudio\.gitclonepush'") {
    throw 'RELEASE_009_WRONG_APPLICATION: This verifier is for GitClonePush only.'
}
$tree = & $aapt dump xmltree $apk AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'RELEASE_004_INVALID_APK: Manifest could not be read.' }
$manifestText = $tree -join "`n"
if ($manifestText -match 'android:debuggable[^\r\n]*0xffffffff') { throw 'RELEASE_005_DEBUGGABLE: Build the release variant.' }
foreach ($attribute in @('allowBackup', 'usesCleartextTraffic')) {
    if ($manifestText -notmatch "android:$attribute[^\r\n]*\(type 0x12\)0x0(?:\s|$)") {
        throw "RELEASE_006_UNSAFE_POLICY: $attribute must explicitly be false. Rebuild with current source."
    }
}
if ($manifestText -notmatch 'android:networkSecurityConfig') { throw 'RELEASE_006_UNSAFE_POLICY: Network security configuration is missing.' }
$target = [regex]::Match(($badging -join "`n"), "targetSdkVersion:'(\d+)'")
if (-not $target.Success -or [int]$target.Groups[1].Value -lt 36) { throw 'RELEASE_007_TARGET_SDK: This release must target API 36 or newer.' }
$privateReceiverPermission = 'com.threeastudio.gitclonepush.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
if ($manifestText -notmatch ('(?s)E: permission[^\r\n]*\r?\n(?:(?!E: ).)*' + [regex]::Escape($privateReceiverPermission) + '(?:(?!E: ).)*android:protectionLevel[^\r\n]*\(type 0x11\)0x2(?:\s|$)')) {
    throw 'RELEASE_008_PERMISSION_REVIEW: The AndroidX private receiver permission must be signature-protected.'
}
$allowed = @('android.permission.INTERNET', 'android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE', $privateReceiverPermission)
foreach ($line in $badging) {
    if ($line -match "^uses-permission(?:-sdk-\d+)?: name='([^']+)'") {
        if ($Matches[1] -notin $allowed) { throw "RELEASE_008_PERMISSION_REVIEW: Unexpected permission $($Matches[1])." }
    }
}
Write-Output 'PASS: signature, non-debug release, target SDK, permissions, and manifest security policy.'
Write-Output "APK SHA-256: $((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)"
Write-Output 'This is a local distribution check, not Google Play Protect approval or a malware scan.'
