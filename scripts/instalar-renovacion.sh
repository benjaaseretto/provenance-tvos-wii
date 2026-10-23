#!/bin/zsh
# Programa renovar.sh todos los días a las 5:40 con un LaunchAgent de macOS.
set -euo pipefail
RAIZ=${0:A:h:h}
LABEL=local.provenance-tvos.renovar
PLIST=~/Library/LaunchAgents/$LABEL.plist
cat > $PLIST <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>/bin/zsh</string><string>$RAIZ/scripts/renovar.sh</string></array>
  <key>StartCalendarInterval</key>
  <dict><key>Hour</key><integer>5</integer><key>Minute</key><integer>40</integer></dict>
</dict>
</plist>
EOF
launchctl bootout gui/$(id -u) $PLIST 2>/dev/null || true
launchctl bootstrap gui/$(id -u) $PLIST
echo "renovación diaria programada ($PLIST)"
