param(
    [string]$Ffmpeg = 'C:\ffmpeg.exe',
    [string]$Ffprobe = 'C:\ffprobe.exe'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectPath = Split-Path -Parent $PSScriptRoot
$publicationPath = Join-Path $projectPath 'publicacao-play-store'
$outputPath = Join-Path $publicationPath 'video-youtube'
$workingPath = Join-Path $outputPath '_producao'
New-Item -ItemType Directory -Force -Path $workingPath | Out-Null
$screenPath = Join-Path $publicationPath 'prints\telefone'

$scenes = @(
    @{ Tag = 'CONHEÇA O APP'; Title = "Chat de Rua.`nVeja como usar.";
       Lead = "Converse com quem está por perto,`npelo Bluetooth e sem internet.";
       Points = @('Sem cadastro para conversar.', 'Instale o app nos dois aparelhos.', 'Um guia rápido para começar.');
       Screen = '01-inicio.png'; Caption = 'Interface real do aplicativo';
       Voice = 'Este é o Chat de Rua. Converse com outra pessoa por perto, pelo Bluetooth, sem usar internet.' },
    @{ Tag = '01 / IDIOMA'; Title = "Escolha o idioma`nda interface.";
       Lead = "São cinco opções para você escolher.`nA preferência fica salva no aparelho.";
       Points = @('Escolha no primeiro uso e toque em Continuar.', 'Para mudar depois, use o botão Idioma.', 'A troca funciona sem internet.');
       Screen = '05-selecao-de-idioma.png'; Caption = 'Seletor dos cinco idiomas';
       Voice = 'Primeiro, escolha o idioma da interface. São cinco opções. Você pode mudar depois pelo botão Idioma, na tela inicial.' },
    @{ Tag = '02 / CONEXÃO'; Title = "Conecte dois`naparelhos próximos.";
       Lead = "Instale o app nos dois aparelhos,`nligue o Bluetooth e permita os`nacessos solicitados pelo Android.";
       Points = @('No primeiro aparelho: Abrir Sala.', 'No outro aparelho: Buscar Salas.', 'Confirme o pareamento e aceite a entrada.');
       Screen = '01-inicio.png'; Caption = 'Confirme também os pedidos do Android';
       Highlights = @(@(42, 625, 996, 126), @(42, 773, 996, 126));
       Voice = 'Instale o app nos dois aparelhos e ligue o Bluetooth. Em um, toque em Abrir Sala. No outro, em Buscar Salas. Confirme o pareamento e aceite a entrada.' },
    @{ Tag = '03 / SENHA DA SALA'; Title = "Combine uma`nsenha própria.";
       Lead = "Use a mesma senha nos dois aparelhos`npara ler as mensagens da conversa.";
       Points = @('A senha padrão é igual para todos.', 'Para mais privacidade, desative essa opção.', 'Combine uma senha própria com a outra pessoa.');
       Screen = '02-senha-da-sala.png'; Caption = 'Senha digitada não é salva nem enviada';
       Voice = 'Use a mesma senha nos dois aparelhos. A senha padrão é igual para todos. Para mais privacidade, desative a opção e combine uma senha própria.' },
    @{ Tag = '04 / MENSAGENS E MÍDIA'; Title = "Troque mensagens`ne compartilhe.";
       Lead = "Com a conexão ativa, envie texto,`nimagens, áudios e arquivos MP3.";
       Points = @('Use os botões na parte inferior da conversa.', 'Permita o microfone para gravar um áudio.', 'O envio precisa de uma conexão ativa.');
       Screen = '03-conversa.png'; Caption = 'Exemplo após desconectar os aparelhos';
       Highlights = @(@(42, 1740, 996, 155));
       Voice = 'Com a conexão ativa, envie mensagens, imagens, áudios e arquivos M P três. A tela de exemplo mostra a conversa depois de desconectar.' },
    @{ Tag = '05 / FIGURINHAS'; Title = "Crie e guarde`nsuas figurinhas.";
       Lead = "Dê seu toque à conversa com imagens,`nanimações ou vídeos curtos.";
       Points = @('Abra Figurinhas e toque em Criar.', 'Escolha o conteúdo e salve no acervo.', 'O acervo permanece após limpar a conversa.');
       Screen = '04-figurinhas.png'; Caption = 'O envio de figurinhas requer conexão';
       Voice = 'Em Figurinhas, toque em Criar e escolha uma imagem, animação ou vídeo curto. O acervo continua salvo depois de limpar a conversa.' },
    @{ Tag = '06 / ORGANIZAÇÃO'; Title = "Organize a`nconversa.";
       Lead = "Selecione as mensagens que deseja`napagar ou use a opção Limpar.";
       Points = @('As exclusões acontecem no seu aparelho.', 'As cópias no outro aparelho permanecem.', 'O acervo de figurinhas é preservado.');
       Screen = '03-conversa.png'; Caption = 'Exclusão local, sem apagar a cópia do contato';
       Highlights = @(@(885, 155, 155, 105), @(860, 630, 110, 110));
       Voice = 'Selecione mensagens para excluir, ou toque em Limpar. A exclusão é local e não apaga as cópias da outra pessoa.' },
    @{ Tag = 'PRONTO PARA CONVERSAR'; Title = "Perto de você.`nSem internet.";
       Lead = "Mantenha os aparelhos próximos`ne o Bluetooth ligado.";
       Points = @('Combine a senha com a outra pessoa.', 'Se a conexão cair, conecte novamente.', 'Chat de Rua: converse por perto.');
       Screen = '01-inicio.png'; Caption = 'O alcance depende dos aparelhos e do ambiente';
       Voice = 'Mantenha os aparelhos próximos e o Bluetooth ligado. Se a conexão cair, conecte novamente para continuar.' }
)

function New-Brush([string]$Hex) {
    return [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($Hex))
}
function New-Font([single]$Size, [bool]$Bold = $false) {
    $style = if ($Bold) { [Drawing.FontStyle]::Bold } else { [Drawing.FontStyle]::Regular }
    return [Drawing.Font]::new('Segoe UI', $Size, $style, [Drawing.GraphicsUnit]::Pixel)
}
function Write-Text($Graphics, [string]$Text, $Font, $Brush, [single]$X, [single]$Y,
        [single]$Width, [single]$Height) {
    $format = [Drawing.StringFormat]::new()
    $format.FormatFlags = [Drawing.StringFormatFlags]::LineLimit
    try {
        $needed = $Graphics.MeasureString($Text, $Font, [int]$Width, $format)
        if ($needed.Height -gt $Height + 2) { throw "Text does not fit: $Text ($($needed.Height)/$Height)" }
        $Graphics.DrawString($Text, $Font, $Brush, [Drawing.RectangleF]::new($X, $Y, $Width, $Height), $format)
    } finally { $format.Dispose() }
}
function Draw-Scene($Scene, [int]$Index) {
    $bitmap = [Drawing.Bitmap]::new(1920, 1080, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [Drawing.Graphics]::FromImage($bitmap)
    $white = New-Brush '#FFFFFF'; $mint = New-Brush '#C2E4DE'
    $accent = New-Brush '#84E0C7'; $muted = New-Brush '#93B6B4'
    $panel = New-Brush '#123C40'; $line = New-Brush '#315459'
    $dark = New-Brush '#062124'; $black = New-Brush '#041A1D'
    $fonts = @((New-Font 30 $true), (New-Font 23 $true), (New-Font 74 $true),
        (New-Font 35), (New-Font 29), (New-Font 21), (New-Font 24 $true))
    try {
        $g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $g.Clear([Drawing.ColorTranslator]::FromHtml('#0B292D'))
        $g.FillRectangle($panel, 1210, 0, 710, 1080)
        $icon = [Drawing.Image]::FromFile((Join-Path $publicationPath 'graficos\icone-512x512.png'))
        try { $g.DrawImage($icon, [Drawing.Rectangle]::new(100, 65, 62, 62)) } finally { $icon.Dispose() }
        Write-Text $g 'CHAT DE RUA' $fonts[0] $mint 185 78 800 48
        Write-Text $g 'COMO USAR' $fonts[1] $accent 1320 42 500 40
        Write-Text $g $Scene.Tag $fonts[1] $accent 100 218 1040 45
        Write-Text $g $Scene.Title $fonts[2] $white 94 285 1085 210
        Write-Text $g $Scene.Lead $fonts[3] $mint 100 510 1070 150
        $pointY = 712
        foreach ($point in $Scene.Points) {
            $g.FillEllipse($accent, 103, $pointY + 13, 9, 9)
            Write-Text $g $point $fonts[4] $white 133 $pointY 1040 64
            $pointY += 75
        }
        $image = [Drawing.Image]::FromFile((Join-Path $screenPath $Scene.Screen))
        try {
            $g.FillRectangle($black, 1310, 99, 480, 854)
            $g.DrawImage($image, [Drawing.Rectangle]::new(1295, 85, 480, 854))
        } finally { $image.Dispose() }
        $border = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#A4D7CD'), 3)
        try { $g.DrawRectangle($border, 1294, 84, 482, 856) } finally { $border.Dispose() }
        if ($Scene.Highlights) {
            $pen = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#F5C45B'), 4)
            try {
                foreach ($rect in $Scene.Highlights) {
                    $g.DrawRectangle($pen, [single](1295 + $rect[0] * 480 / 1080),
                        [single](85 + $rect[1] * 854 / 1920),
                        [single]($rect[2] * 480 / 1080), [single]($rect[3] * 854 / 1920))
                }
            } finally { $pen.Dispose() }
        }
        Write-Text $g $Scene.Caption $fonts[5] $mint 1250 955 620 65
        Write-Text $g 'Telas de demonstração com conteúdo fictício.' $fonts[5] $muted 100 988 1120 40
        Write-Text $g ('{0:00} / {1:00}' -f ($Index + 1), $scenes.Count) $fonts[6] $mint 1775 990 130 40
        $g.FillRectangle($line, 100, 1040, 1720, 5)
        $g.FillRectangle($accent, 100, 1040, [single](1720 * ($Index + 1) / $scenes.Count), 5)
        $path = Join-Path $workingPath ('cena-{0:00}.png' -f ($Index + 1))
        $bitmap.Save($path, [Drawing.Imaging.ImageFormat]::Png)
        return $path
    } finally {
        foreach ($font in $fonts) { $font.Dispose() }
        foreach ($brush in @($white, $mint, $accent, $muted, $panel, $line, $dark, $black)) { $brush.Dispose() }
        $g.Dispose(); $bitmap.Dispose()
    }
}
function Time-Code([double]$Seconds, [bool]$Srt = $false) {
    $span = [TimeSpan]::FromMilliseconds([Math]::Round($Seconds * 1000))
    if ($Srt) { return '{0:00}:{1:00}:{2:00},{3:000}' -f [int]$span.TotalHours, $span.Minutes, $span.Seconds, $span.Milliseconds }
    return '{0:00}:{1:00}' -f [int]$span.TotalMinutes, $span.Seconds
}
function Caption-Chunks([string]$Text) {
    $chunks = [Collections.Generic.List[string]]::new()
    $current = ''
    foreach ($word in ($Text -split '\s+')) {
        if ($current.Length -gt 0 -and $current.Length + $word.Length + 1 -gt 78) {
            $chunks.Add($current); $current = ''
        }
        $current = if ($current) { "$current $word" } else { $word }
    }
    if ($current) { $chunks.Add($current) }
    return $chunks.ToArray()
}
function Caption-Lines([string]$Text) {
    $line = ''; $lines = [Collections.Generic.List[string]]::new()
    foreach ($word in ($Text -split '\s+')) {
        if ($line.Length -gt 0 -and $line.Length + $word.Length + 1 -gt 43) { $lines.Add($line); $line = '' }
        $line = if ($line) { "$line $word" } else { $word }
    }
    if ($line) { $lines.Add($line) }
    return ($lines -join "`n")
}

$voice = New-Object -ComObject SAPI.SpVoice
$tokens = $voice.GetVoices('', '')
$selectedVoice = $null
for ($i = 0; $i -lt $tokens.Count; $i++) {
    if ($tokens.Item($i).GetAttribute('Language') -match '(^|;)416($|;)') {
        $selectedVoice = $tokens.Item($i); break
    }
}
if ($null -eq $selectedVoice) { throw 'No installed Portuguese (Brazil) voice found.' }
$voice.Voice = $selectedVoice; $voice.Rate = 1; $voice.Volume = 100
$culture = [Globalization.CultureInfo]::InvariantCulture
$timeline = [Collections.Generic.List[object]]::new()
$srt = [Text.StringBuilder]::new(); $captionIndex = 1; $start = 0.0
$concat = [Collections.Generic.List[string]]::new()
for ($i = 0; $i -lt $scenes.Count; $i++) {
    $scene = $scenes[$i]
    $number = '{0:00}' -f ($i + 1)
    $imagePath = Draw-Scene $scene $i
    $wav = Join-Path $workingPath "voz-$number.wav"
    $stream = New-Object -ComObject SAPI.SpFileStream
    $format = New-Object -ComObject SAPI.SpAudioFormat
    $format.Type = 22; $stream.Format = $format
    $stream.Open($wav, 3, $false)
    try { $voice.AudioOutputStream = $stream; $null = $voice.Speak($scene.Voice, 0) }
    finally { $stream.Close(); $voice.AudioOutputStream = $null }
    $durationText = (& $Ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 $wav) -join ''
    if ($LASTEXITCODE -ne 0) { throw "ffprobe failed: $wav" }
    $speechDuration = [double]::Parse($durationText.Trim(), $culture)
    $duration = [Math]::Ceiling([Math]::Max(5.5, $speechDuration + 1.15) * 30) / 30
    $durationArg = $duration.ToString('0.000000', $culture)
    $fadeOut = ($duration - 0.23).ToString('0.000000', $culture)
    $segment = Join-Path $workingPath "segmento-$number.mp4"
    & $Ffmpeg -hide_banner -loglevel warning -y -loop 1 -framerate 30 -i $imagePath -i $wav `
        -t $durationArg -vf "fade=t=in:st=0:d=0.25,fade=t=out:st=${fadeOut}:d=0.23,format=yuv420p" `
        -af 'adelay=350,apad' -c:v libx264 -preset veryfast -crf 19 -threads 4 `
        -r 30 -c:a aac -b:a 160k -ar 48000 -ac 2 -movflags +faststart $segment `
        2> (Join-Path $workingPath "ffmpeg-$number.log")
    if ($LASTEXITCODE -ne 0) { throw "Video encoding failed: scene $number" }
    $concat.Add("file '" + ($segment.Replace('\', '/').Replace("'", "'\''")) + "'")
    $caption = $scene.Voice.Replace('M P três', 'MP3')
    $chunks = @(Caption-Chunks $caption)
    $totalWords = ($caption -split '\s+').Count
    $cueStart = $start + 0.35
    foreach ($chunk in $chunks) {
        $weight = ($chunk -split '\s+').Count / [double]$totalWords
        $cueEnd = $cueStart + $speechDuration * $weight
        $null = $srt.AppendLine("$captionIndex")
        $null = $srt.AppendLine("$(Time-Code $cueStart $true) --> $(Time-Code ($cueEnd - 0.04) $true)")
        $null = $srt.AppendLine((Caption-Lines $chunk))
        $null = $srt.AppendLine()
        $captionIndex++; $cueStart = $cueEnd
    }
    $timeline.Add([ordered]@{ cena = $i + 1; titulo = $scene.Tag; inicio = $start;
        duracao = $duration; voz_segundos = $speechDuration; narracao = $caption; tela = $scene.Screen })
    $start += $duration
    Write-Output "Cena $number/$($scenes.Count) renderizada: $([Math]::Round($duration, 1)) s."
}
$utf8 = [Text.UTF8Encoding]::new($false)
$listPath = Join-Path $workingPath 'segmentos.txt'
[IO.File]::WriteAllLines($listPath, $concat, $utf8)
$videoPath = Join-Path $outputPath 'chat-de-rua-como-usar-1080p.mp4'
& $Ffmpeg -hide_banner -loglevel warning -y -f concat -safe 0 -i $listPath `
    -c:v copy -af 'loudnorm=I=-16:TP=-1.5:LRA=11' -c:a aac -b:a 192k -ar 48000 -ac 2 `
    -metadata title='Chat de Rua — Como usar' -metadata comment='Tutorial com telas reais e conteúdo de demonstração.' `
    -movflags +faststart $videoPath 2> (Join-Path $workingPath 'ffmpeg-final.log')
if ($LASTEXITCODE -ne 0) { throw 'Final video encoding failed.' }
[IO.File]::WriteAllText((Join-Path $outputPath 'legendas-pt-BR.srt'), $srt.ToString(), $utf8)
$metadata = [ordered]@{ titulo = 'Chat de Rua — Como usar'; idioma = 'pt-BR';
    voz = $selectedVoice.GetDescription(); duracao_prevista = $start; cenas = $timeline.ToArray() }
$metadata | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $outputPath 'roteiro-e-tempos.json') -Encoding utf8

$chapters = ($timeline | ForEach-Object { "$(Time-Code $_.inicio) $($_.titulo -replace '^\d+ / ', '')" }) -join "`n"
$description = @"
Chat de Rua: como conversar por Bluetooth sem internet

Veja como escolher o idioma, conectar dois aparelhos próximos, combinar uma senha e usar mensagens, imagens, áudios, MP3 e figurinhas no Chat de Rua.

Instale o aplicativo nos dois aparelhos, mantenha o Bluetooth ligado e confirme as permissões e o pareamento solicitados pelo Android. O envio precisa de uma conexão ativa. A senha padrão é igual para todos; para mais privacidade, combine uma senha própria.

Conteúdo:
$chapters

As telas usam conteúdo fictício de demonstração. A conversa exibida está desconectada e não representa uma transferência Bluetooth filmada. Excluir mensagens no seu aparelho não apaga as cópias da outra pessoa. O alcance depende dos aparelhos e do ambiente.

Interface disponível em português, inglês, alemão, chinês simplificado e espanhol. A mudança de idioma não traduz as mensagens dos participantes.

Link do aplicativo: adicionar somente quando existir uma página pública na loja.
"@
[IO.File]::WriteAllText((Join-Path $outputPath 'titulo-e-descricao-youtube.txt'), $description.Trim() + "`n", $utf8)
$scriptText = ($timeline | ForEach-Object { "[$(Time-Code $_.inicio)] $($_.titulo)`n$($_.narracao)`n" }) -join "`n"
[IO.File]::WriteAllText((Join-Path $outputPath 'roteiro-narracao.txt'), $scriptText, $utf8)

$cover = [Drawing.Image]::FromFile((Join-Path $workingPath 'cena-01.png'))
$thumbnail = [Drawing.Bitmap]::new(1280, 720, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
$thumbGraphics = [Drawing.Graphics]::FromImage($thumbnail)
try {
    $thumbGraphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $thumbGraphics.DrawImage($cover, [Drawing.Rectangle]::new(0, 0, 1280, 720))
    $thumbnail.Save((Join-Path $outputPath 'miniatura-youtube-1280x720.png'), [Drawing.Imaging.ImageFormat]::Png)
} finally { $thumbGraphics.Dispose(); $thumbnail.Dispose(); $cover.Dispose() }

$preview = [Drawing.Bitmap]::new(1280, 360, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
$pg = [Drawing.Graphics]::FromImage($preview)
try {
    $pg.Clear([Drawing.Color]::White)
    $pg.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    for ($i = 0; $i -lt $scenes.Count; $i++) {
        $file = Join-Path $workingPath ('cena-{0:00}.png' -f ($i + 1))
        $image = [Drawing.Image]::FromFile($file)
        try { $pg.DrawImage($image, [Drawing.Rectangle]::new(320 * ($i % 4), 180 * [Math]::Floor($i / 4), 320, 180)) }
        finally { $image.Dispose() }
    }
    $preview.Save((Join-Path $outputPath 'previa-das-cenas.png'), [Drawing.Imaging.ImageFormat]::Png)
} finally { $pg.Dispose(); $preview.Dispose() }

Write-Output "Vídeo pronto: $videoPath ($([Math]::Round($start, 1)) s)."
