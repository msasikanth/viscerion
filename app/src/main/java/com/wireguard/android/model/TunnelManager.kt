/*
 * Copyright © 2017-2018 WireGuard LLC.
 * Copyright © 2018-2019 Harsh Shandilya <msfjarvis@gmail.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.Application
import com.wireguard.android.BR
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.configStore.ConfigStore
import com.wireguard.android.model.Tunnel.Statistics
import com.wireguard.android.util.ExceptionLoggers
import com.wireguard.android.util.KotlinCompanions
import com.wireguard.android.util.ObservableSortedKeyedArrayList
import com.wireguard.android.util.ObservableSortedKeyedList
import com.wireguard.config.Config
import java9.util.Comparators
import java9.util.concurrent.CompletableFuture
import java9.util.concurrent.CompletionStage
import timber.log.Timber
import java.util.ArrayList

class TunnelManager(private var configStore: ConfigStore) : BaseObservable() {
    private val context = Application.get()
    private val completableTunnels = CompletableFuture<ObservableSortedKeyedList<String, Tunnel>>()
    private val tunnels = ObservableSortedKeyedArrayList<String, Tunnel>(COMPARATOR)
    private val delayedLoadRestoreTunnels = ArrayList<CompletableFuture<Void>>()
    private var haveLoaded: Boolean = false

    private fun addToList(name: String, config: Config?, state: Tunnel.State): Tunnel {
        val tunnel = Tunnel(this, name, config, state)
        tunnels.add(tunnel)
        return tunnel
    }

    fun create(name: String, config: Config?): CompletionStage<Tunnel> {
        if (Tunnel.isNameInvalid(name))
            return CompletableFuture.failedFuture(IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name)))
        if (tunnels.containsKey(name)) {
            val message = context.getString(R.string.tunnel_error_already_exists, name)
            return CompletableFuture.failedFuture(IllegalArgumentException(message))
        }
        return Application.asyncWorker.supplyAsync { config?.let { configStore.create(name, it) } }
            .thenApply { savedConfig -> addToList(name, savedConfig, Tunnel.State.DOWN) }
    }

    internal fun delete(tunnel: Tunnel): CompletionStage<Void> {
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            setLastUsedTunnel(null)
        tunnels.remove(tunnel)
        return Application.asyncWorker.runAsync {
            if (originalState == Tunnel.State.UP)
                Application.backend.setState(tunnel, Tunnel.State.DOWN)
            try {
                configStore.delete(tunnel.name)
            } catch (e: Exception) {
                if (originalState == Tunnel.State.UP)
                    Application.backend.setState(tunnel, Tunnel.State.UP)
                // Re-throw the exception to fail the completion.
                throw e
            }
        }.whenComplete { _, e ->
            if (e == null)
                return@whenComplete
            // Failure, put the tunnel back.
            tunnels.add(tunnel)
            if (wasLastUsed)
                setLastUsedTunnel(tunnel)
        }
    }

    @Bindable
    fun getLastUsedTunnel(): Tunnel? {
        return lastUsedTunnel
    }

    private fun setLastUsedTunnel(tunnel: Tunnel?) {
        if (tunnel == lastUsedTunnel)
            return
        lastUsedTunnel = tunnel
        notifyPropertyChanged(BR.lastUsedTunnel)
        Application.appPrefs.lastUsedTunnel = tunnel?.name ?: ""
    }

    internal fun getTunnelConfig(tunnel: Tunnel): CompletionStage<Config> {
        return Application.asyncWorker.supplyAsync { configStore.load(tunnel.name) }
            .thenApply(tunnel::onConfigChanged)
    }

    fun getTunnels(): CompletableFuture<ObservableSortedKeyedList<String, Tunnel>> {
        return completableTunnels
    }

    fun onCreate() {
        Application.asyncWorker.supplyAsync { configStore.enumerate() }
            .thenAcceptBoth(
                Application.asyncWorker.supplyAsync { Application.backend.enumerate() }
            ) { present, running -> this.onTunnelsLoaded(present, running) }
            .whenComplete(ExceptionLoggers.E)
    }

    private fun onTunnelsLoaded(present: Iterable<String>, running: Collection<String>) {
        for (name in present)
            addToList(name, null, if (running.contains(name)) Tunnel.State.UP else Tunnel.State.DOWN)
        val lastUsedName = Application.appPrefs.lastUsedTunnel
        if (lastUsedName.isNotEmpty())
            setLastUsedTunnel(tunnels[lastUsedName])
        var toComplete: Array<CompletableFuture<Void>>?
        synchronized(delayedLoadRestoreTunnels) {
            haveLoaded = true
            toComplete = delayedLoadRestoreTunnels.toTypedArray()
            delayedLoadRestoreTunnels.clear()
        }
        restoreState(true).whenComplete { v, t ->
            toComplete?.let {
                it.forEach { future ->
                    if (t == null)
                        future.complete(v)
                    else
                        future.completeExceptionally(t)
                }
            }
        }
        completableTunnels.complete(tunnels)
    }

    fun refreshTunnelStates() {
        Application.asyncWorker.supplyAsync { Application.backend.enumerate() }
            .thenAccept { running ->
                tunnels.forEach { tunnel ->
                    val state = if (running?.contains(tunnel.name) == true) Tunnel.State.UP else Tunnel.State.DOWN
                    tunnel.onStateChanged(state)
                    Application.backendAsync.thenAccept { backend ->
                        if (backend is WgQuickBackend)
                            backend.postNotification(state, tunnel)
                    }
                }
            }.whenComplete(ExceptionLoggers.E)
    }

    fun restoreState(force: Boolean): CompletionStage<Void> {
        if (!force && !Application.appPrefs.restoreOnBoot)
            return CompletableFuture.completedFuture(null)
        synchronized(delayedLoadRestoreTunnels) {
            if (!haveLoaded) {
                val f = CompletableFuture<Void>()
                delayedLoadRestoreTunnels.add(f)
                return f
            }
        }
        val previouslyRunning = Application.appPrefs.runningTunnels
        return KotlinCompanions.streamForStateChange(tunnels, previouslyRunning, this)
    }

    fun saveState() {
        Application.appPrefs.runningTunnels =
            tunnels.asSequence().filter { it.state == Tunnel.State.UP }.map { it.name }.toSet()
    }

    fun restartActiveTunnels() {
        completableTunnels.thenAccept { tunnels ->
            tunnels.forEach { tunnel ->
                if (tunnel.state == Tunnel.State.UP)
                    tunnel.setState(Tunnel.State.DOWN).whenComplete { _, _ -> tunnel.setState(Tunnel.State.UP) }
            }
        }
    }

    internal fun setTunnelConfig(tunnel: Tunnel, config: Config): CompletionStage<Config> {
        return Application.asyncWorker.supplyAsync {
            val appliedConfig = Application.backend.applyConfig(tunnel, config)
            configStore.save(tunnel.name, appliedConfig)
        }.thenApply(tunnel::onConfigChanged)
    }

    internal fun setTunnelName(tunnel: Tunnel, name: String): CompletionStage<String> {
        if (Tunnel.isNameInvalid(name))
            return CompletableFuture.failedFuture(IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name)))
        if (tunnels.containsKey(name)) {
            val message = context.getString(R.string.tunnel_error_already_exists, name)
            return CompletableFuture.failedFuture(IllegalArgumentException(message))
        }
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            setLastUsedTunnel(null)
        tunnels.remove(tunnel)
        return Application.asyncWorker.supplyAsync {
            if (originalState == Tunnel.State.UP)
                Application.backend.setState(tunnel, Tunnel.State.DOWN)
            configStore.rename(tunnel.name, name)
            val newName = tunnel.onNameChanged(name)
            if (originalState == Tunnel.State.UP)
                Application.backend.setState(tunnel, Tunnel.State.UP)
            newName
        }.whenComplete { _, e ->
            // On failure, we don't know what state the tunnel might be in. Fix that.
            if (e != null)
                getTunnelState(tunnel)
            // Add the tunnel back to the manager, under whatever name it thinks it has.
            tunnels.add(tunnel)
            if (wasLastUsed)
                setLastUsedTunnel(tunnel)
        }
    }

    fun setTunnelState(tunnel: Tunnel, state: Tunnel.State): CompletionStage<Tunnel.State> {
        // Ensure the configuration is loaded before trying to use it.
        return tunnel.configAsync.thenCompose {
            Application.asyncWorker.supplyAsync {
                Application.backend.setState(
                    tunnel,
                    state
                )
            }
        }.whenComplete { newState, e ->
            // Ensure onStateChanged is always called (failure or not), and with the correct state.
            tunnel.onStateChanged(if (e == null) newState else tunnel.state)
            if (e == null && newState == Tunnel.State.UP)
                setLastUsedTunnel(tunnel)
            saveState()
        }
    }

    class IntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val manager = Application.tunnelManager
            var tunnelName: String? = null
            var integrationSecret: String? = null
            var state: Tunnel.State? = null
            if (intent == null || intent.action == null)
                return
            when (intent.action) {
                "com.wireguard.android.action.REFRESH_TUNNEL_STATES" -> {
                    manager.refreshTunnelStates()
                    return
                }
                "${BuildConfig.APPLICATION_ID}.SET_TUNNEL_UP" -> {
                    tunnelName = intent.getStringExtra(TUNNEL_NAME_INTENT_EXTRA)
                    integrationSecret = intent.getStringExtra(INTENT_INTEGRATION_SECRET_EXTRA)
                    state = Tunnel.State.UP
                }
                "${BuildConfig.APPLICATION_ID}.SET_TUNNEL_DOWN" -> {
                    tunnelName = intent.getStringExtra(TUNNEL_NAME_INTENT_EXTRA)
                    integrationSecret = intent.getStringExtra(INTENT_INTEGRATION_SECRET_EXTRA)
                    state = Tunnel.State.DOWN
                }
                else -> Timber.tag("IntentReceiver").d("Invalid intent action: ${intent.action}")
            }
            if (!Application.appPrefs.allowTaskerIntegration || Application.appPrefs.taskerIntegrationSecret.isEmpty()) {
                Timber.tag("IntentReceiver")
                    .e("Tasker integration is disabled! Not allowing tunnel state change to pass through.")
                return
            }
            if (tunnelName != null && state != null && integrationSecret == Application.appPrefs.taskerIntegrationSecret) {
                Timber.tag("IntentReceiver").d("Setting $tunnelName's state to $state")
                manager.getTunnels().thenAccept { tunnels ->
                    val tunnel = tunnels[tunnelName]
                    tunnel?.let {
                        manager.setTunnelState(it, state)
                    }
                }
            } else if (tunnelName == null) {
                Timber.tag("IntentReceiver").d("Intent parameter tunnel_name not set!")
            } else {
                Timber.tag("IntentReceiver").e("Intent integration secret mis-match! Exiting...")
            }
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "wg-quick_tunnels"
        private val COMPARATOR = Comparators.thenComparing(
            String.CASE_INSENSITIVE_ORDER, Comparators.naturalOrder()
        )
        private var lastUsedTunnel: Tunnel? = null
        private const val TUNNEL_NAME_INTENT_EXTRA = "tunnel_name"
        private const val INTENT_INTEGRATION_SECRET_EXTRA = "integration_secret"

        internal fun getTunnelState(tunnel: Tunnel): CompletionStage<Tunnel.State> {
            return Application.asyncWorker.supplyAsync { Application.backend.getState(tunnel) }
                .thenApply(tunnel::onStateChanged)
        }

        fun getTunnelStatistics(tunnel: Tunnel): CompletionStage<Statistics> {
            return Application.asyncWorker
                .supplyAsync { Application.backend.getStatistics(tunnel) }
                .thenApply(tunnel::onStatisticsChanged)
        }
    }
}
