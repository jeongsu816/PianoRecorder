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

### [미확인] — 다음 단계에서 가장 먼저 확인할 것
1. USB 연결 시 FP-30X가 **MIDI 장치로 보이는지** (`MidiManager.getDevices()`)
2. **오디오 녹음(AudioRecord)과 MIDI 수신을 동시에** 할 수 있는지 (복합 USB 장치로 동작하는지)
3. USB 오디오 입력이 지원하는 샘플레이트 / 채널 수 (스테레오 가능 여부)
4. 피아노가 USB 오디오 입력(폰→피아노)을 USB 오디오 출력(피아노→폰)으로 되돌려 보내는지 (루프 가능성)
5. Roland USB 벤더 ID가 0x0582 인지 [추측]
6. FP-30X의 USB 드라이버 관련 설정(Generic/Vendor 등)이 인식에 영향을 주는지 [미확인]

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

### Step 1. 확인용 최소 앱 (Probe) — 지금 할 일
본 앱의 첫 단계가 되도록 작성.

- [ ] `MidiManager.getDevices()`로 장치 목록 로그 (이름, 벤더, 포트 수)
- [ ] 피아노 출력 포트에 `MidiReceiver` 연결 → 건반 이벤트 로그 (note on/off, velocity, CC64 페달)
- [ ] `AudioManager.getDevices(GET_DEVICES_INPUTS / OUTPUTS)`로 USB 장치의 샘플레이트·채널 수 로그
- [ ] `AudioRecord`를 USB 입력으로 켠 상태에서 MIDI 수신이 **동시에** 되는지 확인
- [ ] 피아노 입력 포트로 note on/off 몇 개 보내서 피아노가 소리를 내는지 확인 (MIDI 재생 경로)
- [ ] USB 벤더/제품 ID 확인 (디바이스 필터용)

성공 기준: MIDI 이벤트 수신 + 오디오 녹음 동시 동작, 피아노로 MIDI 송신 시 소리 남

### Step 2. MVP
- 녹음(오디오+MIDI) → MediaStore 저장 → 목록 표시 → 두 가지 재생 → 삭제/이름 변경

### Step 3. 부가 기능
- 공유, USB 연결 자동 실행, 자체 업데이트 모듈

### 결정이 필요한 항목
- 케이블 연결 상태에서 "스마트폰으로 재생"의 출력 경로 (폰 스피커 강제 vs 연결 상태 따름)
- 오디오 포맷 (m4a 비트레이트, 스테레오 여부 — Step 1 결과에 따라)
- UI 프레임워크 (Compose 권장, 사용자 결정)
