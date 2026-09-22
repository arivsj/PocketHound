# Estado do trabalho — 11/set, 20:5x

> **Atualização de 22/set** (esta rodada): corrigido o bug do "sem transcrição ainda" (a cauda esperava um `replay.done` que o PC descarta) e teto de replay na origem (`tail=400`); **só o fim entra** depois de um atraso grande (cauda de 10 mensagens, em vez de horas de replay); **descoberta na LAN** (o app escutando
> o farol UDP do PC e o farol do desk passando a dizer quais aparelhos ele
> conhece — em casa o celular acha o PC sozinho, sem IP digitado); botão **↻ atualizar** no chat, com
> marca d'água de `seq` e redutor idempotente; e quatro correções de reconexão no
> app (prazo de silêncio que matava o laço, batimento do PC descartado, cache do
> caminho direto/P2P, conexão QUIC zumbi reaproveitada). **107 testes de JVM,
> todos passando**; APK de debug reconstruído. O diagnóstico da conexão e as
> opções de caminho estão em **`CONEXAO.md`** — leia antes de mexer em transporte.
>
> **Para retomar, leia `PENDENCIAS.md`**: o bug da aprovação pendente está
> **resolvido e confirmado em campo** (a causa era o plugin não publicar o pedido
> quando não havia celular conectado; a correção é o `waitForPhone`), com o estado
> dos três repositórios e o roteiro para repetir o teste.
>
> Nada foi commitado depois de `81d837b` (app) e `da0e61d` (desk): a regra da casa
> pede autorização a cada commit.

Documento de passagem: o que está pronto, o que foi descoberto, o que ficou em
aberto e por onde continuar. Apague quando não servir mais.

---

## 1. O que está pronto, testado e instalado

**No celular** (APK de debug instalado às 19:51 e de novo depois da correção do
P2P; `app/build/outputs/apk/debug/app-debug.apk`):

| Melhoria | Onde |
|---|---|
| Linha de ferramenta com rótulo + assunto ("Comando · Check node and ignore rules") em vez de JSON cru, com o corpo abrindo no toque | `core/session/ToolSummary.kt` + `core/session/TranscriptReducer.kt` |
| Raciocínio recolhido ("Pensou · <primeira frase>") | `ui/chat/ChatScreen.kt` (`LinhaDePasso`) |
| Resultado casado com a chamada pelo `callId` (antes saía "resultado · " vazio) | `TranscriptReducer.kt` |
| Estado do turno: "trabalhando há 0:42 · passo 12 · 2 na fila" e resumo de tokens | `core/session/TurnStatus.kt` |
| Rolagem que não sequestra: só desce se você já estava no fim, com pílula "N novas · ir para o fim" | `ui/chat/ChatScreen.kt` |
| Vigia do prompt: "o PC aceitou mas não deu sinal de vida" / "começou e parou sem responder" | `core/session/PromptWatchdog.kt` |
| Ordem das sessões por atividade + destino explícito ("→ TÍTULO") | `core/session/SessionOrder.kt` + `ChatScreen.kt` |
| Cartão de aprovação diz o que houve com o envio ("enviando…", "comando entregue ao PC em X ms", motivo da recusa) | `ui/approvals/ApprovalsScreen.kt` + `HoundRepository.decide` |

**Testes:** 96 de JVM, todos passando (`./gradlew :app:testDebugUnitTest`), sendo
novos: `ToolSummaryTest`, `TurnStatusTest`, `TranscriptRowsTest`,
`PromptWatchdogTest`, `SessionOrderTest`.

**No plugin do Harness** (`~/dsh-plugins/pockethound`, fora deste repositório):

- **Aprovação ao mesmo tempo no celular e no PC** (`shareWithDesktop`, ligado por
  padrão): antes o celular segurava a pergunta com exclusividade por 90 s e a tela
  do PC só perguntava DEPOIS do estouro — era isso que travava o desenvolvimento.
  Agora os dois recebem junto e a primeira resposta vale; quando o PC responde, o
  cartão do celular é retirado na hora (`hub.withdrawApproval`).
- **Tamanho da fila** ("N na fila"): o plugin passou a projetar
  `agent/inbox/spliced` e o hub publica o número absoluto.
- Testes do plugin: **33 (self-test) + 28 (host-test), todos passando**.
- O pacote instalado em `~/.dsh/profiles/node_modules/dsh-pockethound` já está
  atualizado e **rodando** (`pedidas=2 estouros=0` comprova).

**No repositório do desk** (`/media/.../backup/dev/PocketHound desk`):

- `plugin/` — cópia do plugin dentro do repo, para que um clone do desk já venha
  com a ponte (antes o README mandava rodar um caminho que só existia nesta
  máquina).
- `scripts/sync-plugin.sh` (+ `--check`) — mantém a cópia em dia com a fonte.
- `README.md` — seção "Rodando" reescrita: `./plugin/install.sh` e o aviso de que
  o Harness precisa reiniciar depois.

---

## 2. O bug principal encontrado hoje

**O celular não conseguia aprovar porque, pelo túnel P2P, todo comando chegava
sem tipo e era descartado em silêncio.**

- `core/transport/P2pTransport.kt`, método `pedido()`: o corpo do POST ia como
  **payload solto**.
- `src/transport/server.js` (desk) lê `body.type` e `body.payload` — recebia
  `type = ''` e caía no `default: 'comando desconhecido do celular'`.
- Consequência: com o transporte em **P2P_ONLY** (era o caso do aparelho),
  aprovar, mandar prompt e cancelar morriam ali. O sentido PC → celular
  continuava perfeito porque o SSE é um GET e não passa por esse código — foi por
  isso que o cartão aparecia e a resposta nunca voltava.
- Prova: a ponte registrou **16 pedidos e 16 estouros, zero decididos**; e um
  pedido cronometrado levava **102,5 s** (90 s de espera + delegação).
- Correção: o corpo passou a ser o quadro inteiro, igual ao caminho direto
  (`PhCodec.outbound(call.type, call.payload, call.session)`). Compilado e
  instalado. **Confirmado pelo usuário: a aprovação pelo celular passou a
  funcionar.**

## 2.2 O prompt nunca chegava: serviço invisível no contexto do plugin

Depois da correção do P2P, a aprovação passou a funcionar mas o prompt continuava
sumindo: o app dizia "a sessão já estava ocupada / na fila" e nada chegava.

- **Prova**: chamando `/prompt` na ponte, direto, para uma sessão que a própria
  ponte listava como viva:
  `{"ok":false,"error":"sessão não encontrada: session-062461aa-..."}`.
- **Causa**: o plugin procurava o agente com `ctx.agents` — acesso por
  **propriedade**, que só resolve quando o serviço está no mesmo escopo do
  contexto. O plugin é montado pela camada do usuário (`cordis.patch.yml`) e ali
  a propriedade vem `undefined`, **sem erro**, porque a leitura está dentro de um
  `try/catch`. O mesmo valia para `ctx.sessionQuery` (por isso a lista do celular
  nunca trazia o corpus do disco — só sessões vivas) e `ctx.sessionPersistence`.
- **Por que a aprovação funcionava e o prompt não**: a aprovação usa o
  `request.agent` que o próprio Harness entrega ao hook; o prompt precisa
  *procurar* o agente, e era aí que o serviço invisível aparecia.
- **Correção**: `servico(ctx, nome)` tenta a propriedade e cai para
  `ctx.get(nome)`, que varre a árvore. Aplicado em `agents`, `sessionQuery` e
  `sessionPersistence`.
- Testes do plugin: **33 (self-test) + 28 (host-test)**, com o contexto de teste
  mudado para expor os serviços **apenas** via `ctx.get` — é assim que ele os
  encontra em campo, e o teste existe para isso não voltar.
- **RESOLVIDO em campo**: o Harness foi reiniciado (processo novo, ponte na porta
  35827) e um prompt vindo do celular apareceu no log da sessão como
  `agent/inbox/spliced` + `user/message` (turno 18): `'teste cel'`. De quebra, a
  lista de sessões passou a trazer o corpus frio do disco
  (`session-31f43c89`, `session-37c31a7d`, `84bfc509`, `e0c4cc27`...), que nunca
  aparecia antes.

## 2.3 O aviso falso de "está na fila" (corrigido no app)

`HoundRepository.sendPrompt` decidia se a sessão estava ocupada por
`sessao.status == SessionStatus.Running`, e o PC manda `status: "live"` querendo
dizer apenas "esta sessão existe na memória". Resultado: o vigia anunciava "a
sessão já estava ocupada: o prompt entrou na fila do próximo turno" com o agente
parado — a mensagem que mais confundiu o diagnóstico. Agora quem responde é o
**turno** (`_turnStatus[sessao.id]?.running`). Corrigido, compilado e instalado.

---

## 3. O que ficou em aberto (por onde continuar)

### 3.1 O cartão do celular some antes de dar tempo de tocar

Medido agora, com o plugin novo: o pedido é publicado, o **PC responde em ~10 s**
(ele está na frente do usuário, que clica) e o cartão do celular é retirado na
hora — `approval.resolved { outcome: allowed-once, by: desktop }`. O usuário vê
"só no computador". **É comportamento correto da correção, mas péssimo para
testar e discutir.** Opções, para decidir:

1. O cartão do celular, ao ser retirado, ficar alguns segundos mostrando
   "aprovado no PC às 20:51" em vez de sumir (melhor UX; só no app).
2. Uma janela curta de exclusividade para o celular antes de soltar no PC
   (ex.: 8 s) — dá chance de tocar sem devolver os 90 s de espera.
3. Só mandar para o celular quando a tela do PC não estiver atendendo.

### 3.2 O prompt que o celular mandou não chegou à sessão

Sintoma relatado: o app disse "a sessão já estava ocupada: o prompt entrou na
fila do próximo turno" e depois "100 e poucos segundos sem nenhum quadro nesta
sessão". Como a sessão estava ociosa, o mais provável é:

- **o mapeamento `live` → "ocupada" está errado no app**: o PC manda
  `status: "live"` para dizer "esta sessão existe na memória", e o app traduz
  isso como `SessionStatus.Running` (`HoundRepository.paraSessao`). Por isso o
  vigia marca "na fila" mesmo com a sessão parada. Correção: usar o turno de
  verdade (`TurnStatus.running`), não o status do PC.
- ou o prompt foi para **outra sessão** (a selecionada no app) — inclusive a
  sessão grande do desk (477 mil eventos) que estava travada com turno aberto.

Falta medir: qual sessão o app tinha selecionada e o que a ponte registrou no
`/prompt` (o desk loga "prompt do celular: entregue").

### 3.3 Outras pendências

- **Commits**: nada foi commitado, em nenhum dos três repositórios (app, desk,
  plugin) — a regra da casa pede sua autorização antes. No app: `ChatScreen.kt`,
  `HoundRepository.kt`, `ApprovalsScreen.kt`, `RootViewModel.kt`,
  `TranscriptReducer.kt`, `Frames.kt`, `PhModels.kt`, `P2pTransport.kt`,
  `README.md` + 4 arquivos novos de `core/session` + 5 de teste.
- **Sessão travada** do desk (477 mil eventos, turno aberto desde 18:20) continua
  travada; vale cancelar o turno pelo celular (agora que o comando chega) ou
  começar sessão nova naquele projeto.
- `phones=6` na ponte: o desk acumula conexões antigas do celular. Não quebra
  nada, mas indica que o celular reconecta sem fechar a anterior — vale olhar.

---

## 4. Como retomar

```bash
# app: testar e instalar
cd /home/aridev/AndroidStudioProjects/PocketHound
export JAVA_HOME=/home/aridev/programs/android-studio/jbr
./gradlew :app:testDebugUnitTest :app:assembleDebug
/home/aridev/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# plugin: testar
cd ~/dsh-plugins/pockethound && node .dev/self-test.mjs && node .dev/host-test.mjs

# desk: sincronizar a cópia do plugin e conferir
cd "/media/aridev/970254cf-301b-4767-929c-e756efc3764d/backup/dev/PocketHound desk"
./scripts/sync-plugin.sh --check
```

Ferramentas de diagnóstico que eu deixei prontas (em `build/gui-probe/`, pasta
ignorada pelo git): `probe3.mjs` (print da GUI do Harness pelo CDP),
`vigia-fluxo.py` / `vigia3.py` (quadros de aprovação na ponte em tempo real),
`tap.py` (toca num botão do celular pelo texto, achando o ancestral clicável),
`teste-aprovacao-concorrente.mjs` e `teste-fila.mjs` (provas do plugin novo).
