package com.clinscout.pagealertlive

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: MonitorEngine
    private lateinit var alarm: AlarmPlayer
    private lateinit var routing: NetworkRouting
    private var lock: PowerManager.WakeLock? = null
    private var collector: Job? = null
    private var lastNotification = ""
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        channels(this)
        routing = NetworkRouting(this)
        engine = MonitorEngine(scope, repository, onHttpError = ::notifyHttpError, routeChooser = routing)
        alarm = AlarmPlayer(this) { message ->
            if (repository.state.value.alarmError != message) repository.update { it.copy(alarmError = message) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Accept controls from older notifications without promoting a service.
        // Current controls use a receiver and never enqueue service starts.
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_DISARM) {
            send(this, intent.action!!)
            if (!foregroundStarted) stopSelf(startId)
            return if (foregroundStarted && repository.state.value.running) START_STICKY else START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> repository.update(true) { it.copy(running = true, interruption = null) }
            ACTION_TEST -> repository.update(true) {
                val event = AlertEvent(System.currentTimeMillis(), "__test__", "Test alarm", "Manual alarm test")
                it.copy(alarms = it.alarms + (event.monitorId to event))
            }
        }
        // Enter once, using the state after the command. Reentering on Disarm
        // can asynchronously repost an old alarm over the updated status.
        // Workers and audio start only after this initial foreground entry.
        if (!foregroundStarted) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(repository.state.value),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            foregroundStarted = true
        }
        repository.update { it.copy(serviceAlive = true) }
        repository.heartbeat()
        if (collector == null) {
            collector = scope.launch {
                repository.state.collect { state ->
                    if (state.running && state.monitors.none { it.enabled }) {
                        repository.update(true) { it.copy(running = false) }
                        return@collect
                    }
                    routing.reconcile(state)
                    engine.reconcile(state)
                    if (!state.running && state.alarms.isEmpty()) {
                        alarm.stop()
                        releaseWakeLock()
                        foregroundStarted = false
                        lastNotification = ""
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                        stopSelf()
                        return@collect
                    }
                    holdWakeLock()
                    if (state.alarms.isNotEmpty()) alarm.start() else alarm.stop()
                    updateNotification(state)
                }
            }
            scope.launch {
                var ticks = 0
                while (isActive) {
                    delay(1000)
                    val state = repository.state.value
                    if (!state.running && state.alarms.isEmpty()) continue
                    updateNotification(state)
                    ticks++
                    if (ticks % 5 == 0 && state.alarms.isNotEmpty()) alarm.start()
                    if (ticks % 10 == 0) repository.heartbeat()
                }
            }
        }
        return if (repository.state.value.running || repository.state.value.alarms.isNotEmpty()) START_STICKY else START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout") // Intentionally held for the user-started session; released on stop/destroy.
    private fun holdWakeLock() {
        if (lock?.isHeld == true) return
        lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PageAlertLive:Monitoring").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseWakeLock() { if (lock?.isHeld == true) lock?.release(); lock = null }

    private fun notification(state: AppState, rows: List<String> = WatchdogNotification.lines(state, System.currentTimeMillis())): Notification {
        val active = state.alarms.isNotEmpty()
        val title = if (active) "Alarm • ${state.alarms.values.joinToString { it.name }}" else getString(R.string.monitoring_title)
        val text = if (active) "${state.alarms.values.first().detail} • Tap Disarm to stop sound" else
            "${state.monitors.count { it.enabled }} active monitors • Randomized checks"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (active) ALARM_CHANNEL else STATUS_CHANNEL
        val expanded = (if (active) listOf(text) else emptyList()) + rows
        val expandedText = expanded.joinToString("\n").ifEmpty { text }
        val publicVersion = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (active) R.string.alarm_title else R.string.monitoring_title))
            .setContentText(if (active) "Alarm active • Disarm to stop sound" else text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setContentIntent(open)
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(if (active) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE)
            .apply {
                if (active) {
                    addAction(control("Disarm alarm", ACTION_DISARM, 1))
                    if (state.monitors.any { it.id in state.alarms }) addAction(openAlarm(state))
                }
                addAction(control("Stop all", ACTION_STOP, 2))
            }.build()
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setContentIntent(open).setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
            .setCategory(if (active) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion)
            .setPriority(if (active) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .apply {
                if (active) {
                    addAction(control("Disarm alarm", ACTION_DISARM, 1))
                    if (state.monitors.any { it.id in state.alarms }) addAction(openAlarm(state))
                }
                addAction(control("Stop all", ACTION_STOP, 2))
            }.build()
    }

    private fun control(label: String, command: String, code: Int) =
        NotificationCompat.Action.Builder(0, label, action(command, code))
            .setAuthenticationRequired(false).build()

    private fun action(command: String, code: Int) = PendingIntent.getBroadcast(this, code,
        Intent(this, AlarmControlReceiver::class.java).setAction(command), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun openAlarm(state: AppState) = NotificationCompat.Action.Builder(0, "Disarm & open webpage",
        PendingIntent.getActivity(this, 4, AlarmWebpages.intent(this, state), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setAuthenticationRequired(true).build()

    private fun updateNotification(state: AppState) {
        val rows = WatchdogNotification.lines(state, System.currentTimeMillis())
        val key = "${state.running}|${state.monitors.count { it.enabled }}|${state.alarms}|$rows"
        if (key == lastNotification) return
        lastNotification = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state, rows))
    }

    private fun notifyHttpError(event: AlertEvent) {
        val open = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java).putExtra("tab", 1),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(this, HTTP_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("HTTP error • ${event.name}")
            .setContentText(event.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.detail))
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(event.time).build()
        getSystemService(NotificationManager::class.java).notify("http:${event.monitorId}", HTTP_NOTIFICATION_ID, notice)
    }

    override fun onDestroy() {
        scope.cancel()
        engine.close()
        routing.close()
        alarm.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        repository.onServiceStopped()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.clinscout.pagealertlive.START"
        const val ACTION_STOP = "com.clinscout.pagealertlive.STOP"
        const val ACTION_DISARM = "com.clinscout.pagealertlive.DISARM"
        const val ACTION_TEST = "com.clinscout.pagealertlive.TEST"
        private const val NOTIFICATION_ID = 41
        // Channel importance cannot be raised after creation. Upgrade the old
        // default LOW channel while retaining explicit user importance choices.
        const val STATUS_CHANNEL = "monitoring_status_v2"
        const val ALARM_CHANNEL = "alarms"
        const val HTTP_CHANNEL = "http_errors"
        const val HTTP_NOTIFICATION_ID = 42

        @SuppressLint("ImplicitSamInstance") // stopService matches an Intent's component, not its object identity.
        fun send(context: Context, command: String) {
            when (command) {
                ACTION_STOP -> {
                    context.repository.update(true) { it.copy(running = false, alarms = emptyMap(), alarmError = null, interruption = null) }
                    context.stopService(Intent(context, MonitorService::class.java))
                    context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    return
                }
                ACTION_DISARM -> {
                    // The existing service observes this state change and stops
                    // playback. Acknowledge must never start/restart a service:
                    // Stop may arrive before a queued start is delivered.
                    context.repository.acknowledge()
                    if (!context.repository.state.value.running) {
                        context.stopService(Intent(context, MonitorService::class.java))
                        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    }
                    return
                }
            }
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java).setAction(command))
        }
        fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val previous = manager.getNotificationChannel("monitoring")
            val importance = when {
                previous?.importance == NotificationManager.IMPORTANCE_NONE -> NotificationManager.IMPORTANCE_NONE
                Build.VERSION.SDK_INT >= 29 && previous?.hasUserSetImportance() == true -> previous.importance
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "Monitoring status", importance).apply {
                description = "Persistent monitoring controls, including on the lock screen when allowed in device settings"
                setSound(null, null)
                enableVibration(false)
            })
            manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL, "Triggered alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.alarm_channel_description)
                setSound(null, null) // The service owns the continuous alarm, not the notification channel.
            })
            manager.createNotificationChannel(NotificationChannel(HTTP_CHANNEL, "HTTP errors", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Website request failures"
            })
        }
    }
}

class AlarmControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MonitorService.ACTION_DISARM || intent.action == MonitorService.ACTION_STOP) {
            MonitorService.send(context, intent.action!!)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val state = context.repository.state.value
        if (state.running || state.alarms.isNotEmpty()) {
            try { MonitorService.send(context, "com.clinscout.pagealertlive.RESTORE") }
            catch (error: Exception) { android.util.Log.e("PageAlertLive", "Restart blocked; open the app to resume", error) }
        }
    }
}
