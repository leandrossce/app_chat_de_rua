param(
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    [string]$Serial = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectPath = Split-Path -Parent $PSScriptRoot
$outputPath = Join-Path $projectPath 'publicacao-play-store'
$packageName = 'com.capongasoftware.chat_de_rua'
function Invoke-Adb([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n")
}
function Copy-DeviceFile([string]$Remote, [string]$Local) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Adb
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in @('-s', $Serial, 'exec-out', 'run-as', $packageName, 'cat', $Remote)) {
        $info.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::Start($info)
    $stream = [IO.File]::Create($Local)
    try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw $process.StandardError.ReadToEnd() }
    $process.Dispose()
}
function Save-RgbPng([string]$Path) {
    $original = [Drawing.Image]::FromFile($Path)
    $rgb = [Drawing.Bitmap]::new($original.Width, $original.Height,
            [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $graphics = [Drawing.Graphics]::FromImage($rgb)
    try {
        $graphics.Clear([Drawing.Color]::FromArgb(244, 247, 247))
        $graphics.DrawImageUnscaled($original, 0, 0)
    } finally { $graphics.Dispose(); $original.Dispose() }
    try { $rgb.Save($Path, [Drawing.Imaging.ImageFormat]::Png) } finally { $rgb.Dispose() }
}
$originalSize = Invoke-Adb @('shell', 'wm', 'size')
$originalDensity = Invoke-Adb @('shell', 'wm', 'density')
$sizeOverride = if ($originalSize -match 'Override size:\s*(\d+x\d+)') { $Matches[1] } else { 'reset' }
$densityOverride = if ($originalDensity -match 'Override density:\s*(\d+)') { $Matches[1] } else { 'reset' }
$profiles = @(
    @{ Name = 'telefone'; Width = 1080; Height = 1920; Density = 420 },
    @{ Name = 'tablet-7'; Width = 1080; Height = 1920; Density = 280 },
    @{ Name = 'tablet-10'; Width = 1440; Height = 2560; Density = 280 }
)
$files = @('01-inicio.png', '02-senha-da-sala.png', '03-conversa.png', '04-figurinhas.png',
    '05-selecao-de-idioma.png')
try {
    foreach ($profile in $profiles) {
        $destination = Join-Path $outputPath "prints\$($profile.Name)"
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        Invoke-Adb @('shell', 'am', 'force-stop', $packageName) | Out-Null
        Invoke-Adb @('shell', 'wm', 'size', "$($profile.Width)x$($profile.Height)") | Out-Null
        Invoke-Adb @('shell', 'wm', 'density', "$($profile.Density)") | Out-Null
        $result = Invoke-Adb @('shell', 'am', 'instrument', '-w', '-e', 'suite', 'screenshots',
                "$packageName.test/$packageName.StickerInstrumentation")
        $result | Set-Content -LiteralPath (Join-Path $outputPath "validacao\captura-$($profile.Name).txt") -Encoding utf8
        if ($result -notmatch 'OK: capturas salvas') { throw $result }
        foreach ($name in $files) {
            $local = Join-Path $destination $name
            Copy-DeviceFile "files/play-store/$name" $local
            Save-RgbPng $local
            $check = [Drawing.Image]::FromFile($local)
            try {
                if ($check.Width -ne $profile.Width -or $check.Height -ne $profile.Height) {
                    throw "Unexpected screenshot size: $($check.Width)x$($check.Height)"
                }
            } finally { $check.Dispose() }
        }
        Copy-DeviceFile 'files/play-store/icone-512.png' (Join-Path $outputPath 'graficos\icone-512x512.png')
        Write-Output "Capturado: $($profile.Name), $($profile.Width)x$($profile.Height), $($files.Count) telas."
    }
} finally {
    Invoke-Adb @('shell', 'wm', 'size', $sizeOverride) | Out-Null
    Invoke-Adb @('shell', 'wm', 'density', $densityOverride) | Out-Null
}
