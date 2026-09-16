#!/usr/bin/env bash
#
# 构建可上架 AppGallery 的 App Pack（.app）。
#
# 为什么要绕过 hvigor 的签名任务：
#   hvigor 只接受 DevEco 加密后的密码密文（DecipherUtil.decryptPwd 强制要求长度 >= 32
#   并做 AES-128-GCM 解密），明文密码一律报 00303116。而那份密文依赖 DevEco 在本机
#   生成的 ~/.ohos/config/material/，无法在 CI 或别人机器上复现。
#   所以这里让 hvigor 只出**未签名**产物，再用官方 hap-sign-tool 依次签 HAP 和 App Pack。
#
# 签名材料放在仓库外（默认 ~/.ohos/release），密码单独放一个文件，不进 Git：
#   ~/.ohos/release/pwd                 单行密码（keystore 密码 == key 密码）
#   ~/.ohos/release/pokerogue.p12       密钥库
#   ~/.ohos/release/pokerogue-release.cer   发布证书链（PEM，含根/中间/叶子）
#   ~/.ohos/release/pokerogue-release.p7b   发布 Profile
#
# 用法：
#   scripts/build-release-app.sh
#   SIGN_DIR=/path/to/material scripts/build-release-app.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DEVECO_HOME="${DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents}"
export DEVECO_SDK_HOME="${DEVECO_SDK_HOME:-$DEVECO_HOME/sdk}"
export PATH="$DEVECO_HOME/tools/node/bin:$DEVECO_HOME/tools/ohpm/bin:$PATH"
HVIGOR="$DEVECO_HOME/tools/hvigor/bin/hvigorw"
SIGN_TOOL="$DEVECO_SDK_HOME/default/openharmony/toolchains/lib/hap-sign-tool.jar"

SIGN_DIR="${SIGN_DIR:-$HOME/.ohos/release}"
KEY_ALIAS="${KEY_ALIAS:-pokerogue}"
PWD_FILE="${PWD_FILE:-$SIGN_DIR/pwd}"
KEYSTORE="${KEYSTORE:-$SIGN_DIR/pokerogue.p12}"
APP_CERT="${APP_CERT:-$SIGN_DIR/pokerogue-release.cer}"
PROFILE="${PROFILE:-$SIGN_DIR/pokerogue-release.p7b}"
# 与 build-profile.json5 的 compatibleSdkVersion 保持一致
COMPATIBLE_VERSION="${COMPATIBLE_VERSION:-24}"

fail() { echo "ERROR: $*" >&2; exit 1; }

for f in "$PWD_FILE" "$KEYSTORE" "$APP_CERT" "$PROFILE"; do
  [ -f "$f" ] || fail "缺少签名材料: $f"
done
[ -x "$HVIGOR" ] || fail "找不到 hvigorw: $HVIGOR"
[ -f "$SIGN_TOOL" ] || fail "找不到 hap-sign-tool: $SIGN_TOOL"

KEY_PWD="$(cat "$PWD_FILE")"

OUT_DIR="$ROOT/build/outputs/default"
HAP_UNSIGNED="$ROOT/entry/build/default/outputs/default/entry-default-unsigned.hap"
APP_UNSIGNED="$OUT_DIR/harmony-pokerouge-default-unsigned.app"
[ -f "$APP_UNSIGNED" ] || APP_UNSIGNED="$(ls "$OUT_DIR"/*-unsigned.app 2>/dev/null | head -1)"
APP_NAME="$(basename "${APP_UNSIGNED%-unsigned.app}")"
APP_SIGNED="$OUT_DIR/$APP_NAME-release-signed.app"

echo "==> 1/5 hvigor 构建未签名 HAP + App Pack"
"$HVIGOR" assembleHap --mode module -p module=entry@default -p product=default \
  -p buildMode=release --no-daemon
"$HVIGOR" assembleApp --mode project -p product=default -p buildMode=release --no-daemon

[ -f "$HAP_UNSIGNED" ] || fail "未生成 $HAP_UNSIGNED"
[ -f "$APP_UNSIGNED" ] || fail "未生成 App Pack（$OUT_DIR）"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
HAP_SIGNED="$TMP/entry-default.hap"

echo "==> 2/5 用发布材料签 HAP"
java -jar "$SIGN_TOOL" sign-app \
  -mode localSign \
  -keyAlias "$KEY_ALIAS" \
  -keyPwd "$KEY_PWD" \
  -appCertFile "$APP_CERT" \
  -profileFile "$PROFILE" \
  -inFile "$HAP_UNSIGNED" \
  -signAlg SHA256withECDSA \
  -keystoreFile "$KEYSTORE" \
  -keystorePwd "$KEY_PWD" \
  -compatibleVersion "$COMPATIBLE_VERSION" \
  -outFile "$HAP_SIGNED"

echo "==> 3/5 把签名后的 HAP 换回 App Pack"
rm -rf "$TMP/app"
mkdir -p "$TMP/app"
unzip -q "$APP_UNSIGNED" -d "$TMP/app"
cp "$HAP_SIGNED" "$TMP/app/entry-default.hap"
( cd "$TMP/app" && zip -q -X -r "$TMP/repacked.app" . )

echo "==> 4/5 签 App Pack"
java -jar "$SIGN_TOOL" sign-app \
  -mode localSign \
  -keyAlias "$KEY_ALIAS" \
  -keyPwd "$KEY_PWD" \
  -appCertFile "$APP_CERT" \
  -profileFile "$PROFILE" \
  -inFile "$TMP/repacked.app" \
  -signAlg SHA256withECDSA \
  -keystoreFile "$KEYSTORE" \
  -keystorePwd "$KEY_PWD" \
  -inForm zip \
  -outFile "$APP_SIGNED"

echo "==> 5/5 校验产物"
java -jar "$SIGN_TOOL" verify-app \
  -inFile "$APP_SIGNED" \
  -outCertChain "$TMP/verify.cer" \
  -outProfile "$TMP/verify.p7b" >/dev/null

# 内层 HAP 也必须是发布签名，否则 AppGallery 会拒
rm -rf "$TMP/check"
mkdir -p "$TMP/check"
unzip -q "$APP_SIGNED" -d "$TMP/check"
java -jar "$SIGN_TOOL" verify-app \
  -inFile "$TMP/check/entry-default.hap" \
  -outCertChain "$TMP/hap.cer" \
  -outProfile "$TMP/hap.p7b" >/dev/null

openssl smime -verify -in "$TMP/hap.p7b" -inform DER -noverify 2>/dev/null \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
bi=d.get('bundle-info',{})
di=d.get('debug-info')
print('  内层 HAP  profile :', d.get('type'), '/', d.get('app-distribution-type'))
print('  bundle          :', bi.get('bundle-name'))
print('  app-identifier  :', bi.get('app-identifier'))
print('  device-ids      :', di.get('device-ids') if di else None)
"

echo
echo "OK -> $APP_SIGNED"
ls -l "$APP_SIGNED"
