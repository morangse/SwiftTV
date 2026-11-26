param(
    [string]$SdkRoot = 'C:\Android\Sdk',
    [string]$JdkRoot = 'C:\Program Files\Java\jdk-17'
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$installerRoot = Join-Path $repoRoot 'installer'
if (!(Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms\android-36\android.jar'))) { throw 'Android SDK platform 36 is required; set -SdkRoot.' }
if (!(Test-Path -LiteralPath (Join-Path $JdkRoot 'bin\java.exe'))) { throw 'JDK 17 is required; set -JdkRoot.' }
if (!(Test-Path -LiteralPath (Join-Path $installerRoot 'gradlew.bat'))) { throw 'installer project is missing.' }

$env:JAVA_HOME = $JdkRoot
$env:ANDROID_HOME = $SdkRoot
$escapedSdk = $SdkRoot.Replace('\','/').Replace(':','\:')
[IO.File]::WriteAllText((Join-Path $installerRoot 'local.properties'), 'sdk.dir=' + $escapedSdk + [Environment]::NewLine)
Push-Location $installerRoot
try {
    & '.\gradlew.bat' ':app:testDebugUnitTest' ':app:assembleDebug' ':app:assembleRelease' --no-daemon --console plain
    if ($LASTEXITCODE -ne 0) { throw 'Installer Gradle validation/build failed.' }
    $delivery = Join-Path $repoRoot 'output\delivery'
    [void][IO.Directory]::CreateDirectory($delivery)
    Copy-Item -LiteralPath 'app\build\outputs\apk\release\app-release.apk' -Destination (Join-Path $delivery 'SwiftTV-Installer-1.1.4.apk')
    Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination (Join-Path $delivery 'SwiftTV-Installer-1.1.4-debug.apk')
    Get-ChildItem -LiteralPath $delivery -Filter 'SwiftTV-Installer-*.apk' | Get-FileHash -Algorithm SHA256 | Select-Object Path,Hash
} finally { Pop-Location }
