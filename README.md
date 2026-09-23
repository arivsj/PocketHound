# PocketHound (Android)

App Android do **PocketHound**: o controle de bolso do DeepSeek Harness (DSH).
O celular manda o pedido, o PC executa no Harness, e **tudo o que o Harness pensa,
escreve e pede volta para o celular em tempo real** — inclusive os **pedidos de
aprovação**, que você decide de onde estiver.

O par deste repositório é o **PocketHound desk** (Electron, no PC) e os plugins do
Harness, que vivem em **[arivsj/dsh-plugins](https://github.com/arivsj/dsh-plugins)**:
o [`pockethound`](https://github.com/arivsj/dsh-plugins/tree/main/pockethound), que
abre a ponte por onde este app fala (**obrigatório**), e o
[`session-cost`](https://github.com/arivsj/dsh-plugins/tree/main/session-cost), de
onde vem o valor em dólar do rodapé (opcional). A especificação completa está em
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

### Plugins (dependência do PC)

O app fala com o Harness por dois plugins que moram em
**[arivsj/dsh-plugins](https://github.com/arivsj/dsh-plugins)** — fora deste
repositório; a instalação deles é separada do APK:

```bash
cd ~/dsh-plugins/pockethound && ./install.sh   # obrigatório: a ponte /ph/*
cd ~/dsh-plugins/session-cost && ./install.sh  # opcional: dólar e contexto no rodapé
```

Cada `install.sh` é idempotente: copia o pacote para
`$DSH_HOME/profiles/node_modules/` e garante a entry em
`$DSH_HOME/cordis.patch.yml` (camada do usuário = todos os perfis e todos os
workspaces). A entry compõe a quente, mas o **código** do plugin só carrega no
boot: **reinicie o Harness** depois de atualizar um plugin.
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
- **Notificações de bandeja** (`notificacao/`): o `NotificacaoService` é um
  serviço em primeiro plano que **mantém o processo vivo em segundo plano — e
  com o app fechado pela lista de tarefas** (é ele que segura a conexão com o
  PC) e converte os `Aviso`s do `HoundRepository` em notificação do Android:
  `approval.request` / `question.request` ("Autorização necessária", canal de
  alta que aparece até na tela bloqueada — a aprovação tem janela de ~90 s) e
  `turn.end` concluído ("Trabalho concluído", id por sessão: atualiza em vez
  de duplicar). Com a tela aberta não notifica (o cartão já está lá); ao sair
  da tela, o que estava pendente vira notificação. O toque abre o chat, a
  resolução no PC retira o aviso da bandeja, e a permissão `POST_NOTIFICATIONS`
  (Android 13+) é pedida na abertura. **Limite honesto:** *forçar parada*
  derruba o serviço — aí só um push externo (FCM/ntfy) avisaria, que é o
  próximo degrau quando quiser. Testes: `NotificacaoTest` (textos e ids).
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
- **Vigia do prompt** (`core/session/PromptWatchdog.kt`): o PC **aceitar** um
  prompt não é o mesmo que **responder**. O vigia separa quadro de abertura
  (`turn.start`, `step.start`, o eco da sua mensagem) de quadro de produção
  (texto, raciocínio, ferramenta, fim de turno) e, passado o prazo, a aba Chat
  diz o que aconteceu em vez de ficar muda. Prazo maior quando a sessão já estava
  ocupada — aí a demora é a fila, não um defeito.
- **Ordem e destino das sessões** (`core/session/SessionOrder.kt`): a lista do
  celular seguia a ordem de chegada dos quadros, que não quer dizer nada para
  quem olha a tela; agora quem está trabalhando vem primeiro e, dentro do grupo,
  a que deu sinal de vida mais recente (`lastSeen`). Sem escolha do humano, o
  destino é a mais recente **não-subagente**, e a aba Chat declara em texto para
  onde o próximo prompt vai.
- **Linhas de passo, como no Harness** (`core/session/ToolSummary.kt`): cada
  chamada vira uma linha legível — rótulo e assunto ("Comando · Check node and
  ignore rules", "Leitura · src/Main.kt") — em vez do JSON dos argumentos, que
  esconde justamente a frase que o modelo escreveu para ser lida. O JSON vira o
  **detalhe**, que abre no toque. O resultado casa com a chamada pelo `callId` e
  traz o nome que antes saía vazio ("resultado · "). O raciocínio entra recolhido,
  com a primeira frase à mostra.
- **Estado do turno** (`core/session/TurnStatus.kt`): "trabalhando há 0:42 ·
  passo 12 · 2 na fila" enquanto roda, e o resumo (turnos, tokens) quando para. A
  fila vem do PC: o plugin passou a projetar `agent/inbox/spliced` e o hub publica
  o tamanho **absoluto** da fila, para um replay não contar duas vezes.
- **Rolagem que não sequestra** (`ui/chat/ChatScreen.kt`): a conversa só desce
  sozinha se o usuário já estava no fim. Se ele subiu para ler, a tela fica onde
  ele deixou e aparece uma pílula "N novas · ir para o fim" — um toque reata.
  Mesma regra do Harness no navegador, onde nada aparece e a conversa simplesmente
  não se mexe.
- **Decisão de aprovação com resposta visível** (`core/model/PhModels.kt` +
  `ui/approvals`): o cartão diz se o comando **saiu** ("enviando ao PC…", "comando
  entregue ao PC em 120 ms"), ou por que **não saiu** (HTTP, sem contato). Sem
  isso, "toquei e nada mudou" é indistinguível de "o comando nem saiu do aparelho".
- **Descoberta na rede local** (`core/transport/LanBeacon.kt`): o PC **já**
  gritava o endereço dele na porta UDP 7412 a cada 3 s — faltava alguém
  escutando. O app agora escuta, valida (serviço, versão, porta plausível) e só
  segue o farol de um PC **que conhece este aparelho**: a lista `dev` do anúncio
  (prova forte, porque o id do aparelho não viaja em anúncio nenhum) ou, num desk
  antigo que ainda não manda a lista, o nome com que ele foi pareado. Era isso
  que faltava para "estou em casa, na mesma rede" voltar a conectar depois de o
  DHCP trocar o IP do PC — e por que a decisão de confiar é uma função pura
  testada (`Farol.confiavel`), não um `if` no laço do soquete: seguir um farol é
  escolher para quem mandar o token. O caminho aparece como `lan` na Torre.
- **Repositório ligado** (`data/repo/HoundRepository.kt`): dobra os quadros do
  `SessionClient` em `sessions`, `transcript`, `approvals`, `deskState`,
  `notices` e `promptStatus`. O estado começa **vazio**, não com exemplo — dado
  de exemplo numa tela que deveria mostrar o PC é pior que tela vazia.
- **Botão de atualizar a conversa** (`data/repo/HoundRepository.kt` +
  `core/session/MarcaDoReplay.kt` + `ui/chat/ChatScreen.kt`): a conversa do
  celular é montada do fluxo ao vivo, e o fluxo perde pedaços — a rede troca de
  torre, o app é suspenso, o rádio entope e o PC descarta um delta. Quando isso
  acontece a tela fica **velha e muda**, indistinguível de "o agente parou". O ↻
  da barra do chat pede ao PC o reenvio do buffer dele (o mesmo replay da
  reconexão, que ele guarda para todos os aparelhos) e dobra só o que faltava:
  uma marca d'água de `seq` diz o que já passou e um `juntar` no redutor nunca
  repete id — item repetido derrubaria a lista, porque o id é a chave de cada
  linha. A faixa acima do composer conta o que voltou ("4 novidades", "nada
  novo", "não deu para confirmar: …").
- **Chegou atrasado? Só o fim entra** (`core/session/CaudaDoReplay.kt` +
  `data/repo/HoundRepository.kt`): quando o app volta depois de um tempo fora, o
  PC reenvia o buffer dele — horas de conversa. Despejar isso na tela é errado de
  um jeito que se sente: a conversa enche de coisa velha e o que interessa afunda.
  A regra é a do usuário: **se não viu, já foi**. O replay grande vai para uma
  transcrição à parte, e no fim só a **cauda** entra na tela — as últimas 10
  mensagens de conversa, com os passos que vieram depois da mais antiga delas, e
  um teto de 60 linhas para um turno gigante de ferramentas não virar o mesmo
  despejo. Queda de rede comum (menos de 200 quadros de distância) não corta
  nada: perder as últimas mensagens por um soluço de dois segundos seria pior que
  o problema. O que já estava na tela **fica** — o corte é do que chegou agora.
  Um aviso na Torre explica por que a conversa não tem tudo.

  **A cauda aparece a cada quadro, não no fim do replay** — e o fim da
  recuperação tem três caminhos (o `replay.done` do PC, um silêncio de 6 s depois
  da rajada, ou um teto de 30 s). Isso não é zelo: a primeira versão esperava só
  o `replay.done`, e o PC **descarta** esse quadro quando o celular está para trás
  (ele estava na lista de "substituíveis" da contrapressão) — ou seja, justamente
  num replay grande. Preso, o app engolia tudo em silêncio: a tela dizia "sem
  transcrição ainda" com o agente trabalhando. A lição ficou no código dos dois
  lados: no PC, `replay.done` saiu da lista de descartáveis; no app, nada de
  controle pode depender de **um** quadro só.

  **E o replay tem teto na origem**: o app pede `/ph/stream?cursor=N&tail=400`, e
  o PC manda no máximo os últimos 400 quadros do buraco. Sem isso, voltar depois
  de um tempo fora empurrava ~700 KB (o anel inteiro, 4 000 quadros) pelo rádio
  antes de a primeira mensagem nova aparecer — e o que interessa está no fim
  dessa fila. O cursor anda do mesmo jeito, então o app não fica devendo nada; um
  PC que ainda não conhece o parâmetro continua mandando tudo.
- **Reconexão que volta sozinha** (`core/session/SessionClient.kt` +
  `core/transport/P2pTransport.kt` + `core/transport/Sse.kt`): quatro defeitos
  que faziam a conexão cair e não voltar — (1) o prazo de silêncio chegava como
  `CancellationException` e matava o laço de reconexão; (2) o batimento do PC era
  descartado no decodificador e uma conexão saudável **e ociosa** era derrubada a
  cada 45 s; (3) a escolha direto/P2P ficava em cache por 1 min depois de a rede
  mudar; (4) uma conexão QUIC zumbi era reaproveitada e pendurava o pedido por até
  3 minutos — agora o fluxo descarta a conexão ao terminar e um comando repetível
  ganha uma segunda tentativa com handshake novo.
- **Custo e contexto no rodapé** (`ui/chat/ChatScreen.kt` + `core/session/TurnStatus.kt`):
  abaixo do campo de prompt aparece `US$ 0,0142 · contexto 12% · 120k tokens`. O
  gasto **não é calculado aqui**: o plugin do celular lê as projeções do Harness (o
  `session-cost`, que tarifa cada requisição no horário dela, e o medidor de
  contexto do próprio Harness) e publica um quadro `stats` — assim o celular e o
  navegador mostram o mesmo número. O retrato é **estado**: o desk o reenvia a cada
  conexão, então quem abre o app depois vê o gasto. Sem os plugins, a linha
  simplesmente não aparece. O botão **enviar ficou à direita**, como em mensageiro, e
  o `steer` (o modo que interrompe o turno em curso) ficou na esquerda.
- **Testes** de JVM (**137**, todos passando): `ContractTest`, `SseDecoderTest`,
  `TranscriptReducerTest`, `TranscriptRowsTest`, `AtualizacaoTest`,
  `CaudaDoReplayTest`, `LanBeaconTest`, `P2pFramingTest`, `FramesTest`,
  `PairingPayloadTest`, `QrDecodeTest`, `PromptWatchdogTest`, `SessionOrderTest`,
  `ToolSummaryTest` e `TurnStatusTest`.
  Mais o `P2pTunnelTest`, que roda **no aparelho** — ver abaixo.

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
| Notificações | ✅ aprovações, perguntas (`pockethound_ask`) e fim de turno já notificam pelo serviço de primeiro plano (`notificacao/`); falta só o quadro `notice` (`pockethound_notify`) |
| Regras | o "não perguntar de novo" já chega ao desk pelo `approval.decide`, mas a tela ainda não mostra as regras ativas |
| Pareamento | não há leitor de QR (CameraX + ZXing já estão nas dependências) nem o handshake `hello`/`hello.ack` que grava o token no `SecureStore` |
| Aprovações | o "não perguntar de novo" ainda não vira regra no desk |
| Ajustes | só o modo de transporte é persistido; endereço, nome do dispositivo e prazo ainda não são salvos |
| Replay | ✅ o cursor (`lastSeq`) agora é **carregado na abertura**: um reinício de processo não reabre o anel do PC do começo — era isso que ressuscitava aprovações/perguntas já respondidas quando o quadro `resolved` tinha saído do anel |

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
├── notificacao/                 NotificacaoService (1º plano) · Aviso · NotificacaoTexto
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
