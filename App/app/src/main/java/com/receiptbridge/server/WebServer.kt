package com.receiptbridge.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.receiptbridge.data.PrintJobFactory
import com.receiptbridge.data.repository.JobRepository
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WebServer : Service() {

    @Inject
    lateinit var jobRepository: JobRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val serverLock = Any()
    private var server: io.ktor.server.engine.ApplicationEngine? = null
    @Volatile
    private var isServerStarting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        startServer()
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "ReceiptBridgeServer"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Print Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ReceiptBridge Server Running")
            .setContentText("Listening on port 9900")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

        startForeground(1, notification)
    }

    private fun startServer() {
        synchronized(serverLock) {
            if (server != null || isServerStarting) {
                return
            }
            isServerStarting = true
        }

        serviceScope.launch {
            try {
                val createdServer = embeddedServer(CIO, port = 9900) {
                    install(ContentNegotiation) {
                        gson { }
                    }
                    routing {
                        get("/status") {
                            call.respond(mapOf("status" to "running"))
                        }
                        post("/print") {
                            val body = call.receiveText()

                            val job = try {
                                PrintJobFactory.createFromPayloadJson(body)
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "status" to "error",
                                        "message" to (e.message ?: "Invalid payload")
                                    )
                                )
                                return@post
                            }

                            jobRepository.createJob(job)
                            
                            call.respond(mapOf(
                                "status" to "queued",
                                "job_id" to job.id
                            ))
                        }
                    }
                }
                createdServer.start(wait = false)
                server = createdServer
            } catch (e: Exception) {
                e.printStackTrace()
                server?.stop(1000, 2000)
                server = null
                stopSelf() // Stop service if server fails
            } finally {
                synchronized(serverLock) {
                    isServerStarting = false
                }
            }
        }
    }

    override fun onDestroy() {
        synchronized(serverLock) {
            server?.stop(1000, 2000)
            server = null
            isServerStarting = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
