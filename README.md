# PianoRecorder

Roland FP-30X 디지털 피아노를 Android 폰에 USB 케이블로 연결해서, 연주를 **MIDI와 피아노 소리(오디오)로 함께** 녹음하고 듣는 앱입니다.

> An Android app that records a Roland FP-30X digital piano over a single USB cable — MIDI and stereo audio at the same time — and plays recordings back either through the piano (MIDI) or the phone speaker (audio). The UI is Korean only. Design notes and measurements are in [CLAUDE.md](CLAUDE.md) (Korean).

## 기능

- 녹음 버튼 하나로 **오디오(m4a)와 MIDI(mid)를 동시에** 녹음
- 두 가지 재생
  - **피아노로**: MIDI를 피아노로 보내 피아노가 연주를 그대로 다시 침 (음색 변경, 하프 페달 포함)
  - **폰으로**: 녹음된 소리를 폰 스피커로 재생 (케이블이 꽂혀 있어도 폰 스피커로)
- 이름 변경(날짜·시간이 기본 이름), 삭제, 공유(카톡 등으로 파일 전송)
- 저장 위치 선택: 내장 메모리 / microSD (SD에 있는 녹음은 아이콘 표시)
- 피아노를 꽂으면 앱 자동 실행
- 가로 화면에서는 녹음 버튼이 오른쪽으로 이동, 폰의 자동 회전 설정과 무관하게 회전

녹음은 공용 저장소 `Music/PianoRecorder/`에 같은 이름의 `.m4a` + `.mid` 한 쌍으로 저장됩니다. 앱을 지워도 남고, 다른 앱의 파일 선택기에서 바로 보입니다.

## 필요한 것

| 항목 | 내용 |
|---|---|
| 피아노 | Roland FP-30X (확인됨). USB로 오디오와 MIDI를 함께 내보내는 다른 Roland 피아노도 동작할 가능성이 있음. 자동 실행 필터는 FP-30X의 VID/PID(`0x0582`/`0x01B1`)라 다른 기종은 [usb_device_filter.xml](app/src/main/res/xml/usb_device_filter.xml) 수정 필요 |
| 폰 | Android 10(API 29) 이상. 갤럭시 S20(Android 13), 갤럭시 Z 폴드8(Android 17)에서 확인 |
| 케이블 | 피아노 뒷면 USB Computer(B) 단자 ↔ 폰 USB-C. 폰이 USB 호스트가 되는 케이블(일반 USB-C ↔ USB-B 프린터 케이블) |

## 동작 원리와 알아낸 것

자세한 설계 이유, 실험 과정, 측정값은 [CLAUDE.md](CLAUDE.md)에 있습니다. 다른 프로젝트에도 쓸모 있을 만한 것만 추리면:

- **FP-30X는 USB 복합 장치**: 오디오(44.1kHz 스테레오, 입·출력)와 MIDI가 케이블 하나로 동시에 동작합니다. Android 표준 `AudioRecord`와 `MidiManager`로 별도 드라이버 없이 쓸 수 있습니다.
- **오디오와 MIDI의 시간 맞춤**: `startRecording()` 호출 시각과 오디오 첫 프레임 시각의 차이가 녹음마다 100~300ms로 달라서, `AudioRecord.getTimestamp()`로 첫 프레임의 실제 시각을 역산해 MIDI를 맞춥니다. 맞춘 뒤에도 오디오가 MIDI보다 약 60ms 늦는데(AAC 인코더 여백 + 피아노 내부 지연으로 추정), 녹음마다 거의 일정합니다.
- **Android 13(갤럭시 S20)의 MIDI timestamp 버그**: `MidiReceiver.onSend`의 timestamp가 그 메시지가 아니라 **직전 메시지를 받은 시각**으로 옵니다. 그래서 화음이 ~100ms 어긋나 기록됐습니다. OS timestamp를 버리고 받은 순간의 `System.nanoTime()`을 씁니다.
- **USB 연결 중 "폰으로" 재생의 음량**: 미디어 음량은 출력 장치별로 따로 저장되고, USB가 연결돼 있으면 음량 버튼과 `adjustStreamVolume` 모두 USB 쪽 음량을 바꿉니다. 폰 스피커 음량이 0이면 케이블을 빼고 조절해야 합니다(앱에서 해결하지 않음).
- **MIDI 파일**: 라이브러리 없이 SMF Format 0을 직접 씁니다(1 tick = 1ms). 읽기는 Format 1, 템포 변경, running status까지 처리합니다.

## 빌드

```bash
./gradlew assembleDebug
```

디버그 빌드는 패키지 이름이 `app.pianorecorder.dev`, 앱 이름이 "PianoRecorder 개발"이라 릴리스 앱과 나란히 설치됩니다.

```bash
./gradlew testDebugUnitTest
```

### 릴리스 서명

서명 정보는 저장소에 두지 않고 환경변수로만 받습니다. 환경변수가 없으면 서명되지 않은 APK가 나옵니다.

| 환경변수 | 내용 |
|---|---|
| `PIANORECORDER_KEYSTORE` | 키스토어 파일 경로 |
| `PIANORECORDER_KEYSTORE_PASSWORD` | 키스토어 비밀번호 (PKCS12라 키 비밀번호도 같음) |
| `PIANORECORDER_KEY_ALIAS` | 키 alias (기본값 `pianorecorder`) |

[scripts/release.sh](scripts/release.sh)는 비밀번호를 Bitwarden/Vaultwarden CLI(`bw`)에서 꺼내 위 환경변수로 넘기고 빌드합니다. 다른 방식으로 비밀번호를 관리한다면 환경변수만 직접 설정해 `./gradlew assembleRelease`를 실행하면 됩니다.

## 구조

```
app/src/main/java/app/pianorecorder/
├── PianoApp.kt            하드웨어 자원(피아노 연결, 녹음기)을 프로세스에 하나만 두는 곳
├── MainActivity.kt
├── midi/                  MidiParser(바이트 → 메시지), Smf(파일 읽기·쓰기), PianoConnection(USB MIDI 연결)
├── record/Recorder.kt     오디오(AAC) + MIDI 동시 녹음, 시간 맞춤
├── playback/              MidiPlayer(피아노로), PhonePlayer(폰 스피커로)
├── storage/               RecordingStore(MediaStore), Volumes(내장/SD), Settings
└── ui/                    Compose 화면, ViewModel, 공유
```

## 라이선스

[MIT](LICENSE)

SD 카드 아이콘은 [Material Icons](https://github.com/google/material-design-icons)(Apache License 2.0)의 `sd_card`입니다.
