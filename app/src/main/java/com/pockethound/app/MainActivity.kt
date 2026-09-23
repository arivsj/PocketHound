package com.pockethound.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.pockethound.app.ui.nav.PhNavRoot
import com.pockethound.app.ui.theme.PocketHoundTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Pedir os avisos na abertura (Android 13+). Recusar não quebra nada: o
     * serviço de primeiro plano segue rodando e os pedidos seguem chegando na
     * tela — só a bandeja fica muda.
     */
    private val pedirNotificacoes =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        abrirRotaDoIntent(intent)
        pedirNotificacaoSePrecisa()
        setContent {
            PocketHoundTheme {
                PhNavRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        abrirRotaDoIntent(intent)
    }

    /**
     * Tocou a notificação? O extra [EXTRA_ROTA] vira o pedido de rota do
     * Application — único lugar que sobrevive a processo novo e a Activity nova.
     */
    private fun abrirRotaDoIntent(intent: Intent?) {
        val rota = intent?.getStringExtra(EXTRA_ROTA) ?: return
        (application as PocketHoundApp).abrirEm(rota)
    }

    private fun pedirNotificacaoSePrecisa() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val concedida =
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!concedida) pedirNotificacoes.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** Rota que a notificação pediu para abrir (constante no Intent). */
        const val EXTRA_ROTA = "ph.rota"
    }
}
