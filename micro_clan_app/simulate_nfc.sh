#!/bin/bash
if [ -z "$1" ]; then
  echo "Usage: ./simulate_nfc.sh \"Type:Terreau;Origin:Sarcomusation;Lot:42\""
  echo "   or: ./simulate_nfc.sh app/src/main/assets/schema/product_terreau.json"
  exit 1
fi

if [ -f "$1" ]; then
  echo "Lecture du fichier de schema : $1"
  PAYLOAD=$(cat "$1" | tr -d '\n' | tr -d '\r')
else
  PAYLOAD=$1
fi

# Échapper les guillemets et les points-virgules pour le shell Android adb
ESCAPED_PAYLOAD=$(echo "$PAYLOAD" | sed 's/"/\\"/g' | sed 's/;/\\;/g')

adb shell "am broadcast -a be.heyman.android.etymoclan.SIMULATE_NFC --es payload \"$ESCAPED_PAYLOAD\""
