package app.pianorecorder.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import app.pianorecorder.R
import app.pianorecorder.storage.Volume
import app.pianorecorder.storage.Volumes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import app.pianorecorder.record.Recorder
import app.pianorecorder.storage.Recording
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(vm: MainViewModel, onRecordClick: () -> Unit) {
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val connected by vm.piano.connected.collectAsStateWithLifecycle()
    val recState by vm.recorder.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val storageVolume by vm.storageVolume.collectAsStateWithLifecycle()
    val recording = recState is Recorder.State.Recording
    // 폰을 옆으로 눕혀 USB를 꽂으면 가로가 된다. 가로에서는 아래 녹음 바가 화면을 너무 가려서 오른쪽으로 옮긴다.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showSettings by remember { mutableStateOf(false) }

    var renaming by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    var sharing by remember { mutableStateOf<Recording?>(null) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scrollToTop by vm.scrollToTop.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTop) {
        if (scrollToTop > 0) listState.animateScrollToItem(0)
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("피아노 녹음") },
                actions = {
                    Text(
                        if (connected) "🎹 연결됨" else "🔌 연결 안 됨",
                        color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                    // 설정은 저장 위치 하나뿐이라, 고를 SD 카드가 없으면 버튼째 숨긴다 (슬롯 유무는 알 수 없어서 "지금 꽂힌 카드"로 판단).
                    // 단 SD로 설정해 둔 채 카드를 뺀 경우에는 보여서, 왜 내장 메모리에 저장되는지 알리고 되돌릴 수 있게 한다.
                    val showStorageSetting = volumes.any { it.removable } || storageVolume != Volumes.PRIMARY
                    if (showStorageSetting) {
                        IconButton(onClick = { showSettings = true }, enabled = !recording) { Text("⚙️", fontSize = 20.sp) }
                    } else {
                        Spacer(Modifier.width(16.dp))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!landscape) {
                Surface(tonalElevation = 3.dp) {
                    RecordControls(
                        recState, connected, onRecordClick,
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(vertical = 16.dp),
                    )
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (recordings.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "아직 녹음이 없어요\n피아노를 연결하고 녹음 버튼을 눌러 보세요",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(recordings, key = { it.id }) { rec ->
                            RecordingItem(
                                rec = rec,
                                expanded = rec.id == selected,
                                playing = playing?.takeIf { it.id == rec.id }?.target,
                                enabled = !recording,
                                pianoConnected = connected,
                                onClick = { vm.select(rec.id) },
                                onPlay = { vm.play(rec, it) },
                                onStop = vm::stopPlayback,
                                onRename = { renaming = rec },
                                onDelete = { deleting = rec },
                                onShare = {
                                    val kinds = shareKinds(rec)
                                    if (kinds.size == 1) share(context, rec, kinds[0]) else sharing = rec
                                },
                            )
                        }
                    }
                }
            }
            if (landscape) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxHeight()) {
                    RecordControls(
                        recState, connected, onRecordClick,
                        Modifier.fillMaxHeight().width(150.dp).padding(horizontal = 12.dp),
                        verticalCenter = true,
                    )
                }
            }
        }
    }

    if (showSettings) {
        StorageDialog(
            volumes = volumes,
            selected = storageVolume,
            onSelect = vm::setStorageVolume,
            onDismiss = { showSettings = false },
        )
    }
    renaming?.let { rec ->
        RenameDialog(rec, onDismiss = { renaming = null }) { newName ->
            vm.rename(rec, newName).also { if (it == null) renaming = null }
        }
    }
    sharing?.let { rec ->
        AlertDialog(
            onDismissRequest = { sharing = null },
            title = { Text("무엇을 보낼까요?") },
            text = {
                Column {
                    shareKinds(rec).forEach { kind ->
                        TextButton(onClick = { sharing = null; share(context, rec, kind) }, Modifier.fillMaxWidth()) {
                            Text(kind.label, Modifier.fillMaxWidth())
                        }
                    }
                    Text(
                        "보통은 소리(m4a)를 보내면 돼요. MIDI는 피아노나 음악 프로그램에서 열 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sharing = null }) { Text("취소") } },
        )
    }
    deleting?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("녹음 삭제") },
            text = { Text("'${rec.name}'을(를) 지울까요?\n오디오와 MIDI 파일이 함께 지워져요.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(rec); deleting = null }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

/** 경과 시간 + 녹음 버튼 + 안내 문구. 세로에서는 아래 바, 가로에서는 오른쪽 패널에 들어간다. */
@Composable
private fun RecordControls(
    state: Recorder.State,
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    verticalCenter: Boolean = false,
) {
    val recording = state is Recorder.State.Recording
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (verticalCenter) Arrangement.Center else Arrangement.Top,
    ) {
        if (state is Recorder.State.Recording) {
            Elapsed(state.startedAtElapsed)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = onClick,
            enabled = recording || connected,
            shape = CircleShape,
            modifier = Modifier.size(80.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Text(if (recording) "■" else "●", fontSize = 32.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                recording -> "누르면 녹음을 멈춰요"
                connected -> "누르면 녹음을 시작해요"
                else -> "피아노에 케이블을 연결해 주세요"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StorageDialog(volumes: List<Volume>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("저장 위치") },
        text = {
            Column {
                volumes.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(v.name) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = v.name == selected, onClick = { onSelect(v.name) })
                        if (v.removable) {
                            Icon(painterResource(R.drawable.ic_sd_card), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(v.label)
                    }
                }
                val note = when {
                    volumes.none { it.name == selected } -> "선택했던 SD 카드가 빠져 있어서 지금은 내장 메모리에 저장돼요."
                    volumes.none { it.removable } -> "microSD 카드가 없어요. 카드를 넣으면 여기에 나타나요."
                    else -> "새로 녹음하는 것부터 이 위치에 저장돼요. 이미 있는 녹음은 옮겨지지 않아요."
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun Elapsed(startedAt: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) { now = SystemClock.elapsedRealtime(); delay(200) }
    }
    Text(
        "● 녹음 중  ${formatDuration(now - startedAt)}",
        color = MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun RecordingItem(
    rec: Recording,
    expanded: Boolean,
    playing: Target?,
    enabled: Boolean,
    pianoConnected: Boolean,
    onClick: () -> Unit,
    onPlay: (Target) -> Unit,
    onStop: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = if (expanded) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                 else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rec.onRemovable) {
                    Icon(
                        painterResource(R.drawable.ic_sd_card), contentDescription = "SD 카드",
                        Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(rec.name, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                listOfNotNull(
                    SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(rec.dateAddedSec * 1000)),
                    rec.durationMs.takeIf { it > 0 }?.let(::formatDuration),
                    if (rec.audioUri == null) "MIDI만" else null,
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            AnimatedVisibility(expanded && enabled) {
                Column(Modifier.padding(top = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayButton("🎹 피아노로", playing == Target.PIANO,
                            enabled = pianoConnected && rec.midiUri != null,
                            Modifier.weight(1f), { onPlay(Target.PIANO) }, onStop)
                        PlayButton("📱 폰으로", playing == Target.PHONE,
                            enabled = true, Modifier.weight(1f), { onPlay(Target.PHONE) }, onStop)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onShare) { Text("공유") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onRename) { Text("이름 변경") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onDelete) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButton(
    label: String,
    isPlaying: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    if (isPlaying) {
        Button(onClick = onStop, modifier = modifier) { Text("■ 정지") }
    } else {
        FilledTonalButton(onClick = onPlay, enabled = enabled, modifier = modifier) { Text("▶ $label") }
    }
}

@Composable
private fun RenameDialog(rec: Recording, onDismiss: () -> Unit, onConfirm: (String) -> String?) {
    var text by remember { mutableStateOf(rec.name) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름 변경") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
        },
        confirmButton = { TextButton(onClick = { error = onConfirm(text) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
