# Figurinhas

O botão **Figurinhas** da conversa abre o acervo. A criação e a consulta não exigem uma conexão Bluetooth ativa; o envio exige uma sala conectada.

1. Toque em **Criar** e escolha uma imagem estática, GIF/WebP animado ou um vídeo curto.
2. Confira a prévia, dê um nome e toque em **Salvar no acervo**.
3. Toque em uma figurinha salva para visualizar, enviar à sala conectada ou excluir do acervo.
4. Toque em uma figurinha na conversa para visualizar e salvar uma cópia no acervo, inclusive se ela foi recebida.

Imagens estáticas mantêm a transparência e são ajustadas para até 512 pixels. Animações são ajustadas para até 384 pixels e aproximadamente 8 quadros por segundo, em repetição e sem som. GIFs, WebP animados e vídeos devem ter até 6 segundos. O arquivo original pode ter até 50 MB, e a figurinha preparada, até 4 MB. O formato de vídeo precisa ser decodificável pelo Android do aparelho. Imagens animadas com resolução superior a 4 megapixels e vídeos acima de 9 megapixels são rejeitados para limitar o consumo de memória.

O acervo fica em `files/stickers`, no armazenamento privado do aplicativo. Não depende da galeria, do arquivo original ou de permissões de armazenamento. Limpar a conversa, fechar o aplicativo ou desconectar não apaga as figurinhas. Desinstalar o aplicativo ou limpar seus dados remove o acervo.

O envio de figurinhas requer esta versão nos dois aparelhos. Texto, imagem, áudio e MP3 conservam os tipos de conteúdo e o formato de criptografia anteriores. A orientação vertical permanece configurada.

## Implementação

- `StickerCodec`: converte imagens, animações e vídeos em quadros PNG/WebP, removendo metadados e qualquer áudio. Os quadros de WebP animado são compostos com transparência, mistura e descarte. O formato interno STK1 contém dimensões, número de quadros, durações e imagens independentes, com limites validados também no recebimento.
- `StickerStore`: gravação atômica dos arquivos privados, nomes e miniaturas; leitura paginada pela interface.
- `StickerView`: decodifica fora da thread da interface, mantém apenas um bitmap por figurinha visível e interrompe o trabalho quando a janela é ocultada ou a view é removida.
- `StickerController`: seleção de arquivos pelo seletor do sistema, criação cancelável, prévia, acervo e exclusão.
- `MainActivity`: novo tipo de conteúdo 5 para figurinhas, transportado pelo protocolo de mídia existente e protegido pelo mesmo AES-GCM. Cancelar durante a escrita fecha a conexão para não deixar um pacote incompleto no fluxo.

## Validação

Os testes em `app/src/androidTest` usam apenas APIs do Android, sem dependências externas. Os arquivos de teste estão incluídos. Para recriá-los, execute `python app/src/androidTest/generate_fixtures.py` com FFmpeg disponível.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.capongasoftware.chat_de_rua.test/com.capongasoftware.chat_de_rua.StickerInstrumentation
```

O resultado final da instrumentação informa `OK` e o número de verificações; falhas incluem a exceção e o nome da verificação. A suíte cobre transparência, GIF, WebP com quadros parciais e descarte, vídeo e orientação, limites, cancelamento, arquivos truncados, persistência, exclusão, criptografia e regressões de texto/imagem/áudio/MP3. Também verifica o acesso offline ao acervo, a preservação após limpar o chat e a orientação vertical. A transferência Bluetooth entre aparelhos físicos deve ser conferida separadamente.
