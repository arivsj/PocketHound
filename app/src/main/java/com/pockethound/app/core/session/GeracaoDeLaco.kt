package com.pockethound.app.core.session

import java.util.concurrent.atomic.AtomicInteger

/**
 * Decide quem pode escrever o estado do link: so o laco da geracao vigente.
 *
 * Reconectar cancela o laco velho e nasce outro. O cancelamento e assincrono —
 * o laco velho so morre no proximo ponto de suspensao — e enquanto isso ele
 * ainda podia gravar "offline" POR CIMA do "conectando" do laco novo, e o
 * badge ficava mentindo (campo, 23/09). A geracao resolve sem esperar ninguém
 * morrer: quem nasce anota o numero, quem e velho nao reconhece o proprio
 * numero no teste e sai em silencio.
 */
class GeracaoDeLaco {

    private val atual = AtomicInteger(0)

    /** Abre uma nova geracao e devolve o numero dela (o direito de escrever). */
    fun proxima(): Int = atual.incrementAndGet()

    /** O numero que esta valendo agora. */
    fun vigente(): Int = atual.get()

    /** Este laco ainda e o dono do estado? */
    fun vigente(minha: Int): Boolean = atual.get() == minha
}
