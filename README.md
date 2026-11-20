# Chat de Rua

Aplicativo Android de conversa por Bluetooth, com texto, imagens, áudio, MP3,
figurinhas e seleção de idioma. O código do aplicativo, os recursos, os testes e
o Gradle Wrapper fazem parte deste repositório.

## Compilar e verificar

Use JDK 17 ou superior compatível com o Android Gradle Plugin 8.11.1 e instale o
Android SDK 36. Configure o caminho do SDK no Android Studio ou no arquivo local
`local.properties` (`sdk.dir=...`). Esse arquivo não deve ser enviado ao Git.

No Windows:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python tools/validate-localization.py
```

O Gradle baixa as dependências na primeira compilação. A versão de desenvolvimento
usa a chave de debug local do Android e não exige a chave privada de produção.
Consulte [os testes de figurinhas](docs/figurinhas.md) para executar a instrumentação
em um aparelho ou emulador. A transferência Bluetooth deve ser validada em
aparelhos físicos.

## Gerar uma versão de produção

Copie `keystore.properties.example` para `keystore.properties`, preencha o caminho
da chave existente, o alias e as senhas apenas na sua máquina e execute:

```powershell
.\gradlew.bat :app:bundleRelease
```

A compilação de produção exige a configuração privada de assinatura. Para manter
as atualizações do aplicativo publicado, preserve a chave existente e seu backup
seguro fora do GitHub.

## Arquivos privados e gerados

O `.gitignore` exclui chaves e senhas de assinatura, arquivos `.env`, configurações
locais, caches, builds, APKs/AABs e materiais locais de publicação. Backups de
chaves e arquivos temporários também são excluídos. Não use `git add -f` nesses
arquivos. Antes de um commit, confira `git status --short` e `git diff --cached`.
O `.gitignore` não remove arquivos que já tenham sido versionados.

Os arquivos de mídia em `app/src/androidTest/assets` são dados dos testes e devem
permanecer no repositório. Os scripts de publicação ficam em `tools`; os materiais
gerados ficam na pasta local ignorada `publicacao-play-store`.

A senha padrão de sala incorporada ao aplicativo é compartilhada por todas as
instalações e é pública no código. Para restringir a participação na sala, use uma
senha personalizada e compartilhe-a apenas com os participantes.
