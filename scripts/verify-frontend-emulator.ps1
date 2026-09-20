param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('emulator-5554', 'emulator-5580', 'emulator-5582')]
    [string]$Serial,
    [Parameter(Mandatory = $true)]
    [ValidateSet(26, 37)]
    [int]$Api
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$appApk = Join-Path $workspace 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $workspace 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$resultDir = Join-Path $workspace "build/frontend-review/api$Api"
if (!(Test-Path -LiteralPath $appApk) -or !(Test-Path -LiteralPath $testApk)) { throw 'Build both APKs first.' }
$emulatorFlag = (& adb -s $Serial shell getprop ro.kernel.qemu).Trim()
$deviceApi = (& adb -s $Serial shell getprop ro.build.version.sdk).Trim()
$avdName = (& adb -s $Serial emu avd name | Select-Object -First 1).Trim()
if ($emulatorFlag -ne '1' -or $deviceApi -ne "$Api" -or $avdName -ne "anitabi-redesign-api$Api") {
    throw 'Refusing to modify a device outside the dedicated frontend AVDs.'
}
New-Item -ItemType Directory -Path $resultDir -Force | Out-Null
& adb -s $Serial install -r $appApk
if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
& adb -s $Serial install -r $testApk
if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
& adb -s $Serial shell settings put global window_animation_scale 0
& adb -s $Serial shell settings put global transition_animation_scale 0
& adb -s $Serial shell settings put global animator_duration_scale 0
# This UI-only suite must never request an emulator location fix.
& adb -s $Serial shell pm revoke cn.anitabi.navigator android.permission.ACCESS_FINE_LOCATION
& adb -s $Serial shell pm revoke cn.anitabi.navigator android.permission.ACCESS_COARSE_LOCATION
$classes = 'cn.anitabi.navigator.ui.discovery.DiscoveryUiContractTest,cn.anitabi.navigator.ui.UiRedesignContractTest,cn.anitabi.navigator.security.AppSettingsStoreMigrationTest,cn.anitabi.navigator.ui.AppShellInstrumentedTest,cn.anitabi.navigator.ui.FrontendRemainingFlowsTest,cn.anitabi.navigator.ui.DiscoveryStartupInstrumentedTest'
$testLog = Join-Path $resultDir 'instrumentation.txt'
& adb -s $Serial shell am instrument -w -e captureFrontendReview true -e class $classes cn.anitabi.navigator.test/cn.anitabi.navigator.TestAnitabiRunner 2>&1 | Set-Content -LiteralPath $testLog -Encoding UTF8
$result = Get-Content -LiteralPath $testLog -Raw
& adb -s $Serial pull /sdcard/Android/data/cn.anitabi.navigator/files/frontend-review $resultDir | Out-Null
@{
    api = $Api
    avd = $avdName
    appSha256 = (Get-FileHash -LiteralPath $appApk -Algorithm SHA256).Hash.ToLowerInvariant()
    testSha256 = (Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash.ToLowerInvariant()
    capturedAtUtc = [DateTime]::UtcNow.ToString('o')
    passed = [bool]($result -match 'OK \(\d+ tests?\)' -and $result -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED')
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultDir 'evidence.json') -Encoding UTF8
Get-Content -LiteralPath $testLog -Tail 65
if ($result -notmatch 'OK \(\d+ tests?\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { exit 1 }
