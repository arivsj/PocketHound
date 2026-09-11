package com.pockethound.app

import com.pockethound.app.core.model.PairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {

    // Valores SINTETICOS. O ticket do iroh e uma credencial: quem o tem alcanca
    // o no. Um ticket de verdade num repositorio publico e uma porta aberta.
    private val payloadValido =
        "pockethound://pair?v=1&n=pop-os" +
            "&t=endpointEXEMPLOnaoEUmTicketDeVerdade0000000000000000000000000000" +
            "&k=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" +
            "&x=1799999999999"

    @Test
    fun parseExtraiTodosOsCampos() {
        val parsed = PairingPayload.parse(payloadValido)!!
        assertEquals(1, parsed.version)
        assertEquals("pop-os", parsed.pcName)
        assertTrue(parsed.ticket.startsWith("endpointEXEMPLO"))
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", parsed.key)
        assertEquals(1799999999999L, parsed.expiresAt)
    }

    @Test
    fun parseDecodificaNomeComEspacoECaracteresEscapados() {
        val parsed = PairingPayload.parse(
            "pockethound://pair?v=1&n=PC%20do%20Ari&t=ticket1&k=chave1&x=1000",
        )!!
        assertEquals("PC do Ari", parsed.pcName)
    }

    @Test
    fun parseAceitaNomeAusente() {
        val parsed = PairingPayload.parse("pockethound://pair?v=1&t=ticket1&k=chave1&x=1000")!!
        assertNull(parsed.pcName)
    }

    @Test
    fun parseRecusaPayloadInvalido() {
        assertNull(PairingPayload.parse(null))
        assertNull(PairingPayload.parse(""))
        assertNull(PairingPayload.parse("   "))
        // esquema errado
        assertNull(PairingPayload.parse("cyberbot://pair?v=1&t=t&k=k&x=1"))
        // host errado
        assertNull(PairingPayload.parse("pockethound://outro?v=1&t=t&k=k&x=1"))
        // sem esquema
        assertNull(PairingPayload.parse("https://exemplo.com/pair?v=1&t=t&k=k&x=1"))
        // versão desconhecida
        assertNull(PairingPayload.parse("pockethound://pair?v=2&t=t&k=k&x=1"))
        // sem versão
        assertNull(PairingPayload.parse("pockethound://pair?t=t&k=k&x=1"))
        // sem ticket
        assertNull(PairingPayload.parse("pockethound://pair?v=1&k=k&x=1"))
        // sem chave
        assertNull(PairingPayload.parse("pockethound://pair?v=1&t=t&x=1"))
        // sem expiração
        assertNull(PairingPayload.parse("pockethound://pair?v=1&t=t&k=k"))
        // expiração não numérica
        assertNull(PairingPayload.parse("pockethound://pair?v=1&t=t&k=k&x=abc"))
        // expiração zerada
        assertNull(PairingPayload.parse("pockethound://pair?v=1&t=t&k=k&x=0"))
    }

    @Test
    fun expiracaoEhComparadaComORelogioRecebido() {
        val parsed = PairingPayload.parse("pockethound://pair?v=1&t=t&k=k&x=5000")!!
        assertFalse(parsed.isExpired(nowMs = 4_999L))
        assertTrue(parsed.isExpired(nowMs = 5_000L))
        assertTrue(parsed.isExpired(nowMs = 5_001L))
    }

    @Test
    fun enderecoECodigoVemDoQrQuandoPresentes() {
        val parsed = PairingPayload.parse(
            "pockethound://pair?v=1&n=pop-os&t=ticket&k=chave&x=9999999999999" +
                "&a=http%3A%2F%2F192.168.0.10%3A7411&c=482913",
        )!!
        assertEquals("http://192.168.0.10:7411", parsed.address)
        assertEquals("482913", parsed.code)
    }

    @Test
    fun semEnderecoECodigoOPareamentoContinuaValido() {
        // É o caminho que funciona hoje: sem QR, o usuário digita o endereço e o
        // código. O payload não pode exigir o que nem todo PC sabe gerar.
        val parsed = PairingPayload.parse("pockethound://pair?v=1&t=t&k=k&x=9999999999999")!!
        assertNull(parsed.address)
        assertNull(parsed.code)
    }

    @Test
    fun enderecoSoAceitaEsquemaHttp() {
        // Um QR adulterado com outro esquema viraria uma requisição para onde o
        // atacante mandasse.
        val parsed = PairingPayload.parse(
            "pockethound://pair?v=1&t=t&k=k&x=9999999999999&a=file%3A%2F%2F%2Fetc%2Fpasswd",
        )!!
        assertNull(parsed.address)
    }

    @Test
    fun codigoSoAceitaSeisDigitos() {
        // A mesma regra do PC. Sem isto o app mandaria lixo e o usuário veria
        // "codigo invalido" sem entender por que.
        assertNull(PairingPayload.parse("pockethound://pair?v=1&t=t&k=k&x=9999999999999&c=123")!!.code)
        assertNull(PairingPayload.parse("pockethound://pair?v=1&t=t&k=k&x=9999999999999&c=abcdef")!!.code)
        assertEquals("000123", PairingPayload.parse("pockethound://pair?v=1&t=t&k=k&x=9999999999999&c=000123")!!.code)
    }
}

