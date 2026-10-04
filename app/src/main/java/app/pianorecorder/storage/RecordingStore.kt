package app.pianorecorder.storage

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 녹음 1개 = 같은 이름의 .m4a + .mid 한 쌍. DB 없이 MediaStore의 파일명이 곧 표시 이름이다.
 * 둘 중 하나만 있어도 목록에 나온다(예: 오디오 장치가 없어 MIDI만 녹음된 경우).
 */
data class Recording(
    val name: String,
    val audioUri: Uri?,
    val midiUri: Uri?,
    val durationMs: Long,
    val dateAddedSec: Long,
) {
    val uris get() = listOfNotNull(audioUri, midiUri)
}

/** 다른 설치본이 만든 파일(재설치 후)을 고치거나 지울 때 사용자 확인이 필요하다는 신호 */
class NeedsConsentException(val intentSender: IntentSender) : Exception()

class RecordingStore(context: Context) {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun list(): List<Recording> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        class Row(val uri: Uri, val base: String, val ext: String, val duration: Long, val added: Long)
        val rows = ArrayList<Row>()
        resolver.query(
            collection, projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(RELATIVE_PATH), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val dot = name.lastIndexOf('.')
                if (dot <= 0) continue
                rows += Row(
                    ContentUris.withAppendedId(collection, c.getLong(0)),
                    name.substring(0, dot), name.substring(dot + 1).lowercase(),
                    c.getLong(2), c.getLong(3),
                )
            }
        }
        return rows.groupBy { it.base }.map { (base, group) ->
            val audio = group.firstOrNull { it.ext == AUDIO_EXT }
            val midi = group.firstOrNull { it.ext == MIDI_EXT }
            Recording(base, audio?.uri, midi?.uri, audio?.duration ?: 0, group.minOf { it.added })
        }.filter { it.audioUri != null || it.midiUri != null }
            .sortedByDescending { it.dateAddedSec }
    }

    /** 쓰는 동안 다른 앱(파일 선택기 등)에 보이지 않도록 IS_PENDING=1로 만든다. */
    fun createPending(name: String, ext: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, if (ext == AUDIO_EXT) "audio/mp4" else "audio/midi")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values) ?: error("MediaStore insert 실패: $name.$ext")
    }

    fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun discard(uri: Uri) {
        resolver.delete(uri, null, null)
    }

    fun exists(name: String, current: List<Recording>) = current.any { it.name.equals(name, ignoreCase = true) }

    /** 두 파일의 이름을 함께 바꾼다. 내 파일이 아니면 [NeedsConsentException] — 동의 후 다시 호출. */
    fun rename(rec: Recording, newName: String) = withConsent(rec.uris, write = true) {
        rec.audioUri?.let { rename(it, "$newName.$AUDIO_EXT") }
        rec.midiUri?.let { rename(it, "$newName.$MIDI_EXT") }
    }

    private fun rename(uri: Uri, displayName: String) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, displayName) }, null, null)
    }

    /**
     * 두 파일을 함께 지운다. 내 파일이 아니면 [NeedsConsentException].
     * Android 11+의 삭제 요청은 사용자가 승인하면 시스템이 직접 지우므로 재호출이 필요 없다.
     */
    fun delete(rec: Recording) = withConsent(rec.uris, write = false) {
        rec.uris.forEach { resolver.delete(it, null, null) }
    }

    private fun withConsent(uris: List<Uri>, write: Boolean, block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            val sender = when {
                Build.VERSION.SDK_INT >= 30 ->
                    if (write) MediaStore.createWriteRequest(resolver, uris).intentSender
                    else MediaStore.createDeleteRequest(resolver, uris).intentSender
                e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> throw e
            }
            throw NeedsConsentException(sender)
        }
    }

    companion object {
        const val RELATIVE_PATH = "Music/PianoRecorder/"
        const val AUDIO_EXT = "m4a"
        const val MIDI_EXT = "mid"
        /** 파일명에 쓸 수 없거나 문제를 일으키는 문자 */
        private val INVALID = Regex("""[/\\:*?"<>|\u0000-\u001F]""")

        /** 이름 변경 입력 검증. 문제가 없으면 null, 있으면 사용자에게 보여 줄 이유 */
        fun validateName(name: String): String? = when {
            name.isBlank() -> "이름을 입력해 주세요"
            INVALID.containsMatchIn(name) -> "다음 문자는 쓸 수 없어요:  / \\ : * ? \" < > |"
            name.startsWith(".") -> "점(.)으로 시작할 수 없어요"
            name.length > 100 -> "이름이 너무 길어요"
            else -> null
        }
    }
}
