package com.pockethound.app.core.session

/**
 * O que houve com o último pedido de "atualizar" da conversa.
 *
 * O botão existe porque o celular PODE perder o fim da conversa, e perde em
 * silêncio: a rede troca de torre, o aplicativo é suspenso em segundo plano, o
 * PC descarta um delta sob contrapressão do rádio, ou o pedaço do buffer que o
 * celular queria já tinha sido jogado fora quando ele voltou. Em todos esses
 * casos a tela fica **velha e muda** — e velha e muda é indistinguível de "o
 * agente parou de escrever".
 *
 * Um toque pede ao PC o reenvio do que ele ainda guarda, e esta faixa conta o
 * que voltou. Sem ela, "toquei e não mudou nada" teria duas causas opostas —
 * não havia nada para vir, ou o PC não respondeu — e nenhuma pista de qual foi.
 *
 * @param pedida já houve um pedido (a faixa só aparece depois do primeiro toque).
 * @param emCurso o pedido está no ar agora.
 * @param novidades linhas que entraram na conversa por causa do reenvio.
 * @param reenviados quadros que o PC reenviou ao todo (novos ou não).
 * @param quandoMs quando o pedido terminou (ms desde a época).
 * @param motivo o que impediu de confirmar o fim do reenvio, quando nada confirmou.
 */
data class EstadoDaAtualizacao(
    val pedida: Boolean = false,
    val emCurso: Boolean = false,
    val novidades: Int = 0,
    val reenviados: Int = 0,
    val quandoMs: Long = 0L,
    val motivo: String? = null,
) {
    /** A atualização terminou sem confirmação do PC? */
    val semConfirmacao: Boolean get() = !emCurso && motivo != null
}

/**
 * Compara o tamanho de cada transcrição antes e depois do reenvio.
 *
 * Só o crescimento conta. Uma sessão pode desaparecer no meio da atualização
 * (o quadro `session.gone`) e uma conta que aceitasse número negativo diria que
 * a atualização *tirou* mensagens da conversa.
 *
 * Pura de propósito: é a regra que a faixa da tela mostra, e ela pode ser
 * testada sem tela, sem rede e sem Android.
 */
object ContagemDaAtualizacao {

    /**
     * @param antes linhas por sessão quando o reenvio foi pedido.
     * @param depois linhas por sessão quando o reenvio terminou.
     * @return quantas linhas entraram na conversa.
     */
    fun novidades(antes: Map<String, Int>, depois: Map<String, Int>): Int =
        depois.entries.sumOf { (sessao, tamanho) -> (tamanho - (antes[sessao] ?: 0)).coerceAtLeast(0) }
}
