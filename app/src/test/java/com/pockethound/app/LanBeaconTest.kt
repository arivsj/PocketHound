package com.pockethound.app

import com.pockethound.app.core.transport.Farol
import com.pockethound.app.core.transport.PcNaLan
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O farol da rede local: quem pode ser seguido, e quando.
 *
 * A decisão é pequena mas não é boba: seguir um farol significa mandar o token
 * do aparelho para aquele endereço, e o token é a única credencial do Harness.
 * Por isso as regras são puras e estão aqui, e não perdidas no laço do soquete.
 */
class LanBeaconTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val agora = 1_000_000L

    private fun anuncio(
        service: String = "pockethound",
        v: Int = 1,
        port: Int = 7411,
        name: String = "desk-do-aridev",
        dev: String = "",
    ): String = buildString {
        append("{\"service\":\"").append(service).append("\",")
        append("\"v\":").append(v).append(',')
        append("\"port\":").append(port).append(',')
        append("\"name\":\"").append(name).append("\",")
        append("\"mode\":\"auto\",")
        append("\"at\":").append(agora)
        if (dev.isNotEmpty()) append(",\"dev\":[").append(dev).append("]")
        append('}')
    }

    private fun pc(
        host: String = "192.168.0.10",
        nome: String = "desk-do-aridev",
        dispositivos: List<String> = emptyList(),
        vistoEm: Long = agora,
    ) = PcNaLan(host = host, porta = 7411, nome = nome, dispositivos = dispositivos, vistoEm = vistoEm)

    /* ------------------------------------------------------------- o pacote */

    @Test
    fun `o anuncio valido vira endereco`() {
        val visto = Farol.ler(anuncio(), json, "192.168.0.10", agora)
        assertEquals("192.168.0.10", visto?.host)
        assertEquals(7411, visto?.porta)
        assertEquals("http://192.168.0.10:7411", visto?.baseUrl)
        assertEquals("desk-do-aridev", visto?.nome)
    }

    @Test
    fun `o endereco vem de quem mandou, nunca do pacote`() {
        // O corpo do pacote não carrega host de propósito: um campo "host" só
        // serviria para alguém anunciar o endereço de outra máquina.
        val visto = Farol.ler(anuncio(), json, "10.0.0.7", agora)
        assertEquals("10.0.0.7", visto?.host)
    }

    @Test
    fun `pacote de outro servico e ignorado`() {
        assertNull(Farol.ler(anuncio(service = "chromecast"), json, "192.168.0.10", agora))
    }

    @Test
    fun `versao futura e ignorada`() {
        // PC mais novo falando um protocolo que mudou: melhor não usar do que
        // falar errado.
        assertNull(Farol.ler(anuncio(v = 2), json, "192.168.0.10", agora))
    }

    @Test
    fun `porta impossivel e ignorada`() {
        assertNull(Farol.ler(anuncio(port = 0), json, "192.168.0.10", agora))
    }

    @Test
    fun `pacote quebrado nao derruba nada`() {
        assertNull(Farol.ler("{isso nao e json", json, "192.168.0.10", agora))
    }

    /* ------------------------------------------------------------ a confianca */

    @Test
    fun `pc que lista este aparelho e de confianca`() {
        val dono = pc(dispositivos = listOf("outro-id", "meu-id"))
        assertTrue(Farol.confiavel(dono, "meu-id", "desk-do-aridev", agora))
    }

    @Test
    fun `lista de aparelhos sem o meu id recusa, mesmo com o nome certo`() {
        // Quando o PC diz quem ele conhece, é ISSO que vale. Aceitar pelo nome
        // aqui seria trocar a prova forte pela fraca.
        val estranho = pc(dispositivos = listOf("id-de-outro-celular"))
        assertFalse(Farol.confiavel(estranho, "meu-id", "desk-do-aridev", agora))
    }

    @Test
    fun `desk antigo sem lista e aceito pelo nome`() {
        // A prova fraca existe para o desk que ainda não anuncia `dev`. Ela sobe
        // a barra (nome de máquina não é segredo na rede), mas não é cadeado.
        assertTrue(Farol.confiavel(pc(), "meu-id", "desk-do-aridev", agora))
    }

    @Test
    fun `nome diferente e recusado`() {
        assertFalse(Farol.confiavel(pc(nome = "pc-do-vizinho"), "meu-id", "desk-do-aridev", agora))
    }

    @Test
    fun `sem pc pareado nada e aceito`() {
        assertFalse(Farol.confiavel(pc(), "meu-id", null, agora))
        assertFalse(Farol.confiavel(pc(), "meu-id", "   ", agora))
    }

    @Test
    fun `anuncio velho deixa de valer`() {
        val velho = pc(vistoEm = agora - Farol.VALIDADE_MS - 1)
        assertFalse(Farol.confiavel(velho, "meu-id", "desk-do-aridev", agora))
    }

    @Test
    fun `a lista de aparelhos do pacote e lida`() {
        val visto = Farol.ler(anuncio(dev = "\"meu-id\",\"outro\""), json, "192.168.0.10", agora)
        assertEquals(listOf("meu-id", "outro"), visto?.dispositivos)
    }
}
