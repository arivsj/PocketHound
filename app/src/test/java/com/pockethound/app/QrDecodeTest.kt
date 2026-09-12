package com.pockethound.app

import com.pockethound.app.core.model.PairingPayload
import com.pockethound.app.ui.pairing.QrDecode
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A decodificacao do QR, com um QR DE VERDADE.
 *
 * O recurso qr-luminance.bin foi gerado pelo proprio desk (src/core/pairing-qr.js):
 * e o QR que ele desenha, convertido para o plano Y do YUV -- exatamente o que o
 * CameraX entrega por quadro. O ticket e sintetico.
 *
 * Passando esses bytes pelo MESMO caminho que a camera usa, o teste prova a
 * compatibilidade entre o que o PC desenha e o que o celular le, sem camera e
 * sem aparelho.
 *
 * Esta em luminancia crua e nao em PNG porque o classpath de teste do Android
 * nao tem java.awt nem javax.imageio -- nao ha decodificador de imagem ali.
 */
class QrDecodeTest {

    private val esperado: String by lazy {
        javaClass.getResourceAsStream("/qr-esperado.txt")!!.bufferedReader().readText().trim()
    }

    /** A luminancia (Y) do QR, exatamente como a camera entregaria. */
    private val luminancia: ByteArray by lazy {
        GZIPInputStream(javaClass.getResourceAsStream("/qr-luminance.bin")!!).use { it.readBytes() }
    }

    private val dimensoes: Pair<Int, Int> by lazy {
        val texto = javaClass.getResourceAsStream("/qr-dimensoes.txt")!!.bufferedReader().readText().trim()
        val partes = texto.split(" ")
        partes[0].toInt() to partes[1].toInt()
    }

    private fun ler(rotacao: Int): String? = QrDecode.decodificar(
        luminancia,
        dimensoes.first,
        dimensoes.second,
        rotacao,
        QrDecode.leitor(),
    )

    @Test
    fun leOQrQueODeskGera() {
        // O teste que importa: o QR desenhado pelo PC tem de ser legivel pelo
        // decodificador do celular. Se o formato do payload mudar de um lado so,
        // isto quebra aqui e nao na mao do usuario.
        assertEquals(esperado, ler(0))
    }

    @Test
    fun oPayloadLidoEUmPareamentoValido() {
        val payload = PairingPayload.parse(ler(0))
        assertNotNull("o QR do desk nao virou um pareamento valido", payload)
        assertEquals("pop-os", payload!!.pcName)
        assertEquals("246813", payload.code)
        assertEquals("http://192.168.0.10:7411", payload.address)
        assertTrue("o ticket precisa vir no QR", payload.ticket.isNotBlank())
    }

    @Test
    fun leComOCelularDeitado() {
        // O sensor entrega o quadro girado quando o aparelho esta de lado. Sem a
        // correcao de rotacao o sintoma e "a camera nao le nada" -- que nao aponta
        // para rotacao e faz perder horas.
        assertEquals(esperado, ler(90))
        assertEquals(esperado, ler(270))
    }

    @Test
    fun leComOCelularDeCabecaParaBaixo() {
        assertEquals(esperado, ler(180))
    }

    @Test
    fun imagemSemQrDevolveNull() {
        // Ruido uniforme: o caso normal da camera, que passa a maior parte do
        // tempo apontada para nada.
        val branco = ByteArray(200 * 200) { 0xFF.toByte() }
        assertNull(QrDecode.decodificar(branco, 200, 200, 0, QrDecode.leitor()))
    }

    @Test
    fun bufferCurtoNaoDerruba() {
        // Um quadro truncado nao pode lancar: a analise roda a 30 quadros por
        // segundo e uma excecao por quadro mataria a camera.
        assertNull(QrDecode.decodificar(ByteArray(10), 200, 200, 0, QrDecode.leitor()))
    }
}
