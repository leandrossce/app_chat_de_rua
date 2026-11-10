$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectPath = Split-Path -Parent $PSScriptRoot
$outputPath = Join-Path $projectPath 'publicacao-play-store'
$graphicsPath = Join-Path $outputPath 'graficos'
New-Item -ItemType Directory -Force -Path $graphicsPath | Out-Null
function Brush([string]$Hex) { return [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($Hex)) }
function Font([single]$Size, [bool]$Bold = $false) {
    $style = if ($Bold) { [Drawing.FontStyle]::Bold } else { [Drawing.FontStyle]::Regular }
    return [Drawing.Font]::new('Segoe UI', $Size, $style, [Drawing.GraphicsUnit]::Pixel)
}
function Bubble($Graphics, [single]$X, [single]$Y, [single]$Width, [single]$Height, $Fill, [bool]$Right) {
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $radius = [single]28
    $path.AddArc($X, $Y, $radius, $radius, 180, 90)
    $path.AddArc($X + $Width - $radius, $Y, $radius, $radius, 270, 90)
    $path.AddArc($X + $Width - $radius, $Y + $Height - $radius, $radius, $radius, 0, 90)
    $path.AddArc($X, $Y + $Height - $radius, $radius, $radius, 90, 90)
    $path.CloseFigure()
    $Graphics.FillPath($Fill, $path)
    $path.Dispose()
    $points = if ($Right) {
        [Drawing.PointF[]]@([Drawing.PointF]::new($X+$Width-60,$Y+$Height-2),
            [Drawing.PointF]::new($X+$Width-25,$Y+$Height+24),[Drawing.PointF]::new($X+$Width-25,$Y+$Height-2))
    } else {
        [Drawing.PointF[]]@([Drawing.PointF]::new($X+25,$Y+$Height-2),
            [Drawing.PointF]::new($X+25,$Y+$Height+24),[Drawing.PointF]::new($X+60,$Y+$Height-2))
    }
    $Graphics.FillPolygon($Fill, $points)
}
$white = Brush '#FFFFFF'
$dark = Brush '#103D40'
$mint = Brush '#C8EFE5'
$muted = Brush '#C2E4DE'
$accent = Brush '#67D2B7'
$bitmap = [Drawing.Bitmap]::new(1024, 500, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
$g = [Drawing.Graphics]::FromImage($bitmap)
$fonts = @((Font 27 $true), (Font 58 $true), (Font 29), (Font 23 $true))
try {
    $g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([Drawing.ColorTranslator]::FromHtml('#103D40'))
    $g.DrawString('CHAT DE RUA', $fonts[0], $muted, [single]64, [single]55)
    $g.DrawString("Converse`npor perto.", $fonts[1], $white, [Drawing.RectangleF]::new(60, 123, 560, 155))
    $g.DrawString("Pelo Bluetooth.`nMesmo sem internet.", $fonts[2], $muted,
        [Drawing.RectangleF]::new(64, 312, 520, 95))
    # Promotional illustration, kept separate from the unmodified app screenshots.
    Bubble $g 650 110 260 112 $white $false
    Bubble $g 706 270 240 112 $mint $true
    $g.FillRectangle($dark, 680, 140, 144, 10)
    $g.FillRectangle($dark, 680, 163, 195, 10)
    $g.FillRectangle($dark, 680, 186, 112, 10)
    foreach ($x in @(744, 790, 836)) { $g.FillEllipse($dark, $x, 316, 15, 15) }
    $pen = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#67D2B7'), 3)
    $pen.DashStyle = [Drawing.Drawing2D.DashStyle]::Dot
    $g.DrawLine($pen, 630, 424, 936, 424)
    $pen.Dispose()
    $bitmap.Save((Join-Path $graphicsPath 'destaque-1024x500.png'), [Drawing.Imaging.ImageFormat]::Png)
} finally {
    $g.Dispose(); $bitmap.Dispose()
    foreach ($font in $fonts) { $font.Dispose() }
    foreach ($brush in @($white, $dark, $mint, $muted, $accent)) { $brush.Dispose() }
}
$screens = Get-ChildItem (Join-Path $outputPath 'prints') -Filter '*.png' -Recurse | Sort-Object FullName
if ($screens.Count -gt 0) {
    $columns = ($screens | Group-Object DirectoryName | Measure-Object Count -Maximum).Maximum
    $sheet = [Drawing.Bitmap]::new([int](20 + 315 * $columns), 1260, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [Drawing.Graphics]::FromImage($sheet)
    $labelFont = Font 18 $true
    $brush = Brush '#103D40'
    try {
        $g.Clear([Drawing.Color]::White)
        $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $row = 0
        foreach ($profile in @('telefone', 'tablet-7', 'tablet-10')) {
            $column = 0
            foreach ($screen in ($screens | Where-Object { $_.Directory.Name -eq $profile })) {
                $image = [Drawing.Image]::FromFile($screen.FullName)
                $x = 30 + 315 * $column
                $y = 35 + 415 * $row
                $g.DrawString("$profile / $($screen.BaseName)", $labelFont, $brush, [single]$x, [single]($y-25))
                $width = [int](360 * $image.Width / $image.Height)
                $g.DrawImage($image, [Drawing.Rectangle]::new($x, $y, $width, 360))
                $image.Dispose()
                $column++
            }
            $row++
        }
        $sheet.Save((Join-Path $outputPath 'validacao\contato-das-telas.png'), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $g.Dispose(); $sheet.Dispose(); $labelFont.Dispose(); $brush.Dispose() }
}
$imageRecords = foreach ($file in Get-ChildItem $outputPath -Recurse -Filter '*.png') {
    $image = [Drawing.Image]::FromFile($file.FullName)
    try {
        [ordered]@{ arquivo = [IO.Path]::GetRelativePath($outputPath, $file.FullName);
            largura = $image.Width; altura = $image.Height; formato = $image.PixelFormat.ToString(); bytes = $file.Length }
    } finally { $image.Dispose() }
}
$limits = @(
    @{ File = '01-nome.txt'; Limit = 30 },
    @{ File = '02-descricao-curta.txt'; Limit = 80 },
    @{ File = '03-descricao-completa.txt'; Limit = 4000 },
    @{ File = '04-notas-da-versao.txt'; Limit = 500 }
)
$textRecords = foreach ($entry in $limits) {
    $content = (Get-Content -Raw -LiteralPath (Join-Path $outputPath "textos\$($entry.File)")).Trim()
    if ($content.Length -gt $entry.Limit) { throw "Texto excede limite: $($entry.File)" }
    [ordered]@{ arquivo = $entry.File; caracteres = $content.Length; limite = $entry.Limit }
}
$manifest = [ordered]@{
    data = '2026-11-08'; idioma = 'pt-BR'; pacote = 'com.capongasoftware.chat_de_rua';
    versionCode = 1; versionName = '1.0'; minSdk = 24; targetSdk = 36;
    idiomas_interface = @('pt-BR', 'en-US', 'de-DE', 'zh-CN', 'es-ES');
    capturas = 'Views reais renderizadas no Android 15 / API 35, conteúdo fictício, sem redimensionar a interface';
    imagens = @($imageRecords); textos = @($textRecords)
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $outputPath 'validacao\manifesto.json') -Encoding utf8
Write-Output "Assets e limites verificados: $(@($imageRecords).Count) imagens."
