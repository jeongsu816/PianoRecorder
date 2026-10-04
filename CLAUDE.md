# PianoRecorder (가칭) — Roland FP-30X 녹음 Android 앱

> 이 문서는 claude.ai 대화(2026-10-04)에서 정리한 내용을 Claude Code 세션으로 이어가기 위한 인계 문서입니다.
> 프로젝트 루트에 `CLAUDE.md`로 두면 Claude Code가 세션 시작 시 자동으로 읽습니다.

## 작업 방식 (사용자 선호)

- 코드보다 설계 이유, trade-off, 동작 원리 설명을 중시
- 확인된 사실과 추측을 명확히 구분할 것 (이 문서도 [확인] / [추측] / [미확인]으로 표시)
- 언어: Kotlin

---

## 1. 목적

집에 있는 Roland FP-30X 디지털 피아노를 아이(2명)가 각자 자기 스마트폰에 USB 케이블로 연결해서,
자기 연주를 **MIDI + 피아노 소리(오디오)** 로 녹음하고 자기 폰에서 들을 수 있게 하는 개인용 앱.

- 블루투스(Roland Piano App)는 아이가 둘이라 매번 연결 대상을 바꾸는 게 번거로움 → USB 케이블 방식 선택
- 케이블을 꽂는 행위 자체가 "누가 피아노를 쓰는지"를 정함

## 2. 하드웨어 / 환경

| 항목 | 내용 |
|---|---|
| 피아노 | Roland FP-30X. 뒷면에 USB Computer(B타입), USB Memory(A타입) 단자 |
| 케이블 | USB-C ↔ USB-B 프린터 케이블 (C 플러그에 Rd 내장 → 폰이 USB 호스트가 됨) |
| 아이 폰 | Android, Google Family Link 감독 계정 |
| 개발 | 개발자 폰에서 개발, 아이 폰에는 완성 APK만 설치 |

## 3. 확인된 사실 / 미확인 사항

### [확인] 2026-10-04 케이블 테스트 결과
- USB 연결 상태에서 **삼성 기본 음성 녹음 앱으로 녹음 시 피아노 소리가 그대로 녹음됨**
  → FP-30X의 USB 오디오 입력(UAC)이 안드로이드 표준 오디오 스택으로 동작. 자체 USB 드라이버 불필요
- USB 연결 상태에서 재생하면 **피아노 스피커로 소리가 나옴**
  → USB 오디오 출력도 동작. 안드로이드가 USB 오디오 장치 연결 시 기본 출력 경로를 USB로 전환
- USB 해제 후 재생하면 폰 스피커로 나옴 (정상 경로 복귀)

### [확인] 기존 동작
- Roland Piano App(블루투스)으로 녹음한 것은 MIDI. 재생 시 폰이 아니라 피아노에서 소리가 남
- 피아노 내부 레코더는 1곡만 저장(새 녹음 시 덮어씀), USB 메모리에는 SMF로 최대 100곡

### [확인] 2026-10-04 Probe 앱 테스트 결과 (SM-F971N, Android 17)
- **MIDI 장치로 인식됨**: `[USB] 'Roland Roland Digital Piano'`, 출력 포트 1 / 입력 포트 1 (포트 이름은 빈 문자열)
- **USB VID=0x0582, PID=0x01B1** → Roland 벤더 ID 추측이 맞음. 디바이스 필터에 사용
- USB 인터페이스: Audio Control 1 + Audio Streaming 2(입·출력) + MIDI Streaming 1 → **오디오와 MIDI가 하나의 복합 USB 장치**
- USB 오디오 입·출력: **44100Hz 고정, 스테레오(ch=2)**, 장치 인코딩은 float(4) / 24bit packed(21)
  - 녹음한 WAV의 좌우 채널이 서로 다름 → 실제 스테레오
  - 연주하지 않을 때는 샘플이 정확히 0(디지털 무음) → 아날로그 잡음 없음
- `UNPROCESSED` 소스 미지원(false) → `VOICE_RECOGNITION`으로 녹음. 무음이 완전한 0이고 레벨 변화도 자연스러워서 별도 처리가 들어간 흔적은 없음 [추측]
- `setPreferredDevice(USB)` 후 `routedDevice`가 USB 입력으로 확정됨
- **오디오 녹음과 MIDI 수신 동시 동작 확인** (8초 녹음 중 MIDI 16개 수신, 오디오 피크 같은 구간에서 상승)
- MIDI 수신 지연(onSend timestamp → 콜백 처리): 대부분 1ms 미만, 최대 약 4ms
- Note Off는 `0x80` + 릴리스 벨로시티로 옴 (0x90 vel0 방식 아님). Active Sensing·Clock은 오지 않음 (Roland는 보낼 것이라던 추측이 틀림)
- **루프 없음**: 녹음 중 USB 출력으로 1kHz 사인파를 보냈는데 녹음은 계속 완전한 0. 피아노가 폰→피아노 오디오를 폰으로 되돌려 보내지 않음
- 녹음 레벨: 중간 세기(vel 27~64)로 쳤을 때 피크 -4 ~ -10dBFS, 클리핑 없음
- 피아노에 케이블을 꽂아도 무선 디버깅 연결이 유지됨
- 케이블을 뺐다 꽂으면 MIDI 장치 id가 바뀜(2 → 3) → id를 저장해 두지 말고 매번 장치 목록에서 찾아야 함
- 케이블을 다시 꽂아도 권한 창이 뜨지 않음. MIDI는 `MidiManager`(시스템 서비스)를 거치므로 앱이 USB 권한을 직접 받을 필요가 없음.
  자동 실행(5.5)용 `USB_DEVICE_ATTACHED` 필터를 넣으면 그때부터 "이 앱으로 열기" 창이 뜰 것 [추측]
- **피아노로 MIDI 송신 시 소리 남** (테스트음 도-미-솔-도 + 화음, 사용자 확인) → "피아노로 재생" 경로 확인
- **루프 테스트 사인파가 피아노 스피커에서 들림** (사용자 확인) → USB 출력은 동작하는데 녹음은 0 → "루프 없음" 확정
- 페달 3개 모두 수신됨 (ch1)
  - CC64 서스테인: **연속값(0~127, 하프 페달)**. 밟고 뗄 때 약 8ms 간격으로 6~7개씩 옴
  - CC66 소스테누토: 0 / 127 두 값만
  - CC67 소프트: 연속값(0~127)
  - SMF 기록 시 CC를 걸러내거나 on/off로 줄이지 말고 받은 그대로 기록해야 하프 페달 표현이 유지됨
- 피아노에서 음색을 바꾸면 **CC0(Bank MSB) → CC32(Bank LSB) → Program Change**가 한 묶음으로 옴 (ch1)
  - 예: 기본 피아노 0/68/0, 다른 음색 8/71/4, 1/67/49
  - CC7(볼륨)이 ch1·ch3으로 오기도 함 → 듀얼/스플릿의 두 번째 음색이 ch3인 듯 [추측]
  - 녹음 시작 전에 고른 음색은 녹음에 안 들어감 → 앱이 마지막으로 본 음색 설정을 기억했다가 녹음 맨 앞(0ms)에 넣음.
    앱을 켜기 전에 고른 음색은 알 수 없음 (SysEx로 현재 음색을 물어볼 수 있는지는 [미확인])

### [발견] 오디오와 MIDI의 시간 맞춤
- 녹음 시작 시각을 기준점으로 잡으면 첫 음이 **오디오에서 약 0.1초 먼저** 나타남 (MIDI 2.63초 vs 오디오 2.53초)
- 원인 [추측]: `startRecording()` 호출 시각과 오디오 첫 프레임이 실제로 녹음된 시각이 다름 (오디오 경로의 시작 지연)
- 대응: Step 2에서는 `AudioRecord.getTimestamp()`로 "프레임 위치 ↔ nanoTime"을 맞추고, MIDI 이벤트 시각을 그 기준으로 변환

### [미확인] — 남은 것
1. 피아노 마스터 볼륨이 USB 녹음 레벨에 영향을 주는지 (세게 쳤을 때 클리핑 여부)
2. FP-30X의 USB 드라이버 설정(Generic/Vendor 등)이 인식에 영향을 주는지 (현재 설정 그대로 인식은 잘 됨)

---

## 4. 요구사항

1. 실행하면 녹음 목록, 하단에 녹음 버튼
2. 녹음 버튼 → MIDI와 피아노 소리를 함께 녹음, 버튼은 중지 버튼으로 바뀜
3. 녹음 선택 시 재생 버튼 2개: **피아노로 재생**, **스마트폰으로 재생**
4. 녹음 삭제
5. 기본 이름은 날짜·시간, 이름 변경 가능
6. 저장은 Scoped Storage 공용 저장소(MediaStore) — 메신저에서 바로 첨부 가능하게
7. (추가) 앱 내 공유 버튼
8. (추가) 앱 자체 업데이트 기능 (NAS에서 새 APK 받아 설치)

---

## 5. 설계 결정과 이유

### 5.1 저장 구조
- 위치: MediaStore 공용 컬렉션, 상대 경로 `Music/PianoRecorder/`
  - 이유: 앱을 지워도 녹음 유지, 카톡 등 다른 앱의 파일 선택기에 바로 보임
  - 앱 전용 폴더(`getExternalFilesDir`)는 앱 삭제 시 같이 지워지고 공유가 불편해 제외
- 녹음 1개 = 파일 2개 한 쌍: `YYYY-MM-DD_HH-mm-ss.mid` + `YYYY-MM-DD_HH-mm-ss.m4a`
- **파일명을 표시 이름으로 사용 (DB 없음)** — 파일이 곧 source of truth, 동기화 문제 없음
  - 이름 변경·삭제는 항상 두 파일을 함께 처리
  - 파일명에 쓸 수 없는 문자(`/` 등) 입력 차단
- 녹음 중에는 `IS_PENDING = 1`로 만들고 완료 후 `0` → 쓰는 중인 파일이 다른 앱에 노출되지 않음
- [미확인] `.mid`(`audio/midi`)가 `MediaStore.Audio` 컬렉션에 들어가는지. 안 되면 `MediaStore.Files`/Downloads 사용 검토
- 재설치 후에는 이전 파일 소유권이 끊김 → 수정/삭제 시 `createWriteRequest` / `createDeleteRequest`로 사용자 확인 필요

### 5.2 녹음
- 오디오: `AudioRecord`
  - 입력 장치를 USB(`AudioDeviceInfo`, `TYPE_USB_DEVICE`)로 `setPreferredDevice()` 명시
  - 가능하면 `MediaRecorder.AudioSource.UNPROCESSED` 사용 (음성 녹음 앱식 자동 음량/잡음 제거 회피)
  - 저장은 m4a(AAC, `MediaCodec` + `MediaMuxer`). 초기에는 WAV로 시작해도 무방
- MIDI: `MidiManager`로 장치 열기 → 피아노의 출력 포트(`MidiOutputPort`)에 `MidiReceiver` 연결
  - `onSend`의 timestamp(nanoTime 기준)로 이벤트 시각 기록
  - 오디오와 같은 시작 시각 기준으로 맞춤
- MIDI 파일: **SMF Format 0 직접 작성** (헤더 청크 + 트랙 청크 + VLQ 델타타임). 라이브러리 없이 가능한 규모
- 녹음 중 주의
  - 받은 MIDI를 피아노로 되돌려 보내지 않음 (같은 음이 두 번 남)
  - 녹음 중 앱이 소리를 내지 않음 (USB 출력 → 피아노 → 녹음에 섞일 수 있음)

### 5.3 재생
- **피아노로 재생 (MIDI)**: 저장된 이벤트를 피아노의 입력 포트(`MidiInputPort`)로 원래 간격대로 전송
  - 안드로이드 MIDI API에 공개 스케줄러가 없음 [추측] → 재생 스레드에서 `System.nanoTime()` 기준으로 직접 타이밍 맞춤
  - 중지 시 각 채널에 All Notes Off(CC123)와 서스테인 해제(CC64 = 0) 전송 (음이 계속 울리는 문제 방지)
- **스마트폰으로 재생 (오디오)**: m4a 재생
  - 케이블이 꽂혀 있으면 기본적으로 피아노 스피커로 나감 [확인]
  - 폰 스피커로 강제하려면 `TYPE_BUILTIN_SPEAKER`를 `setPreferredDevice()`로 지정
  - **결정 필요**: 케이블 연결 시에도 폰 스피커로 강제할지, 연결 상태를 따를지
- 오디오 파일이 없을 때(오디오 녹음 실패 등) 대체: MIDI를 폰 내장 신디사이저로 재생 (음색은 단순한 GM 피아노)

### 5.4 공유
- MediaStore content URI를 `ACTION_SEND`에 넣고 `FLAG_GRANT_READ_URI_PERMISSION` → FileProvider 불필요
- 기본은 m4a 공유, MIDI는 옵션
- [미확인] 메신저별로 음성 메시지처럼 보내지는지, 파일로 보내지는지

### 5.5 USB 연결 자동 실행
- 매니페스트에 `USB_DEVICE_ATTACHED` 인텐트 필터 + 디바이스 필터(Roland 벤더 ID)
- 첫 연결 시 "이 장치에 항상 이 앱 사용" 체크하면 이후 권한 창 생략

### 5.6 자체 업데이트 모듈
- 배포 방식 비교 결과 자체 업데이트로 결정
  - F-Droid 개인 저장소: 클라이언트에 공식 저장소가 기본 포함되어 아이가 다른 앱도 설치 가능 → 제외
  - 자체 업데이트: 이 앱 하나만 설치 권한 보유, 개발자의 APK만 설치 가능 → 범위가 가장 좁음
- 구조
  - NAS에 `version.json` (`versionCode`, APK URL, SHA-256, 변경 내용) + APK
  - 앱 시작 시 확인 → "업데이트 있음" 표시 → 사용자가 눌러서 설치
  - **녹음/재생 중에는 절대 설치하지 않음**
  - `PackageInstaller` Session API: 세션 생성 → APK 쓰기 → `commit` → `STATUS_PENDING_USER_ACTION`이면 확인 창 인텐트 실행
  - 매니페스트에 `REQUEST_INSTALL_PACKAGES`, 아이 폰에서 이 앱만 "출처를 알 수 없는 앱 설치" 허용
- 신뢰 구조
  - 핵심 검증은 안드로이드가 수행: 같은 서명 키가 아니면 업데이트 거부, versionCode 다운그레이드 차단
  - SHA-256은 다운로드 손상 확인용
- [추측] Android 12+ `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`는 설치 주체(installer of record)가 자신일 때 가능.
  첫 설치를 ADB로 하면 첫 자체 업데이트는 확인 창이 뜨고, 이후 조용한 업데이트가 될 가능성 → 실제 확인 필요
- NAS 접근: 집 Wi-Fi 전용이면 내부망 주소로 충분. 외부에서도 받으려면 HTTPS 리버스 프록시 뒤에 둠

### 5.7 서명 / 설치 / 디버깅
- **릴리스 키스토어를 하나 만들어 고정**하고 NAS에 백업. 디버그 키는 PC마다 달라 업데이트 시 서명 불일치 발생
- 아이 폰 첫 설치: Family Link 부모 앱 → 아이 프로필 → 로그인된 기기 → 개발자 옵션 허용 → USB 디버깅 → `adb install` → **설치 후 개발자 옵션 다시 끄기**
- 개발 중 디버깅은 **무선 디버깅** 사용: 폰의 USB 단자를 피아노(호스트 모드)에 써야 해서 USB 디버깅(디바이스 모드)과 동시 사용 불가

### 5.8 향후 고려: Android 개발자 인증
- Google이 Play 밖 설치 앱에도 인증된 개발자 등록을 요구하는 정책 시행 중
  - 2026-09-30: 브라질, 인도네시아, 싱가포르, 태국 적용
  - 2027: 전 세계 인증 기기로 확대 예정
- 자체 업데이트도 Play 밖 설치라 영향 예상 [추측]
- 취미·학생용 제한 배포 계정(최대 20대, 신분증·등록비 없음)이 계획되어 있음 → 2027 전 패키지 이름 등록 권장
- ADB 설치는 예외로 남아 있음 (기사 기준)

---

## 6. 다음 단계

### Step 1. 확인용 최소 앱 (Probe) — ✅ 완료 (2026-10-04)
본 앱의 첫 단계가 되도록 작성.

- [x] `MidiManager.getDevices()`로 장치 목록 로그 (이름, 벤더, 포트 수)
- [x] 피아노 출력 포트에 `MidiReceiver` 연결 → 건반 이벤트 로그 (note on/off, velocity, CC64 페달)
- [x] `AudioManager.getDevices(GET_DEVICES_INPUTS / OUTPUTS)`로 USB 장치의 샘플레이트·채널 수 로그
- [x] `AudioRecord`를 USB 입력으로 켠 상태에서 MIDI 수신이 **동시에** 되는지 확인
- [x] 피아노 입력 포트로 note on/off 몇 개 보내서 피아노가 소리를 내는지 확인 (MIDI 재생 경로)
- [x] USB 벤더/제품 ID 확인 (디바이스 필터용)

성공 기준: MIDI 이벤트 수신 + 오디오 녹음 동시 동작, 피아노로 MIDI 송신 시 소리 남 → **모두 충족**

### Step 2. MVP — 구현 완료, 실기기 테스트 중 (2026-10-04)
- 녹음(오디오+MIDI) → MediaStore 저장 → 목록 표시 → 두 가지 재생 → 삭제/이름 변경
- 구조: `midi/`(MidiParser, Smf, PianoConnection) · `record/Recorder` · `storage/RecordingStore` ·
  `playback/`(MidiPlayer, PhonePlayer) · `ui/`(MainViewModel, RecordingsScreen)
- 결정: "스마트폰으로 재생"은 케이블이 꽂혀 있어도 **항상 폰 내장 스피커** (사용자 결정)
- 결정: m4a = AAC-LC 44.1kHz 스테레오 192kbps (1분 약 1.4MB)
- SMF: Format 0, 템포 120 + 분해능 500 → 1 tick = 1ms
- 녹음 중 화면 꺼짐 방지(FLAG_KEEP_SCREEN_ON). 전원 버튼으로 끄면 백그라운드 마이크 제한에 걸릴 수 있음
  → 필요하면 나중에 마이크 타입 포그라운드 서비스로 보강
- [확인] 2026-10-04 MVP 1차 테스트
  - `.mid`(`audio/midi`)가 `MediaStore.Audio` 컬렉션에 정상 저장됨 → Files/Downloads 대안 불필요
  - 녹음 → 저장 → 한글 이름 변경(두 파일 함께) → 삭제 동작
  - getTimestamp 보정량이 녹음마다 크게 다름(309ms, 117ms) → 호출 시각 기준으로는 못 맞춤, 보정이 필수
  - 보정 후 오디오가 MIDI보다 **62ms 늦음, 음 16개 모두 59~65ms**(고정 지연)
    - 약 23ms는 AAC 인코더 프라이밍 [추측]: MediaMuxer가 edit list(elst)를 쓰지 않아 디코더가 앞 여백까지 재생
    - 나머지 약 40ms는 피아노 내부(건반 → 음원 → USB 오디오) 지연 [추측]
    - 각 재생은 자기 파일만 쓰므로 지금은 영향 없음. 오버더빙 등 두 파일을 겹칠 때 상수로 보정
- 확인할 것: `setPreferredDevice(BUILTIN_SPEAKER)`가 USB 연결 중에도 지켜지는지 (`routedDevice`는 start 직후 null이라 로그로 판단 불가)

### Step 3. 부가 기능
- 공유, USB 연결 자동 실행, 자체 업데이트 모듈

### 향후 아이디어
- **겹쳐 녹음(오버더빙)**: 1트랙을 피아노로 재생하면서 2트랙을 다른 음색으로 녹음.
  MIDI 송수신 동시 동작은 [확인]. 오디오는 피아노가 섞어서 보내므로 합쳐진 소리가 녹음됨.
  트랙별 채널/Program Change 분리 필요. FP-30X의 멀티팀브럴(GM2 16채널) 지원은 [추측]

### 결정이 필요한 항목
- ~~"스마트폰으로 재생"의 출력 경로~~ → 항상 폰 스피커로 결정
- ~~오디오 포맷~~ → 44.1kHz 스테레오 AAC 192kbps
- ~~UI 프레임워크~~ → Jetpack Compose로 결정. 패키지 이름 `app.pianorecorder`, minSdk 29
