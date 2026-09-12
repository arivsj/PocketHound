package com.pockethound.app.ui.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.pockethound.app.core.model.PairingPayload
import com.pockethound.app.ui.common.PhButton
import com.pockethound.app.ui.common.PhButtonVariant
import com.pockethound.app.ui.common.PhScreenScaffold
import com.pockethound.app.ui.common.PhState
import com.pockethound.app.ui.common.PhStateKind
import com.pockethound.app.ui.theme.PhDanger
import com.pockethound.app.ui.theme.PhText
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhViolet
import com.pockethound.app.ui.theme.PhVoid
import java.util.concurrent.Executors

/**
 * Leitor de QR do PocketHound.
 *
 * CameraX entrega os quadros e o ZXing decodifica. Nada de biblioteca de UI de
 * scanner: a dependencia ja estava no build e o codigo e curto o bastante para
 * nao valer uma caixa-preta.
 *
 * ## Detalhes que fazem diferenca na pratica
 *
 * - **STRATEGY_KEEP_ONLY_LATEST**: a analise descarta quadros em vez de enfileirar.
 *   Sem isso, um aparelho rapido acumula quadros e o QR demora a ser lido.
 * - **Nao fecha o ImageProxy antes de ler**: fechar cedo demais devolve buffer
 *   vazio e o leitor simplesmente nunca acha nada, sem erro nenhum.
 * - **Uma leitura por vez**: depois de achar, para de analisar. Sem isso o
 *   callback dispara varias vezes para o mesmo QR e o pareamento comeca em
 *   duplicata.
 * - **So aceita payload do PocketHound**: um QR de outro produto nao e erro do
 *   usuario; a tela diz isso em vez de ficar muda.
 *
 * @param onLido chamado com o payload ja validado.
 * @param onCancelar volta para a tela de pareamento.
 */
@Composable
fun QrScannerRoute(
    onLido: (PairingPayload) -> Unit,
    onCancelar: () -> Unit,
) {
    val contexto = LocalContext.current
    var temPermissao by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(contexto, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var recusada by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }
    // Trava de leitura unica: o analisador roda a ~30 quadros por segundo e sem
    // isto o mesmo QR dispara o callback varias vezes.
    var jaLeu by remember { mutableStateOf(false) }

    val pedirPermissao = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedida ->
        temPermissao = concedida
        recusada = !concedida
    }

    LaunchedEffect(Unit) {
        if (!temPermissao) pedirPermissao.launch(Manifest.permission.CAMERA)
    }

    PhScreenScaffold(title = "Ler QR", subtitle = "aponte para a tela do PC") { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().background(PhVoid).padding(innerPadding)) {
            when {
                temPermissao -> {
                    CameraPreview(
                        aoDecodificar = { texto ->
                            if (jaLeu) return@CameraPreview
                            val payload = PairingPayload.parse(texto)
                            if (payload == null) {
                                // QR de outro produto, ou payload adulterado. Nao e
                                // erro do usuario: a tela explica e segue procurando.
                                aviso = "Esse QR não é do PocketHound. Aponte para o código mostrado no PC."
                            } else {
                                jaLeu = true
                                onLido(payload)
                            }
                        },
                    )

                    // Mira: quatro cantos, no lugar de um retângulo cheio — o meio
                    // fica livre para o QR aparecer.
                    MiraDeLeitura(modifier = Modifier.align(Alignment.Center))

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        aviso?.let {
                            PhState(message = it, kind = PhStateKind.Error)
                        }
                        Text(
                            text = "No PC: Dispositivos → Parear novo dispositivo",
                            color = PhTextDim,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                        PhButton(
                            text = "cancelar",
                            onClick = onCancelar,
                            variant = PhButtonVariant.Ghost,
                        )
                    }
                }

                recusada -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PhState(
                            message = "O app precisa da câmera para ler o QR. " +
                                "Você ainda pode digitar o endereço e o código de 6 dígitos à mão.",
                            kind = PhStateKind.Error,
                        )
                        PhButton(text = "pedir de novo", onClick = { pedirPermissao.launch(Manifest.permission.CAMERA) })
                        PhButton(text = "voltar", onClick = onCancelar, variant = PhButtonVariant.Ghost)
                    }
                }

                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "pedindo permissão da câmera…", color = PhText)
                    }
                }
            }
        }
    }
}

/**
 * CameraX + ZXing.
 *
 * O analisador roda num executor proprio — o padrao do CameraX e a thread
 * principal, que ficaria travada a cada quadro.
 *
 * @param aoDecodificar chamado com o texto de cada QR reconhecido.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun CameraPreview(aoDecodificar: (String) -> Unit) {
    val contexto = LocalContext.current
    val donoDoCiclo = LocalLifecycleOwner.current
    val analisador = remember { QrDecode.leitor() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(contexto).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE desenha num TextureView em vez de SurfaceView.
            //
            // O SurfaceView e mais eficiente, mas NAO aparece em captura de
            // tela: o screencap do adb devolve preto no lugar da imagem. Sem
            // isso, qualquer diagnostico visual do leitor vira adivinhacao — e
            // foi exatamente o que aconteceu quando fui conferir se a camera
            // estava mesmo mostrando imagem.
            //
            // Para um leitor de QR o custo e irrelevante: a tela fica aberta
            // poucos segundos.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    // O bind mora no DisposableEffect, NAO no update do AndroidView.
    //
    // O update roda a cada recomposicao. Com unbindAll+bindToLifecycle ali, a
    // camera era desligada e religada sem parar ate o CameraX desistir:
    //
    //     Use cases [Preview, ImageAnalysis] now DETACHED for camera
    //     Camera@19ef7de[id=0]  CLOSED
    //
    // O sintoma era a tela preta, sem erro nenhum do nosso lado -- so o HAL
    // reclamando de timeout. Um bind, um unbind, presos ao ciclo de vida.
    DisposableEffect(donoDoCiclo) {
        val futuro = ProcessCameraProvider.getInstance(contexto)
        var provedor: ProcessCameraProvider? = null
        val ouvinte = Runnable {
            try {
                val obtido = futuro.get()
                provedor = obtido
                val previa = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analise = ImageAnalysis.Builder()
                    // Descartar quadros em vez de enfileirar: num aparelho rapido a
                    // fila cresce e o QR demora segundos para ser lido.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { bloco ->
                        bloco.setAnalyzer(executor) { imagem ->
                            try {
                                imagem.lendoQr(analisador)?.let(aoDecodificar)
                            } catch (_: Throwable) {
                                // Quadro ilegivel e rotina: o proximo vem em 33 ms.
                            } finally {
                                // Fechar SEMPRE, e so depois de ler. Fechar antes devolve
                                // buffer vazio e o leitor nunca acha nada, sem erro nenhum.
                                imagem.close()
                            }
                        }
                    }
                obtido.unbindAll()
                obtido.bindToLifecycle(
                    donoDoCiclo,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previa,
                    analise,
                )
            } catch (_: Throwable) {
                // Sem camera utilizavel (emulador, por exemplo). A tela de
                // pareamento manual continua valendo.
            }
        }
        futuro.addListener(ouvinte, ContextCompat.getMainExecutor(contexto))

        onDispose {
            try { provedor?.unbindAll() } catch (_: Throwable) { /* ja solto */ }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Tenta decodificar o QR de um quadro da camera.
 *
 * @param analisador leitor do ZXing, reutilizado entre quadros.
 * @return o texto do QR, ou null quando nao ha QR neste quadro.
 */
private fun ImageProxy.lendoQr(analisador: MultiFormatReader): String? {
    val quadro = image ?: return null
    val plano = quadro.planes.firstOrNull() ?: return null
    val bytes = ByteArray(plano.buffer.remaining())
    plano.buffer.get(bytes)
    return QrDecode.decodificar(bytes, quadro.width, quadro.height, imageInfo.rotationDegrees, analisador)
}

/**
 * A decodificacao, sem Android.
 *
 * Separada do [ImageProxy] de proposito: o ZXing e Java puro, entao esta parte
 * roda num teste de JVM comum — sem camera, sem aparelho, sem emulador. E a
 * parte que erra (rotacao, formato do buffer), entao e a que mais precisa de
 * teste.
 */
object QrDecode {

    /**
     * Le um QR de um quadro em luminancia (Y) e devolve o texto.
     *
     * @param bytes um byte por pixel, no formato Y do YUV.
     * @param largura largura do quadro como veio do sensor.
     * @param altura altura do quadro como veio do sensor.
     * @param rotacaoGraus rotacao informada pelo sensor (0, 90, 180, 270).
     * @param leitor leitor do ZXing, reutilizado entre quadros.
     * @return o texto do QR, ou null quando nao ha QR reconhecivel.
     */
    fun decodificar(
        bytes: ByteArray,
        largura: Int,
        altura: Int,
        rotacaoGraus: Int,
        leitor: MultiFormatReader,
    ): String? {
        // O buffer vem na orientação do sensor; a rotação diz como endireitar.
        // Sem isto, um QR na vertical simplesmente não decodifica — e o sintoma
        // é "a câmera não lê nada", que não aponta para rotação.
        val (dados, l, a) = when (rotacaoGraus) {
            90 -> girar(bytes, largura, altura, 90)
            180 -> girar(bytes, largura, altura, 180)
            270 -> girar(bytes, largura, altura, 270)
            else -> Triple(bytes, largura, altura)
        }
        if (l <= 0 || a <= 0 || dados.size < l * a) return null

        val fonte = PlanarYUVLuminanceSource(dados, l, a, 0, 0, l, a, false)
        return try {
            leitor.decodeWithState(BinaryBitmap(HybridBinarizer(fonte))).text
        } catch (_: Throwable) {
            // NotFoundException e o caso normal: a maioria dos quadros nao tem QR.
            null
        } finally {
            leitor.reset()
        }
    }

    /**
     * Monta o leitor com as dicas que importam para QR de tela.
     *
     * @return o leitor pronto.
     */
    fun leitor(): MultiFormatReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                // QR de tela de PC costuma ser denso; TRY_HARDER custa CPU e
                // resolve os casos em que a leitura falha por um pixel.
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
}

/**
 * Gira a luminancia em 90/180/270 graus.
 *
 * @param origem bytes no formato Y (um byte por pixel).
 * @param largura largura original.
 * @param altura altura original.
 * @param graus rotacao desejada.
 * @return os bytes girados e as novas dimensoes.
 */
private fun girar(origem: ByteArray, largura: Int, altura: Int, graus: Int): Triple<ByteArray, Int, Int> {
    if (graus == 180) {
        val saida = ByteArray(origem.size)
        for (i in origem.indices) saida[i] = origem[origem.size - 1 - i]
        return Triple(saida, largura, altura)
    }

    val novaLargura = altura
    val novaAltura = largura
    val saida = ByteArray(origem.size)
    for (y in 0 until altura) {
        for (x in 0 until largura) {
            val origemIndice = y * largura + x
            val destino = if (graus == 90) {
                x * novaLargura + (novaLargura - 1 - y)
            } else {
                (novaAltura - 1 - x) * novaLargura + y
            }
            saida[destino] = origem[origemIndice]
        }
    }
    return Triple(saida, novaLargura, novaAltura)
}

/**
 * Quatro cantos em violeta: o meio fica livre para o QR aparecer.
 *
 * Desenhado no Canvas e nao com bordas: uma borda desenha os quatro lados, e o
 * que se quer e so dois por canto. Com quatro caixas de borda o resultado seria
 * um quadrado fechado, que esconde justamente o que precisa ser visto.
 */
@Composable
private fun MiraDeLeitura(modifier: Modifier = Modifier) {
    val lado = 240.dp
    val traco = 3.dp
    val canto = 34.dp
    val cor = PhViolet
    androidx.compose.foundation.Canvas(modifier = modifier.size(lado)) {
        val t = traco.toPx()
        val c = canto.toPx()
        val l = size.width
        val a = size.height
        // superior esquerdo
        drawLine(cor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(c, 0f), t)
        drawLine(cor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, c), t)
        // superior direito
        drawLine(cor, androidx.compose.ui.geometry.Offset(l, 0f), androidx.compose.ui.geometry.Offset(l - c, 0f), t)
        drawLine(cor, androidx.compose.ui.geometry.Offset(l, 0f), androidx.compose.ui.geometry.Offset(l, c), t)
        // inferior esquerdo
        drawLine(cor, androidx.compose.ui.geometry.Offset(0f, a), androidx.compose.ui.geometry.Offset(c, a), t)
        drawLine(cor, androidx.compose.ui.geometry.Offset(0f, a), androidx.compose.ui.geometry.Offset(0f, a - c), t)
        // inferior direito
        drawLine(cor, androidx.compose.ui.geometry.Offset(l, a), androidx.compose.ui.geometry.Offset(l - c, a), t)
        drawLine(cor, androidx.compose.ui.geometry.Offset(l, a), androidx.compose.ui.geometry.Offset(l, a - c), t)
    }
}
