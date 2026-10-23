#!/bin/zsh
# Re-firma Provenance (tvOS) con un perfil de Apple ID gratis y lo instala en el Apple TV.
# Uso: scripts/resign.sh <perfil.mobileprovision>
set -euo pipefail
source "${0:A:h}/config.sh"
cd $RAIZ

PROFILE=${1:?falta el .mobileprovision}
security cms -D -i "$PROFILE" > profile.plist
TEAM=$(/usr/libexec/PlistBuddy -c 'Print :TeamIdentifier:0' profile.plist)
APPID=$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' profile.plist)
BUNDLE=${APPID#$TEAM.}
CERT_SHA=$(/usr/libexec/PlistBuddy -c 'Print :DeveloperCertificates:0' profile.plist | openssl x509 -inform DER -noout -fingerprint -sha1 | cut -d= -f2 | tr -d :)

# Fuera de ~/Documents: iCloud vuelve a poner atributos extendidos y codesign los rechaza.
WORK=~/.atv-work
rm -rf "$WORK" && mkdir -p "$WORK" && unzip -q "$PROVENANCE_IPA" -d "$WORK"
APP=$WORK/Payload/Provenance.app
xattr -cr "$APP"
find "$APP" -name '._*' -delete
rm -rf "$APP/PlugIns" "$APP/_CodeSignature" "$APP/embedded.mobileprovision"
cp "$PROFILE" "$APP/embedded.mobileprovision"
/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier $BUNDLE" "$APP/Info.plist"

patch/build.sh
cp patch/libckstub.dylib "$APP/Frameworks/"
python3 patch/solo_wii.py "$APP"
python3 patch/control_remoto.py "$APP"
python3 patch/add_dylib.py "$APP/Provenance" @executable_path/Frameworks/libckstub.dylib

cat > entitlements.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>application-identifier</key><string>$APPID</string>
  <key>com.apple.developer.team-identifier</key><string>$TEAM</string>
  <key>get-task-allow</key><true/>
  <key>keychain-access-groups</key><array><string>$APPID</string></array>
</dict></plist>
EOF

find "$APP/Frameworks" -depth \( -name '*.framework' -o -name '*.dylib' \) -print0 |
  xargs -0 -n 20 codesign -f -s "$CERT_SHA" --timestamp=none
codesign -f -s "$CERT_SHA" --timestamp=none --entitlements entitlements.plist "$APP"
codesign -v --deep "$APP" && echo "firma OK"

xcrun devicectl device install app --device $ATV_UDID "$APP" > /dev/null
echo "Provenance instalado"
