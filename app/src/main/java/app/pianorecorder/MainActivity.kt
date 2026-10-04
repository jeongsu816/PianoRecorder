package app.pianorecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pianorecorder.probe.AudioProbe
import app.pianorecorder.probe.MidiProbe
import app.pianorecorder.probe.ProbeLog

/**
 * Step 1 Probe 화면. 버튼 순서가 곧 확인 순서:
 * 장치 스캔 → MIDI 열기(건반 이벤트 확인) → 녹음 시작(MIDI와 동시 동작 확인) → 루프 테스트 → 테스트음 송신
 */
class MainActivity : ComponentActivity() {
    private val log = ProbeLog()
    private lateinit var midi: MidiProbe
    private lateinit var audio: AudioProbe

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) audio.startRecording() else log.i("마이크 권한 거부됨 — 녹음 불가")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        midi = MidiProbe(this, log)
        audio = AudioProbe(this, log, midi)
        log.i("PianoRecorder Probe. 피아노를 USB로 연결한 뒤 '장치 스캔'부터 눌러 주세요")

        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    ProbeScreen(
                        log = log,
                        audio = audio,
                        onScan = { midi.scan(); audio.scan() },
                        onOpenMidi = { midi.open() },
                        onCloseMidi = { midi.close() },
                        onToggleRecord = ::toggleRecord,
                        onLoopTest = { audio.loopTest() },
                        onSendNotes = { midi.sendTestNotes() },
                    )
                }
            }
        }
    }

    private fun toggleRecord() {
        when {
            audio.isRecording.value -> audio.stopRecording()
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> audio.startRecording()
            else -> requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        audio.stopRecording()
        midi.release()
        super.onDestroy()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProbeScreen(
    log: ProbeLog,
    audio: AudioProbe,
    onScan: () -> Unit,
    onOpenMidi: () -> Unit,
    onCloseMidi: () -> Unit,
    onToggleRecord: () -> Unit,
    onLoopTest: () -> Unit,
    onSendNotes: () -> Unit,
) {
    val lines by log.lines.collectAsStateWithLifecycle()
    val recording by audio.isRecording.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = onScan) { Text("장치 스캔") }
            FilledTonalButton(onClick = onOpenMidi) { Text("MIDI 열기") }
            FilledTonalButton(onClick = onCloseMidi) { Text("MIDI 닫기") }
            Button(
                onClick = onToggleRecord,
                colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                         else ButtonDefaults.buttonColors(),
            ) { Text(if (recording) "■ 녹음 중지" else "● 녹음 시작") }
            FilledTonalButton(onClick = onLoopTest, enabled = recording) { Text("루프 테스트") }
            FilledTonalButton(onClick = onSendNotes, enabled = !recording) { Text("피아노로 테스트음") }
            FilledTonalButton(onClick = { log.clear() }) { Text("로그 지우기") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), state = listState) {
            items(lines) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}
