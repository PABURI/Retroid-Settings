#!/system/bin/sh
# Root helpers for the controller (run by the daemon or over adb root):
#   hs_tools.sh layout xbox|nintendo
#   hs_tools.sh stock_led         put the stick LEDs back to the stock lighting setting
#   hs_tools.sh calib_stock       print the stock settings app's calibration
# The stock settings app's preferences are updated too so it keeps the same choice after reboot.

AYN=org.lineageos.settings.ayn
PREF=shared_prefs/org.lineageos.settings.ayn_preferences.xml
F=""
for d in /data/user_de/0/$AYN /data/data/$AYN; do [ -f "$d/$PREF" ] && F="$d/$PREF"; done
setpref() { [ -n "$F" ] && sed -i "s#$1#$2#" "$F"; }

case "$1" in
  layout)
    echo "$2" > /sys/class/moorechip-joystick/joystick/layout
    if [ "$2" = "xbox" ]; then o=nintendo; else o=xbox; fi
    setpref "gamepad_layout\">$o<" "gamepad_layout\">$2<"
    am force-stop $AYN
    ;;
  stock_led)
    set -- $(sed -n 's/.*global_lighting">\([0-9]*\):\([0-9]*\):\([0-9]*\):\([0-9]*\)<.*/\1 \2 \3 \4/p' "$F" 2>/dev/null)
    b=${1:-0}; grep -q 'rgb_lighting_enable" value="true"' "$F" 2>/dev/null || b=0
    for l in /sys/class/leds/*stick*; do echo "${2:-255} ${3:-255} ${4:-255}" > "$l/multi_intensity"; echo "$b" > "$l/brightness"; done
    ;;
  calib_stock)
    sed -n 's/.*joystick_calibration">\([^<]*\)<.*/\1/p' "$F" 2>/dev/null
    ;;
esac
