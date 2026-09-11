# PocketHound (Android)

App Android do **PocketHound**: o controle de bolso do DeepSeek Harness (DSH).
O celular manda o pedido, o PC executa no Harness, e **tudo o que o Harness pensa,
escreve e pede volta para o celular em tempo real** — inclusive os **pedidos de
aprovação**, que você decide de onde estiver.

O par deste repositório é o **PocketHound desk** (Electron, no PC) e o plugin
**dsh-pockethound** (dentro do DSH). A especificação completa está em
`docs/ARQUITETURA.md` e `docs/DESIGN.md` do repositório do desk.

---

## Arquitetura

```
┌──────────────────────── PC ─────────────────────────┐
│                                                      │
│   DSH (dsh web / headless)                           │
│     │  session/event · approval/request              │
│     ▼                                                │
│   ┌──────────────────────┐                           │
│   │ dsh-pockethound      │  plugin host              │
│   │  • observa sessões   │                           │
│   │  • intercepta aprovações                         │
│   │  • ponte loopback    │  HTTP + SSE 127.0.0.1:P   │
│   └──────────┬───────────┘                           │
│              │                                       │
│   ┌──────────▼───────────┐                           │
│   │ PocketHound desk     │  Electron                 │
│   │  DshLink ─► Hub ─► Transport                      │
│   └──────────┬───────────┘                           │
│              │  P2P (QUIC/iroh + relay)              │
└──────────────┼───────────────────────────────────────┘
               │
   ┌───────────▼────────────────────────────┐
   │ PocketHound (Android)  ← este repo     │
   │  ChatScreen · ApprovalsScreen          │
   │  FleetScreen · SettingsScreen          │
   │  PhNav (NavHost + barra inferior)      │
   │        │                               │
   │        ▼                               │
   │  HoundRepository (estado único)        │
   │        │                               │
   │  ┌─────┴──────┐                        │
   │  │ Transport  │  AUTO · DIRECT · P2P   │
   │  └─────┬──────┘                        │
   │  SecureStore (Tink) · SettingsStorage  │
   └────────────────────────────────────────┘
```

O protocolo é orientado a eventos: todo quadro tem o mesmo envelope
`{ v, seq, ts, type, session, payload }` e `seq` é monotônico por PC — é ele que
permite trocar de transporte (direto ⇄ P2P) sem perder nada, bastando reenviar o
cursor com `subscribe { cursor }`.

---

## Como compilar

Requisitos: JDK 17+, Android SDK com `compileSdk 36`. Se o Gradle reclamar que
não encontra o `aapt2`, descomente `android.aapt2FromMavenOverride` em
`gradle.properties` e aponte para o build-tools da sua máquina.

> Compilado e testado com o JDK 21 do JetBrains Runtime do Android Studio. Se o
> `java` não estiver no PATH:
> `export JAVA_HOME=/caminho/para/android-studio/jbr`.

```bash
./gradlew :app:assembleDebug          # APK de debug
./gradlew :app:testDebugUnitTest      # testes unitários (JVM)
./gradlew installDebug                # instala no aparelho ligado
```

O `local.properties` precisa apontar o SDK (`sdk.dir=...`) e não é versionado.

Versões: AGP 8.11.2 · Kotlin 2.2.21 · Gradle 8.13 · Compose BOM 2024.12.01 ·
Hilt 2.57.1 · Ktor 3.0.3 · compileSdk/targetSdk 36 · minSdk 26.

---

## O que já está pronto

- **Build** completo e equivalente ao do DoGCyberAgent (mesmas versões de Kotlin,
  AGP e Compose, mesmas dependências): Hilt, Ktor, kotlinx.serialization, DataStore,
  Coil, WorkManager, CameraX + ZXing, Tink.
- **Tema** (`ui/theme`): a paleta violeta do DESIGN.md com os nomes em camelCase,
  `darkColorScheme` mapeado, escala de `Shapes` 6/10/16/22/28 dp e tipografia
  Orbitron (display) + JetBrains Mono (corpo), com as fontes embarcadas.
- **Design system** (`ui/common/PhComponents.kt`): `PhCard`, `PhButton`
  (primário/perigo/ghost), `PhBadge`, `PhStatusDot`, `PhSectionTitle`, `PhBar`,
  `PhState` (vazio/carregando/erro), `PhTextField`, `PhKeyValue`, `PhDivider`,
  `PhScreenScaffold` e `PhSecureScreen` — todos com o glow violeta equivalente ao
  neon do sistema antigo.
- **Chuva de caracteres** (`ui/matrix/PhRain.kt`): mesma animação do projeto
  anterior (colunas, rastro de 16 glifos, troca de glifo a cada 90 ms, palavras de
  aviso onde a chuva bate no cartão, saída do centro para as pontas), agora em
  violeta — corpo `PhViolet`, cabeça `PhVioletSoft`, e a cor acompanha a carga do
  PC (violeta em repouso, âmbar sob carga, `PhDanger` na faixa crítica).
- **Navegação** (`ui/nav`): `PhNav` (NavHost com as 4 abas, barra inferior só
  aparece depois do pareamento), `PhBottomBar` (pill deslizante + badge de
  pendências) e `RootViewModel` (estado compartilhado).
- **Telas** (`ui/chat`, `ui/approvals`, `ui/fleet`, `ui/settings`, `ui/pairing`):
  estrutura visual completa, estados de UI, cards do design system e dados de
  exemplo vindos do `HoundRepository`.
- **Núcleo** (`core`): o envelope e todos os tipos do protocolo
  (`core/model/Frames.kt`), o parser puro do QR de pareamento
  (`core/model/PairingPayload.kt`), o `SecureStore` com Tink + Keystore e o
  `SettingsStorage` com DataStore.
- **Transporte direto de verdade** (`core/transport/Transport.kt`): o
  `DirectTransport` fala Ktor — `POST /ph/frame` para os comandos, `GET /ph/stream`
  para o SSE e `GET /ph/ping` para a sonda de 1,5 s. O token vai em
  `Authorization: Bearer` em **todas** as chamadas, inclusive o stream.
- **Transporte P2P** (`core/transport/P2pTransport.kt`): túnel QUIC pelo iroh
  (hole punching + relay), para alcançar o PC **de fora da rede local**. É
  transparente — dentro do túnel vão as mesmas requisições, com o mesmo Bearer.
  O `IrohEndpointProvider` mantém um único endpoint (bind protegido por mutex) e
  guarda a chave no Tink + Keystore.
- **Decodificador de SSE** (`core/transport/Sse.kt`): separado da rede de
  propósito — é a parte que erra fácil e a única que dá para testar sem servidor.
  Trata batimento (`:`), concatenação de `data:` e, principalmente, **não perde o
  último evento quando a conexão fecha sem a linha vazia final**.
- **Cliente de sessão** (`core/session/SessionClient.kt`): concentra as três
  coisas que, espalhadas, dão errado — **reconexão com recuo exponencial**,
  **cursor persistido** e **repasso dos quadros**. É o único lugar do app que fala
  com o PC; nenhuma tela abre conexão nem guarda posição de leitura.
- **Pareamento funcional** (`core/session/PairingClient.kt`): a tela troca o
  código de 6 dígitos pelo token em `POST /ph/pair`, sonda o PC antes (para
  distinguir "não achei o PC" de "código errado") e grava o token no SecureStore.
- **Redutor da transcrição** (`core/session/TranscriptReducer.kt`): a regra que
  junta os deltas. Sem ela, uma resposta de três parágrafos viraria dezenas de
  balões de duas palavras. Função pura, testada sem tela e sem rede.
- **Repositório ligado** (`data/repo/HoundRepository.kt`): dobra os quadros do
  `SessionClient` em `sessions`, `transcript`, `approvals`, `deskState` e
  `notices`. O estado começa **vazio**, não com exemplo — dado de exemplo numa
  tela que deveria mostrar o PC é pior que tela vazia.
- **Testes** de JVM (**51**, todos passando): `ContractTest`, `SseDecoderTest`,
  `TranscriptReducerTest`, `P2pFramingTest`, `FramesTest` e `PairingPayloadTest`.
  Mais `P2pTunnelTest`, que roda **no aparelho** — ver abaixo.

## O teste que liga as duas pontas

O `P2pTunnelTest` é o único lugar onde o celular e o PC se encontram de verdade.
Ele roda no emulador (ou num aparelho) porque **o iroh é nativo e não carrega numa
JVM pura** — numa tentativa de teste unitário ele falha com `UnsatisfiedLinkError`.

O que ele faz, sem nada simulado:

1. Pareia contra o desk pelo caminho direto e guarda o token;
2. Sobe o endpoint QUIC do aparelho;
3. Sonda o **túnel** (`p2p.probe()`) — este passo já prova que dois runtimes
   diferentes concordam no ALPN e no enquadramento;
4. Manda um comando de verdade pelo túnel;
5. Abre o stream e confirma que chegam quadros.

```bash
# 1. o desk, sem Electron
node .dev/headless-desk.mjs 7451

# 2. a ponte P2P (noutro terminal)
npm run p2p:serve

# 3. o teste, no emulador
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.deskUrl=http://10.0.2.2:7451 \
  -Pandroid.testInstrumentationRunnerArguments.code=<código> \
  -Pandroid.testInstrumentationRunnerArguments.ticket=<ticket>
```

`10.0.2.2` é como o emulador enxerga o host. Num aparelho de verdade, use o IP do
PC na rede local.

**Resultado observado:** a ponte registrou as três travessias vindas do celular
(`GET /ph/ping`, `POST /ph/frame`, `GET /ph/stream`) e o desk viu o pareamento e
a conexão do stream. O teste leva ~8 s.

## O teste de contrato — por que ele existe

`ContractTest` desserializa **exatamente** os quadros que o PC emite. O arquivo
`app/src/test/resources/fixtures.json` é gerado pelo repositório do desk
(`.dev/contract-fixtures.mjs`) e é a fonte da verdade compartilhada entre os três
repositórios.

Ele existe por causa de um defeito concreto do projeto anterior: o app chamava
rotas que **não existiam** no PC, e havia **três portas diferentes** em circulação
(5000 no default, 5005 no texto de ajuda, 5055 no QR de teste) sem nada que
pegasse a divergência antes de ela chegar ao usuário.

Na primeira execução ele reprovou **três divergências reais** que já existiam
neste app:

| O que estava errado | O que o protocolo manda |
|---|---|
| `TurnEventPayload` sem `turn`/`step` | o plugin sempre os mandou; sem eles o app não agrupa os deltas do mesmo passo |
| `HelloPayload.version` como `String` | o protocolo manda **número** — o app quebraria no primeiro quadro |
| `DeskStatePayload` com `{cpu, mem, gpu, temp, load, uptime}` | o desk manda `{cpuPercent, memUsedMb, memTotalMb, cores, loadAvg[], temperatureC, …}` |

Também faltavam os quadros `question.request`/`question.answer` (o fluxo da
ferramenta `pockethound_ask`) e `replay.done`.

---

## O que ainda é TODO

Tudo o que depende de rede ou de um PC de verdade está marcado com
`// TODO(pockethound):` no código. Em resumo:

| Área | Falta |
|---|---|
| Transporte | o `TransportSelector` sonda os dois caminhos, mas ainda não reage à troca de rede em tempo real (nem tem o cache de 60 s que o projeto anterior usava) |
| Leitor de QR | a tela aceita o endereço e o código digitados; o leitor com CameraX + ZXing ainda não existe (as dependências já estão no build) |
| Notificações | `pockethound_notify` / `pockethound_ask` não chamam o sistema de notificação do Android |
| Regras | o "não perguntar de novo" já chega ao desk pelo `approval.decide`, mas a tela ainda não mostra as regras ativas |
| Pareamento | não há leitor de QR (CameraX + ZXing já estão nas dependências) nem o handshake `hello`/`hello.ack` que grava o token no `SecureStore` |
| Aprovações | o "não perguntar de novo" ainda não vira regra no desk |
| Ajustes | só o modo de transporte é persistido; endereço, nome do dispositivo e prazo ainda não são salvos |
| Replay | o cursor (`lastSeq`) é exibido mas não é usado para reenviar o buraco |
| Notificações | `pockethound_notify` / `pockethound_ask` não chamam o sistema de notificação do Android |

### A dependência nativa do P2P

```kotlin
implementation(libs.iroh.android)   // computer.iroh:iroh-android:1.1.0
```

Ela traz biblioteca nativa, e por isso o APK de debug passou de ~24 MB para ~65 MB.
O build limita as ABIs a **arm64-v8a e x86_64**: `armeabi-v7a` e `x86` dobrariam o
APK sem servir a nenhum aparelho atual.

O `PocketHoundApp` chama `IrohAndroid.installAndroidContext(this)` no `onCreate`.
Sem isso o primeiro bind do endpoint falha com um erro nativo que não diz o que
faltou.

---

## Estrutura

```
app/src/main/java/com/pockethound/app/
├── MainActivity.kt · PocketHoundApp.kt
├── core/
│   ├── model/Frames.kt          envelope + tipos do protocolo + PhCodec
│   ├── model/PairingPayload.kt  parser do QR (JVM puro)
│   ├── model/PhModels.kt        sessões, aprovações, transcrição, DeskState
│   ├── storage/SecureStore.kt   Tink AEAD + Keystore (token)
│   ├── storage/SettingsStorage.kt DataStore (o resto)
│   └── transport/Transport.kt   interface + TransportMode + stubs
├── data/repo/HoundRepository.kt estado único da UI (dados de exemplo)
├── di/AppModule.kt              Json + HttpClient do Ktor
└── ui/
    ├── theme/       Color.kt · Theme.kt · Type.kt
    ├── common/      PhComponents.kt
    ├── matrix/      PhRain.kt
    ├── nav/         PhNav.kt · PhBottomBar.kt · RootViewModel.kt
    ├── chat/        ChatScreen.kt
    ├── approvals/   ApprovalsScreen.kt
    ├── fleet/       FleetScreen.kt
    ├── settings/    SettingsScreen.kt
    └── pairing/     PairingScreen.kt
```

---

## Paleta

Os tokens vivem em `ui/theme/Color.kt` e são os mesmos do CSS do desk (DESIGN.md §1):
mesma estrutura do sistema ciano anterior, eixo deslocado para violeta.

| Token | Hex | Substitui |
|---|---|---|
| `PhVoid` | `#06030E` | — |
| `PhBg` | `#0A0716` | `#0A0A1A` |
| `PhSurface` | `#140E28` | `#12122A` |
| `PhSurface2` | `#1C1436` | `#1A1A3A` |
| `PhSurface3` | `#251A47` | — |
| `PhBorder` | `#2E2154` | `#2A2A5A` |
| `PhBorderHi` | `#4A3390` | — |
| `PhViolet` | `#B36BFF` | `#00FFF7` (acento primário) |
| `PhVioletSoft` | `#D6B4FF` | — |
| `PhVioletDeep` | `#7B3FE4` | — |
| `PhMagenta` | `#E45CFF` | `#FF00EA` |
| `PhIndigo` | `#7C7CFF` | — |
| `PhAmber` | `#FF8A3D` | `#FF6B00` |
| `PhDanger` | `#FF2E6A` | `#FF0044` |
| `PhWarn` | `#FFB020` | — |
| `PhOk` | `#35E39B` | `#00FF41` |
| `PhInfo` | `#6BA8FF` | — |
| `PhText` | `#DCD2F5` | `#C0C0E0` |
| `PhTextDim` | `#8A7BB5` | `#6060A0` |
| `PhTextMute` | `#5B4F7D` | — |

---

## Privacidade

- O token do PC fica cifrado com **Tink AEAD** (AES256-GCM) e a chave-mestra no
  **Android Keystore**. `EncryptedSharedPreferences` não é usado (deprecado).
- A tela de pareamento é `FLAG_SECURE`.
- Regra herdada dos projetos anteriores: **transporte seguro não é autorização**.
  O canal protege o caminho; o handshake com token protege o comando.
