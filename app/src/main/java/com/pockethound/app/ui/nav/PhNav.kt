package com.pockethound.app.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pockethound.app.PocketHoundApp
import com.pockethound.app.ui.chat.ChatRoute
import com.pockethound.app.ui.fleet.FleetRoute
import com.pockethound.app.ui.pairing.PairingRoute
import com.pockethound.app.ui.settings.SettingsRoute
import com.pockethound.app.ui.theme.PhBg
import com.pockethound.app.ui.workspaces.WorkspacesRoute

object Routes {
    const val Pairing = "pairing"
    const val Chat = "chat"

    /** Onde a decisão de aprovação mora agora: dentro do chat. */
    const val Sessions = "sessions"
    const val Fleet = "fleet"
    const val Settings = "settings"

    val tabs = listOf(Chat, Sessions, Fleet, Settings)
}

private fun NavHostController.goSingleTop(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * Raiz do app: NavHost + barra inferior.
 *
 * Nada é montado antes do pareamento — sem PC pareado não há o que mostrar nas abas,
 * então a barra inferior nem aparece.
 */
@Composable
fun PhNavRoot(viewModel: RootViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val isPaired by viewModel.isPaired.collectAsStateWithLifecycle()
    val pendingApprovals by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route

    LaunchedEffect(isPaired) {
        val target = if (isPaired) Routes.Chat else Routes.Pairing
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
        }
    }

    // O toque numa notificação pede uma rota. O pedido mora no Application (o
    // processo pode ter NASCIDO por causa do toque) e é consumido UMA vez aqui —
    // sem isso, abrir "Autorização necessária" cairia na última aba visitada.
    val app = LocalContext.current.applicationContext as PocketHoundApp
    val rotaPedida by app.rotaPedida.collectAsStateWithLifecycle()

    LaunchedEffect(rotaPedida, isPaired) {
        val alvo = rotaPedida ?: return@LaunchedEffect
        if (!isPaired) return@LaunchedEffect
        navController.navigate(alvo) { launchSingleTop = true }
        app.rotaConsumida()
    }

    Scaffold(
        containerColor = PhBg,
        bottomBar = {
            if (isPaired && currentRoute != Routes.Pairing) {
                PhBottomBar(
                    selectedRoute = phNavItems
                        .firstOrNull { item ->
                            currentDestination?.hierarchy?.any { it.route == item.route } == true
                        }
                        ?.route,
                    approvalCount = pendingApprovals,
                    onSelect = { route -> navController.goSingleTop(route) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Pairing,
            // Entrada com deslize curto + fade. O deslocamento é de 1/12 da tela
            // para dar profundidade sem parecer pesado.
            enterTransition = {
                fadeIn(animationSpec = tween(200)) +
                    slideInHorizontally(animationSpec = tween(300)) { width -> width / 12 }
            },
            exitTransition = {
                fadeOut(animationSpec = tween(140)) +
                    slideOutHorizontally(animationSpec = tween(300)) { width -> -width / 14 }
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(200)) +
                    slideInHorizontally(animationSpec = tween(300)) { width -> -width / 12 }
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(140)) +
                    slideOutHorizontally(animationSpec = tween(300)) { width -> width / 14 }
            },
            // consumeWindowInsets: o padding do Scaffold de fora é dado aqui e marcado
            // como já consumido; sem isso o Scaffold de dentro soma o inset de novo.
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(Routes.Pairing) {
                PairingRoute(
                    onPaired = {
                        navController.navigate(Routes.Chat) {
                            popUpTo(Routes.Pairing) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.Chat) { ChatRoute() }
            composable(Routes.Sessions) {
                WorkspacesRoute(onIrParaChat = { navController.goSingleTop(Routes.Chat) })
            }
            composable(Routes.Fleet) { FleetRoute() }
            composable(Routes.Settings) {
                SettingsRoute(onUnpaired = { navController.goSingleTop(Routes.Pairing) })
            }
        }
    }
}
