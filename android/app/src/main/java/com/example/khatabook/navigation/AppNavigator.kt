package com.example.khatabook.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Encapsulates the application's nested backstack navigation logic.
 */
class AppNavigator(
    val navStack: SnapshotStateList<AppScreen>
) {
    val currentScreen: AppScreen
        get() = navStack.lastOrNull() ?: AppScreen.PARTIES

    fun navigateTo(screen: AppScreen) {
        if (currentScreen != screen) {
            // Instant tab toggle between Home (Parties) and More
            if ((currentScreen == AppScreen.PARTIES && screen == AppScreen.MORE) ||
                (currentScreen == AppScreen.MORE && screen == AppScreen.PARTIES)
            ) {
                navStack.clear()
                navStack.add(screen)
            } else {
                navStack.add(screen)
            }
        }
    }

    fun navigateBack(onFinish: () -> Unit) {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.size - 1)
        } else if (currentScreen == AppScreen.MORE) {
            // More tab back returns to Home (Parties)
            navStack.clear()
            navStack.add(AppScreen.PARTIES)
        } else {
            onFinish()
        }
    }

    fun popToRoot(rootScreen: AppScreen = AppScreen.PARTIES) {
        navStack.clear()
        navStack.add(rootScreen)
    }
}

@Composable
fun rememberAppNavigator(
    initialScreen: AppScreen = AppScreen.PARTIES
): AppNavigator {
    val navStack = rememberSaveable(saver = navStackSaver) {
        mutableStateListOf(initialScreen)
    }
    return rememberAppNavigator(navStack)
}

@Composable
fun rememberAppNavigator(
    navStack: SnapshotStateList<AppScreen>
): AppNavigator {
    return androidx.compose.runtime.remember(navStack) {
        AppNavigator(navStack)
    }
}
