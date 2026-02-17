package com.receiptbridge.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.receiptbridge.ui.screens.SettingsScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigateToPrinters = { navController.navigate("printers") },
                onNavigateToQueue = { navController.navigate("queue") }
            )
        }
        
        composable("printers") {
            ProfilesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable("queue") {
            QueueScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
