# Build libgphoto2 + libusb for Android using NDK
# Run from project root (PowerShell): .\scripts\build-gphoto2-android.ps1
# Requires: MSYS2 with make + tar + bash, Android NDK.
param(
    [string]$NdkRoot = "D:\Android\Sdk\ndk\25.2.9519653",
    [string]$BuildRoot = "E:\PhtonView\.gphoto2-build",
    [string]$InstallRoot = "E:\PhtonView\app\gphoto2\prebuilt",
    [string]$MsysBash = "D:\msys2\usr\bin\bash.exe"
)

$ErrorActionPreference = "Stop"

function Convert-ToMsysPath($winPath) {
    $winPath = $winPath -replace '\\', '/'
    if ($winPath -match '^([A-Za-z]):/(.*)$') {
        return "/$($matches[1].ToLower())/$($matches[2])"
    }
    return $winPath
}

if (-not (Test-Path $MsysBash)) {
    Write-Error "MSYS2 bash not found at $MsysBash"
}

function Invoke-Msys($command) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $MsysBash
    $psi.Arguments = "-lc `"$command`""
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.WorkingDirectory = (Get-Location).Path
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    if ($stdout) { Write-Host $stdout }
    if ($stderr) { Write-Warning $stderr }
    if ($proc.ExitCode -ne 0) { throw "MSYS2 command failed: $command" }
}

$makePath = "$($MsysBash -replace 'bash.exe$', 'make.exe')"
if (-not (Test-Path $makePath)) {
    Write-Host "GNU make not found inside MSYS2. Installing..." -ForegroundColor Yellow
    Invoke-Msys "pacman -S --noconfirm make"
}
Write-Host "Using make: $makePath"

if (-not (Test-Path $NdkRoot)) {
    Write-Error "NDK not found at $NdkRoot"
}

$abis = @(
    @{Name="arm64-v8a"; Triple="aarch64-linux-android"; Api=24},
    @{Name="armeabi-v7a"; Triple="arm-linux-androideabi"; Api=24}
)

$libusbVersion = "1.0.26"
$libtoolVersion = "2.4.7"
$gphoto2Version = "2.5.31"

New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null

$BuildRootMsys = Convert-ToMsysPath $BuildRoot
$InstallRootMsys = Convert-ToMsysPath $InstallRoot
$NdkRootMsys = Convert-ToMsysPath $NdkRoot

$libusbTar = "$BuildRoot\libusb-$libusbVersion.tar.bz2"
$libtoolTar = "$BuildRoot\libtool-$libtoolVersion.tar.xz"
$gphoto2Tar = "$BuildRoot\libgphoto2-$gphoto2Version.tar.bz2"

if (-not (Test-Path $libusbTar)) {
    Write-Host "Downloading libusb..."
    Invoke-WebRequest -Uri "https://github.com/libusb/libusb/releases/download/v$libusbVersion/libusb-$libusbVersion.tar.bz2" -OutFile $libusbTar
}
if (-not (Test-Path $libtoolTar)) {
    Write-Host "Downloading libtool..."
    Invoke-WebRequest -Uri "https://ftpmirror.gnu.org/libtool/libtool-$libtoolVersion.tar.xz" -OutFile $libtoolTar
}
if (-not (Test-Path $gphoto2Tar)) {
    Write-Host "Downloading libgphoto2..."
    Invoke-WebRequest -Uri "https://github.com/gphoto/libgphoto2/releases/download/v$gphoto2Version/libgphoto2-$gphoto2Version.tar.bz2" -OutFile $gphoto2Tar
}

$libusbSrc = "$BuildRoot\libusb-$libusbVersion"
$libtoolSrc = "$BuildRoot\libtool-$libtoolVersion"
$gphoto2Src = "$BuildRoot\libgphoto2-$gphoto2Version"

if (-not (Test-Path $libusbSrc)) {
    tar -xjf $libusbTar -C $BuildRoot
}
if (-not (Test-Path $libtoolSrc)) {
    tar -xJf $libtoolTar -C $BuildRoot
}
if (-not (Test-Path $gphoto2Src)) {
    tar -xjf $gphoto2Tar -C $BuildRoot
}

function Get-BuildEnv($ndk, $abi) {
    $binMsys = "$NdkRootMsys/toolchains/llvm/prebuilt/windows-x86_64/bin"
    $cc = "$binMsys/$($abi.Triple)$($abi.Api)-clang"
    $cxx = "$binMsys/$($abi.Triple)$($abi.Api)-clang++"
    $ar = "$binMsys/llvm-ar"
    $ranlib = "$binMsys/llvm-ranlib"
    $strip = "$binMsys/llvm-strip"
    $sysroot = "$NdkRootMsys/toolchains/llvm/prebuilt/windows-x86_64/sysroot"
    $prefixMsys = "$InstallRootMsys/$($abi.Name)"
    return @{
        CC = $cc
        CXX = $cxx
        AR = $ar
        RANLIB = $ranlib
        STRIP = $strip
        CFLAGS = "--sysroot=$sysroot -I$prefixMsys/include -fPIC"
        LDFLAGS = "--sysroot=$sysroot -L$prefixMsys/lib"
        PKG_CONFIG_PATH = "$prefixMsys/lib/pkgconfig"
        PREFIX = $prefixMsys
    }
}

foreach ($abi in $abis) {
    $buildEnv = Get-BuildEnv $NdkRoot $abi
    New-Item -ItemType Directory -Force -Path "$InstallRoot\$($abi.Name)\lib" | Out-Null
    New-Item -ItemType Directory -Force -Path "$InstallRoot\$($abi.Name)\include" | Out-Null

    # Build libusb
    $buildDir = "$BuildRoot\libusb-build-$($abi.Name)"
    $buildDirMsys = "$BuildRootMsys/libusb-build-$($abi.Name)"
    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

    Write-Host "Configuring libusb for $($abi.Name)..."
    Invoke-Msys "cd '$buildDirMsys' && '$BuildRootMsys/libusb-$libusbVersion/configure' --host=$($abi.Triple) --prefix=$($buildEnv.PREFIX) --disable-udev --enable-shared --disable-static CC=$($buildEnv.CC) CXX=$($buildEnv.CXX) AR=$($buildEnv.AR) RANLIB=$($buildEnv.RANLIB) CFLAGS='$($buildEnv.CFLAGS)' LDFLAGS='$($buildEnv.LDFLAGS)'"

    Write-Host "Building libusb for $($abi.Name)..."
    Invoke-Msys "cd '$buildDirMsys' && make -j4"

    Write-Host "Installing libusb for $($abi.Name)..."
    Invoke-Msys "cd '$buildDirMsys' && make install"

    # Build libtool (libltdl) for libgphoto2
    $buildDirLtdl = "$BuildRoot\libtool-build-$($abi.Name)"
    $buildDirLtdlMsys = "$BuildRootMsys/libtool-build-$($abi.Name)"
    New-Item -ItemType Directory -Force -Path $buildDirLtdl | Out-Null

    Write-Host "Configuring libtool for $($abi.Name)..."
    Invoke-Msys "cd '$buildDirLtdlMsys' && '$BuildRootMsys/libtool-$libtoolVersion/configure' --host=$($abi.Triple) --prefix=$($buildEnv.PREFIX) --enable-shared --disable-static CC=$($buildEnv.CC) CXX=$($buildEnv.CXX) AR=$($buildEnv.AR) RANLIB=$($buildEnv.RANLIB) CFLAGS='$($buildEnv.CFLAGS)' LDFLAGS='$($buildEnv.LDFLAGS)'"

    Write-Host "Building libtool for $($abi.Name)..."
    Invoke-Msys "cd '$buildDirLtdlMsys' && make -j4"

    Write-Host "Installing libtool for $($abi.Name)..."
    Invoke-Msys "cd '$buildDirLtdlMsys' && make install"

    # Build libgphoto2
    $buildDir2 = "$BuildRoot\gphoto2-build-$($abi.Name)"
    $buildDir2Msys = "$BuildRootMsys/gphoto2-build-$($abi.Name)"
    New-Item -ItemType Directory -Force -Path $buildDir2 | Out-Null

    Write-Host "Configuring libgphoto2 for $($abi.Name)..."
    Invoke-Msys "cd '$buildDir2Msys' && '$BuildRootMsys/libgphoto2-$gphoto2Version/configure' --host=$($abi.Triple) --prefix=$($buildEnv.PREFIX) --enable-shared --disable-static --with-libusb=$($buildEnv.PREFIX) --disable-serial --without-libxml-2.0 --without-gd CC=$($buildEnv.CC) CXX=$($buildEnv.CXX) AR=$($buildEnv.AR) RANLIB=$($buildEnv.RANLIB) CFLAGS='$($buildEnv.CFLAGS)' LDFLAGS='$($buildEnv.LDFLAGS)' PKG_CONFIG_PATH=$($buildEnv.PKG_CONFIG_PATH)"

    Write-Host "Building libgphoto2 for $($abi.Name)..."
    Invoke-Msys "cd '$buildDir2Msys' && make -j4"

    Write-Host "Installing libgphoto2 for $($abi.Name)..."
    Invoke-Msys "cd '$buildDir2Msys' && make install"
}

# Copy headers to common include path
$commonInclude = "$InstallRoot\include"
New-Item -ItemType Directory -Force -Path $commonInclude | Out-Null
$firstAbi = $abis[0]
robocopy "$InstallRoot\$($firstAbi.Name)\include" $commonInclude /E /NFL /NDL

Write-Host "Build complete. Prebuilt libraries are in $InstallRoot" -ForegroundColor Green
