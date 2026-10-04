package app.pianorecorder.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.pianorecorder.storage.Recording

enum class ShareKind(val label: String) { AUDIO("소리 (m4a)"), MIDI("MIDI (mid)"), BOTH("둘 다") }

/**
 * MediaStore content URI를 그대로 넘긴다. 내 앱이 만든 MediaStore 항목이라 FileProvider 없이
 * FLAG_GRANT_READ_URI_PERMISSION만으로 받는 앱(카톡 등)이 읽을 수 있다.
 * ACTION_SEND(_MULTIPLE)의 EXTRA_STREAM은 시스템이 ClipData로 옮겨 주므로 권한 부여가 함께 전달된다.
 */
fun share(context: Context, rec: Recording, kind: ShareKind) {
    val (uris, mime) = when (kind) {
        ShareKind.AUDIO -> listOfNotNull(rec.audioUri) to "audio/mp4"
        ShareKind.MIDI -> listOfNotNull(rec.midiUri) to "audio/midi"
        ShareKind.BOTH -> rec.uris to "audio/*"
    }
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
    }
    intent.type = mime
    intent.putExtra(Intent.EXTRA_TITLE, rec.name)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "${rec.name} 공유"))
}

/** 보낼 수 있는 종류. 한 가지뿐이면 고르는 창 없이 바로 보낸다. */
fun shareKinds(rec: Recording): List<ShareKind> = when {
    rec.audioUri != null && rec.midiUri != null -> ShareKind.entries
    rec.audioUri != null -> listOf(ShareKind.AUDIO)
    else -> listOf(ShareKind.MIDI)
}
