# Idiomas do aplicativo

O Chat de Rua oferece português do Brasil (`pt-BR`), inglês dos Estados Unidos
(`en-US`), alemão da Alemanha (`de-DE`), chinês simplificado (`zh-CN`) e espanhol
da Espanha (`es-ES`).

Na primeira abertura, o aplicativo apresenta a seleção de idioma antes de pedir
permissões ou iniciar as operações de Bluetooth. A sugestão acompanha um idioma
compatível do aparelho; se não houver, sugere português. A escolha só é salva ao
tocar em **Continuar**. Instalações existentes também recebem o seletor na primeira
abertura após esta atualização.

O botão **Idioma**, na tela inicial, permite mudar a preferência posteriormente.
A alteração atualiza os textos imediatamente, sem recriar a Activity. Mensagens,
rascunho, senha, nome escolhido pelo usuário, seleção de mensagens e conexão são
preservados. Os nomes dos idiomas aparecem em suas próprias línguas para permitir
reencontrar a opção desejada. Cancelar a seleção mantém o idioma anterior.

Todos os idiomas são incluídos no APK e no App Bundle, inclusive quando instalados
pela Play Store: a divisão por idioma do bundle está desativada. A mudança funciona
sem internet. As telas pertencentes ao Android, como permissões e seleção de
arquivos, seguem as configurações do sistema.

## Manutenção

- Recursos padrão em `app/src/main/res/values/strings.xml`: português do Brasil.
- Traduções completas em `values-en-rUS`, `values-de-rDE`, `values-zh-rCN` e
  `values-es-rES`.
- `AppLanguage` salva uma preferência exclusiva do aplicativo e aplica a locale.
- `LocalizedText` mantém a identidade dos recursos nos textos da interface,
  incluindo parâmetros e plurais, para atualizar os componentes existentes.
  Textos digitados e recebidos não contêm essa identificação e permanecem intactos.
- Os nomes Bluetooth das novas salas acompanham o idioma escolhido. A descoberta
  reconhece os prefixos dos cinco idiomas e o prefixo legado `Conversar`. UUID,
  criptografia e formato dos pacotes permanecem os mesmos.

Ao adicionar textos de interface programaticamente, use `uiText` na MainActivity
ou `LocalizedText.text(context, resource, args)` em componentes que permaneçam
visíveis durante a troca. Os atributos XML são vinculados na inflação do layout.

## Validação

```powershell
python tools/validate-localization.py
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline
adb shell am instrument -w -e suite language com.capongasoftware.chat_de_rua.test/com.capongasoftware.chat_de_rua.StickerInstrumentation
```

O validador confere cobertura, recursos duplicados, plurais e parâmetros de
formatação. A suíte Android exercita o seletor real, confirmação e cancelamento,
troca entre os cinco idiomas com mensagens na tela, preservação dos campos e da
seleção, identificação de salas e restauração da preferência ao reabrir.

Validação executada no emulador: 89 verificações de idiomas, 11 de salas, 32 de
seleção/exclusão de mensagens, 6 de compatibilidade Android e 31 de figurinhas
(169 no total). Compilações debug/release e lint concluídos sem erros. O lint
mantém avisos do projeto e sinaliza `AppBundleLocaleChanges`, apesar da divisão
estar desativada; a configuração final foi conferida no `BundleConfig.pb` do AAB,
com a dimensão `LANGUAGE` explicitamente desativada.

Pacotes atualizados em `app/build/outputs/apk/release/app-release.apk` e
`app/build/outputs/bundle/release/app-release.aab`, com a assinatura de release
configurada no projeto. As cópias em `publicacao-play-store/pacote` também foram
atualizadas com esta versão; `app/release` continua com arquivos antigos.

O material da loja em `publicacao-play-store` inclui cinco capturas por formato
(telefone e tablets de 7 e 10 polegadas), com a interface atual e o seletor dos
cinco idiomas. Os textos da ficha permanecem em português do Brasil e descrevem a
troca offline de idioma, sem tradução automática das mensagens dos participantes.
