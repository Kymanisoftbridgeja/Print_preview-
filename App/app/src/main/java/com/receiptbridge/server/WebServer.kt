package com.receiptbridge.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.receiptbridge.R
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.repository.JobRepository
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
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
    private var server: io.ktor.server.engine.ApplicationEngine? = null

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
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this icon exists or use system default
            .build()

        startForeground(1, notification)
    }

    private fun startServer() {
        if (server != null) return

        serviceScope.launch {
            try {
                server = embeddedServer(CIO, port = 9900) {
                    install(ContentNegotiation) {
                        gson { }
                    }
                    routing {
                        get("/status") {
                            call.respond(mapOf("status" to "running"))
                        }
                        post("/print") {
                            // We receive the raw JSON and save it directly
                            // Validation happens at processing time or we can parse here to validate
                            val body = call.receiveText()
                            
                            // Basic validation: check if it parses? 
                             // For now, assume valid or catch in processor
                            
                            val job = PrintJob(
                                payloadJson = body
                            )
                            jobRepository.createJob(job)
                            
                            call.respond(mapOf(
                                "status" to "queued",
                                "job_id" to job.id
                            ))
                        }
                    }
                }.start(wait = true)
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf() // Stop service if server fails
            }
        }
    }

    override fun onDestroy() {
        server?.stop(1000, 2000)
        super.onDestroy()
    }
}
