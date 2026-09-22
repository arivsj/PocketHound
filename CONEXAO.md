# Conexão — por que cai, e por onde seguir

Documento de trabalho, 22/set. Junta o diagnóstico do que foi medido, o que já
foi corrigido e as opções de caminho. Apague quando a decisão estiver tomada.

---

## 1. O sintoma

- "fica o tempo todo desconectando";
- "às vezes demora chegar as coisas novas";
- "tem hora que a conexão cai e não volta mais".

## 2. Como a conexão funciona hoje

    celular (Kotlin)  --QUIC/iroh ou HTTP+SSE-->  desk (Electron)  -->  ponte do
    plugin (dentro do Harness)

São **quatro elos e três processos** no caminho de cada mensagem:

| Elo | O que é | Como morre |
|---|---|---|
| App | `SessionClient` + `P2pTransport` + Ktor | rede muda, app suspenso em segundo plano |
| Túnel | `bridge.py` (Python + iroh, em `vendor/`) | supervisor só tenta **5 vezes** e desiste |
| Desk | app Electron (janela) | **fechar a janela derruba o celular inteiro** |
| Plugin | ponte de loopback dentro do Harness | reinício do Harness troca a porta (o desk reencontra) |

O P2P não é o problema em si: o iroh faz hole punching e tem relay. O problema é
que **quatro coisas precisam estar de pé ao mesmo tempo**, e nenhuma delas avisa
quando cai.

### Medido agora (22/set, 17h30)

- `ps` → **o desk não está rodando** (0 processos).
- `GET /health` da ponte do plugin → `subscribers: 0, phones: 0`: ninguém
  conectado. Com o desk fechado, o celular **não tem por onde entrar** — nem na
  mesma rede, nem por P2P. É a explicação mais provável do "cai e não volta mais".
- A ponte do plugin reiniciou às 17h08 (o Harness subiu de novo) e a porta mudou
  de 42853 para 36339 — o desk reencontra pelo `bridge.json`, o celular não
  precisa saber.

## 3. Defeitos encontrados e corrigidos no app hoje

1. **O prazo de silêncio matava o laço de reconexão.** `TimeoutCancellationException`
   É uma `CancellationException`; o `catch` novo que eu tinha escrito rethrowava as
   duas e o laço morria na primeira rede ruim. Agora só o cancelamento de fora
   (`stop()`) mata o laço.
2. **O batimento do PC era jogado fora.** O SSE manda `: beat` a cada 15 s e o
   `SseDecoder` descartava a linha — `timeout(45 s)` contava silêncio e derrubava
   conexão **saudável e ociosa** (sessão parada, ninguém escrevendo). Agora o
   batimento vira `IncomingFrame.Beat`: não é dado, mas é sinal de vida.
3. **A escolha direto/P2P ficava velha.** O seletor guarda o caminho por 1 min;
   quando a rede mudava (Wi-Fi → 4G) o app insistia no caminho morto. Agora cair o
   fluxo `invalidar()` a escolha e a próxima tentativa sonda de novo.
4. **Conexão zumbi reaproveitada.** O `P2pTransport` guardava a conexão QUIC; uma
   conexão "morta mas aberta" pendurava o pedido até **3 minutos** (`REQUEST_TIMEOUT_MS`)
   e, pior, o fluxo do SSE que caía não a descartava — a próxima tentativa subia na
   mesma conexão morta, para sempre. Agora: prazo curto (20 s) quando a conexão é
   reaproveitada, descarte no fim do fluxo, e uma segunda tentativa com handshake
   novo para quadro repetível (nunca para `prompt.send`/`session.create`).

Testes: **107 de JVM, todos passando** (`AtualizacaoTest` é o arquivo novo).

## 4. O que continua frágil (estrutural, precisa de decisão)

- **O desk é um app de janela no caminho crítico.** Fechar a janela = celular sem
  caminho. Não há serviço, nem bandeja, nem caminho alternativo.
- **O supervisor do túnel desiste** depois de 5 quedas (`MAX_RESTARTS`).
- **Relay público do iroh**: sem ele, CGNAT (4G) não atravessa.
- **Buffer de 4 000 quadros** no plugin e no desk: ausência longa perde história
  (o botão ↻ só fecha buracos dentro dessa janela).
- **Python no meio**: `httpx.AsyncClient` novo por bi-stream, asyncio, uniffi.

## 5. Física do NAT, sem ilusão

| Cenário | Dá para conectar direto, sem servidor nenhum? |
|---|---|
| Mesma rede (Wi-Fi de casa) | **Sim** — descoberta local e HTTP direto |
| Celular em 4G, PC atrás de NAT | Só com **hole punching**, e hole punching precisa de alguém para trocar endereços: um relay/DNS de sinalização (iroh usa o do n0; o jogo usa MQTT/Nostr públicos) |
| CGNAT dos dois lados | Precisa de **relay** (o direto não existe) |

Ou seja: "sem servidor externo" é possível de verdade **na mesma rede**, e no 4G
o que dá para escolher é *qual* terceiro troca endereços — público, seu próprio
VPS, ou um túnel de rede pronto (Tailscale/Netbird).

## 6. Por que o P2P do jogo (paintBalon) parece tão estável

O jogo usa **Trystero** (`@trystero-p2p/*`): sinalização por MQTT público
(`broker.emqx.io`, `hivemq`) ou Nostr, e **WebRTC DataChannel** para os dados.
Três diferenças que explicam a sensação:

1. **O navegador cuida da conexão.** ICE, DTLS e SCTP se renegociam sozinhos
   quando a rede muda; o código do jogo nunca vê um objeto "conexão" que pode
   virar zumbi em silêncio — que é exatamente o defeito nº 4 da lista acima.
2. **O jogo fala o tempo todo.** Estado a cada quadro (30–60 Hz); silêncio nunca
   acontece. Um chat fica minutos calado — e o nosso prazo de silêncio tratava
   isso como queda.
3. **Ninguém derruba a conexão em nível de aplicação.** Não há "timeout de
   silêncio", nem processo Python intermediário, nem app Electron de janela
   segurando a porta.

## 7. Opções de caminho

### A. Enxugar a cadeia: o P2P dentro do plugin do Harness
O plugin já tem tudo o que o celular precisa no lado do PC (`hub.js`: `seq`, anel,
replay por cursor, aprovações, perguntas). Hoje o desk **duplica** esse trabalho e
o Python só faz proxy. Com o endpoint iroh dentro do plugin (há binding Node do
iroh), o caminho vira `celular → plugin`: dois elos em vez de quatro, e o desk
volta a ser só UI/pareamento.
- **Ganha**: some o Electron e o Python do caminho crítico; a única coisa que
  precisa estar viva é o Harness — que precisa estar vivo de qualquer jeito.
- **Custa**: dependência nativa nova no plugin, e mexer na ponte que hoje funciona.

### B. LAN primeiro, sempre — **FEITO em 22/set**
Quando os dois estão na mesma rede, não há motivo para túnel nenhum. O desk já
mandava o farol UDP (porta 7412) a cada 3 s; faltava alguém escutando.

O que foi feito:

- **App**: `core/transport/LanBeacon.kt` escuta a 7412, valida o pacote (serviço,
  versão, porta) e publica o PC encontrado. O `TransportSelector` passa a usar
  **o endereço do farol** antes do endereço gravado no pareamento, e o caminho
  aparece como `lan` na Frota. Um anúncio vale 13 s (o PC fala a cada 3 s).
- **Confiança** (`Farol.confiavel`, função pura e testada): só é seguido o farol
  de um PC que **conhece este aparelho** — a lista `dev` do anúncio (prova forte;
  o id do aparelho não viaja em anúncio nenhum) ou, num desk antigo que ainda não
  manda a lista, o nome com que ele foi pareado (prova fraca). Sem uma das duas,
  o endereço descoberto é ignorado: seguir um farol é escolher para quem mandar o
  token, e o token é a única credencial do Harness.
- **Desk** (fora deste repositório): o farol passou a levar `dev: [ids dos
  aparelhos pareados]` — só ids, nunca o hash do token. São duas linhas
  (`src/main.js` e `src/transport/server.js`); o `npm run selftest` do desk
  continua com 57/57.

O que isso resolve: o endereço gravado no pareamento envelhecia (DHCP troca o IP
do PC, o celular troca de rede) e o sintoma era "estou em casa, na mesma rede, e
não conecta". O farol diz qual é o endereço **agora** — sem relay, sem P2P e sem
ninguém digitando IP. O que **não** resolve: o uso na rua, que continua P2P.

### C. WebRTC, como no jogo
Transporte WebRTC (DataChannel) no app — `org.webrtc` no Android — com sinalização
por MQTT/Nostr públicos ou pelo Firebase que o jogo já usa. No PC, quem pode ser o
par é o **renderer do Electron** (Chromium), que já fala WebRTC nativamente.
- **Ganha**: a mesma robustez que você já viu no jogo; ICE renegocia sozinho.
- **Custa**: alto — biblioteca nativa nova no app, uma camada de protocolo nova,
  e sinalização que também é serviço de terceiro.

### D. Túnel de rede pronto (Tailscale / Netbird / WireGuard)
Celular e PC entram numa rede privada; o app continua HTTP+SSE, apontando para o
endereço do PC nessa rede. O `DirectTransport` que já existe passa a ser o único
transporte; iroh, Python e o proxy saem do projeto.
- **Ganha**: de longe o melhor resultado por hora de trabalho — NAT, roaming e
  relay resolvidos por software maduro.
- **Custa**: depende de um terceiro (dá para hospedar o controlador, ex.:
  headscale, mas aí é servidor seu).

### E. Relay próprio
Hospedar o relay do iroh (ou um broker MQTT) num VPS seu: mantém a arquitetura
atual e tira a dependência do relay público do n0.
- **Ganha**: controle e previsibilidade; continua "sem terceiro".
- **Custa**: um servidor para manter (e o usuário pediu para evitar servidor
  externo).

## 8. Recomendação

1. **Agora** (feito): os quatro defeitos do app acima. Eles explicam a maior parte
   do "desconecta o tempo todo" e do "demora chegar".
2. **Feito também**: B (LAN primeiro) — app escutando o farol e desk anunciando
   quem ele conhece. Em casa o celular acha o PC sozinho.
3. **Em seguida, barato**: o desk precisa parar de ser ponto único de falha —
   bandeja/serviço em vez de janela, e supervisor que não desiste depois de 5
   tentativas.
4. **Decisão de fundo**: A (tirar desk e Python do caminho), C (WebRTC como o
   jogo) ou D (tunel de rede pronto). A escolha depende de quanto você quer
   continuar dono do transporte.

## 9. Como medir da próxima vez

```bash
# quanto o plugin produziu, e se alguém está conectado
curl -s http://127.0.0.1:$(python3 -c "import json;print(json.load(open('$HOME/.dsh/pockethound/bridge.json'))['port'])")/health
```

Os campos que interessam: `subscribers` (o desk), `phones` (o celular visto pelo
desk), `counters.dropped` (quadro descartado na contrapressão) e `seq` (crescendo =
o Harness está produzindo). `subscribers: 0` com o app aberto significa que o
problema está **antes** do celular.
