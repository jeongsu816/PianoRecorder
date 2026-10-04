package app.pianorecorder.probe

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 화면 표시용 + Logcat(tag "Probe") 동시 출력.
 * MIDI 콜백 스레드, 녹음 스레드, UI 스레드에서 모두 호출되므로 StateFlow.update(원자적 CAS)로 갱신한다.
 */
class ProbeLog {
    private val startMs = SystemClock.elapsedRealtime()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(msg: String) {
        Log.i(TAG, msg)
        val t = (SystemClock.elapsedRealtime() - startMs) / 1000.0
        val line = "%7.2f  %s".format(t, msg)
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun clear() = _lines.update { emptyList() }

    companion object {
        const val TAG = "Probe"
        private const val MAX_LINES = 1000
    }
}
