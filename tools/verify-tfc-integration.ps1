param(
    [Parameter(Mandatory = $true)][string]$VoxyJar,
    [string]$JavaHome = 'C:/Program Files/Java/jdk-21.0.11'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    $voxy = (Resolve-Path -LiteralPath $VoxyJar).Path
    $env:JAVA_HOME = $JavaHome
    & './gradlew.bat' -I tools/import-verification.init.gradle :1.21.1:classes :1.21.1:writeImportVerificationClasspath --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'TFC verification preparation failed' }
    $classpath = (Get-Content -LiteralPath 'build/import-verification-classpath.txt') -join ';'
    $output = Join-Path $repo ('build/tfc-integration-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $output | Out-Null
    $tests = @('VerifyTfcMerge', 'VerifyTfcOptions', 'VerifyTfcToggle', 'VerifyFogOptionAvailability')
    $sources = $tests | ForEach-Object { Join-Path $PSScriptRoot "${_}.java" }
    & "$JavaHome/bin/javac.exe" -proc:none -cp $classpath -d $output @sources
    if ($LASTEXITCODE -ne 0) { throw 'TFC verification compilation failed' }
    foreach ($test in $tests) {
        [string[]]$testArguments = @()
        if ($test -eq 'VerifyTfcMerge') { $testArguments = @($voxy) }
        & "$JavaHome/bin/java.exe" -ea -cp "$output;$classpath" $test @testArguments
        if ($LASTEXITCODE -ne 0) { throw "TFC verification failed: $test" }
    }
} finally {
    Pop-Location
}
