param(
    [string]$SdkRoot = 'C:\Android\Sdk',
    [string]$JdkRoot = 'C:\Program Files\Java\jdk-17',
    [switch]$Release
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!(Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms\android-36\android.jar'))) { throw 'Android SDK platform 36 is required; set -SdkRoot.' }
if (!(Test-Path -LiteralPath (Join-Path $JdkRoot 'bin\java.exe'))) { throw 'JDK 17 is required; set -JdkRoot.' }

$gradleProps = Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties')
$versionLine = $gradleProps | Where-Object { $_ -match '^swifttv\.versionName=' } | Select-Object -First 1
$versionName = if ($versionLine) { ($versionLine -split '=', 2)[1].Trim() } else { '0.4.4' }

$env:JAVA_HOME = $JdkRoot
$env:ANDROID_HOME = $SdkRoot
$escapedSdk = $SdkRoot.Replace('\','/').Replace(':','\:')
[IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), 'sdk.dir=' + $escapedSdk + [Environment]::NewLine)
if ($Release) {
    $signingRoot = Join-Path $projectRoot '.signing'
    [void][IO.Directory]::CreateDirectory($signingRoot)
    $keyStore = Join-Path $signingRoot 'swifttv-release.p12'
    $properties = Join-Path $signingRoot 'signing.properties'
    if (!(Test-Path -LiteralPath $keyStore)) {
        if (Test-Path -LiteralPath $properties) { throw 'Signing properties exist without their keystore. Restore the keystore before building.' }
        $randomBytes = New-Object byte[] 32
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($randomBytes)
        $signingPassword = [Convert]::ToBase64String($randomBytes)
        $env:SWIFTV_SIGNING_PASSWORD = $signingPassword
        try {
            & (Join-Path $JdkRoot 'bin\keytool.exe') -genkeypair -keystore $keyStore -storetype PKCS12 -storepass:env SWIFTV_SIGNING_PASSWORD -keypass:env SWIFTV_SIGNING_PASSWORD -alias swifttv -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=SwiftTV Local Release'
            if ($LASTEXITCODE -ne 0) { throw 'Release keystore generation failed.' }
            [IO.File]::WriteAllText($properties, 'storePassword=' + $signingPassword, [Text.UTF8Encoding]::new($false))
        } finally { Remove-Item Env:\SWIFTV_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
    }
    if (!(Test-Path -LiteralPath $properties)) { throw 'Restore .signing/signing.properties; never replace a published signing identity.' }
}
Push-Location $projectRoot
try {
    $tasks = @(
        ':app:testStandardDebugUnitTest', ':app:testLiteDebugUnitTest',
        ':app:lintStandardDebug', ':app:lintLiteDebug',
        ':app:assembleStandardDebug', ':app:assembleLiteDebug',
        ':app:assembleStandardDebugAndroidTest', ':app:assembleLiteDebugAndroidTest'
    )
    if ($Release) { $tasks += @(':app:assembleStandardRelease', ':app:assembleLiteRelease') }
    & '.\gradlew.bat' @tasks --no-daemon --console plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle validation/build failed.' }
    $delivery = Join-Path $projectRoot 'output\delivery'
    [void][IO.Directory]::CreateDirectory($delivery)
    Copy-Item -LiteralPath 'app\build\outputs\apk\standard\debug\app-standard-debug.apk' -Destination (Join-Path $delivery "SwiftTV-$versionName-debug.apk")
    Copy-Item -LiteralPath 'app\build\outputs\apk\lite\debug\app-lite-debug.apk' -Destination (Join-Path $delivery "SwiftTV-Lite-$versionName-debug.apk")
    Copy-Item -LiteralPath 'app\build\outputs\apk\androidTest\standard\debug\app-standard-debug-androidTest.apk' -Destination (Join-Path $delivery "SwiftTV-$versionName-tests.apk")
    Copy-Item -LiteralPath 'app\build\outputs\apk\androidTest\lite\debug\app-lite-debug-androidTest.apk' -Destination (Join-Path $delivery "SwiftTV-Lite-$versionName-tests.apk")
    if ($Release) {
        Copy-Item -LiteralPath 'app\build\outputs\apk\standard\release\app-standard-release.apk' -Destination (Join-Path $delivery "SwiftTV-$versionName.apk")
        Copy-Item -LiteralPath 'app\build\outputs\apk\lite\release\app-lite-release.apk' -Destination (Join-Path $delivery "SwiftTV-Lite-$versionName.apk")
    }
    Get-ChildItem -LiteralPath $delivery -Filter "SwiftTV*-$versionName*.apk" | Get-FileHash -Algorithm SHA256 | Select-Object Path,Hash
} finally { Pop-Location }
