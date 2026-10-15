package com.clinscout.pagealertlive

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

object AlarmWebpages {
    const val IDS = "alarm_ids"
    const val TIMES = "alarm_times"

    fun targets(state: AppState, ids: List<String>, times: List<Long>): List<Monitor> {
        if (ids.size != times.size) return emptyList()
        val expected = ids.zip(times).toMap()
        return state.monitors.filter { monitor -> state.alarms[monitor.id]?.let { expected[monitor.id] == it.time } == true }
    }

    fun intent(context: Context, state: AppState): Intent {
        val alarms = state.alarms.values.filter { event -> state.monitors.any { it.id == event.monitorId } }
        return Intent(context, AlarmWebpageActivity::class.java)
            .setAction("com.clinscout.pagealertlive.DISARM_AND_OPEN")
            .setData(Uri.Builder().scheme("pagealert").authority("alarm-webpage")
                .appendPath(alarms.joinToString("|") { "${it.monitorId}:${it.time}" }).build())
            .putStringArrayListExtra(IDS, ArrayList(alarms.map { it.monitorId }))
            .putExtra(TIMES, alarms.map { it.time }.toLongArray())
    }
}

/** A direct activity PendingIntent avoids notification trampoline restrictions. */
class AlarmWebpageActivity : Activity() {
    private var names = arrayListOf<String>()
    private var urls = arrayListOf<String>()
    private var chooser: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            names = savedInstanceState.getStringArrayList("names") ?: arrayListOf()
            urls = savedInstanceState.getStringArrayList("urls") ?: arrayListOf()
        } else {
            val targets = AlarmWebpages.targets(repository.state.value,
                intent.getStringArrayListExtra(AlarmWebpages.IDS).orEmpty(),
                intent.getLongArrayExtra(AlarmWebpages.TIMES)?.toList().orEmpty())
            names = ArrayList(targets.map { it.name })
            urls = ArrayList(targets.map { it.url })
            // A stale action must not disarm newly rearmed alarms.
            if (targets.isNotEmpty()) MonitorService.send(this, MonitorService.ACTION_DISARM)
        }
        if (urls.isEmpty() || names.size != urls.size) { finish(); return }
        if (urls.size == 1) { open(0); return }
        chooser = AlertDialog.Builder(this).setTitle("Open alarm webpage")
            .setItems(names.toTypedArray()) { _, index -> open(index) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun open(index: Int) { BrowserLinks.open(this, urls[index]); finish() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("names", names)
        outState.putStringArrayList("urls", urls)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { chooser?.dismiss(); super.onDestroy() }
}
