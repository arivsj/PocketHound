package com.pockethound.app.data.repo

import com.pockethound.app.core.model.ApprovalDecidePayload
import com.pockethound.app.core.model.ApprovalRequest
import com.pockethound.app.core.model.DecisaoDeAprovacao
import com.pockethound.app.core.model.DeskState
import com.pockethound.app.core.model.EstadoDaDecisao
import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.LinkState
import com.pockethound.app.core.model.LinkStatus
import com.pockethound.app.core.model.Notice
import com.pockethound.app.core.model.NoticeLevel
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.PromptMode
import com.pockethound.app.core.model.PromptSendPayload
import com.pockethound.app.core.model.QuestionAnswerItem
import com.pockethound.app.core.model.PerguntaNaTela
import com.pockethound.app.core.model.RespostaDaPergunta
import com.pockethound.app.core.model.QuestionAnswerPayload
import com.pockethound.app.core.model.Session
import com.pockethound.app.core.model.SessionCancelPayload
import com.pockethound.app.core.model.SessionCreatePayload
import com.pockethound.app.core.model.SessionSelectPayload
import com.pockethound.app.core.model.SessionSnapshot
import com.pockethound.app.core.model.SessionStatus
import com.pockethound.app.core.model.SessionUpsertPayload
import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.core.model.TurnKind
import com.pockethound.app.core.model.Workspace
import com.pockethound.app.core.session.CaudaDoReplay
import com.pockethound.app.core.session.ConnectionStatus
import com.pockethound.app.core.session.ContagemDaAtualizacao
import com.pockethound.app.core.session.EstadoDaAtualizacao
import com.pockethound.app.core.session.FilaDePerguntas
import com.pockethound.app.core.session.MarcaDoReplay
import com.pockethound.app.core.session.PromptAck
import com.pockethound.app.core.session.PromptStatus
import com.pockethound.app.core.session.PromptWatchdog
import com.pockethound.app.core.session.SessionClient
import com.pockethound.app.core.session.SessionOrder
import com.pockethound.app.core.session.TranscriptReducer
import com.pockethound.app.core.session.TurnStatus
import com.pockethound.app.core.session.TurnStatusReducer
import java.util.concurrent.atomic.AtomicInteger
import com.pockethound.app.core.storage.SecureStore
import com.pockethound.app.core.storage.SettingsStorage
import com.pockethound.app.core.transport.TransportMode
import com.pockethound.app.core.transport.TransportResult
import com.pockethound.app.notificacao.Aviso
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Fonte unica de estado da UI.
 *
 * Nao abre conexao nem guarda posicao de leitura: quem faz isso e o
 * [SessionClient]. Aqui so DOBRAMOS os quadros que ele entrega nos estados que
 * as telas observam.
 *
 * O estado comeca VAZIO, nao com exemplo. Dado de exemplo numa tela que deveria
 * mostrar o PC e pior que tela vazia: o usuario nao sabe se esta vendo a maquina
 * dele ou uma demonstracao.
 */
@Singleton
class HoundRepository @Inject constructor(
    private val settingsStorage: SettingsStorage,
    private val secureStore: SecureStore,
    private val sessionClient: SessionClient,
) {
    // Escopo do processo: o estado compartilhado tem de sobreviver a troca de aba.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _transcript = MutableStateFlow<Map<String, List<TurnItem>>>(emptyMap())
    val transcript: StateFlow<Map<String, List<TurnItem>>> = _transcript.asStateFlow()

    private val _approvals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val approvals: StateFlow<List<ApprovalRequest>> = _approvals.asStateFlow()

    /**
     * Workspaces do Harness — a resposta de `GET /workspaces` da ponte.
     *
     * Sem eles o celular so conversa com sessoes que ja existem; com eles escolhe
     * ONDE trabalhar e abre sessao nova la, que e a ideia central do app na rua.
     */
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    /**
     * Perguntas do agente esperando resposta do celular.
     *
     * A ferramenta de pergunta do Harness chama o provedor da UI web direto, sem
     * gancho; o plugin passou a ENVOLVER esse provedor e a perguntar aqui tambem.
     * Quem responde primeiro vale — e por isso a pergunta precisa de tela, nao so
     * de um aviso.
     */
    private val _perguntas = MutableStateFlow<List<PerguntaNaTela>>(emptyList())
    val perguntas: StateFlow<List<PerguntaNaTela>> = _perguntas.asStateFlow()

    /**
     * Perguntas já resolvidas (requestIds) — a memória que impede o replay de
     * ressuscitar uma pergunta que o PC já teve resposta. Vem do disco na carga
     * inicial e cresce a cada question.resolved (teto em [FilaDePerguntas]).
     *
     * Volatile porque a carga roda noutra coroutine e quem lê é o dobrador de
     * quadros, em outro fio: sem isto o fio do dobrador podia ver a lista vazia.
     */
    @Volatile
    private var perguntasResolvidas: List<String> = emptyList()

    /** O que houve com o ultimo pedido de sessao nova, para a tela contar. */
    private val _criandoSessao = MutableStateFlow<String?>(null)
    val criandoSessao: StateFlow<String?> = _criandoSessao.asStateFlow()

    /**
     * O que houve com o ultimo pedido de workspaces.
     *
     * Existe porque "a lista nao chegou" tem duas causas bem diferentes — o
     * pedido nao saiu do aparelho, ou saiu e a resposta nao voltou — e sem esta
     * linha as duas ficam identicas na tela.
     */
    private val _recadoWorkspaces = MutableStateFlow<String?>(null)
    val recadoWorkspaces: StateFlow<String?> = _recadoWorkspaces.asStateFlow()

    /**
     * O que aconteceu com cada decisão que saiu daqui, por requestId.
     *
     * A fila sozinha não conta essa história: o cartão fica igual enquanto o
     * comando falha em silêncio no caminho.
     */
    private val _decisoes = MutableStateFlow<Map<String, DecisaoDeAprovacao>>(emptyMap())
    val decisoes: StateFlow<Map<String, DecisaoDeAprovacao>> = _decisoes.asStateFlow()

    private val _deskState = MutableStateFlow(DeskState())
    val deskState: StateFlow<DeskState> = _deskState.asStateFlow()

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

    private val _link = MutableStateFlow(LinkState(status = LinkStatus.Offline, path = "-"))

    /** Ligacao com o PC, traduzida do estado do cliente de sessao. */
    val link: StateFlow<LinkState> = _link.asStateFlow()

    /**
     * A escolha da sessao foi do humano?
     *
     * Enquanto for false, a sessao ativa acompanha a lista (a mais recente). Assim
     * que o humano toca num chip, a escolha dele manda — e o app para de mover o
     * destino debaixo do dedo.
     */
    private val _escolhaManual = MutableStateFlow(false)
    val escolhaManual: StateFlow<Boolean> = _escolhaManual.asStateFlow()

    /** Acompanhamento do prompt em voo, para a tela poder avisar o que travou. */
    private val _promptStatus = MutableStateFlow(PromptStatus())
    val promptStatus: StateFlow<PromptStatus> = _promptStatus.asStateFlow()

    /**
     * Estado do turno por sessao: trabalhando ou parado, ha quanto tempo, quantos
     * passos, quantas na fila e quantos tokens.
     *
     * A tela precisa disso para responder a pergunta que o usuario faz o tempo
     * todo: "esta acontecendo alguma coisa?".
     */
    private val _turnStatus = MutableStateFlow<Map<String, TurnStatus>>(emptyMap())
    val turnStatus: StateFlow<Map<String, TurnStatus>> = _turnStatus.asStateFlow()

    /**
     * Avisos para o sistema de notificação do Android.
     *
     * Nasce AQUI de propósito: este é o único ponto em que os quadros já
     * passaram pela marca de replay e pelo filtro de recuperação — notificar
     * direto do fluxo de quadros reavizaria o passado a cada reconexão. Publicar
     * é tryEmit: quem consome é o serviço de primeiro plano, e notificação
     * nunca pode segurar o dobrador de quadros.
     */
    private val _avisosSistema = MutableSharedFlow<Aviso>(replay = 0, extraBufferCapacity = 64)
    val avisosSistema: SharedFlow<Aviso> = _avisosSistema.asSharedFlow()

    private val watchdog = PromptWatchdog()
    private var vigia: Job? = null

    /**
     * O maior `seq` já dobrado na conversa.
     *
     * É o que permite pedir o reenvio do buffer do PC sem duplicar o que já está
     * na tela: o que volta com número menor já passou por aqui.
     */
    private val marca = MarcaDoReplay()

    /** Um reenvio pedido pelo botão "atualizar" está no ar? */
    @Volatile
    private var revalidando: Boolean = false

    /** Tamanho de cada transcrição quando o reenvio foi pedido. */
    private var antesDaAtualizacao: Map<String, Int> = emptyMap()

    /**
     * Quadros que voltaram no reenvio em curso.
     *
     * Atômico porque quem zera é a linha de comando da tela e quem soma é o
     * coletor de quadros — dois fios diferentes.
     */
    private val reenviados = AtomicInteger(0)

    /**
     * Chegamos muito depois da conversa?
     *
     * Ligado quando o primeiro quadro de um replay está a mais de
     * [SALTO_MAXIMO_QUADROS] do último que a tela tinha. Enquanto isso, o replay
     * não vai para a tela: vai para [cauda], e só o fim dele entra.
     */
    private var recuperando: Boolean = false

    /** Quando a recuperação em curso começou (ms). */
    private var recuperacaoDesde: Long = 0L

    /** Quando chegou o último quadro de conversa (ms) — o relógio da rajada. */
    private var ultimoTurnoEm: Long = 0L

    /**
     * A transcrição da recuperação, por sessão — já cortada na cauda.
     *
     * Vive separada da transcrição da tela porque essa é a ÚNICA forma de mostrar
     * o fim de um buraco grande sem mostrar o buraco inteiro.
     */
    private val cauda = mutableMapOf<String, List<TurnItem>>()

    /**
     * A transcrição como ela estava quando a recuperação começou, por sessão.
     *
     * É a base sobre a qual a cauda é desenhada. Congelada de propósito: se a
     * base fosse a transcrição viva, cada linha que saísse da cauda continuaria
     * na tela, e o buraco voltaria a crescer pelo outro lado.
     */
    private var antesDaRecuperacao: Map<String, List<TurnItem>> = emptyMap()

    /** O que houve com o último toque em "atualizar". */
    private val _atualizacao = MutableStateFlow(EstadoDaAtualizacao())
    val atualizacao: StateFlow<EstadoDaAtualizacao> = _atualizacao.asStateFlow()

    val pairing: StateFlow<SessionSnapshot> = settingsStorage.session
        .stateIn(scope, SharingStarted.Eagerly, SessionSnapshot())

    init {
        // Um unico ponto de entrada dos quadros: tudo o que chega do PC passa
        // por aqui, na ordem em que o PC mandou.
        scope.launch {
            sessionClient.frames.collect { quadro -> aplicar(quadro) }
        }
        scope.launch {
            // Amortecimento do link: enquanto o Harness está mudo, o ciclo
            // queda→tento→queda emite um estado novo a cada 1-3 s, e cada estado
            // novo trocava o texto da bandeja e fazia o ícone piscar. O debounce
            // só deixa passar um estado que se SUSTENTA por 4 s — o zapear nunca
            // chega à tela, e o "voltou"/"caiu" de verdade aparece no máximo 4 s
            // atrasado. (Os estados do StateFlow são iguais entre si quando não
            // mudam, então um link estável não fica segurando o emissions.)
            sessionClient.state.debounce(LINK_DEBOUNCE_MS).collect { estado ->
                _link.value = LinkState(
                    status = when (estado.status) {
                        ConnectionStatus.Online -> LinkStatus.Online
                        ConnectionStatus.Conectando, ConnectionStatus.Reconectando -> LinkStatus.Connecting
                        ConnectionStatus.Offline -> LinkStatus.Offline
                    },
                    path = estado.path,
                    latencyMs = estado.latencyMs,
                    reason = estado.reason,
                )
            }
        }
        // A memoria de resolvidas carrega ANTES do laco nascer: um
        // question.request do replay que chegasse primeiro ressuscitaria uma
        // pergunta que o PC ja tem por respondida.
        scope.launch {
            perguntasResolvidas = runCatching { settingsStorage.readPerguntasResolvidas() }
                .getOrDefault(emptyList())
            sessionClient.start(scope)
        }
    }

    /** Dobra um quadro do PC no estado da tela. */
    private fun aplicar(quadro: IncomingFrame) {
        // Reenvio pedido à mão: o PC devolve o buffer inteiro, do começo, e a
        // maior parte dele já está na tela. Um quadro com número menor que a
        // marca já passou por aqui — dobrá-lo de novo duplicaria a conversa, e
        // a marca é o único juiz disso porque o `seq` nasce no PC, não aqui.
        //
        // A checagem vem ANTES de tudo de propósito: um `turn.start` antigo
        // dobrado de novo deixaria a sessão "trabalhando" para sempre, e um
        // `session.upsert` velho desfaria o que chegou depois.
        val agora = System.currentTimeMillis()

        // O fim da recuperação NÃO pode depender de um quadro só. O PC descarta o
        // `replay.done` quando o celular está para trás — ele é "substituível" na
        // contrapressão do rádio, e um replay grande é exatamente quando ele é
        // descartado. Preso esperando um quadro que nunca vem, o app engolia tudo
        // em silêncio: foi o sintoma de 22/set, "as mensagens não chegam".
        if (recuperando && recuperacaoAcabou(quadro, agora)) encerrarRecuperacao()

        if (quadro.seq > 0L) {
            // O PC recomeçou a contar (o Harness reiniciou)? Então a marca não
            // vale mais: sem isto, TUDO o que chega depois é descartado como
            // repetido e a conversa congela — o defeito de 22/set à noite.
            // O caso do ↻ (revalidando) fica de fora: ali o que volta para trás
            // é passado de verdade, e é a marca que o impede de duplicar.
            if (!revalidando && marca.renumerou(quadro.seq)) {
                marca.limpar()
                avisar(
                    NoticeLevel.Info,
                    "O PC reiniciou a contagem",
                    "A conversa nova voltou a ser aceita: o Harness foi reiniciado e a numeração dele recomeçou.",
                )
            }
            if (revalidando) reenviados.incrementAndGet()
            if (!marca.aceita(quadro.seq)) return
            // Chegou MUITO depois do que estava na tela: o que vem agora é o
            // replay de um buraco grande, e buraco grande é história, não notícia.
            // A medida é feita ANTES de marcar, contra o que a tela já tinha.
            if (!revalidando && quadro.seq - marca.ultimo > SALTO_MAXIMO_QUADROS) {
                comecarRecuperacao(agora)
            }
            marca.marcou(quadro.seq)
        }
        when (quadro) {
            is IncomingFrame.SessionUpsert -> {
                val sessao = quadro.payload.paraSessao()
                // A ordem vem da ATIVIDADE de cada sessao, nao da chegada do
                // quadro: quem esta trabalhando agora fica no topo.
                _sessions.value = SessionOrder.order(
                    _sessions.value.filterNot { it.id == sessao.id } + sessao,
                )
                if (!_escolhaManual.value) {
                    _activeSessionId.value = SessionOrder.defaultActive(_sessions.value)?.id
                }
                // Sessao que o PC nao considera viva nao esta trabalhando — sem
                // isto a tela ficaria girando por uma sessao que ja morreu.
                _turnStatus.update { atual ->
                    atual + (sessao.id to TurnStatusReducer.sessao(
                        atual[sessao.id] ?: TurnStatus(),
                        sessao.status == SessionStatus.Running,
                    ))
                }
            }

            is IncomingFrame.SessionGone -> {
                val id = quadro.payload.id
                _sessions.value = _sessions.value.filterNot { it.id == id }
                _transcript.update { atual -> atual - id }
                _turnStatus.update { atual -> atual - id }
                // A sessão morreu; a cauda dela (se havia uma recuperação em
                // curso) não pode ressuscitar na hora de juntar.
                cauda.remove(id)
                if (_activeSessionId.value == id) {
                    // A sessao escolhida sumiu: volta a escolher sozinho, senao o
                    // destino fica apontando para o vazio.
                    _escolhaManual.value = false
                    _activeSessionId.value = SessionOrder.defaultActive(_sessions.value)?.id
                }
            }

            is IncomingFrame.TurnEvent -> {
                val id = quadro.session ?: _activeSessionId.value ?: return

                // O vigia do prompt NUNCA fica de fora — nem na recuperação. É ele
                // quem apaga o aviso "enviado — esperando o PC" quando o PC prova
                // que produziu resposta. Deixá-lo fora do intervalo de recuperação
                // deixava o aviso mentindo na tela depois de a resposta já ter
                // chegado: quem manda um prompt com o agente trabalhando vê "a
                // sessão já estava ocupada: o prompt entrou na fila do próximo
                // turno", e o aviso ficava ali muito depois de o turno terminar.
                //
                // O preço disso: um quadro antigo do replay também pode apagar o
                // aviso antes da hora. É o erro menor dos dois — um aviso a menos
                // por alguns segundos contra um aviso que fica mentindo na tela.
                observarPrompt(id, quadro.payload.kind)

                // "O trabalho que começou terminou" — o outro gatilho de notificação.
                // Só o FIM de turno concluído conta: reason nulo conta como
                // concluído (é o motivo óbvio do contrato), cancelo/erro não — avisar
                // "concluído" de um turno que o usuário parou seria mentira. E na
                // recuperação não avisa nada: aquilo é passado voltando do replay.
                if (quadro.payload.kind == TurnKind.TurnEnd && !recuperando) {
                    val motivo = quadro.payload.reason
                    if (motivo == null || motivo == "completed") {
                        val sessao = _sessions.value.firstOrNull { it.id == id }
                        // Subagente terminando é rotina do Harness — ruído, não notícia.
                        if (sessao == null || !sessao.isSubagent) {
                            notificar(Aviso.deTurno(id, sessao?.title))
                        }
                    }
                }

                // O estado do turno congela na recuperação — um `turn.start` antigo
                // dobrado como se fosse agora deixaria a sessão "trabalhando" para
                // sempre. A exceção é a fila: ela é número absoluto do PC, então o
                // quadro do buraco não inventa estado, só atualiza o retrato.
                // Congelada, ela ficava presa em "1 na fila" com o PC dizendo zero.
                if (!recuperando || TurnStatusReducer.dobraDuranteRecuperacao(quadro.payload.kind)) {
                    _turnStatus.update { atual ->
                        atual + (id to TurnStatusReducer.fold(atual[id] ?: TurnStatus(), quadro))
                    }
                }

                if (recuperando) {
                    ultimoTurnoEm = agora
                    // O replay grande vai para uma transcrição SEPARADA, e só a
                    // cauda dela é que aparece. Despejar horas de conversa antiga
                    // na frente de quem abriu o app é o oposto de mostrar o
                    // presente.
                    //
                    // E a cauda entra na TELA na hora, a cada quadro — não no fim
                    // do replay. O fim pode nunca ser anunciado (o `replay.done` é
                    // descartável na contrapressão), e esperar por ele deixava a
                    // tela muda com o agente trabalhando.
                    val comCauda = CaudaDoReplay.cortar(
                        TranscriptReducer.reduce(cauda[id].orEmpty(), quadro),
                    )
                    cauda[id] = comCauda
                    // A base é a transcrição CONGELADA de quando a recuperação
                    // começou: é o que impede o buraco de voltar a crescer na tela
                    // conforme a cauda anda.
                    _transcript.update { atual ->
                        atual + (id to juntarSemRepetir(antesDaRecuperacao[id].orEmpty(), comCauda))
                    }
                    return
                }
                _transcript.update { atual ->
                    atual + (id to TranscriptReducer.reduce(atual[id].orEmpty(), quadro))
                }
            }

            is IncomingFrame.ApprovalRequest -> {
                val pedido = quadro.payload
                val novo = ApprovalRequest(
                    requestId = pedido.requestId,
                    toolName = pedido.toolName,
                    callId = pedido.callId,
                    reason = pedido.reason,
                    argsPreview = pedido.args.toString().take(600),
                    sessionId = quadro.session,
                    expiresAt = pedido.expiresAt,
                )
                _approvals.update { atual -> atual.filterNot { it.requestId == pedido.requestId } + novo }
                // Fora da recuperação: o que volta no replay de um buraco grande é
                // passado — e passado não buzina no bolso de ninguém.
                if (!recuperando) notificar(Aviso.deAprovacao(novo))
            }

            is IncomingFrame.ApprovalResolved -> {
                // A decisao pode ter vindo do desktop ou de uma regra
                // 'nao perguntar de novo' - a fila esvazia de qualquer jeito.
                _approvals.update { atual -> atual.filterNot { it.requestId == quadro.payload.requestId } }
                _decisoes.update { atual -> atual - quadro.payload.requestId }
                // Decidida em qualquer tela (aqui, no PC, ou por regra): a
                // notificação parou de pedir alguma coisa — some da bandeja.
                notificar(Aviso.Cancelar(Aviso.idAprovacao(quadro.payload.requestId)))
            }

            is IncomingFrame.WorkspaceList -> {
                _workspaces.value = quadro.payload.workspaces.map { info ->
                    Workspace(id = info.id, title = info.title, path = info.path, sessions = info.sessions)
                }
                _recadoWorkspaces.value = _workspaces.value.size.toString() + " workspace(s) recebidos do Harness"
            }

            is IncomingFrame.ReplayDone -> {
                // Só o do DESK fecha o replay, e ele vem sem número (`seq == 0`).
                // O do plugin vem numerado e está DENTRO do anel: num replay grande
                // ele chega no meio, contando uma história velha — tratá-lo como
                // fim cortaria a recuperação pela metade.
                if (quadro.seq != 0L) return
                // Segunda rede para o PC que renumerou: o desk diz qual é o topo
                // do anel dele, e um topo muito abaixo da marca é um Harness que
                // reiniciou. Vale a pena mesmo sem quadro novo nenhum — é o caso
                // do replay vazio, em que nada chega para denunciar sozinho.
                if (quadro.payload.to > 0L &&
                    quadro.payload.to + MarcaDoReplay.FOLGA_RENUMERACAO < marca.ultimo
                ) {
                    marca.limpar()
                }
                if (recuperando) encerrarRecuperacao()
                // Numa atualização pedida pelo usuário, é aqui que ela acaba: o PC
                // já reenviou o que tinha e a cortina fecha.
                if (revalidando) {
                    concluirAtualizacao()
                } else {
                    // No caminho normal, é a hora de pedir o que só o PC sabe — a
                    // lista de workspaces, que dá o "onde trabalhar".
                    pedirWorkspaces()
                }
            }

            is IncomingFrame.DeskState -> {
                _deskState.value = DeskState.fromPayload(quadro.payload)
            }

            is IncomingFrame.Notice -> {
                avisar(
                    quadro.payload.level,
                    quadro.payload.title.ifBlank { "PocketHound" },
                    quadro.payload.body,
                )
            }

            is IncomingFrame.QuestionRequest -> {
                val pedido = quadro.payload
                // A regra pura de entra/nao-ressuscita mora em FilaDePerguntas;
                // identidade igual = nada mudou (replay de resolvida ou trava
                // local preservada) e, nesse caso, nao reavisa.
                val antes = _perguntas.value
                val depois = FilaDePerguntas.receber(antes, pedido, perguntasResolvidas)
                if (depois !== antes) {
                    _perguntas.value = depois
                    // O pockethound_ask no bolso: mesma urgência da aprovação.
                    if (!recuperando) notificar(Aviso.dePergunta(pedido))
                }
            }

            is IncomingFrame.QuestionResolved -> {
                val resolucao = quadro.payload
                // Resolvida — por mim, pelo PC ou por regra: o cartao SAI da fila.
                // E o que o proprio answerQuestion e o hub do plugin ja diziam
                // ("o cartao sai do celular na hora"); a versao antiga marcava
                // respondida e mantinha, e o cartao nunca mais saia.
                _perguntas.value = FilaDePerguntas.resolver(_perguntas.value, resolucao.requestId)
                // E vira MEMORIA: sem isto, o replay de reconexão ou de renumeração
                // reentrega o question.request e a pergunta volta "de pe" com a
                // mesma pergunta ja respondida — o "reapareceu" de campo.
                if (resolucao.requestId !in perguntasResolvidas) {
                    perguntasResolvidas =
                        FilaDePerguntas.marcarResolvida(perguntasResolvidas, resolucao.requestId)
                    val paraGuardar = perguntasResolvidas
                    scope.launch { runCatching { settingsStorage.updatePerguntasResolvidas(paraGuardar) } }
                }
                // Respondida em qualquer tela: retira o aviso da bandeja.
                notificar(Aviso.Cancelar(Aviso.idPergunta(resolucao.requestId)))
            }

            else -> Unit
        }
    }

    /** Traduz o payload de sessao do protocolo no modelo da tela. */
    private fun SessionUpsertPayload.paraSessao(): Session = Session(
        id = id,
        title = title,
        workspace = workspace,
        // O PC fala 'live'/'cold'; a tela fala em estado de agente.
        status = when (status) {
            "live" -> SessionStatus.Running
            "cold" -> SessionStatus.Idle
            else -> SessionStatus.fromWire(status)
        },
        origin = origin,
        depth = depth,
        events = events,
        lastSeen = lastSeen,
        createdAt = createdAt,
    )

    /**
     * Sessao de destino agora.
     *
     * Sem escolha do humano, vale a mais recente em atividade — nunca a primeira
     * que chegou, que era como o prompt ia parar numa sessao que ninguem pediu.
     */
    fun activeSession(): Session? = SessionOrder.resolve(_sessions.value, _activeSessionId.value)

    /**
     * Troca a sessao de destino.
     *
     * A partir daqui a escolha e do humano e a lista para de mover o destino
     * sozinha.
     */
    fun selectSession(sessionId: String) {
        _escolhaManual.value = true

        // Sessão que o app ainda não conhece — uma conversa fria escolhida na lista
        // de workspaces — entra como retrato mínimo. Sem isto o destino não
        // resolvia (SessionOrder.resolve cai no padrão), a escolha se perdia e
        // parecia que o toque não fazia nada. Quando o PC resumir a sessão, o
        // session.upsert traz o título de verdade e substitui este retrato.
        if (_sessions.value.none { it.id == sessionId }) {
            _sessions.value = SessionOrder.order(
                _sessions.value + Session(id = sessionId, title = "", status = SessionStatus.Idle),
            )
        }

        _activeSessionId.value = sessionId
        scope.launch {
            sessionClient.send(
                FrameType.SessionSelect,
                PhCodec.payloadOf(SessionSelectPayload(sessionId)),
                sessionId,
            )
        }
    }

    /**
     * Manda o prompt e NAO inventa a resposta.
     *
     * A linha do usuario entra na tela na hora, como confirmacao local; o resto
     * vem do PC pelo mesmo caminho de todos os outros quadros. Ecoar uma resposta
     * falsa aqui faria a tela mentir quando o PC recusasse o comando.
     *
     * Aceito nao e respondido: por isso o [PromptWatchdog] entra em cena aqui. A
     * sessao que aceita o prompt e morre sem responder deixa a tela muda, e mudo e
     * indistinguivel de "esta pensando".
     *
     * @param text o que o usuario escreveu.
     * @param furarFila se a mensagem deve entrar NO TURNO EM CURSO (prioridade),
     *   em vez de esperar a vez na fila do proximo turno.
     */
    fun sendPrompt(text: String, furarFila: Boolean = false) {
        val limpo = text.trim()
        if (limpo.isEmpty()) return
        val sessao = activeSession() ?: return

        appendToTranscript(
            sessao.id,
            TurnItem(id = "local-" + System.nanoTime(), kind = TurnItemKind.UserMessage, text = limpo),
        )

        // Sessao ja trabalhando: o prompt entra na fila do proximo turno e a
        // demora e legitima. O prazo do vigia sabe disso.
        //
        // Quem responde isso e o TURNO, nao o status da sessao: o PC manda
        // "live" querendo dizer "esta sessao existe na memoria", e traduzir isso
        // como "ocupada" fazia o aviso de fila aparecer com o agente parado —
        // mentira que custou uma tarde de diagnostico.
        //
        // E "furar fila" nao entra na fila: o PC injeta a mensagem no turno que
        // ja esta rodando (`next-step`), entao nao ha fila esperando a vez. Por
        // isso ele nao conta como "ocupada" — o aviso da tela diz o que houve de
        // verdade, e o prazo e o longo so porque a resposta comeca no proximo
        // passo, que pode estar no meio de uma ferramenta demorada.
        val ocupada = !furarFila && _turnStatus.value[sessao.id]?.running == true
        scope.launch {
            val envio = sessionClient.send(
                FrameType.PromptSend,
                PhCodec.payloadOf(PromptSendPayload(sessao.id, limpo, PromptMode.de(furarFila))),
                sessao.id,
            )
            when (envio) {
                is TransportResult.Ok -> {
                    _promptStatus.value = watchdog.sent(
                        sessao.id,
                        limpo,
                        ocupada,
                        System.currentTimeMillis(),
                        furarFila = furarFila,
                    )
                    agendarVigia()
                }

                is TransportResult.HttpError ->
                    avisar(NoticeLevel.Error, "O PC recusou o prompt", "HTTP " + envio.code + ": " + envio.message)

                is TransportResult.NetworkError ->
                    avisar(NoticeLevel.Warn, "Sem contato com o PC", "O prompt não foi entregue.")
            }
        }
    }

    /**
     * Pede ao PC o reenvio do que ele ainda guarda e dobra só o que faltava.
     *
     * ## Por que isto existe
     *
     * A conversa do celular é montada a partir do fluxo ao vivo: o que não
     * chega, não existe. E o que não chega tem várias causas — a rede troca de
     * torre, o aplicativo fica suspenso em segundo plano, o rádio entope e o PC
     * descarta o delta, ou o pedaço do buffer que o celular queria já tinha sido
     * jogado fora quando ele voltou. Em todos esses casos a tela fica **velha e
     * muda**, e velha e muda é indistinguível de "o agente parou de escrever".
     *
     * O PC guarda os últimos milhares de quadros para o replay de reconexão —
     * ele faz isso para TODOS os celulares, conectados ou não. Este é o mesmo
     * replay, pedido na hora em que o humano desconfia da tela.
     */
    fun atualizar() {
        if (_atualizacao.value.emCurso) return

        antesDaAtualizacao = tamanhos()
        reenviados.set(0)
        revalidando = true
        _atualizacao.value = EstadoDaAtualizacao(pedida = true, emCurso = true)

        // Cursor 0 = o começo do que o PC ainda tem. "Do último que vi" não
        // serve para o que este botão existe: é justamente o buraco no meio da
        // conversa que se quer fechar.
        sessionClient.resync(0L)

        // O fim do reenvio é anunciado pelo PC (`replay.done`). Quando ele não
        // vem — PC fora do ar, caminho caído —, o prazo fecha a cortina: rodinha
        // girando para sempre é pior que dizer que não deu.
        scope.launch {
            delay(PRAZO_ATUALIZACAO_MS)
            if (revalidando) concluirAtualizacao("o PC não confirmou o fim do reenvio")
        }
    }

    /**
     * Fecha a atualização em curso e conta o que ela trouxe.
     *
     * @param motivo o que impediu a confirmação do PC, quando houve.
     */
    private fun concluirAtualizacao(motivo: String? = null) {
        revalidando = false
        _atualizacao.value = EstadoDaAtualizacao(
            pedida = true,
            emCurso = false,
            novidades = ContagemDaAtualizacao.novidades(antesDaAtualizacao, tamanhos()),
            reenviados = reenviados.get(),
            quandoMs = System.currentTimeMillis(),
            motivo = motivo,
        )
    }

    /** Quantas linhas cada conversa tem agora. */
    private fun tamanhos(): Map<String, Int> = _transcript.value.mapValues { it.value.size }

    /**
     * Começa uma recuperação a partir de [agora].
     *
     * @param agora instante da decisão.
     */
    private fun comecarRecuperacao(agora: Long) {
        recuperando = true
        recuperacaoDesde = agora
        ultimoTurnoEm = agora
        cauda.clear()
        antesDaRecuperacao = _transcript.value
    }

    /**
     * A recuperação acabou?
     *
     * Três caminhos, porque um só não basta:
     *
     * 1. o `replay.done` do desk (`seq == 0`), quando ele chega;
     * 2. um silêncio depois da rajada — o replay é um jorro contínuo, o fluxo
     *    normal tem pausas;
     * 3. um teto de tempo, para o caso de nenhum dos dois acontecer.
     *
     * @param quadro quadro que chegou agora.
     * @param agora instante da chegada.
     */
    private fun recuperacaoAcabou(quadro: IncomingFrame, agora: Long): Boolean {
        if (quadro is IncomingFrame.ReplayDone && quadro.seq == 0L) return true
        if (agora - recuperacaoDesde > RECUPERACAO_MAXIMA_MS) return true
        return agora - ultimoTurnoEm > RECUPERACAO_OCIOSA_MS
    }

    /**
     * Fecha a recuperação.
     *
     * Não há nada para "aplicar" aqui: a cauda já está na tela desde o primeiro
     * quadro (ver `aplicar`). O que se faz é parar de podar.
     */
    private fun encerrarRecuperacao() {
        val tinhaCauda = cauda.isNotEmpty()
        recuperando = false
        cauda.clear()
        antesDaRecuperacao = emptyMap()
        if (!tinhaCauda) return
        avisar(
            NoticeLevel.Info,
            "Chegou atrasado",
            "Só o fim da conversa entrou na tela (as últimas " + CaudaDoReplay.MENSAGENS +
                " mensagens). O que passou antes disso já era.",
        )
    }


    /**
     * Junta linhas novas no fim, sem repetir id.
     *
     * A cauda pode trazer de volta um quadro que já estava na tela (o replay
     * sempre começa antes do fim); id repetido é chave repetida na lista, e isso
     * derruba a tela inteira.
     *
     * @param atual transcrição da sessão.
     * @param novos linhas a acrescentar, em ordem.
     */
    private fun juntarSemRepetir(atual: List<TurnItem>, novos: List<TurnItem>): List<TurnItem> {
        val conhecidos = atual.mapTo(mutableSetOf()) { it.id }
        val acrescentar = novos.filter { conhecidos.add(it.id) }
        return if (acrescentar.isEmpty()) atual else atual + acrescentar
    }

    /** Pede ao PC a lista de workspaces do Harness. */
    fun pedirWorkspaces() {
        _recadoWorkspaces.value = "pedindo ao PC…"
        scope.launch {
            val envio = sessionClient.send(FrameType.WorkspaceList, kotlinx.serialization.json.JsonObject(emptyMap()))
            _recadoWorkspaces.value = when (envio) {
                is TransportResult.Ok ->
                    "comando entregue ao PC em " + envio.latencyMs + " ms · resposta: " +
                        envio.raw.orEmpty().take(80)

                is TransportResult.HttpError -> "o PC recusou: HTTP " + envio.code + " — " + envio.message.take(90)
                is TransportResult.NetworkError ->
                    "não saiu do aparelho: " + (envio.cause.message ?: envio.cause::class.simpleName.orEmpty())
            }
        }
    }

    /**
     * Abre uma sessao nova — num workspace conhecido ou num caminho novo.
     *
     * Sessao nao muda de pasta: e assim que se "muda de workspace". Quem confirma
     * que deu certo e o quadro `session.upsert` que o PC publica em seguida; aqui
     * so contamos o que houve com o envio.
     */
    fun criarSessao(workspaceId: String? = null, path: String? = null) {
        _criandoSessao.value = "abrindo sessão…"
        scope.launch {
            val envio = sessionClient.send(
                FrameType.SessionCreate,
                PhCodec.payloadOf(SessionCreatePayload(workspaceId = workspaceId, path = path)),
            )
            _criandoSessao.value = when (envio) {
                is TransportResult.Ok -> null
                is TransportResult.HttpError -> "o PC recusou: HTTP " + envio.code
                is TransportResult.NetworkError ->
                    "não saiu do aparelho: " + (envio.cause.message ?: "sem contato")
            }
        }
    }

    fun cancelTurn() {
        val sessao = activeSession() ?: return
        scope.launch {
            sessionClient.send(
                FrameType.SessionCancel,
                PhCodec.payloadOf(SessionCancelPayload(sessao.id)),
                sessao.id,
            )
        }
    }

    /**
     * Apaga a conversa da sessão ativa da TELA — o "limpar tudo" do diálogo de
     * parar.
     *
     * Só o que o usuário vê: transcrição, cauda de recuperação e a base
     * congelada dela (sem esta, a cauda juntaria as linhas velhas de volta na
     * hora seguinte). A marca de replay NÃO mexe, e é de propósito: é ela que
     * impede os mesmos quadros de reencherem a conversa na próxima reconexão —
     * limpo continua limpo. O que o PC guarda no anel não se apaga daqui (não
     * há apagador no protocolo); some da SUA tela, que é de onde ele nunca deve
     * ter saído sem você pedir.
     */
    fun limparConversa() {
        val sessao = _activeSessionId.value ?: return
        _transcript.update { atual -> atual - sessao }
        cauda.remove(sessao)
        antesDaRecuperacao = antesDaRecuperacao - sessao
    }

    /**
     * Decide uma aprovacao.
     *
     * A fila NAO e esvaziada aqui: quem esvazia e o quadro approval.resolved que o
     * PC devolve. Tirar da tela antes de o PC confirmar esconderia uma falha de
     * rede - o usuario acharia que decidiu.
     */
    fun decide(requestId: String, allowed: Boolean, remember: Boolean = false) {
        _decisoes.update { it + (requestId to DecisaoDeAprovacao(EstadoDaDecisao.Enviando)) }
        scope.launch {
            val envio = sessionClient.send(
                FrameType.ApprovalDecide,
                PhCodec.payloadOf(
                    ApprovalDecidePayload(
                        requestId = requestId,
                        outcome = if (allowed) "allowed-once" else "rejected",
                        remember = remember,
                    ),
                ),
            )
            // A resposta do desk diz se o comando SAIU; quem decide de verdade e o
            // harness, e a confirmacao chega depois pelo quadro approval.resolved.
            // Por isso "entregue" nao e "aprovado" — a tela fala exatamente isso.
            val resultado = when (envio) {
                is TransportResult.Ok -> DecisaoDeAprovacao(
                    EstadoDaDecisao.Entregue,
                    "comando entregue ao PC em " + envio.latencyMs + " ms",
                )

                is TransportResult.HttpError -> DecisaoDeAprovacao(
                    EstadoDaDecisao.Recusada,
                    "o PC recusou (HTTP " + envio.code + "): " + envio.message.take(160),
                )

                is TransportResult.NetworkError -> DecisaoDeAprovacao(
                    EstadoDaDecisao.Recusada,
                    "não saiu do aparelho: " +
                        (envio.cause.message ?: envio.cause::class.simpleName.orEmpty()),
                )
            }
            _decisoes.update { it + (requestId to resultado) }
            if (resultado.estado == EstadoDaDecisao.Recusada) {
                avisar(NoticeLevel.Error, "A decisão não chegou ao PC", resultado.detalhe)
            }
        }
    }

    /**
     * Responde uma pergunta do agente.
     *
     * A fila NAO e esvaziada aqui: quem esvazia e o quadro question.resolved, que
     * pode vir do proprio PC quando a resposta foi dada la. Tirar da tela antes
     * esconderia uma resposta que nao chegou.
     *
     * @param requestId pergunta alvo.
     * @param questionId qual das perguntas do cartao.
     * @param selecionadas opcoes marcadas.
     * @param textoLivre resposta escrita a mao, quando houver.
     */
    fun answerQuestion(
        requestId: String,
        questionId: String,
        selected: List<String>,
        textoLivre: String? = null,
    ) {
        // Trava o cartao NA HORA do toque. Quem confirma e o quadro
        // question.resolved, mas depender so dele deixava o cartao respondivel
        // durante a ida e a volta — e a mesma pergunta era respondida varias vezes.
        _perguntas.update { atual ->
            atual.map { item ->
                if (item.pedido.requestId != requestId) item
                else item.copy(
                    resposta = RespostaDaPergunta(
                        selecionadas = selected,
                        textoLivre = textoLivre,
                        por = "celular",
                    ),
                )
            }
        }
        scope.launch {
            val envio = sessionClient.send(
                FrameType.QuestionAnswer,
                PhCodec.payloadOf(
                    QuestionAnswerPayload(
                        requestId = requestId,
                        answers = listOf(QuestionAnswerItem(questionId, selected, textoLivre)),
                    ),
                ),
            )
            when (envio) {
                is TransportResult.Ok -> Unit // resposta entregue; o resolved fecha a fila.
                is TransportResult.HttpError -> if (envio.code == 404) {
                    // O PC RECUSOU dizendo que não conhece o pedido (unknown-request):
                    // a pergunta já morreu lá — resolvida num caminho cujo resolved
                    // se perdeu, ou reenvio do desk de um pedido antigo (o zumbi do
                    // Firebase). Sem a tumba, o desk a recriaria na próxima reconexão
                    // do celular; sem a retirada, o cartão ficaria na tela para sempre.
                    descartarPergunta(
                        requestId,
                        "o PC não conhece mais esta pergunta — o cartão foi retirado e não volta.",
                    )
                } else {
                    avisar(NoticeLevel.Error, "A resposta não chegou ao PC", "HTTP " + envio.code + ": a pergunta continua aberta.")
                }
                is TransportResult.NetworkError ->
                    avisar(NoticeLevel.Error, "A resposta não chegou ao PC", "A pergunta continua aberta.")
            }
        }
    }

    /**
     * O PC não conhece a pergunta: a retirada é o único desfecho honesto.
     *
     * Tira da fila, grava a tumba (é ela que segura o reenvio `seq: 0` do desk
     * para sempre) e some com o aviso da bandeja — os três lugares onde a mesma
     * mentira viveria.
     */
    private fun descartarPergunta(requestId: String, motivo: String) {
        _perguntas.value = FilaDePerguntas.resolver(_perguntas.value, requestId)
        if (requestId !in perguntasResolvidas) {
            perguntasResolvidas = FilaDePerguntas.marcarResolvida(perguntasResolvidas, requestId)
            val ids = perguntasResolvidas
            scope.launch { runCatching { settingsStorage.updatePerguntasResolvidas(ids) } }
        }
        notificar(Aviso.Cancelar(Aviso.idPergunta(requestId)))
        avisar(NoticeLevel.Warn, "Pergunta retirada", motivo)
    }

    fun markPaired(deviceId: String, pcName: String, directBaseUrl: String, token: String, p2pTicket: String = "") {
        // O token e segredo: vai para o Tink + Keystore, nunca para o DataStore.
        secureStore.writeToken(token)
        scope.launch { settingsStorage.updatePairing(deviceId, pcName, directBaseUrl, p2pTicket) }
        avisar(NoticeLevel.Success, "Pareado", pcName + " (" + deviceId + ")")
    }

    fun setTransportMode(mode: TransportMode) {
        scope.launch { settingsStorage.updateTransportMode(mode) }
    }

    fun unpair() {
        scope.launch {
            settingsStorage.clearPairing()
            secureStore.clear()
        }
        _sessions.value = emptyList()
        _transcript.value = emptyMap()
        _turnStatus.value = emptyMap()
        _approvals.value = emptyList()
        // As perguntas também são do vínculo com o PC: sem pareamento não há
        // de quem esperar resposta, e a fila antiga ficava órfã na tela.
        _perguntas.value = emptyList()
        _decisoes.value = emptyMap()
        _activeSessionId.value = null
        _escolhaManual.value = false
        _promptStatus.value = watchdog.clear()
        _atualizacao.value = EstadoDaAtualizacao()
        revalidando = false
        reenviados.set(0)
        antesDaAtualizacao = emptyMap()
        recuperando = false
        cauda.clear()
        // Sem pareamento não há PC para reenviar nada: a marca d'água também cai,
        // senão a conversa do próximo PC nasceria com quadros "já vistos".
        marca.limpar()
        vigia?.cancel()
        vigia = null
    }

    /**
     * Reavalia o prazo do prompt em voo.
     *
     * O laco acorda de tempo em tempo em vez de dormir o prazo inteiro: assim o
     * aviso aparece no instante certo e o contador da tela acompanha a espera.
     */
    private fun agendarVigia() {
        vigia?.cancel()
        vigia = scope.launch {
            while (true) {
                delay(PASSO_VIGIA_MS)
                val estado = watchdog.tick(System.currentTimeMillis())
                _promptStatus.value = estado
                if (estado.ack != PromptAck.Waiting) break
            }
            val estado = _promptStatus.value
            if (estado.ack == PromptAck.Silent || estado.ack == PromptAck.Stalled) avisarTrava(estado)
        }
    }

    /** Um quadro do PC chegou: ele diz se o prompt em voo ja foi respondido. */
    private fun observarPrompt(sessionId: String?, kind: String?) {
        if (_promptStatus.value.ack == PromptAck.None) return
        val depois = watchdog.frame(sessionId, kind, System.currentTimeMillis())
        _promptStatus.value = depois
        if (depois.ack == PromptAck.None) {
            vigia?.cancel()
            vigia = null
        }
    }

    /**
     * Traduz a trava num aviso.
     *
     * Os dois casos tem causas diferentes e o texto diz qual e qual: nenhum quadro
     * nenhum costuma ser transporte, e quadro de abertura sem producao e o
     * sintoma da sessao travada atras de um turno aberto.
     */
    private fun avisarTrava(estado: PromptStatus) {
        val segundos = (estado.waitedMs / 1000).toLong()
        val titulo = if (estado.ack == PromptAck.Stalled) {
            "A sessão começou e parou sem responder"
        } else {
            "O PC aceitou o prompt, mas a sessão não deu sinal"
        }
        avisar(
            NoticeLevel.Warn,
            titulo,
            segundos.toString() + " s de silêncio. A sessão pode estar travada atrás de um turno " +
                "aberto — toque em parar ou escolha outra sessão.",
        )
    }

    companion object {
        /** De quanto em quanto tempo o vigia reavalia o prazo. */
        const val PASSO_VIGIA_MS = 5_000L

        /**
         * A partir de quantos quadros de distância o atraso vira "cheguei tarde".
         *
         * Abaixo disso o replay é uma queda de rede comum e entra inteiro: perder
         * as últimas mensagens por causa de um soluço de dois segundos seria pior
         * que o problema que o corte resolve.
         */
        const val SALTO_MAXIMO_QUADROS = 200L

        /**
         * Silêncio, no meio de uma recuperação, que significa "a rajada acabou".
         *
         * O replay chega como um jorro contínuo; o fluxo normal tem pausas — e o
         * desk manda `desk.state` a cada 2 s, então a pausa é fácil de medir.
         */
        const val RECUPERACAO_OCIOSA_MS = 6_000L

        /**
         * Teto absoluto de uma recuperação.
         *
         * Rede móvel ruim pode picar a rajada em pedaços com mais de 6 s entre
         * eles; sem este teto o app ficaria podando para sempre.
         */
        const val RECUPERACAO_MAXIMA_MS = 30_000L

        /**
         * Quanto esperar pelo fim do reenvio antes de dizer que não deu.
         *
         * Folgado de propósito: o PC reenvia até alguns milhares de quadros, e
         * numa rede móvel isso não sai em segundos.
         */
        const val PRAZO_ATUALIZACAO_MS = 25_000L

        /** Quanto um estado de link precisa durar para chegar à tela e à bandeja. */
        const val LINK_DEBOUNCE_MS = 4_000L
    }

    private fun avisar(nivel: String, titulo: String, corpo: String) {
        _notices.update { atual -> (listOf(Notice(nivel, titulo, corpo)) + atual).take(20) }
    }

    /**
     * Manda (ou retira) uma notificação do Android — ver [Aviso].
     *
     * Publicar fica MUDO durante recuperação e durante o reenvio manual (↻):
     * os dois reentregam o anel do PC do começo, e passado não buzina no bolso
     * de ninguém — um request cujo resolved caiu fora do anel voltaria a avisar
     * como se fosse novo (foi o zumbi "Qual canal de push" de campo). Cancelar
     * passa sempre: retirar aviso velho nunca é ruído.
     *
     * tryEmit, nunca suspend: se o serviço não estiver rodando (app despareado)
     * o aviso é descartado, e é o certo — não há ninguém para quem avisar.
     */
    private fun notificar(aviso: Aviso) {
        if (aviso is Aviso.Publicar && (recuperando || revalidando)) return
        _avisosSistema.tryEmit(aviso)
    }

    private fun appendToTranscript(sessionId: String, item: TurnItem) {
        _transcript.update { atual -> atual + (sessionId to (atual[sessionId].orEmpty() + item)) }
    }
}
