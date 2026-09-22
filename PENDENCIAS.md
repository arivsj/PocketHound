# Pendências — 22/set, fim do dia

> **PARA INSTALAR E TESTAR QUANDO VOLTAR** — três coisas prontas, três reinícios.
>
> ```bash
> # 1. app (o APK já está construído)
> cd ~/AndroidStudioProjects/PocketHound
> /home/aridev/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
>
> # 2. plugin do Harness (a cópia instalada é separada da fonte)
> cd ~/dsh-plugins && ./install-all.sh && ./doctor.sh
> # depois: reiniciar o Harness (a conversa fica salva)
>
> # 3. desk: fechar e abrir a janela (ele reenvia o retrato como estado)
> ```
>
> **O que observar:**
>
> 1. **O seu prompt aparece uma vez só** (antes aparecia duas). Cantos a conferir:
>    mandar a mesma frase duas vezes (têm de aparecer as duas) e um prompt acima de
>    12 mil caracteres (esse ainda pode duplicar — limite conhecido no código).
> 2. **Abaixo do campo de prompt**: `US$ 0,0142 · contexto 12% · 120k tokens`. O
>    gasto vem do plugin `session-cost`; o contexto, do medidor do próprio Harness.
>    A linha some quando não há o que dizer (sessão nova, perfil sem os plugins).
> 3. **O botão enviar ficou à direita**, como em todo mensageiro; o `steer` ficou
>    sozinho na esquerda (e continua desligado — ver seção 3).
>
> Nada disso tem commit ainda.

Documento de passagem. O que ficou **aberto**, o que foi corrigido hoje e por onde
continuar. Apague quando não servir mais.

---

## 5. Preço clicável e escrito por você (23/set)

**Pedido:** deixar explícito que o preço é escrito pelo usuário; tornar o valor
clicável; o clique abre uma janelinha com um campo ("escreva o modelo que você está
usando") e um botão ("Atualizar preço") que **busca na internet**; o mesmo quando o
usuário troca de modelo.

### O que ficou pronto

| peça | estado |
|---|---|
| preços como **estado do usuário** (`~/.dsh/session-cost/precos.json`, manda sobre a config) | ✅ testado |
| leitor da tabela oficial (pico e fora de pico, por modelo) | ✅ 11 provas com o HTML real guardado em `.dev/fixtures/precos.html` |
| busca na internet + gravação + erro claro para modelo desconhecido | ✅ provado com `node .dev/precos-online.mjs` |
| valor **clicável** abrindo a janelinha | ✅ validado no navegador (a janelinha abre com o texto certo) |
| troca de modelo no meio (a sessão em X, os preços de Y) | ✅ a linha avisa `· preço de X` e a janelinha oferece atualizar |
| botão **Atualizar preço** funcionando no navegador | ⏳ **falta reiniciar o Harness** (ver abaixo) |

### O que falta (e por quê)

A rota HTTP vive na **metade host**, e o hot-reload do Harness troca o módulo mas
**não re-executa o `apply`** do plugin que já está montado: a rota nova não nasce sem
reiniciar o `dsh web`. Provado com controle — a rota do `voice-input` (registrada no
boot) responde JSON, a minha devolvia o HTML do app.

Depois de reiniciar o Harness, o botão passa a funcionar. O caminho inteiro já foi
provado por fora da rota: buscar a página, casar `deepseek-flash` (e `deepseek-v4-pro`,
quando o modelo muda), gravar o arquivo e o cálculo passar a usar a tabela nova.

### Detalhes que ficaram escritos no código

- **A busca é sob demanda de propósito**: preço que muda sozinho no meio de uma conta
  é pior que preço velho e declarado.
- **O que já foi contado fica como estava** ao trocar o preço — aquilo foi cobrado com
  o preço da época.
- **Escrita de cache** entra pela tarifa de entrada sem cache (a DeepSeek não publica
  preço separado para ela).

### Uma armadilha que voltou

`if (ctx.get('webServer') === undefined) return` — o serviço **sobe depois** do plugin,
então esse `return` matava a rota em silêncio. É a mesma armadilha do `ctx.get` que já
tinha mordido o plugin do celular (ESTADO.md §2.2). O certo é deixar o `ctx.inject`
decidir, sem checagem antecipada.

---
## 4. Fila, modelo e "respondendo a" (23/set)

**Pedido:** botão pequeno com ícone de chat mostrando quantos prompts estão na fila,
o nome do modelo em uso num select acima do input (à direita), e mostrar acima da
resposta do agente qual prompt a pediu.

### Feito

- **Fila**: chip com ícone 💬 + o número, acima do campo de prompt. Tocar explica o
  que ela é ("já foi aceito pelo PC: entra quando o turno atual terminar. Nada se
  perdeu — não precisa mandar de novo"), porque essa é a dúvida que aparece.
- **Modelo em uso**: badge à direita, na mesma faixa. O plugin lê o modelo **real da
  sessão** do cabeçalho da última requisição (`session.requestHeader().config`), não
  o nome configurado no plugin de custo.
- **"↳ respondendo a: <prompt>"** acima da primeira resposta de cada turno —
  `core/session/PromptDaResposta.kt`, função pura com 6 testes. A ligação sai da
  ordem da conversa; quando a ordem mente (dois prompts na fila antes da primeira
  resposta), a regra **cala** em vez de apontar o prompt errado. Um turno consome um
  prompt, então a fila anda: o turno seguinte recebe o próximo.

### O que NÃO dá: trocar o modelo pelo celular

Fui atrás da API e o achado é este: a escolha de modelo de uma sessão viva mora num
**mapa privado do gateway web** (`selectionFor(agent)` em `dsh-host-apiproxy`), que só
o próprio gateway escreve. O plugin consegue **ler** (do log) e consegue listar os
modelos (`ctx.llm.listProviders()` + `listModels()`), mas não consegue escrever a
escolha da sessão — não há serviço exposto para isso.

Três caminhos, em ordem de esforço:

1. **Mostrar** o modelo (feito ✅) e deixar a troca para a tela do PC.
2. **Trocar o modelo PADRÃO** (com que as sessões NOVAS nascem): isso tem API
   (`saveDefaultModelSelection`), então o app poderia oferecer o select no momento de
   **criar sessão** (a aba Sessões), não no meio da conversa.
3. **Pedir ao Harness** um serviço público de seleção de modelo. É contribuição ao
   DSH, não ao plugin — vale abrir como assunto com quem mantém o Harness.

### Preço é config, não código

Os preços são **defaults do esquema de configuração** do plugin `session-cost`, com os
valores oficiais do `deepseek-flash`. Trocar preço ou janela de pico é editar a entry
em `~/.dsh/cordis.patch.yml` — sem tocar em JavaScript. (Não são buscados da internet
de propósito: preço que muda sozinho no meio de um cálculo é pior que preço velho.)

---
## 3. Custo e contexto no celular + botão enviar à direita

**Pedido:** mostrar o valor da sessão e a porcentagem de contexto **abaixo do campo
de prompt** no celular, e mandar o botão **enviar para a direita** (como em todo
mensageiro). O `steer` ao lado era dúvida: ele é o outro modo de envio do Harness —
`followup` (entra na fila do próximo turno, é o que o enviar faz) contra `steer`
(**interrompe o turno em curso e injeta a mensagem no meio dele**). No PC os dois já
existem (`target.steer` / `target.followup`); a tela ainda não decide quando oferecer.

**Como foi feito, sem duplicar preço nenhum:** o plugin do celular **lê as projeções
do Harness** em vez de recalcular.

| peça | o que faz |
|---|---|
| `session-cost` (plugin novo) | publica `sessionCost`: gasto em US$, tarifado no horário de cada requisição |
| `dsh-token-meter` (do Harness) | publica `contextPressure`: tokens que a próxima requisição leva e a janela do modelo |
| `pockethound` (plugin do celular) | lê as duas em `text.done`/`turn.end` e publica um quadro `turn.event` com `kind: stats` |
| desk | guarda o último retrato por sessão e **reenvia como estado** a cada conexão |
| app | dobra o `stats` no `TurnStatus` e desenha a linha abaixo do composer |

O mesmo número aparece no celular e no navegador porque a conta é feita **uma vez**, no
host. O retrato vai como estado (e não só como história) pelo mesmo motivo das
aprovações: quem abre o app depois precisa ver o gasto, não só quem estava lá na hora.

**Provas:** 6 verificações novas no host-test do plugin (50 passando), 3 no
`TurnStatusTest` do app (137 passando), 63 no desk. Nota: o host-test carrega a **cópia
instalada** — sem `./install.sh` ele testa o código velho (foi o que aconteceu aqui).

---
## 2. Correção pronta: o prompt aparecia duas vezes

**Sintoma:** o prompt mandado pelo celular aparecia **2x** no chat. O Harness recebia
**uma vez só** — era visual, e isso foi confirmado de duas formas: nesta conversa cada
mensagem do usuário chegou uma vez, e o retry do transporte P2P exclui `prompt.send` de
propósito (para resposta perdida não virar segundo prompt).

**Causa:** dois itens diferentes para a mesma mensagem.

| quem cria | id | quando |
|---|---|---|
| o app, ao tocar em enviar | `local-<nano>` | na hora, para a caixa não parecer travada |
| o PC, quando o Harness registra `user.message` | `u-<seq>` | depois da ida e volta |

O `juntar` do redutor deduplica por **id**, e os dois ids são diferentes de propósito
— então os dois balões ficavam.

**Correção:** `TranscriptReducer.tirarEcoLocal` — ao chegar o eco do PC, o eco local
com o **mesmo texto** sai e o do PC entra no lugar. Um por vez, do fim para o começo,
para duas mensagens iguais de propósito continuarem sendo duas. Limite conhecido: texto
acima de 12 mil caracteres pode não casar, porque o PC corta antes de devolver.

**Provas:** 4 testes novos em `TranscriptReducerTest` — o caso normal, duas iguais de
propósito, eco de outro texto (que **não** pode ser comido) e mensagem que nasce no PC
sem eco local. Total do app: **134 testes de JVM**, APK reconstruído.

---

## 1. O bug da aprovação — RESOLVIDO e confirmado em campo

**Sintoma (21h40):** o agente pediu aprovação com o **app fechado**. O PC recebeu o
pedido; ao abrir o app, a conversa aparecia e **a aprovação não**, mesmo pendente.

**Causa:** o plugin só publicava o pedido se houvesse celular conectado. Em
`lib/hub.js`, `requestApproval` começava com:

```js
if (this.phoneCount <= 0 || this.subscribers.size === 0) return null
```

Com o app fechado não há celular conectado, então o pedido **nunca era publicado**:
sem quadro, o desk não tinha o que guardar nem o que reenviar, e o app não tinha o
que mostrar. O defeito não estava no desk nem no app — estava na porta de entrada
do plugin.

**Correção:** `waitForPhone`. Com `shareWithDesktop`, o hub guarda o pedido mesmo
sem celular, à espera de quem abrir o app (a tela do PC já está na corrida de
qualquer jeito). Sozinho, sem o PC perguntando junto, o celular continua sendo
obrigatório — e aí a delegação segue como antes.

**Confirmado em campo (22/set, ~19h):** a cópia instalada às 18:48 (`install-all.sh`)
tem a correção, o Harness subiu depois dela, e o teste do usuário passou: **app
fechado, pedido chegou, ele abriu o app e aprovou pelo celular**. O `/health` do
momento: `approvals: 8`, `timeouts: 0` — nenhum cartão se retirou sozinho.

Provas automatizadas no `self-test` do plugin (*"o pedido fica pendente sem celular
nenhum"*, *"e vai para o anel, para o desk guardar"*, *"quem chega depois ainda
decide"*, *"e o pedido fecha"*): **37 provas passando**.

### Repetir o teste quando desconfiar

1. `cd ~/dsh-plugins && ./install-all.sh` — a cópia instalada é SEPARADA da fonte;
   sem isto o Harness continua carregando o plugin velho.
2. Reiniciar o Harness (a conversa fica salva; é só retomar).
3. **Fechar o app.**
4. Pedir ao agente algo que exija aprovação (ex.: *"escreva um arquivo de teste no
   repositório do desk"*).
5. **Não responder no PC.** Abrir o app: o cartão deve estar lá, escrito
   **"sem prazo"**, junto com a conversa que chegou.
6. Responder pelo celular e conferir se o comando pendente destrava.

Se falhar de novo, os suspeitos na ordem: (a) a cópia instalada do plugin estar
velha (passo 1); (b) o desk não ter reenviado o estado — o bloco *"Estado de agora,
junto com o fluxo"* em `src/transport/server.js`, testável com `.dev/e2e-live.mjs`;
(c) o app ter descartado o quadro por numeração (ver item 3 da tabela abaixo).

## 2. Os quatro defeitos corrigidos hoje (contexto)

| # | Defeito | Correção | Onde |
|---|---|---|---|
| 1 | `tail=400` cortava **história**, mas aprovação pendente é **estado** — o cartão saía do replay | o desk reenvia aprovações, perguntas e sessões pendentes **a cada conexão**, com `seq: 0` | desk `server.js` + `main.js` |
| 2 | o prazo de 90 s **retirava o cartão** do celular com a pergunta ainda aberta no PC | sem prazo quando o PC pergunta junto (`SEM_PRAZO_MS`) | plugin `index.js` |
| 3 | o `seq` **reinicia com o Harness** e o app descartava tudo como "já visto" (tela congelada, "o PC aceitou mas não deu sinal de vida") | o app detecta a renumeração e zera marca d'água **e** cursor | app `MarcaDoReplay.kt`, `HoundRepository.kt`, `SessionClient.kt` |
| 4 | o desk **descartava `replay.done`** na contrapressão (justo num replay grande) e o app ficava preso na recuperação | `replay.done` saiu da lista de descartáveis; a recuperação tem três caminhos de fim | desk `server.js`, app `HoundRepository.kt` |

Também desta rodada: **cauda de 10 mensagens** ("se não viu, já foi"), **↻ atualizar**,
**descoberta na LAN** (o app escuta o farol UDP e só segue um PC que conhece o
aparelho), e o **replay com teto na origem** (`/ph/stream?cursor=N&tail=400`).

Diagnóstico maior da conexão (por que cai, e os caminhos A/B/C/D): `CONEXAO.md`.

## 3. Estado dos três repositórios — NADA COMMITADO

| Repositório | Mudanças na árvore | O que está no ar |
|---|---|---|
| `~/AndroidStudioProjects/PocketHound` (app) | 6 arquivos: `HoundRepository.kt`, `SessionClient.kt`, `MarcaDoReplay.kt`, `CartaoDeAprovacao.kt`, `CartaoDePergunta.kt`, `PhComponents.kt` (+ testes em `AtualizacaoTest.kt`) | **APK instalado no celular** com tudo |
| `PocketHound desk` | `src/main.js`, `src/transport/server.js`, `.dev/self-test.mjs` | **rodando** com as correções (processo de 18:20) |
| `~/dsh-plugins` (plugin) | `lib/index.js`, `lib/hub.js`, `.dev/self-test.mjs` | **instalado, espera reinício do Harness** |

O último commit é o `81d837b` (app) e o `da0e61d` (desk), feitos antes destas
correções. Autorização para commitar vale uma vez — pedir de novo.

## 4. Comandos do dia a dia

```bash
# app: testes + APK + instalar no celular
cd ~/AndroidStudioProjects/PocketHound
export JAVA_HOME=/home/aridev/programs/android-studio/jbr
./gradlew :app:testDebugUnitTest :app:assembleDebug
/home/aridev/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# desk: provas (63) e o e2e contra o Harness vivo
cd "/media/aridev/970254cf-301b-4767-929c-e756efc3764d/backup/dev/PocketHound desk"
node .dev/self-test.mjs && node .dev/e2e-live.mjs

# plugin: provas (37 + 44) e instalação
cd ~/dsh-plugins/pockethound && node .dev/self-test.mjs && node .dev/host-test.mjs
cd ~/dsh-plugins && ./install-all.sh && ./doctor.sh

# o estado da ponte: quem está conectado e se o Harness está produzindo
curl -s http://127.0.0.1:$(python3 -c "import json;print(json.load(open('$HOME/.dsh/pockethound/bridge.json'))['port'])")/health
```

Leitura do `/health`: `subscribers` = o desk; `phones` = celulares vistos pelo desk;
`seq` crescendo = o Harness produzindo; `pendingApprovals` = pedidos esperando.

## 5. Armadilhas aprendidas hoje (não repetir)

- **A cópia instalada do plugin é outra coisa.** Editar `~/dsh-plugins/*/lib` não muda
  nada até `install-all.sh`; e o Harness só recarrega ao reiniciar.
- **Quadro de controle não pode ser "substituível".** `replay.done` era descartado na
  contrapressão — justo quando importava.
- **Nada de controle pode depender de um quadro só.** O app ficou preso esperando um
  `replay.done` que não vinha.
- **Cuidado com contador que reinicia.** O `seq` nasce no Harness; reiniciar o Harness
  renumera tudo, e quem guarda "o último visto" precisa perceber.
- **História × estado.** Replay é história (pode ter teto); aprovação pendente, sessão
  e pergunta são estado (vão inteiros, toda vez).
- O **desk precisa estar rodando**: fechar a janela derruba o celular (é o próximo
  item barato: bandeja em vez de janela, e supervisor que não desiste).
