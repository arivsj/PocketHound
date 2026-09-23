package com.pockethound.app

import com.pockethound.app.core.transport.Autocura
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A regra que decide quando o endpoint QUIC deve nascer de novo.
 *
 * O caso que originou isto, medido em campo (23/09): o IP do PC girou no 4G e o
 * endpoint do celular, preso na rede antiga, ficou horas falhando SEM abrir
 * conexao nenhuma — o force-stop do app reconectou em 5 segundos. A autocuracao
 * e esse force-stop automatico, e estes testes sao a promessa de que ele dispara
 * no limite certo: nem a cada falha (rede ruim e comum na rua), nem nunca.
 */
class AutocuraTest {

    @Test
    fun umaOuDuasMortasNaoDisparam() {
        val autocura = Autocura(limite = 3)

        assertFalse(autocura.falhou())
        assertFalse(autocura.falhou())
    }

    @Test
    fun aTerceiraMortaSeguidaDispara() {
        val autocura = Autocura(limite = 3)

        assertFalse(autocura.falhou())
        assertFalse(autocura.falhou())
        assertTrue(autocura.falhou())
    }

    @Test
    fun sucessoNoMeioZeraAContagem() {
        val autocura = Autocura(limite = 3)

        autocura.falhou()
        autocura.falhou()
        autocura.sucesso()

        // Recomecou: mais duas mortas ainda nao bastam.
        assertFalse(autocura.falhou())
        assertFalse(autocura.falhou())
        assertTrue(autocura.falhou())
    }

    @Test
    fun depoisDeDispararRecomecaDoZero() {
        val autocura = Autocura(limite = 2)

        // Com limite 2, so a segunda morta seguida dispara.
        assertFalse(autocura.falhou())
        assertTrue(autocura.falhou())

        // A leva que disparou zera a contagem: as proximas duas nao podem
        // disparar de novo — senao uma rede ruim recriaria o endpoint em
        // cascata.
        assertFalse(autocura.falhou())
        assertTrue(autocura.falhou())
    }

    @Test
    fun disparoComLimiteUmNaPrimeira() {
        val autocura = Autocura(limite = 1)

        // Com limite 1, TODA morta dispara — e depois de zerar, a proxima
        // tambem: nao ha "primeira morta isenta".
        assertTrue(autocura.falhou())
        assertTrue(autocura.falhou())
        assertTrue(autocura.falhou())
    }
}
