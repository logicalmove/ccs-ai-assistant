param(
    [string]$CcsRoot = "C:\ti\ccs1280",
    [string]$OutputDir = "output"
)

$plugins = Join-Path $CcsRoot "ccs\eclipse\plugins"
$baseDir = Split-Path $PSScriptRoot -Parent
$srcDir = Join-Path $baseDir "src"
$jarName = "com.qclaw.ccs.assistant_1.0.0.jar"

# --- Step 1: Collect Eclipse JARs ---
$requiredJars = @(
    "org.eclipse.ui",
    "org.eclipse.core.runtime",
    "org.eclipse.jface",
    "org.eclipse.swt",
    "org.eclipse.jface.text",
    "org.eclipse.ui.workbench.texteditor",
    "org.eclipse.ui.editors",
    "org.eclipse.osgi",
    "org.eclipse.equinox.common",
    "org.eclipse.core.jobs",
    "org.eclipse.swt.win32.win32.x86_64"
)

$classpath = @()
foreach ($name in $requiredJars) {
    $found = Get-ChildItem $plugins -Filter "$name*.jar" | Select-Object -First 1
    if ($found) {
        $classpath += $found.FullName
    } else {
        Write-Host "[WARN] Not found: $name" -ForegroundColor Yellow
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

# --- Step 3: Package JAR ---
$outputPath = Join-Path $baseDir $OutputDir
if (-not (Test-Path $outputPath)) { New-Item $outputPath -ItemType Directory | Out-Null }

$jarOutput = Join-Path $outputPath $jarName

# Build argfile for jar command
$jarArgFile = Join-Path $buildDir "jar_args.txt"
"cfm", $jarOutput, (Join-Path $baseDir "META-INF\MANIFEST.MF"), "-C", $buildDir, "." | Set-Content $jarArgFile -Encoding ASCII

Write-Host "`nPackaging JAR..."
& jar "@$jarArgFile" 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] JAR creation failed" -ForegroundColor Red
    exit 1
}

# --- Step 4: Verify ---
$jarSize = (Get-Item $jarOutput).Length
Write-Host "[OK] JAR created: $jarOutput ($jarSize bytes)" -ForegroundColor Green

# Verify Java 11 class version
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarOutput)
$entry = $zip.GetEntry("com/qclaw/ccs/assistant/AIAssistantView.class")
if ($entry) {
    $stream = $entry.Open()
    $buf = New-Object byte[] 8
    $stream.Read($buf, 0, 8) | Out-Null
    $stream.Close()
    $ver = $buf[7]
    Write-Host "[OK] Class version: $ver (55=Java 11)" -ForegroundColor Green
    if ($ver -ne 55) {
        Write-Host "[WARN] Unexpected version $ver (expected 55 for Java 11)" -ForegroundColor Yellow
    }
}
$zip.Dispose()

# --- Step 5: Deploy (optional) ---
$targetPlugin = Join-Path $plugins $jarName
Write-Host "`nTo deploy, copy to: $targetPlugin"
Write-Host "Then clean OSGi cache and restart CCS."
