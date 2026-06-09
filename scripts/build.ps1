param(
    [string]$CcsRoot = "C:\ti\ccs1280",
    [string]$OutputDir = "output"
)

$plugins = Join-Path $CcsRoot "ccs\eclipse\plugins"
$baseDir = Split-Path $PSScriptRoot -Parent
$srcDir = Join-Path $baseDir "src"
$jarName = "com.qclaw.ccs.assistant_1.1.0.jar"

# --- Step 1: Build complete classpath ---
# These are the exact JARs needed for compilation
$requiredPatterns = @(
    "org.eclipse.ui_",
    "org.eclipse.ui.workbench_",
    "org.eclipse.ui.workbench.texteditor_",
    "org.eclipse.ui.editors_",
    "org.eclipse.ui.ide_",
    "org.eclipse.ui.win32_",
    "org.eclipse.jface_",
    "org.eclipse.jface.text_",
    "org.eclipse.text_",
    "org.eclipse.swt_",
    "org.eclipse.swt.win32.win32.x86_64_",
    "org.eclipse.core.runtime_",
    "org.eclipse.core.resources_",
    "org.eclipse.core.commands_",
    "org.eclipse.core.jobs_",
    "org.eclipse.equinox.common_",
    "org.eclipse.equinox.registry_",
    "org.eclipse.osgi_",
    "org.eclipse.e4.core.contexts_",
    "org.eclipse.e4.core.di_",
    "org.eclipse.e4.ui.services_",
    "org.eclipse.e4.ui.workbench_",
    "org.eclipse.emf.common_",
    "org.eclipse.emf.ecore_",
    "org.eclipse.emf.ecore.xmi_",
    "com.ibm.icu_"
)

$classpath = @()
foreach ($pattern in $requiredPatterns) {
    $found = Get-ChildItem $plugins -Filter "$pattern*.jar" | Where-Object { $_.Name -notmatch "\.source" } | Select-Object -First 1
    if ($found) {
        $classpath += $found.FullName
    } else {
        Write-Host "[WARN] Not found: $pattern" -ForegroundColor Yellow
    }
}

$cpString = $classpath -join ";"
Write-Host "Classpath ($($classpath.Count) JARs)"

# --- Step 2: Clean and compile ---
$buildDir = Join-Path $baseDir "target"
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item $buildDir -ItemType Directory | Out-Null

# Use argfile to avoid Windows command line length limit
$argfilePath = Join-Path $buildDir "javac_args.txt"
"-cp", $cpString, "-d", $buildDir, "--release", "11" | Set-Content $argfilePath -Encoding ASCII

# Find all .java files
$javaFiles = Get-ChildItem $srcDir -Recurse -Filter "*.java"
$javaFiles | ForEach-Object { $_.FullName } | Add-Content $argfilePath -Encoding ASCII

Write-Host "`nCompiling $($javaFiles.Count) files with --release 11..."
& javac "@$argfilePath" 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] Compilation failed" -ForegroundColor Red
    exit 1
}
Write-Host "[OK] Compilation successful" -ForegroundColor Green

# --- Step 3: Copy resources to build dir ---
# Copy plugin.xml and icons/ into build dir for packaging
$pluginXml = Join-Path $baseDir "plugin.xml"
if (Test-Path $pluginXml) {
    Copy-Item $pluginXml (Join-Path $buildDir "plugin.xml") -Force
    Write-Host "[OK] Copied plugin.xml to build dir"
} else {
    Write-Host "[FAIL] plugin.xml not found!" -ForegroundColor Red
    exit 1
}

$iconsDir = Join-Path $baseDir "icons"
if (Test-Path $iconsDir) {
    Copy-Item $iconsDir (Join-Path $buildDir "icons") -Recurse -Force
    Write-Host "[OK] Copied icons/ to build dir"
}

# Remove temp files from build dir so they don't get packaged
Remove-Item (Join-Path $buildDir "*_args.txt") -Force -ErrorAction SilentlyContinue

# --- Step 4: Package JAR ---
$outputPath = Join-Path $baseDir $OutputDir
if (-not (Test-Path $outputPath)) { New-Item $outputPath -ItemType Directory | Out-Null }

$jarOutput = Join-Path $outputPath $jarName

# Build argfile for jar command
$jarArgFile = Join-Path $buildDir "jar_args.txt"
"cfm", $jarOutput, (Join-Path $baseDir "META-INF\MANIFEST.MF"), "-C", $buildDir, "." | Set-Content $jarArgFile -Encoding ASCII

# Find jar from JDK
$jarCmd = Get-ChildItem "C:\Program Files\Java" -Recurse -Filter "jar.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jarCmd) {
    Write-Host "[FAIL] jar.exe not found" -ForegroundColor Red
    exit 1
}
Write-Host "jar: $($jarCmd.FullName)"

Write-Host "`nPackaging JAR..."
& $jarCmd.FullName "@$jarArgFile" 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] JAR creation failed" -ForegroundColor Red
    exit 1
}

# --- Step 5: Verify ---
$jarSize = (Get-Item $jarOutput).Length
Write-Host "[OK] JAR created: $jarOutput ($jarSize bytes)" -ForegroundColor Green

# Verify Java 11 class version
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarOutput)
$classes = $zip.Entries | Where-Object { $_.Name -like "*.class" }
foreach ($entry in $classes) {
    $stream = $entry.Open()
    $buf = New-Object byte[] 8
    $stream.Read($buf, 0, 8) | Out-Null
    $stream.Close()
    $ver = $buf[7]
    if ($ver -eq 55) {
        Write-Host "[OK] $($entry.FullName) version: $ver (Java 11)" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] $($entry.FullName) version: $ver (expected 55)" -ForegroundColor Red
    }
}
$zip.Dispose()

# --- Step 6: Deploy to CCS ---
# Remove old version JARs first
Get-ChildItem $plugins -Filter "com.qclaw.ccs.assistant*.jar" | ForEach-Object {
    Write-Host "[CLEAN] Removing old: $($_.Name)"
    Remove-Item $_.FullName -Force
}

$targetPlugin = Join-Path $plugins $jarName
Copy-Item $jarOutput $targetPlugin -Force
Write-Host "[OK] Deployed to: $targetPlugin" -ForegroundColor Green

# --- Step 7: Update bundles.info ---
$bundlesInfo = Join-Path $CcsRoot "ccs\eclipse\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"
$lines = [System.IO.File]::ReadAllLines($bundlesInfo, [System.Text.Encoding]::UTF8)

# Remove old qclaw entries
$newLines = $lines | Where-Object { $_ -notmatch "com\.qclaw\.ccs\.assistant" }
# Add new entry
$newLines += "com.qclaw.ccs.assistant,1.1.0,plugins/$jarName,4,false"

# Write back WITHOUT BOM (critical for OSGi!)
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllLines($bundlesInfo, $newLines, $utf8NoBom)
Write-Host "[OK] bundles.info updated (no BOM)" -ForegroundColor Green

# --- Step 8: Clean OSGi cache ---
$osgiCache = Join-Path $CcsRoot "ccs\eclipse\configuration\org.eclipse.osgi"
if (Test-Path $osgiCache) {
    Remove-Item $osgiCache -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] OSGi cache cleaned" -ForegroundColor Green
}

Write-Host "`n==========================================="
Write-Host "Build complete! Restart CCS to activate."
Write-Host "==========================================="
Write-Host "New features in v1.1.0:"
Write-Host "  - Diagnose Errors: right-click > QClaw > Diagnose Errors (Ctrl+2)"
Write-Host "  - Slash commands: /explain /optimize /fix /generate /review /docs /convert"
Write-Host "  - Token usage display in status bar"
Write-Host "==========================================="
