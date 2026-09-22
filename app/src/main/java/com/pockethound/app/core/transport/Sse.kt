package com.pockethound.app.core.transport

import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Decodifica o fluxo de linhas de um SSE do PocketHound em quadros tipados.
 *
 * Fica separado da rede de propósito: é a parte que erra fácil e a única que dá
 * para testar sem servidor. A [DirectTransport] só entrega linhas; quem decide o
 * que é um quadro é isto aqui.
 *
 * O formato que o PC emite:
 *
 *     id: 1042
 *     data: {"v":1,"seq":1042,...}
 *     (linha vazia)
 *
 * Mais um comentário de batimento a cada 15 s, que começa com `:` e é ignorado.
 *
 * Regras do SSE que importam aqui:
 * - um evento termina na linha **vazia**, não no fim do fluxo;
 * - várias linhas `data:` do mesmo evento se concatenam — o protocolo manda
 *   tudo numa linha só, mas tratar a concatenação custa nada e evita surpresa
 *   se um dia o JSON for multi-linha;
 * - o último evento pode chegar sem a linha vazia final; ele não pode ser
 *   perdido, senão a última mensagem do turno some.
 */
object SseDecoder {

    /**
     * Converte linhas cruas em quadros.
     *
     * Um quadro ilegível é descartado em silêncio: uma linha corrompida não pode
     * derrubar a conexão inteira nem apagar o que já chegou.
     *
     * @return fluxo de quadros na ordem em que o PC os emitiu.
     */
    fun decode(lines: Flow<String>): Flow<IncomingFrame> = flow {
        val acumulado = StringBuilder()
        lines.collect { linha ->
            when {
                linha.isEmpty() -> {
                    if (acumulado.isNotEmpty()) {
                        PhCodec.decode(acumulado.toString())?.let { emit(it) }
                        acumulado.clear()
                    }
                }

                // Batimento do PC (a cada 15 s). Não é dado, mas é SINAL DE VIDA:
                // quem mede silêncio para derrubar o fluxo — o cliente de sessão —
                // precisa vê-lo passar. Descartá-lo em silêncio fazia uma conexão
                // saudável e parada ser derrubada por "silêncio" e refeita de
                // tempos em tempos.
                linha.startsWith(":") -> emit(IncomingFrame.Beat())
                linha.startsWith("data:") -> acumulado.append(linha.removePrefix("data:").trim())
                // `id:`, `event:` e `retry:` não são usados: o `seq` dentro do
                // JSON já é o cursor, e duplicá-lo em `id:` só criaria duas
                // verdades que podem divergir.
            }
        }
        if (acumulado.isNotEmpty()) PhCodec.decode(acumulado.toString())?.let { emit(it) }
    }
}
