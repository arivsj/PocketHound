package com.pockethound.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockethound.app.core.model.Session
import com.pockethound.app.core.model.TodoItem
import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.core.session.EstadoDaAtualizacao
import com.pockethound.app.core.session.PromptAck
import com.pockethound.app.core.session.ProgressoDoPlano
import com.pockethound.app.core.session.PromptDaResposta
import com.pockethound.app.core.session.PromptStatus
import com.pockethound.app.core.session.SessionOrder
import com.pockethound.app.core.session.TurnStatus
import com.pockethound.app.ui.approvals.CartaoDeAprovacao
import com.pockethound.app.ui.common.PhBadge
import com.pockethound.app.ui.questions.CartaoDePergunta
import com.pockethound.app.ui.common.PhButton
import com.pockethound.app.ui.common.PhButtonVariant
import com.pockethound.app.ui.common.PhIconButton
import com.pockethound.app.ui.common.PhScreenScaffold
import com.pockethound.app.ui.common.PhState
import com.pockethound.app.ui.common.PhStateKind
import com.pockethound.app.ui.common.PhTone
import com.pockethound.app.ui.nav.RootViewModel
import com.pockethound.app.ui.theme.PhAmber
import com.pockethound.app.ui.theme.PhBg
import com.pockethound.app.ui.theme.PhBorder
import com.pockethound.app.ui.theme.PhDanger
import com.pockethound.app.ui.theme.PhInfo
import com.pockethound.app.ui.theme.PhOk
import com.pockethound.app.ui.theme.PhSurface
import com.pockethound.app.ui.theme.PhSurface2
import com.pockethound.app.ui.theme.PhText
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhTextMute
import com.pockethound.app.ui.theme.PhViolet
import com.pockethound.app.ui.theme.PhVioletSoft
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatRoute(viewModel: RootViewModel = hiltViewModel()) {
    ChatScreen(viewModel = viewModel)
}

/**
 * Aba Chat: transcricao ao vivo, envio de prompt, cancelar turno, trocar de sessao.
 *
 * ## Como cada passo e escrito
 *
 * Igual ao Harness no navegador: **uma linha por passo**, com rotulo e assunto
 * ("Comando · Check node and ignore rules"), e o corpo atras do toque. O que NAO
 * se faz aqui e despejar o JSON dos argumentos: ele esconde justamente a frase
 * que o modelo escreveu para ser lida.
 */
@Composable
fun ChatScreen(viewModel: RootViewModel) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val promptStatus by viewModel.promptStatus.collectAsStateWithLifecycle()
    val escolhaManual by viewModel.escolhaManual.collectAsStateWithLifecycle()
    val turno by viewModel.activeTurnStatus.collectAsStateWithLifecycle()
    val approvals by viewModel.approvals.collectAsStateWithLifecycle()
    val decisoes by viewModel.decisoes.collectAsStateWithLifecycle()
    val perguntas by viewModel.perguntas.collectAsStateWithLifecycle()
    val atualizacao by viewModel.atualizacao.collectAsStateWithLifecycle()

    // Mesma regra do repositorio: a escolhida, senao a mais recente em atividade.
    val active: Session? = SessionOrder.resolve(sessions, activeId)
    val items = transcript[active?.id].orEmpty()

    // "Respondendo a: <prompt>" acima da resposta. A ligação sai da ordem da
    // conversa; quando a ordem mente (dois prompts na fila antes da primeira
    // resposta), a regra cala em vez de apontar o prompt errado.
    val prompts by remember(items) { mutableStateOf(PromptDaResposta.casar(items)) }

    // O aviso so vale para a sessao que esta na tela: o de outro destino nao e
    // daqui e apareceria como alarme falso.
    val aviso = promptStatus.takeIf { it.ack != PromptAck.None && it.sessionId == active?.id }

    var draft by remember { mutableStateOf("") }
    // Quais linhas estao abertas, por id de item. Vive na tela, nao no dado: o
    // que o PC mandou nao muda quando voce toca numa linha.
    val abertos = remember { mutableStateMapOf<String, Boolean>() }

    // "Nao perguntar de novo" por pedido — vive na tela, como o que esta aberto.
    val lembretes = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    /* ---------------------------------------------------------------- rolagem */

    // A conversa NAO desce sozinha por cima de quem esta lendo mais acima — a
    // mesma regra do Harness no navegador. Perder a linha que se estava lendo e
    // pior do que saber da novidade um segundo depois.
    //
    // Quem decide e o dedo: no instante em que ele arrasta a lista, paramos de
    // acompanhar; quando ele volta ao fim, por qualquer caminho, voltamos.
    val seguindo = remember(listState) { mutableStateOf(true) }

    /** Quantos itens ele ja tinha visto quando saiu do fim. */
    val jaVistos = remember(listState) { mutableIntStateOf(0) }

    // O laco abaixo vive fora da recomposicao: sem isto ele leria a lista do
    // instante em que foi lancado, e nao a de agora.
    val itensAgora by rememberUpdatedState(items)

    // As aprovacoes entram no FIM da mesma lista do chat, entao elas contam para a
    // rolagem: sem isto o cartao chegaria escondido abaixo da dobra.
    val aprovacoesAgora by rememberUpdatedState(approvals)
    val perguntasAgora by rememberUpdatedState(perguntas)

    /**
     * Encosta a ultima mensagem no fim da tela.
     *
     * Alinhar o TOPO do ultimo item nao serve quando ele e maior que a tela: uma
     * resposta ainda sendo escrita ficaria com o texto novo escondido abaixo da
     * dobra. A sobra e exatamente o que ficou para fora; desconta-la mostra o fim
     * de verdade, que e onde o texto novo aparece.
     */
    suspend fun descer(animado: Boolean) {
        // O ultimo item DA LISTA, nao o ultimo da transcricao: as aprovacoes
        // pendentes ficam depois dela.
        val indice = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        if (indice < 0 || listState.layoutInfo.totalItemsCount == 0) return
        val info = listState.layoutInfo
        val ultimo = info.visibleItemsInfo.lastOrNull { it.index == indice }
        val sobra = if (ultimo == null) 0 else ultimo.size - (info.viewportEndOffset - ultimo.offset)
        if (ultimo != null && sobra <= 0) return
        if (animado) listState.animateScrollToItem(indice, sobra) else listState.scrollToItem(indice, sobra)
    }

    LaunchedEffect(listState) {
        // Arrastar e o unico gesto que significa "eu escolho onde ficar". A
        // rolagem que nos fazemos nao passa por aqui, entao nao se confunde.
        listState.interactionSource.interactions.collect { interacao ->
            if (interacao is DragInteraction.Start) seguindo.value = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val ultimo = info.visibleItemsInfo.lastOrNull()
            // Fim visivel = o ULTIMO item aparece inteiro. Item cortado pela borda
            // conta como "nao estou no fim": e assim que o texto que cresce ao
            // vivo empurra a tela, e e assim que o dedo sobe para ler.
            val noFim = info.totalItemsCount == 0 ||
                (ultimo != null && ultimo.index >= info.totalItemsCount - 1 &&
                    ultimo.offset + ultimo.size <= info.viewportEndOffset)
            // As aprovacoes contam como conteudo novo: o cartao tem de aparecer,
            // nao chegar escondido abaixo da dobra.
            Triple(
                noFim,
                itensAgora.size + aprovacoesAgora.size + perguntasAgora.size,
                listState.isScrollInProgress,
            )
        }.collect { (noFim, tamanho, rolando) ->
            when {
                noFim -> {
                    seguindo.value = true
                    jaVistos.intValue = tamanho
                }

                // Estava acompanhando e chegou conteudo: desce sem animacao, para
                // nao brigar com a animacao do quadro anterior.
                seguindo.value && !rolando && tamanho > 0 -> descer(animado = false)
            }
        }
    }

    // Sessao nova na tela: comeca pelo fim, acompanhando.
    LaunchedEffect(active?.id) {
        seguindo.value = true
        jaVistos.intValue = items.size
        abertos.clear()
        if (items.isNotEmpty()) descer(animado = false)
    }

    /** Mensagens que chegaram desde que ele saiu do fim. */
    val novas = if (seguindo.value) 0 else (items.size - jaVistos.intValue).coerceAtLeast(0)

    /* ------------------------------------------------------- relogio do turno */

    // O tempo so corre enquanto ha turno aberto: um relogio sempre ligado gastaria
    // bateria para mostrar um numero parado.
    var agora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(turno.running) {
        while (turno.running) {
            agora = System.currentTimeMillis()
            delay(1_000)
        }
    }

    PhScreenScaffold(
        title = "Chat",
        subtitle = active?.title ?: "nenhuma sessão",
        topBarActions = {
            PhBadge(
                text = if (link.isOnline) "ao vivo" else "offline",
                tone = if (link.isOnline) PhTone.Ok else PhTone.Danger,
                glyph = true,
                modifier = Modifier.padding(end = 4.dp),
            )
            // "Atualizar" é SÓ o ícone de propósito: com o rótulo, três
            // controles nesta barra de 48 dp não deixariam nada para o título da
            // sessão. O nome fica na descrição, para quem lê a tela por leitor.
            PhButton(
                text = "",
                onClick = { viewModel.atualizar() },
                variant = PhButtonVariant.Ghost,
                icon = Icons.Filled.Refresh,
                description = "atualizar a conversa",
                loading = atualizacao.emCurso,
                enabled = !atualizacao.emCurso,
                modifier = Modifier.padding(end = 2.dp),
            )
            PhButton(
                text = "parar",
                onClick = { viewModel.cancelTurn() },
                variant = PhButtonVariant.Danger,
                enabled = turno.running,
                icon = Icons.Filled.Close,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            SessionPickerRow(
                sessions = sessions,
                activeId = active?.id,
                onSelect = viewModel::selectSession,
            )

            DestinoRow(active = active, escolhaManual = escolhaManual, total = sessions.size)

            if (items.isEmpty()) {
                PhState(
                    message = "Sem transcrição ainda. Escreva o primeiro pedido abaixo.",
                    kind = PhStateKind.Empty,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = items, key = { it.id }) { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            prompts[item.id]?.let { prompt ->
                                Text(
                                    text = "\u21B3 respondendo a: " + primeiraLinha(prompt),
                                    color = PhTextDim,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                                )
                            }
                            LinhaDaTranscricao(
                                item = item,
                                aberto = abertos[item.id] == true,
                                onToggle = { abertos[item.id] = abertos[item.id] != true },
                            )
                        }
                    }

                    // A APROVACAO MORA AQUI. Ela nasceu numa aba propria e mudou de
                    // lugar por um motivo pratico: decide-se melhor com a linha que
                    // pediu a permissao na frente, sem trocar de tela no meio do
                    // trabalho. A aba que ela ocupava virou a escolha de workspace.
                    items(items = approvals, key = { "aprovacao-" + it.requestId }) { pedido ->
                        CartaoDeAprovacao(
                            request = pedido,
                            nowMs = agora,
                            decisao = decisoes[pedido.requestId],
                            remember = lembretes[pedido.requestId] == true,
                            onRememberChange = { marcado -> lembretes[pedido.requestId] = marcado },
                            onAllow = {
                                viewModel.decide(pedido.requestId, true, lembretes[pedido.requestId] == true)
                            },
                            onReject = { viewModel.decide(pedido.requestId, false, false) },
                        )
                    }

                    // E a pergunta do agente, pelo mesmo motivo: ela chega nos dois
                    // lugares ao mesmo tempo e quem responder primeiro vale. Sem o
                    // cartão aqui, a pergunta virava um aviso sem onde responder.
                    items(items = perguntas, key = { "pergunta-" + it.pedido.requestId }) { item ->
                        CartaoDePergunta(
                            pedido = item.pedido,
                            resposta = item.resposta,
                            nowMs = agora,
                            onResponder = { questionId, selecionadas, textoLivre ->
                                viewModel.responderPergunta(
                                    requestId = item.pedido.requestId,
                                    questionId = questionId,
                                    selecionadas = selecionadas,
                                    textoLivre = textoLivre,
                                )
                            },
                        )
                    }
                }
            }

            if (novas > 0) {
                PilulaDeNovidades(novas) {
                    seguindo.value = true
                    scope.launch { descer(animado = true) }
                }
            }

            if (atualizacao.pedida) FaixaDaAtualizacao(atualizacao)

            // O plano do turno fica ACIMA da faixa de estado: e' o to-do que o
            // Harness mostra no navegador (a lista do `todo_write`), com o botao
            // fixo em cima e a lista so quando se pede para abrir.
            PainelDoPlano(todos = turno.todos)

            FaixaDeEstado(turno = turno, agora = agora, temHistorico = items.isNotEmpty())

            if (aviso != null) AvisoDePrompt(aviso)

            Composer(
                value = draft,
                onValueChange = { draft = it },
                onSend = { furarFila ->
                    viewModel.sendPrompt(draft, furarFila)
                    draft = ""
                    // Quem acabou de escrever quer ver a propria mensagem: volta a
                    // acompanhar, mesmo que estivesse lendo mais acima.
                    seguindo.value = true
                },
                turno = turno,
            )
        }
    }
}

@Composable
private fun SessionPickerRow(
    sessions: List<Session>,
    activeId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sessions.forEach { session ->
            val selected = session.id == activeId
            PhBadge(
                text = session.title.ifBlank { session.id },
                tone = when {
                    selected -> PhTone.Violet
                    session.status.wire == "waiting" -> PhTone.Warn
                    else -> PhTone.Neutral
                },
                glyph = selected,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(session.id) },
            )
        }
    }
}

/**
 * Diz, sem rodeios, para ONDE o proximo prompt vai.
 *
 * O chip destacado na faixa de cima ja mostra a escolha, mas chip destacado nao e
 * a mesma coisa que destino declarado — e o caso que originou isto foi justamente
 * um prompt que foi parar numa sessao que ninguem tinha escolhido.
 *
 * @param active sessao de destino.
 * @param escolhaManual se o destino foi escolhido no dedo.
 * @param total quantas sessoes existem (com uma so, nao ha o que escolher).
 */
@Composable
private fun DestinoRow(active: Session?, escolhaManual: Boolean, total: Int) {
    if (active == null) return
    // Com uma sessao so, "escolha" nao e uma decisao — nao vale gastar tinta.
    val automatico = !escolhaManual && total > 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhBadge(
            text = "→ " + active.title.ifBlank { active.id }.take(28),
            tone = if (automatico) PhTone.Warn else PhTone.Violet,
            glyph = true,
        )
        Text(
            text = if (automatico) "destino automático — toque num chip para fixar" else pasta(active.workspace),
            color = PhTextDim,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Avisa o que aconteceu com um prompt que o PC ja aceitou.
 *
 * Sem isto a tela fica muda entre "aceito" e "respondido", e mudo e
 * indistinguivel de "o modelo esta pensando" — foi assim que um prompt aceito e
 * nunca respondido pareceu ter sumido.
 *
 * @param estado retrato do prompt em voo.
 */
@Composable
private fun AvisoDePrompt(estado: PromptStatus) {
    val travado = estado.ack != PromptAck.Waiting
    val cor = if (travado) PhDanger else PhInfo
    val titulo = when (estado.ack) {
        PromptAck.Stalled -> "o PC começou e parou sem responder"
        PromptAck.Silent -> "o PC aceitou, mas não deu sinal de vida"
        else -> "enviado — esperando o PC"
    }
    val detalhe = when {
        estado.ack == PromptAck.Silent ->
            segundos(estado.waitedMs) + " sem nenhum quadro desta sessão. Pode estar travada ou o " +
                "turno pode estar aberto — toque em parar ou escolha outra sessão."

        estado.ack == PromptAck.Stalled ->
            segundos(estado.waitedMs) + " desde os primeiros quadros, sem nenhuma resposta. A sessão " +
                "pode estar travada atrás de um turno aberto — toque em parar ou escolha outra sessão."

        // "Furar fila" nao e fila: a mensagem entrou no turno em curso, na frente
        // do que esperava. Dizer "entrou na fila do proximo turno" aqui seria
        // mentira — e essa mentira faz o usuario mandar a mesma coisa duas vezes.
        estado.furarFila -> "você furou a fila: a mensagem entra no meio do turno em curso"

        estado.queued -> "a sessão já estava ocupada: o prompt entrou na fila do próximo turno"

        else -> "aguardando resposta há " + segundos(estado.waitedMs)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cor.copy(alpha = 0.10f))
            .border(1.dp, cor.copy(alpha = 0.40f), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = titulo.uppercase(),
                color = cor,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = detalhe,
                color = PhTextDim,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Linha do "atualizar": conta o que o reenvio trouxe.
 *
 * Ela existe porque "toquei e não mudou nada" tem duas causas opostas — não
 * havia nada para vir, ou o PC não respondeu — e sem esta linha as duas ficam
 * idênticas na tela. É a mesma ideia do aviso de prompt, um degrau abaixo.
 *
 * @param estado retrato do último pedido de atualização.
 */
@Composable
private fun FaixaDaAtualizacao(estado: EstadoDaAtualizacao) {
    val cor = when {
        estado.emCurso -> PhInfo
        estado.semConfirmacao -> PhAmber
        estado.novidades > 0 -> PhOk
        else -> PhTextDim
    }
    val texto = when {
        estado.emCurso -> "buscando no PC o que chegou…"

        estado.semConfirmacao -> "não deu para confirmar: " + estado.motivo

        estado.novidades > 0 ->
            "atualizado · " + estado.novidades + (if (estado.novidades == 1) " novidade" else " novidades")

        // Zero novidade não é falha: é a resposta certa para uma tela que já
        // estava completa, e é bom que ela seja dita em voz baixa.
        else -> "nada novo — o PC não tinha nada além do que já está aqui"
    }
    Text(
        text = texto,
        color = cor,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp),
    )
}

/**
 * O plano do turno — o mesmo to-do que o Harness desenha no navegador.
 *
 * Nasceu de um pedido explicito: o botao fica FIXO acima da faixa de estado
 * ("trabalhando ha x tempo · passo x") e a lista so aparece quando se toca nele.
 * Fechado, o botao cabe numa linha e ainda responde a pergunta que importa —
 * quantas tarefas ja foram e qual esta andando.
 *
 * Some quando o plano esta vazio, e isso e' o normal entre turnos: o Harness zera
 * a projecao a cada inicio de turno, entao o painel acompanha o plano DO TURNO,
 * nao um acumulado da sessao.
 *
 * @param todos o plano, como veio do PC.
 */
@Composable
private fun PainelDoPlano(todos: List<TodoItem>) {
    if (todos.isEmpty()) return
    var aberto by remember { mutableStateOf(false) }
    val progresso = remember(todos) { ProgressoDoPlano.de(todos) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { aberto = !aberto }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PhBadge(
                text = "to-do",
                tone = if (progresso.concluido) PhTone.Ok else PhTone.Violet,
                glyph = true,
            )
            Text(
                text = progresso.contagem,
                color = if (progresso.concluido) PhOk else PhText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = progresso.atual.orEmpty(),
                color = PhTextDim,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = if (aberto) "\u25BE" else "\u25B8",
                color = PhTextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (aberto) {
            todos.forEach { item -> LinhaDaTarefa(item) }
        }
    }
}

/**
 * Uma tarefa do plano: o glifo diz o estado, e o texto encurta quando termina.
 *
 * @param item a tarefa, como o agente escreveu.
 */
@Composable
private fun LinhaDaTarefa(item: TodoItem) {
    val feita = item.status == TAREFA_CONCLUIDA
    val andando = item.status == TAREFA_EM_ANDAMENTO
    val cor = when {
        feita -> PhOk
        andando -> PhViolet
        else -> PhTextDim
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = when {
                feita -> "\u2713"
                andando -> "\u25B8"
                else -> "\u25CB"
            },
            color = cor,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = item.content,
            color = if (feita) PhTextDim else PhText,
            style = MaterialTheme.typography.labelSmall,
            textDecoration = if (feita) TextDecoration.LineThrough else null,
        )
    }
}

/** Os dois status que ganham glifo proprio; o resto e' pendente. */
private const val TAREFA_CONCLUIDA = "completed"
private const val TAREFA_EM_ANDAMENTO = "in_progress"

/**
 * Faixa de estado do turno, logo acima do composer.
 *
 * Responde a pergunta que o usuario faz o tempo todo: "esta acontecendo alguma
 * coisa?". Trabalhando, mostra o tempo e o passo; parado, mostra o resumo do que
 * ja rodou. Sem numero nenhum, ela nao aparece.
 *
 * @param turno estado do turno da sessao na tela.
 * @param agora relogio da tela, que corre so enquanto ha turno aberto.
 * @param temHistorico se ja existe conversa nesta sessao.
 */
@Composable
private fun FaixaDeEstado(turno: TurnStatus, agora: Long, temHistorico: Boolean) {
    val texto = when {
        turno.running -> {
            val base = "trabalhando há " + cronometro(turno.decorrido(agora)) +
                " · passo " + turno.steps
            if (turno.queued > 0) base + " · " + turno.queued + " na fila" else base
        }

        !temHistorico -> return

        turno.turns > 0 -> {
            val base = turno.turns.toString() + (if (turno.turns == 1) " turno" else " turnos")
            val tokens = turno.tokensIn + turno.tokensOut
            if (tokens > 0) base + " · " + compacto(tokens) + " tokens" else base
        }

        else -> return
    }
    val cor = if (turno.running) PhViolet else PhTextDim
    Text(
        text = texto,
        color = cor,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp),
    )
}

/**
 * Pílula de "chegou coisa nova", so com o usuario fora do fim.
 *
 * O Harness do navegador nao tem isso, mas la existem barra de rolagem e a lista
 * de sessoes piscando. No celular, sem esta pilula, a unica pista de que chegou
 * resposta seria o dedo do usuario. Um toque volta a acompanhar.
 *
 * @param novas quantas mensagens chegaram desde que ele saiu do fim.
 * @param onClick volta para o fim e reata o acompanhamento.
 */
@Composable
private fun PilulaDeNovidades(novas: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        PhButton(
            text = novas.toString() + (if (novas == 1) " nova" else " novas") + " · ir para o fim",
            onClick = onClick,
            variant = PhButtonVariant.Ghost,
            icon = Icons.Filled.ArrowDownward,
        )
    }
}

/** "12 s" — o numero redondo basta para o humano perceber que travou. */
private fun segundos(ms: Long): String = (ms / 1000L).toString() + " s"

/** "0:42" / "12:05" — tempo de turno como se le no relogio. */
private fun cronometro(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val minutos = total / 60L
    val segundos = total % 60L
    return minutos.toString() + ":" + segundos.toString().padStart(2, '0')
}

/** "12,4k" — tokens sao muitos para caber inteiros numa linha de celular. */
private fun compacto(valor: Long): String {
    if (valor < 1_000L) return valor.toString()
    val milhar = valor / 100L
    return (milhar / 10L).toString() + "," + (milhar % 10L).toString() + "k"
}

/** Ultimo pedaco do caminho: o diretorio inteiro nao cabe nem ajuda. */
private fun pasta(workspace: String): String =
    workspace.trimEnd('/').substringAfterLast('/').ifBlank { workspace }

/**
 * Desenha um item da transcricao.
 *
 * Mensagem e balão; passo de ferramenta e raciocinio sao LINHA, no formato do
 * Harness: rotulo, assunto e, no toque, o corpo. O corpo nunca fica aberto por
 * padrao — uma sessao longa tem centenas de passos, e quem quer o detalhe pede.
 *
 * @param item item da transcricao.
 * @param aberto se o detalhe esta aberto.
 * @param onToggle alterna o detalhe.
 */
@Composable
private fun LinhaDaTranscricao(
    item: TurnItem,
    aberto: Boolean,
    onToggle: () -> Unit,
) {
    when (item.kind) {
        TurnItemKind.UserMessage -> Balao(item = item, isUser = true)
        TurnItemKind.AssistantMessage -> Balao(item = item, isUser = false)
        TurnItemKind.Notice -> Balao(item = item, isUser = false)
        else -> LinhaDePasso(item = item, aberto = aberto, onToggle = onToggle)
    }
}

/** Balão de conversa (componente só do celular, DESIGN.md §6.3). */
@Composable
private fun Balao(item: TurnItem, isUser: Boolean) {
    val accent = when (item.kind) {
        TurnItemKind.UserMessage -> PhViolet
        TurnItemKind.Notice -> PhTextDim
        else -> PhVioletSoft
    }
    val label = when (item.kind) {
        TurnItemKind.UserMessage -> "você"
        TurnItemKind.Notice -> "aviso"
        else -> "agente"
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isUser) PhViolet.copy(alpha = 0.10f) else PhSurface2)
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = label.uppercase(),
                    color = accent,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = item.text.ifBlank { "(vazio)" },
                    color = PhText,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Linha de um passo: rotulo, assunto e o corpo sob o toque.
 *
 * @param item item da transcricao.
 * @param aberto se o corpo esta aberto.
 * @param onToggle alterna o corpo.
 */
@Composable
private fun LinhaDePasso(item: TurnItem, aberto: Boolean, onToggle: () -> Unit) {
    val raciocinio = item.kind == TurnItemKind.Reasoning
    val resultado = item.kind == TurnItemKind.ToolResult || item.kind == TurnItemKind.Error
    val cor = when {
        item.kind == TurnItemKind.Error || item.ok == false -> PhDanger
        raciocinio -> PhInfo
        resultado -> PhOk
        item.kind == TurnItemKind.ToolCall -> PhAmber
        else -> PhTextDim
    }
    val rotulo = when {
        raciocinio -> "Pensou"
        !item.label.isNullOrBlank() -> item.label
        !item.toolName.isNullOrBlank() -> item.toolName
        else -> "Passo"
    }
    // No raciocinio o assunto e a primeira frase dele; nas ferramentas e o que o
    // proprio modelo escreveu ("description") ou o alvo da acao.
    val assunto = if (raciocinio) primeiraLinha(item.text) else item.subject ?: primeiraLinha(item.text)
    val corpo = if (raciocinio) item.text else item.detail
    // O desfecho aparece mesmo fechado: e o que diz se deu certo. Aberto, o corpo
    // completo toma o lugar dele.
    val desfecho = if (aberto) null else item.text.takeIf { resultado }
    val temCorpo = !corpo.isNullOrBlank()
    val marca = if (resultado) (if (item.ok == false) "✗ " else "✓ ") else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PhSurface2.copy(alpha = 0.5f))
            .border(1.dp, cor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .clickable(enabled = temCorpo, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = (marca + rotulo).uppercase(),
                color = cor,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = assunto.ifBlank { "(sem descrição)" },
                color = if (raciocinio) PhTextDim else PhText,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (temCorpo) {
                Text(
                    text = if (aberto) "▾" else "▸",
                    color = PhTextDim,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (desfecho != null && desfecho.isNotBlank()) {
            Text(
                text = desfecho,
                color = PhTextDim,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (aberto && temCorpo) {
            Text(
                text = corpo.orEmpty(),
                color = PhTextDim,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Primeira linha com conteudo — o que cabe na linha fechada. */
private fun primeiraLinha(texto: String): String =
    texto.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(90)

/**
 * A faixa acima do campo de prompt: fila de um lado, modelo do outro.
 *
 * A fila merece um botao porque ela responde a duvida que aparece o tempo todo —
 * "o meu prompt entrou?" — e um numero solto no meio de outra linha nao responde.
 * Tocar explica o que ela e; tocar de novo esconde.
 *
 * O modelo fica a direita: e o que se confere antes de escrever.
 *
 * @param turno estado da sessao — fila e modelo saem daqui.
 */
@Composable
private fun FaixaDaFilaEModelo(turno: TurnStatus) {
    var explicando by remember { mutableStateOf(false) }
    if (turno.queued <= 0 && turno.modelo.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (turno.queued > 0) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { explicando = !explicando }
                        .background(PhAmber.copy(alpha = 0.14f))
                        .border(1.dp, PhAmber.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "\uD83D\uDCAC",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = turno.queued.toString(),
                        color = PhAmber,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f))
            turno.modelo?.takeIf { it.isNotBlank() }?.let { modelo ->
                PhBadge(text = modelo, tone = PhTone.Neutral)
            }
        }
        if (explicando && turno.queued > 0) {
            Text(
                text = "Prompt na fila ja foi aceito pelo PC: entra quando o turno atual terminar. " +
                    "Nada se perdeu — nao precisa mandar de novo.",
                color = PhTextDim,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
    }
}
/**
 * O composer: campo, botoes e o retrato da sessao.
 *
 * O **enviar fica AO LADO do campo**, alinhado ao rodape do balao, redondo e so
 * com a seta: a mao ja vai para a direita, o rotulo nao cabe e o espaco e do
 * texto. O "furar fila" vive DENTRO do balao, na linha de baixo do que se
 * escreve: ele decide como ESTA mensagem entra, entao pertence ao campo, nao a
 * barra de botoes. E so aparece quando ha texto — sem texto nao ha o que furar.
 *
 * @param value rascunho.
 * @param onValueChange escrita do rascunho.
 * @param onSend envio, com a marca de furar fila daquele envio.
 * @param turno estado da sessao — de onde sai o gasto e o contexto.
 */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: (furarFila: Boolean) -> Unit,
    turno: TurnStatus,
) {
    // A marca vale para UM envio: depois de mandar, o campo esvazia e a linha
    // some com ela. Deixar ligada escondida faria a proxima mensagem furar fila
    // sem ninguem ter pedido.
    var furarFila by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PhSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Fila e modelo, acima do campo: o que voce confere antes de escrever.
        FaixaDaFilaEModelo(turno)

        // O enviar fica AO LADO do campo, alinhado ao rodape dele, e nao embaixo:
        // assim a linha de baixo do balao — a do "furar fila" — nao e empurrada
        // para longe do texto, e o dedo acha o botao na altura do polegar.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BalaoDoPrompt(
                value = value,
                onValueChange = onValueChange,
                furarFila = furarFila,
                onFurarFila = { furarFila = it },
                modifier = Modifier.weight(1f),
            )
            // Redondo e so com o icone: o rotulo "enviar" ja foi dito mil vezes
            // pela seta, e o espaco e do texto.
            PhIconButton(
                icon = Icons.Filled.Send,
                description = "enviar",
                onClick = {
                    onSend(furarFila)
                    furarFila = false
                },
                enabled = value.isNotBlank(),
            )
        }

        // O retrato embaixo do campo, como voce pediu: quanto a sessao custou e
        // quanto do contexto ja foi. Vem do PC, que le as projecoes do Harness.
        RetratoDaSessao(turno)
    }
}

/**
 * O balao do prompt: a borda, o campo e — com texto — a linha do "furar fila".
 *
 * O balao e desenhado AQUI, e nao pelo [PhTextField] de sempre, por um motivo so:
 * o "furar fila" mora DENTRO dele, na linha de baixo do que se escreve. O campo
 * interno fica sem borda propria para nao nascer um segundo risco dentro do balao;
 * a cor da borda — violeta no foco — e a mesma do campo padrao.
 *
 * @param value rascunho.
 * @param onValueChange escrita do rascunho.
 * @param furarFila se a mensagem vai entrar no turno em curso.
 * @param onFurarFila alterna a marca.
 * @param modifier modificador de layout — o envio manda `weight(1f)` para o balão
 *   dividir a linha com o botão redondo.
 */
@Composable
private fun BalaoDoPrompt(
    value: String,
    onValueChange: (String) -> Unit,
    furarFila: Boolean,
    onFurarFila: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interacao = remember { MutableInteractionSource() }
    val focado by interacao.collectIsFocusedAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(PhBg)
            .border(1.dp, if (focado) PhViolet else PhBorder, MaterialTheme.shapes.small),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "O que o Harness deve fazer?",
                    color = PhTextMute,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            singleLine = false,
            minLines = 1,
            maxLines = 5,
            shape = MaterialTheme.shapes.small,
            interactionSource = interacao,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = PhText,
                fontWeight = FontWeight.Normal,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = PhText,
                unfocusedTextColor = PhText,
                cursorColor = PhViolet,
                // A borda e a do balao: aqui dentro, so o texto.
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedPlaceholderColor = PhTextMute,
                unfocusedPlaceholderColor = PhTextMute,
            ),
        )
        // So com texto: a linha existe para decidir COMO esta mensagem entra.
        if (value.isNotBlank()) {
            LinhaDeFurarFila(ligado = furarFila, onAlternar = onFurarFila)
        }
    }
}

/**
 * A marca "furar fila", uma linha abaixo do que se escreve.
 *
 * Marcada, a mensagem nao espera a vez: o PC injeta no turno que ja esta rodando
 * (`next-step`), no proximo passo. O rotulo diz o que muda porque "furar fila"
 * sozinho nao conta o essencial: ela passa na frente do que esperava, e nada do
 * que o agente ja comecou e perdido.
 *
 * @param ligado se a marca esta posta.
 * @param onAlternar alterna a marca.
 */
@Composable
private fun LinhaDeFurarFila(ligado: Boolean, onAlternar: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // `toggleable` com papel de caixa de marcar: quem usa leitor de tela
            // ouve "marcado"/"desmarcado", que e o que a linha e.
            .toggleable(value = ligado, role = Role.Checkbox, onValueChange = onAlternar)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhBadge(
            text = "furar fila",
            tone = if (ligado) PhTone.Violet else PhTone.Neutral,
            glyph = true,
        )
        Text(
            text = if (ligado) {
                "entra no meio do turno, na frente da fila"
            } else {
                "espera a vez na fila do proximo turno"
            },
            color = if (ligado) PhText else PhTextDim,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Gasto da sessao e ocupacao do contexto, numa linha discreta.
 *
 * Some quando nao ha nada para dizer — sessao nova, perfil sem os plugins que
 * calculam, ou PC que ainda nao mandou o retrato. Silencio e melhor que "US$ 0"
 * piscando no rodape de toda conversa.
 *
 * @param turno estado da sessao na tela.
 */
@Composable
private fun RetratoDaSessao(turno: TurnStatus) {
    if (!turno.temRetrato) return
    val partes = buildList {
        if (turno.custoUsd > 0.0) add(emDolar(turno.custoUsd))
        turno.contextoPct?.let { add("contexto " + it.roundToInt() + "%") }
        val tokens = turno.entrada + turno.saida
        if (tokens > 0L) add(compacto(tokens) + " tokens")
    }
    if (partes.isEmpty()) return
    Text(
        text = partes.joinToString("  ·  "),
        color = PhTextDim,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "US$ 0,0142" — casas suficientes para o valor nao virar zero na tela. */
private fun emDolar(valor: Double): String {
    if (!valor.isFinite() || valor <= 0.0) return "US$ 0"
    val casas = if (valor < 0.01) 4 else if (valor < 1.0) 3 else 2
    return "US$ " + String.format(java.util.Locale("pt", "BR"), "%." + casas + "f", valor)
}
