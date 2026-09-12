package com.pockethound.app.core.session

import com.pockethound.app.core.model.Session
import com.pockethound.app.core.model.SessionStatus

/**
 * Ordem das sessoes na tela e escolha da sessao padrao.
 *
 * ## Por que isto existe
 *
 * A ordem em que os quadros `session.upsert` chegam do PC nao tem relacao com o
 * que interessa ao humano: ela reflete a ordem em que o harness mexeu nas
 * sessoes, e a lista do celular fica embaralhada a cada evento.
 *
 * Pior: sem escolha explicita, o app mandava o prompt para a PRIMEIRA da lista —
 * que podia ser uma sessao de subagente, uma sessao vazia ou uma conversa de
 * ontem. O prompt ia para o lugar errado sem avisar ninguem.
 *
 * A regra aqui: **quem esta trabalhando agora vem primeiro; dentro do grupo, a
 * que deu sinal de vida mais recente**. E a sessao padrao nunca e um subagente.
 */
object SessionOrder {

    /**
     * Ordena as sessoes para a tela.
     *
     * A ordenacao e estavel: sessoes sem carimbo de tempo (as que existem so no
     * disco) mantem a ordem relativa em que chegaram, em vez de saltar.
     *
     * @param sessions sessoes conhecidas.
     * @return lista nova, viva primeiro e mais recente antes.
     */
    fun order(sessions: List<Session>): List<Session> = sessions.sortedWith(
        compareByDescending<Session> { it.status == SessionStatus.Running }
            .thenByDescending { it.lastSeen ?: it.createdAt ?: 0L },
    )

    /**
     * Sessao que o app assume enquanto voce nao escolhe nenhuma.
     *
     * Subagente fica de fora: eles existem aos montes, nascem e morrem sozinhos, e
     * mandar um prompt para um deles nao e o que ninguem quer dizer com "manda
     * para o agente". Se so houver subagente, devolve o mais recente deles — e
     * melhor que uma tela sem destino.
     *
     * @param sessions sessoes conhecidas.
     * @return a sessao padrao, ou null quando nao ha nenhuma.
     */
    fun defaultActive(sessions: List<Session>): Session? {
        val ordenadas = order(sessions)
        return ordenadas.firstOrNull { !it.isSubagent } ?: ordenadas.firstOrNull()
    }

    /**
     * Sessao que a lista deve destacar: a escolhida, se ela ainda existir.
     *
     * @param sessions sessoes conhecidas.
     * @param activeId id escolhido pelo humano, quando houver.
     * @return a sessao ativa, ou null quando a lista esta vazia.
     */
    fun resolve(sessions: List<Session>, activeId: String?): Session? =
        sessions.firstOrNull { it.id == activeId } ?: defaultActive(sessions)
}
