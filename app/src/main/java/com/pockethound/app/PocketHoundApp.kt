package com.pockethound.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.pockethound.app.data.repo.HoundRepository
import com.pockethound.app.notificacao.NotificacaoService
import computer.iroh.IrohAndroid
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltAndroidApp
class PocketHoundApp : Application(), Application.ActivityLifecycleCallbacks {

    /**
     * Há telas visíveis agora? Com mais de zero o usuário está olhando o app —
     * e aí notificação é ruído (o cartão já está na tela).
     */
    private var telas = 0
    private val _primeiroPlano = MutableStateFlow(false)
    val primeiroPlano: StateFlow<Boolean> = _primeiroPlano.asStateFlow()

    /**
     * Rota que uma notificação pediu ao ser tocada.
     *
     * O pedido mora no processo, não no Intent: o processo pode até ter NASCIDO
     * por causa do toque, e a Activity lê isto ao criar (e a cada onNewIntent).
     */
    private val _rotaPedida = MutableStateFlow<String?>(null)
    val rotaPedida: StateFlow<String?> = _rotaPedida.asStateFlow()

    /** Uma notificação pediu para abrir [rota]. */
    fun abrirEm(rota: String) {
        _rotaPedida.value = rota
    }

    /** A raiz de navegação atendeu o pedido. */
    fun rotaConsumida() {
        _rotaPedida.value = null
    }

    override fun onCreate() {
        super.onCreate()
        // ANTES de qualquer coisa que possa instanciar o HoundRepository: o iroh é
        // nativo e o primeiro bind sem isto falha com um erro que não diz o que
        // faltou (ver comentário abaixo e README).
        IrohAndroid.installAndroidContext(this)
        registerActivityLifecycleCallbacks(this)

        // O serviço de primeiro plano é quem mantém o processo vivo ouvindo o PC
        // em segundo plano; ele só existe com o PC pareado (ele mesmo se encerra
        // quando o pareamento cai). O ponto de entrada — e não @Inject direto —
        // é proposital: a injecção direta instanciaria o repositório DENTRO do
        // super.onCreate, antes do installAndroidContext acima.
        val pontoDeEntrada = EntryPointAccessors.fromApplication(
            this,
            RepositorioEntryPoint::class.java,
        )
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            pontoDeEntrada.repositorio().pairing.collect { snapshot ->
                if (snapshot.isPaired) NotificacaoService.start(this@PocketHoundApp)
            }
        }
    }

    /* --------------------------------------------- rastro das telas (foreground) */

    override fun onActivityStarted(activity: Activity) {
        telas += 1
        _primeiroPlano.value = telas > 0
    }

    override fun onActivityStopped(activity: Activity) {
        telas = (telas - 1).coerceAtLeast(0)
        _primeiroPlano.value = telas > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * Porta de acesso ao repositório sem injetá-lo no campo (ver onCreate).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RepositorioEntryPoint {
    fun repositorio(): HoundRepository
}
