package app.pianorecorder.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore

/**
 * 저장 위치 하나 (내장 메모리 또는 microSD).
 * [name]은 MediaStore 볼륨 이름: 내장은 "external_primary", SD는 카드 UUID 소문자(예: "3a2b-1c0d").
 */
data class Volume(val name: String, val label: String, val removable: Boolean)

object Volumes {
    const val PRIMARY = MediaStore.VOLUME_EXTERNAL_PRIMARY

    /** 지금 마운트되어 쓸 수 있는 저장 위치. 내장 메모리가 맨 앞. */
    fun available(context: Context): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        val mediaStoreNames = MediaStore.getExternalVolumeNames(context)
        return sm.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { v ->
                val name = when {
                    v.isPrimary -> PRIMARY
                    Build.VERSION.SDK_INT >= 30 -> v.mediaStoreVolumeName
                    else -> v.uuid?.lowercase() // API 29: MediaStore 볼륨 이름 = UUID 소문자
                } ?: return@mapNotNull null
                if (name !in mediaStoreNames) return@mapNotNull null
                Volume(name, if (v.isPrimary) "내장 메모리" else v.getDescription(context), v.isRemovable)
            }
            .sortedBy { it.name != PRIMARY }
    }
}
