package com.pockethound.app.notificacao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pockethound.app.MainActivity
import com.pockethound.app.PocketHoundApp
import com.pockethound.app.R
import com.pockethound.app.core.model.LinkState
import com.pockethound.app.core.model.LinkStatus
import com.pockethound.app.data.repo.HoundRepository
import com.pockethound.app.ui.nav.Routes
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano — o piso de segundo plano deste app.
 *
 * Ele faz duas coisas que, juntas, são o que permite atender "app em segundo
 * plano ou fechado" sem nenhuma infra externa:
 *
 * 1. **Mantém o processo vivo.** Enquanto roda, o Android congela/mataria o
 *    processo em segundo plano; assim o laço de conexão do [HoundRepository]
 *    continua ouvindo o PC (SSE + P2P) e o [PocketHoundApp] continua sabendo o
 *    que está na tela. Sobrevive ao "fechar pela lista de tarefas" — o que NÃO
 *    sobrevive é *forçar parada* nos ajustes, aí só um push externo (FCM/ntfy)
 *    avisaria; é o degrau seguinte, quando quiser.
 * 2. **Converte os [Aviso]s do repositório em notificação do Android** — e só
 *    quando o usuário NÃO está olhando a tela (com a tela aberta, o cartão já
 *    está lá; notificar em cima seria ruído).
 *
 * Nota de honestidade: a notificação persistente ("Conectado ao PC…") é o preço
 * deste desenho — é ela que o sistema exige para manter o serviço vivo.
 */
@AndroidEntryPoint
class NotificacaoService : Service() {

    @Inject
    lateinit var repository: HoundRepository

    private lateinit var app: PocketHoundApp

    /** Vida dos coletores; cancelado no onDestroy. */
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate() // Hilt injeta aqui
        app = application as PocketHoundApp
        rodando = true
        criarCanais()

        // O startForeground é a PRIMEIRA coisa: desde o startForegroundService o
        // sistema dá 5 s, e recusar derruba o app. O link atual é o melhor texto
        // que temos nesse instante; os próximos atualizam pela coleção abaixo.
        ligarPrimeiroPlano(repository.link.value)

        escopo.launch {
            repository.avisosSistema.collect { tratar(it) }
        }
        escopo.launch {
            repository.link.collect { atualizarConexao(it) }
        }
        // Sem PC pareado não há o que ouvir: o serviço se encerra sozinho, e quem
        // o religa quando o pareamento (re)nascer é o PocketHoundApp.
        escopo.launch {
            repository.pairing.collect { snapshot -> if (!snapshot.isPaired) sair() }
        }
        // Varredura de saída de cena: o que chegou com a tela aberta não publicou
        // (a tela já mostrava) e sumiria do radar justo quando virou segundo plano.
        escopo.launch { rastrearSegundoPlano() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        rodando = false
        escopo.cancel()
        super.onDestroy()
    }

    /* ------------------------------------------------------------ do repositório */

    private fun tratar(aviso: Aviso) {
        when (aviso) {
            is Aviso.Cancelar -> NotificationManagerCompat.from(this)
                .cancel(idDeNotificacao(aviso.id))
            is Aviso.Publicar -> if (!app.primeiroPlano.value) publicar(aviso)
        }
    }

    /**
     * Varre o que estava esperando na tela quando o app acaba de ir para segundo
     * plano — aprovações e perguntas abertas viram notificação aí, não antes.
     */
    private suspend fun rastrearSegundoPlano() {
        var naFrente = app.primeiroPlano.value
        app.primeiroPlano.collect { atual ->
            if (naFrente && !atual) {
                repository.approvals.value.forEach { publicar(Aviso.deAprovacao(it)) }
                repository.perguntas.value
                    .filterNot { it.respondida }
                    .forEach { publicar(Aviso.dePergunta(it.pedido)) }
            }
            naFrente = atual
        }
    }

    /* ------------------------------------------------------------ para o Android */

    private fun publicar(aviso: Aviso.Publicar) {
        val notificacao = NotificationCompat.Builder(this, canalDe(aviso.canal))
            .setSmallIcon(R.drawable.ic_notificacao)
            .setColor(ContextCompat.getColor(this, R.color.ph_violet))
            .setContentTitle(aviso.titulo)
            .setContentText(aviso.corpo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(aviso.corpo))
            .setContentIntent(abrirApp(aviso.id, Routes.Chat))
            .setAutoCancel(true)
            // Atualizar a mesma notificação (id estável) não deve buzzar de novo.
            .setOnlyAlertOnce(true)
            // Corpo privado: o que o Harness vai executar não é assunto de
            // tela bloqueada de quem passa por perto.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(categoriaDe(aviso.canal))
            .build()
        // Sem POST_NOTIFICATIONS (Android 13+) o sistema engole o aviso em
        // silêncio — é o esperado quando o usuário recusou os avisos.
        try {
            NotificationManagerCompat.from(this).notify(idDeNotificacao(aviso.id), notificacao)
        } catch (negado: SecurityException) {
            // Recusado pelo sistema: o serviço segue vivo, os avisos seguem mudos.
        }
    }

    /**
     * A notificação persistente do serviço, o retrato atual do link.
     *
     * Ela é o que o sistema exige mostrar para aceitar o primeiro plano — e ela
     * também é útil sozinha: responde "está conectado?" sem abrir o app.
     */
    private fun notificacaoDeConexao(link: LinkState): Notification {
        val (titulo, corpo) = when (link.status) {
            LinkStatus.Online -> "Conectado ao PC" to caminho(link)
            // Textos FIXOS de propósito: o motivo por tentativa (que num Harness
            // mudo vira um erro diferente a cada ciclo) fazia a bandeja trocar de
            // texto sem parar. O diagnóstico cru fica no app (Torre/link) — quem
            // está na rua só precisa saber se voltou ou não.
            LinkStatus.Connecting ->
                "Conectando ao PC…" to "reconectando em segundo plano — não precisa fazer nada"
            LinkStatus.Offline ->
                "Sem conexão com o PC" to "reaviso assim que ele voltar"
        }
        return NotificationCompat.Builder(this, CANAL_CONEXAO)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setColor(ContextCompat.getColor(this, R.color.ph_violet))
            .setContentTitle(titulo)
            .setContentText(corpo)
            .setContentIntent(abrirApp(null, null))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .build()
    }

    private fun caminho(link: LinkState): String {
        val via = if (link.path.isBlank() || link.path == "-") "o caminho disponível" else link.path
        val latencia = link.latencyMs?.let { " · " + it + " ms" } ?: ""
        return "via " + via + latencia
    }

    private fun ligarPrimeiroPlano(link: LinkState) {
        val notificacao = notificacaoDeConexao(link)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_CONEXAO, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_CONEXAO, notificacao)
        }
    }

    private fun atualizarConexao(link: LinkState) {
        NotificationManagerCompat.from(this).notify(ID_CONEXAO, notificacaoDeConexao(link))
    }

    private fun abrirApp(chave: String?, rota: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            if (rota != null) putExtra(MainActivity.EXTRA_ROTA, rota)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            (chave ?: "conexao").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Faixa de id separada da do serviço: um hashCode de pedido que colidisse
     * com [ID_CONEXAO] trocaria a notificação persistente pela avulsa — e o
     * stopForeground seguinte apagaria a errada.
     */
    private fun idDeNotificacao(id: String): Int = 1000 + (id.hashCode() and 0x3FFFFFFF)

    private fun canalDe(canal: CanalNotificacao): String = when (canal) {
        CanalNotificacao.PEDIDOS -> CANAL_PEDIDOS
        CanalNotificacao.TRABALHO -> CANAL_TRABALHO
        CanalNotificacao.CONEXAO -> CANAL_CONEXAO
    }

    private fun categoriaDe(canal: CanalNotificacao): String = when (canal) {
        CanalNotificacao.PEDIDOS -> NotificationCompat.CATEGORY_REMINDER
        CanalNotificacao.TRABALHO -> NotificationCompat.CATEGORY_STATUS
        CanalNotificacao.CONEXAO -> NotificationCompat.CATEGORY_SYSTEM
    }

    private fun criarCanais() {
        val gerenciador = getSystemService(NotificationManager::class.java)
        gerenciador.createNotificationChannel(
            NotificationChannel(
                CANAL_PEDIDOS,
                "Pedidos do PC",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Autorizações e perguntas que precisam da sua resposta."
                enableVibration(true)
            },
        )
        gerenciador.createNotificationChannel(
            NotificationChannel(
                CANAL_TRABALHO,
                "Fim do trabalho",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "O turno em andamento terminou."
            },
        )
        gerenciador.createNotificationChannel(
            NotificationChannel(
                CANAL_CONEXAO,
                "Conexão",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Notificação do serviço que mantém o PC sendo ouvido."
                setShowBadge(false)
            },
        )
    }

    private fun sair() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ID_CONEXAO = 1
        private const val CANAL_PEDIDOS = "pedidos"
        private const val CANAL_TRABALHO = "trabalho"
        private const val CANAL_CONEXAO = "conexao"

        /**
         * Já em execução? Evita martelar startForegroundService a cada pulso de
         * settings (o lastSeq muda de 5 em 5 s e reemite o snapshot de pareamento).
         */
        @Volatile
        private var rodando = false

        /** Sobe o serviço se ele não estiver vivo. Idempotente. */
        fun start(context: Context) {
            if (rodando) return
            // No Android 12+ um start vindo de segundo plano é recusado com
            // IllegalStateException; quem nessa hora estiver religando o serviço é
            // o próprio processo recém-nascido — e o restart pegajoso do sistema
            // entrega o onStartCommand de qualquer jeito.
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, NotificacaoService::class.java),
                )
            }
        }
    }
}
