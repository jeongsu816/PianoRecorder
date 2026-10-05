package app.pianorecorder.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 앱 설정. 지금은 "새 녹음을 저장할 위치" 하나뿐. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _storageVolume = MutableStateFlow(prefs.getString(KEY_VOLUME, null) ?: Volumes.PRIMARY)
    /** 선택한 MediaStore 볼륨 이름. 그 SD 카드가 빠져 있으면 녹음 시 내장 메모리로 대신 저장한다. */
    val storageVolume: StateFlow<String> = _storageVolume

    fun setStorageVolume(name: String) {
        prefs.edit().putString(KEY_VOLUME, name).apply()
        _storageVolume.value = name
    }

    private companion object {
        const val KEY_VOLUME = "storage_volume"
    }
}
