package app.pianorecorder

import android.app.Application
import app.pianorecorder.midi.PianoConnection
import app.pianorecorder.playback.MidiPlayer
import app.pianorecorder.playback.PhonePlayer
import app.pianorecorder.record.Recorder
import app.pianorecorder.storage.RecordingStore

/**
 * 하드웨어 자원(피아노 MIDI 포트, 녹음 세션)은 화면이 아니라 프로세스에 하나만 둔다.
 *
 * 처음에는 ViewModel이 PianoConnection을 만들었는데, 실기기에서 인스턴스가 두 개 살아 있는 상황이
 * 생겼다(2026-10-04 로그). 케이블을 다시 꽂자 둘이 같은 피아노를 열었고, MIDI 입력 포트는 한 곳만
 * 열 수 있어서 늦게 연 쪽은 송신 포트가 null → 화면은 "연결됨"인데 피아노 재생만 소리 없이 실패했다.
 */
class PianoApp : Application() {
    val piano by lazy { PianoConnection(this) }
    val store by lazy { RecordingStore(this) }
    val recorder by lazy { Recorder(this, piano, store) }
    val midiPlayer by lazy { MidiPlayer(piano) }
    val phonePlayer by lazy { PhonePlayer(this) }
}
