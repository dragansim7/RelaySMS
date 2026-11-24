package com.example

import android.app.Application
import com.example.data.local.RelayDatabase
import com.example.data.preferences.RelayPreferences
import com.example.data.remote.AndroidWebViewDomFetcher
import com.example.data.remote.AnosimProvider
import com.example.data.remote.ReceiveSmssProvider
import com.example.data.remote.TempSourceEngine
import com.example.data.repository.RelayRepository
import com.example.worker.TempSyncScheduler
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RelayApplication : Application() {

    lateinit var repository: RelayRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val database = RelayDatabase.getInstance(this)
        val preferences = RelayPreferences(this)
        val anosimProvider = AnosimProvider(httpClient)
        val receiveSmssProvider = ReceiveSmssProvider(httpClient)
        val webViewDomFetcher = AndroidWebViewDomFetcher(this)
        val tempSourceEngine = TempSourceEngine(httpClient, anosimProvider, webViewDomFetcher)
        val tempSyncScheduler = TempSyncScheduler(this)

        repository = RelayRepository(
            dao = database.relayDao(),
            preferences = preferences,
            anosimProvider = anosimProvider,
            receiveSmssProvider = receiveSmssProvider,
            tempSourceEngine = tempSourceEngine,
            tempSyncScheduler = tempSyncScheduler,
        )
    }
}
