# Excluir mensagens enviadas e recebidas

1. Marque a caixa ao lado do cabeçalho de uma ou mais mensagens enviadas ou recebidas. As mensagens marcadas ficam destacadas e podem ser selecionadas juntas.
2. Confira a quantidade na barra de seleção e toque em **Excluir**.
3. Confirme a exclusão. **Cancelar** no diálogo mantém as mensagens e a seleção; **Cancelar seleção** na barra desmarca todas.

A seleção funciona com texto, áudio gravado, MP3, imagem e figurinhas estáticas ou animadas. Copiar texto, ouvir áudio e abrir imagens ou figurinhas continuam disponíveis pelos controles existentes. A exclusão afeta apenas a conversa neste aparelho, mantém mensagens não selecionadas e não apaga figurinhas salvas no acervo.

O botão **Limpar** permanece no cabeçalho, com a confirmação e a função de apagar toda a conversa. Mensagens enviadas ou recebidas depois da seleção não são selecionadas automaticamente.

Excluir um áudio ou MP3 interrompe seu player e remove seu arquivo temporário. Uma mensagem removida durante a descriptografia não reaparece quando o processamento termina.

## Validação

A suíte usa os arquivos de mídia de `app/src/androidTest/assets/stickers` e as APIs de instrumentação já utilizadas pelo projeto:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e suite selection com.capongasoftware.chat_de_rua.test/com.capongasoftware.chat_de_rua.StickerInstrumentation
```

A suíte verifica seleção individual e múltipla de mensagens enviadas e recebidas, seleção conjunta dos dois sentidos, cancelamento, restauração da cor das mensagens enviadas, mensagens novas durante a confirmação, exclusão com áudio em reprodução, limpeza dos arquivos temporários, exclusão durante a descriptografia, preservação do acervo e regressão do botão **Limpar**.

Validação realizada no emulador Android API 35: passaram 32 verificações de seleção e exclusão, 31 de figurinhas e 11 de abertura de sala. As compilações de depuração, testes e release passaram; o lint terminou sem erros, com os 38 avisos já existentes. A transferência Bluetooth entre aparelhos físicos não faz parte desta validação.
