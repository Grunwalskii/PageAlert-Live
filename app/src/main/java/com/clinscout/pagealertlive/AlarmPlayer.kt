package com.clinscout.pagealertlive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.PowerManager

class AlarmPlayer(private val context: Context, private val report: (String?) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var player: MediaPlayer? = null
    private var wanted = false
    private var focusHeld = false
    private var focusRequested = false
    private var useFallback = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (!wanted) return@setOnAudioFocusChangeListener
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    focusHeld = true
                    if (wanted) {
                        try { player?.start(); report(null) }
                        catch (_: Exception) { releasePlayer() }
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    focusHeld = false
                    try { player?.pause() } catch (_: Exception) { }
                    if (wanted) report("Alarm is active, but another app holds audio focus. Playback will retry.")
                }
            }
        }.build()

    fun start() {
        wanted = true
        try {
            if (!focusHeld) {
                focusRequested = true
                focusHeld = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (!focusHeld) { report("Alarm is active. Audio focus unavailable; retrying automatically."); return }
            }
            if (player == null) {
                try { createPlayer(useFallback) }
                catch (_: Exception) { releasePlayer(); useFallback = true; createPlayer(true) }
            }
            if (player?.isPlaying == false) player?.start()
            report(if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) "Alarm volume is zero. Raise alarm volume in device settings." else null)
        } catch (error: Exception) {
            releasePlayer()
            report("Alarm is active, but audio failed: ${error.message}. Retrying automatically.")
        }
    }

    private fun createPlayer(fallback: Boolean) {
        val next = MediaPlayer()
        player = next
        next.setAudioAttributes(attributes)
        next.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        if (fallback) {
            context.resources.openRawResourceFd(R.raw.fallback_alarm).use { asset ->
                next.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            }
        } else {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            next.setDataSource(context, uri)
        }
        next.isLooping = true
        next.setOnErrorListener { failed, _, _ ->
            // A callback from a retired player must not release a newer alarm
            // or report a playback failure after the user has stopped it.
            if (player === failed && wanted) {
                releasePlayer(); useFallback = true
                report("Alarm audio interrupted. Retrying with the built-in alarm.")
            }
            true
        }
        next.prepare()
    }

    private fun releasePlayer() {
        val old = player
        player = null
        try { old?.release() } catch (_: Exception) { }
    }

    fun stop() {
        wanted = false
        releasePlayer()
        val abandon = focusRequested
        focusRequested = false
        try { if (abandon) audio.abandonAudioFocusRequest(focus) }
        catch (error: RuntimeException) { android.util.Log.w("PageAlertLive", "Audio focus cleanup failed", error) }
        finally { focusHeld = false; useFallback = false }
    }
}
