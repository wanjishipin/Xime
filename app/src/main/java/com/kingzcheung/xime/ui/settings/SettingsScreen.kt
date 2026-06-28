package com.kingzcheung.xime.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun SettingsScreen(
    initialRoute: String? = null,
    onThemeChanged: () -> Unit = {},
    onWizardBack: () -> Unit = {}
) {
    val navController = rememberNavController()
    val startDestination = if (initialRoute == "manage_dict") SettingsRoutes.Dictionary
    else if (initialRoute == "schema") SettingsRoutes.Schema
    else SettingsRoutes.Main
    
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(SettingsRoutes.Main) {
            SettingsMainContent(
                onNavigateToSchema = { navController.navigate(SettingsRoutes.Schema) },
                onNavigateToSchemaMarket = { navController.navigate(SettingsRoutes.SchemaMarket) },
                onNavigateToTheme = { navController.navigate(SettingsRoutes.Theme) },
                onNavigateToKeyEffect = { navController.navigate(SettingsRoutes.KeyEffect) },
                onNavigateToLayoutDisplay = { navController.navigate(SettingsRoutes.LayoutDisplay) },
                onNavigateToDictionary = { navController.navigate(SettingsRoutes.Dictionary) },
                onNavigateToPlugins = { navController.navigate(SettingsRoutes.Plugins) },
                onNavigateToSmartPrediction = { navController.navigate(SettingsRoutes.SmartPrediction) },
                onNavigateToSpeechToText = { navController.navigate(SettingsRoutes.SpeechToText) },
                onNavigateToModelManagement = { navController.navigate(SettingsRoutes.ModelManagement) },
                onNavigateToAbout = { navController.navigate(SettingsRoutes.About) },
                onNavigateToWebDav = { navController.navigate(SettingsRoutes.WebDav) }
            )
        }
        composable(SettingsRoutes.Schema) {
            SchemaSettingsContent(
                onBack = {
                    if (initialRoute == "schema") {
                        navController.navigate(SettingsRoutes.Main) {
                            popUpTo(SettingsRoutes.Main) { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                    onWizardBack()
                },
                onNavigateToMarket = { navController.navigate(SettingsRoutes.SchemaMarket) }
            )
        }
        composable(SettingsRoutes.SchemaMarket) {
            SchemaMarketContent(
                onBack = { navController.popBackStack() },
                onNavigateToDetail = { schemeId ->
                    navController.navigate("schema_market_detail/$schemeId")
                },
            )
        }
        composable(
            route = SettingsRoutes.SchemaMarketDetail,
            arguments = listOf(navArgument("schemeId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val schemeId = backStackEntry.arguments?.getString("schemeId") ?: return@composable
            SchemaMarketDetailContent(
                schemeId = schemeId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(SettingsRoutes.Theme) {
            ThemeSettingsContent(
                onBack = { navController.popBackStack() },
                onThemeChanged = onThemeChanged
            )
        }
        composable(SettingsRoutes.Plugins) {
            PluginsSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToPluginSettings = { pluginId ->
                    navController.navigate("${SettingsRoutes.PluginSettings}/$pluginId")
                }
            )
        }
        composable(
            route = "${SettingsRoutes.PluginSettings}/{pluginId}",
            arguments = listOf(navArgument("pluginId") { type = NavType.StringType })
        ) { backStackEntry ->
            val pluginId = backStackEntry.arguments?.getString("pluginId")
            PluginSettingsContent(
                pluginId = pluginId ?: "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.KeyEffect) {
            KeyEffectSettingsContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.LayoutDisplay) {
            LayoutDisplaySettingsContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.SmartPrediction) {
            SmartPredictionSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToModelManagement = { navController.navigate(SettingsRoutes.ModelManagement) }
            )
        }
        composable(SettingsRoutes.SpeechToText) {
            SpeechToTextSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToFunAsrSettings = { navController.navigate(SettingsRoutes.FunAsrSettings) },
                onNavigateToModelManagement = { navController.navigate(SettingsRoutes.ModelManagement) }
            )
        }
        composable(SettingsRoutes.FunAsrSettings) {
            FunAsrSettingsContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.Dictionary) {
            DictionarySettingsContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.WebDav) {
            WebDavSyncContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.About) {
            AboutContent(
                onBack = { navController.popBackStack() },
                onNavigateToPrivacy = { navController.navigate(SettingsRoutes.Privacy) },
                onNavigateToLicenses = { navController.navigate(SettingsRoutes.Licenses) },
                onNavigateToLogViewer = { navController.navigate(SettingsRoutes.LogViewer) }
            )
        }
        composable(SettingsRoutes.ModelManagement) {
            ModelManagementContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.LogViewer) {
            LogViewerScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.Privacy) {
            PrivacyPolicyContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.Licenses) {
            LicensesContent(
                onBack = { navController.popBackStack() }
            )
        }
    }

    if (initialRoute != null) {
        LaunchedEffect(initialRoute) {
            when (initialRoute) {
                "model_management" -> navController.navigate(SettingsRoutes.ModelManagement)
            }
        }
    }
}
