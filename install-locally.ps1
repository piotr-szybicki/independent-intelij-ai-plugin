param(
    [string]$IdeVersion = '2026.2'
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$staging = Join-Path ([System.IO.Path]::GetTempPath()) ("ai-plugin-install-" + [guid]::NewGuid())

try {
    Push-Location $projectRoot

    # Use IntelliJ's bundled Java if JAVA_HOME has not already been configured.
    if (-not $env:JAVA_HOME) {
        $installations = @(Get-ChildItem 'C:\Program Files\JetBrains' -Directory -Filter 'IntelliJ IDEA*' -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'jbr\bin\java.exe') })
        if ($installations.Count -eq 1) {
            $env:JAVA_HOME = Join-Path $installations[0].FullName 'jbr'
        }
    }

    Write-Host 'Building the plugin...'
    & (Join-Path $projectRoot 'gradlew.bat') buildPlugin
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }

    $versionLine = Get-Content (Join-Path $projectRoot 'gradle.properties') |
        Where-Object { $_ -match '^\s*version\s*=' } | Select-Object -First 1
    if (-not $versionLine) { throw 'Could not find the plugin version in gradle.properties.' }
    $version = ($versionLine -split '=', 2)[1].Trim()
    $archive = Join-Path $projectRoot "build\distributions\IntelliJ Platform Plugin Template-$version.zip"
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) { throw "Built archive not found: $archive" }

    Expand-Archive -LiteralPath $archive -DestinationPath $staging
    $source = Join-Path $staging 'IntelliJ Platform Plugin Template\lib'
    $jars = @(Get-ChildItem -LiteralPath $source -Filter '*.jar' -File)
    if ($jars.Count -eq 0) { throw "No plugin JARs found in $archive." }
    $destination = Join-Path $env:APPDATA "JetBrains\IntelliJIdea$IdeVersion\plugins\IntelliJ Platform Plugin Template\lib"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    foreach ($jar in $jars) {
        $target = Join-Path $destination $jar.Name
        Copy-Item -LiteralPath $jar.FullName -Destination $target -Force
        if ((Get-FileHash -LiteralPath $jar.FullName).Hash -ne (Get-FileHash -LiteralPath $target).Hash) {
            throw "Verification failed for $target."
        }
    }

    Write-Host "Installed and verified $($jars.Count) JAR(s) in $destination"
    Write-Host 'If IntelliJ IDEA is running, restart it to load the plugin.'
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
}
