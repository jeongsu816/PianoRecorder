package app.pianorecorder.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * "스마트폰으로 재생": 케이블이 꽂혀 있어도 항상 폰 내장 스피커로 낸다 (2026-10-04 결정).
 * 그대로 두면 Android가 USB 오디오로 보내 피아노 스피커에서 소리가 나서([확인]) "피아노로 재생"과
 * 구분이 안 되기 때문.
 *
 * m4a가 없고 .mid만 있으면 MediaPlayer가 내장 신디사이저(Sonivox)로 MIDI를 재생한다.
 */
class PhonePlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun play(uri: Uri, onEnd: () -> Unit) {
        stop()
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            p.setDataSource(context, uri)
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { p.setPreferredDevice(it) }
            p.setOnCompletionListener { stop(); onEnd() }
            p.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "재생 오류 what=$what extra=$extra")
                stop(); onEnd(); true
            }
            p.prepare()
            p.start()
            Log.i(TAG, "재생 출력: ${p.routedDevice?.productName} type=${p.routedDevice?.type}")
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        player?.run { runCatching { stop() }; release() }
        player = null
    }

    companion object {
        private const val TAG = "PhonePlayer"
    }
}
