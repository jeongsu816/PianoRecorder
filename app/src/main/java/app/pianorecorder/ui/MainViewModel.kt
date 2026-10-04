package app.pianorecorder.ui

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pianorecorder.PianoApp
import app.pianorecorder.midi.Smf
import app.pianorecorder.record.Recorder
import app.pianorecorder.storage.NeedsConsentException
import app.pianorecorder.storage.Recording
import app.pianorecorder.storage.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Target { PIANO, PHONE }

data class Playing(val name: String, val target: Target)

/** 사용자 확인 창(재설치 후 이전 파일 수정/삭제)을 띄운 뒤, 승인되면 실행할 작업 */
class ConsentRequest(val intentSender: IntentSender, val onGranted: () -> Unit)

/**
 * 화면 상태와 동작의 중심. 규칙:
 *  - 녹음 중에는 재생하지 않는다 (USB 출력 → 피아노 → 녹음에 섞일 수 있음, 피아노 재생은 같은 음이 겹침)
 *  - 재생은 한 번에 하나
 *  - 피아노 연결이 끊기면 녹음은 그때까지 저장, 피아노 재생은 중지
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as PianoApp
    val piano = graph.piano
    private val store = graph.store
    val recorder = graph.recorder
    private val midiPlayer = graph.midiPlayer
    private val phonePlayer = graph.phonePlayer

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected

    private val _playing = MutableStateFlow<Playing?>(null)
    val playing: StateFlow<Playing?> = _playing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _consent = MutableStateFlow<ConsentRequest?>(null)
    val consent: StateFlow<ConsentRequest?> = _consent

    init {
        refresh()
        viewModelScope.launch {
            piano.connected.collect { connected ->
                if (!connected && recorder.state.value is Recorder.State.Recording) {
                    stopRecording()
                    _message.value = "피아노 연결이 끊겨서 녹음을 저장했어요"
                }
                if (!connected && _playing.value?.target == Target.PIANO) midiPlayer.stop()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _recordings.value = withContext(Dispatchers.IO) { runCatching { store.list() }.getOrDefault(emptyList()) }
        }
    }

    fun select(name: String) {
        _selected.value = if (_selected.value == name) null else name
    }

    fun consumeMessage() { _message.value = null }
    fun consumeConsent() { _consent.value = null }

    // ── 녹음 ──

    fun startRecording() {
        stopPlayback()
        try {
            val name = recorder.start()
            _selected.value = null
            if (recorder.state.value.let { it is Recorder.State.Recording && !it.withAudio }) {
                _message.value = "피아노 소리(오디오) 입력을 찾지 못해 MIDI만 녹음해요"
            }
            android.util.Log.i("MainViewModel", "녹음 시작 $name")
        } catch (e: Exception) {
            _message.value = "녹음을 시작하지 못했어요: ${e.message}"
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            val name = (recorder.state.value as? Recorder.State.Recording)?.name
            try {
                withContext(Dispatchers.IO) { recorder.stop() }
                refresh()
                _selected.value = name
            } catch (e: Exception) {
                _message.value = "녹음 저장 실패: ${e.message}"
            }
        }
    }

    // ── 재생 ──

    fun play(rec: Recording, target: Target) {
        stopPlayback()
        when (target) {
            Target.PIANO -> {
                val uri = rec.midiUri ?: return
                viewModelScope.launch {
                    val events = try {
                        withContext(Dispatchers.IO) {
                            val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                            Smf.read(bytes)
                        }
                    } catch (e: Exception) {
                        _message.value = "MIDI 파일을 읽지 못했어요: ${e.message}"
                        return@launch
                    }
                    _playing.value = Playing(rec.name, Target.PIANO)
                    midiPlayer.play(events) { failed ->
                        _playing.compareAndSet(Playing(rec.name, Target.PIANO), null)
                        if (failed) _message.value = "피아노로 보내지 못했어요. 케이블을 다시 꽂아 주세요"
                    }
                }
            }
            Target.PHONE -> {
                // m4a가 없으면 .mid를 폰 내장 신디사이저로
                val uri = rec.audioUri ?: rec.midiUri ?: return
                try {
                    phonePlayer.play(uri) { _playing.compareAndSet(Playing(rec.name, Target.PHONE), null) }
                    _playing.value = Playing(rec.name, Target.PHONE)
                } catch (e: Exception) {
                    _message.value = "재생하지 못했어요: ${e.message}"
                }
            }
        }
    }

    fun stopPlayback() {
        midiPlayer.stop()
        phonePlayer.stop()
        _playing.value = null
    }

    // ── 이름 변경 / 삭제 ──

    /** 검증 실패 시 이유를 돌려준다(대화상자에 표시). 성공·진행 중이면 null. */
    fun rename(rec: Recording, newName: String): String? {
        val name = newName.trim()
        if (name == rec.name) return null
        RecordingStore.validateName(name)?.let { return it }
        if (store.exists(name, _recordings.value)) return "같은 이름의 녹음이 이미 있어요"
        stopPlayback()
        runStoreOp(retryAfterConsent = true, done = { _selected.value = name }) { store.rename(rec, name) }
        return null
    }

    fun delete(rec: Recording) {
        stopPlayback()
        // Android 11+ 삭제 요청은 승인 시 시스템이 직접 지우므로 재시도하지 않는다
        runStoreOp(retryAfterConsent = android.os.Build.VERSION.SDK_INT < 30, done = { _selected.value = null }) {
            store.delete(rec)
        }
    }

    private fun runStoreOp(retryAfterConsent: Boolean, done: () -> Unit, op: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { op() }
                done()
            } catch (e: NeedsConsentException) {
                _consent.value = ConsentRequest(e.intentSender) {
                    if (retryAfterConsent) runStoreOp(false, done, op) else { done(); refresh() }
                }
            } catch (e: Exception) {
                _message.value = "실패했어요: ${e.message}"
            }
            refresh()
        }
    }

    /** 화면을 완전히 닫을 때(뒤로 가기 등). 피아노 연결은 프로세스 소유라 여기서 닫지 않는다. */
    override fun onCleared() {
        stopPlayback()
        // 화면 없이 녹음이 계속되지 않도록 저장하고 끝낸다 (stop은 블로킹이라 메인 스레드 밖에서)
        if (recorder.state.value is Recorder.State.Recording) Thread { runCatching { recorder.stop() } }.start()
    }
}
