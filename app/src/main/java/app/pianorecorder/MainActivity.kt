package app.pianorecorder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.pianorecorder.record.Recorder
import app.pianorecorder.ui.ConsentRequest
import app.pianorecorder.ui.MainViewModel
import app.pianorecorder.ui.RecordingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
    }

    /** 재설치 전에 만든 녹음도 목록에 보이려면 읽기 권한 필요 (내가 만든 파일은 권한 없이 보임) */
    private val requestRead = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.refresh()
    }

    private var pendingConsent: ConsentRequest? = null
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        val req = pendingConsent
        pendingConsent = null
        if (it.resultCode == Activity.RESULT_OK) req?.onGranted?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val readPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                             else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!granted(readPermission)) requestRead.launch(readPermission)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // 녹음 중 화면이 꺼지면 백그라운드 마이크 제한으로 녹음이 무음이 될 수 있어 화면을 켜 둔다
                    vm.recorder.state.collect { state ->
                        if (state is Recorder.State.Recording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                launch {
                    vm.consent.collect { req ->
                        if (req != null) {
                            pendingConsent = req
                            vm.consumeConsent()
                            consentLauncher.launch(IntentSenderRequest.Builder(req.intentSender).build())
                        }
                    }
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                RecordingsScreen(vm, onRecordClick = ::toggleRecord)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // 다른 앱에서 파일을 지웠거나 옮겼을 수 있음
    }

    private fun toggleRecord() {
        when {
            vm.recorder.state.value is Recorder.State.Recording -> vm.stopRecording()
            granted(Manifest.permission.RECORD_AUDIO) -> vm.startRecording()
            else -> requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
