package com.pockethound.app

import com.pockethound.app.core.session.GeracaoDeLaco
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O guardiao do estado: so o laco mais novo pode escrever.
 *
 * O caso de campo (23/09): o botao reconectar abria o laco novo, mas o velho —
 * cancelado de forma assincrona — ainda chegava a gravar "offline" DEPOIS, e o
 * badge voltava a mentir com a conexao viva. Estes testes sao a garantia de
 * que a escrita do velho nao passa.
 */
class GeracaoDeLacoTest {

    @Test
    fun aPropriaGeracaoEGrafe() {
        val geracao = GeracaoDeLaco()

        val minha = geracao.proxima()

        assertTrue(geracao.vigente(minha))
    }

    @Test
    fun lacoVelhoPerdeODireitoQuandoOutroNasce() {
        val geracao = GeracaoDeLaco()
        val velho = geracao.proxima()

        val novo = geracao.proxima()

        assertTrue(geracao.vigente(novo))
        assertFalse(geracao.vigente(velho))
    }

    @Test
    fun comUmaUnicaGeracaoAVigenteEEla() {
        val geracao = GeracaoDeLaco()

        val unica = geracao.proxima()

        assertTrue(geracao.vigente(unica))
        assertTrue(geracao.vigente() == unica)
    }
}
