package com.arenaai.duagents

import android.app.Application
import com.arenaai.duagents.core.AppContainer

/**
 * Application entry point — owns the manual dependency container.
 * Engineering decision: manual DI (no Hilt/Koin) — the dependency graph is small and
 * fully compile-time verified, eliminating annotation-processor build risk.
 */
class DuAgentsApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
