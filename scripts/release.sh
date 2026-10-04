#!/usr/bin/env bash
# 릴리스 APK 빌드. 서명 비밀번호는 Vaultwarden(Bitwarden CLI)에서 꺼내 환경변수로만 넘긴다.
#
# 필요: bw 로그인 완료(bw config server / bw login), 키스토어 파일
#   Vaultwarden 항목 "PianoRecorder release keystore": 사용자 이름 = 키 alias, 비밀번호 = 키스토어 비밀번호
set -euo pipefail

ITEM="${PIANORECORDER_BW_ITEM:-PianoRecorder release keystore}"
KEYSTORE="${PIANORECORDER_KEYSTORE:-$HOME/.android-keys/pianorecorder-release.jks}"
BUILD_TOOLS="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0"

[[ -f "$KEYSTORE" ]] || { echo "키스토어가 없습니다: $KEYSTORE" >&2; exit 1; }

# 이미 잠금 해제된 셸이면 그 세션을 쓰고, 아니면 여기서 마스터 비밀번호를 물은 뒤 끝나면 다시 잠근다
lock_after=0
if [[ -z "${BW_SESSION:-}" ]] || ! bw unlock --check >/dev/null 2>&1; then
    BW_SESSION="$(bw unlock --raw)"
    export BW_SESSION
    lock_after=1
fi
trap '[[ $lock_after == 1 ]] && bw lock >/dev/null' EXIT

bw sync >/dev/null
export PIANORECORDER_KEYSTORE="$KEYSTORE"
PIANORECORDER_KEY_ALIAS="$(bw get username "$ITEM")"
PIANORECORDER_KEYSTORE_PASSWORD="$(bw get password "$ITEM")"
export PIANORECORDER_KEY_ALIAS PIANORECORDER_KEYSTORE_PASSWORD

cd "$(dirname "$0")/.."
# --no-daemon: 비밀번호가 담긴 환경변수가 빌드 후에도 상주하는 Gradle 데몬에 남지 않게
./gradlew --no-daemon -q assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
echo "── 서명 확인"
"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | grep -E "SHA-256|DN"
echo "── APK"
sha256sum "$APK"
