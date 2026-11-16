#!/system/bin/sh
# Retroid Settings control daemon. Runs as root (started once through adb root by the app) and keeps
# working with no app process, no adb link and no notification.
#
#   sh hs_daemon.sh <app files dir>
#
# The app talks to it through two files in its own files directory:
#   hs.conf  settings, re-read whenever the file changes:
#            MODE (AUTO|SMOOTH|MANUAL|TARGET) LEVEL TARGET SAFETY SMOOTH CPU_PCT GPU_PCT REASSERT
#            AUTOAPPS EXCLUDE SELF COMPAT
#   hs.cmd   one-shot root commands; picked up within 0.2 s, run, then deleted
# and the daemon touches hs.alive every 2 s so the app knows it is running.

DIR=${1:-/data/data/app.retroidsettings.lineage/files}
VER=${2:-0}                      # script hash from the app; written to hs.alive so the app skips needless restarts
CONF=$DIR/hs.conf
CMD=$DIR/hs.cmd
ALIVE=$DIR/hs.alive
STAMP=/data/local/tmp/hs_conf.stamp
TRIPS=/data/local/tmp/hs_fan_trips
KNOWN=/data/local/tmp/hs_known_apps
echo $$ > /data/local/tmp/hs_daemon.pid

# ---- defaults, then the app's settings ------------------------------------------------------
MODE=SMOOTH; LEVEL=4; TARGET=65; SAFETY=85; SMOOTH=3; CPU_PCT=100; GPU_PCT=100; REASSERT=0
AUTOAPPS=1; EXCLUDE=""; SELF=app.retroidsettings.lineage; COMPAT=""
SLEEPSAVE=1                      # screen off: fan off (our modes) and CPU/GPU capped at 50 %
BATLED=0                         # stick LEDs show the battery level: 0 off, 1 always, 2 while plugged in

# ---- hardware discovery ---------------------------------------------------------------------
FAN=""; FANMAX=8
for d in /sys/class/thermal/cooling_device*; do
  if [ "$(cat "$d/type" 2>/dev/null)" = "pwm-fan" ]; then FAN=$d/cur_state; FANMAX=$(cat "$d/max_state"); break; fi
done
CZ=""; GZ=""
for z in /sys/class/thermal/thermal_zone*; do
  case "$(cat "$z/type" 2>/dev/null)" in cpu-*|cpuss*) CZ="$CZ $z/temp" ;; gpuss*) GZ="$GZ $z/temp" ;; esac
done
POLS=""; for p in /sys/devices/system/cpu/cpufreq/policy*; do POLS="$POLS $p/scaling_max_freq"; done
GPU=/sys/class/kgsl/kgsl-3d0
BL=""; for b in /sys/class/backlight/*; do [ -f "$b/bl_power" ] && { BL=$b/bl_power; break; }; done   # 0 = screen on
sleeping=0
LEDS=""; for l in /sys/class/leds/*stick*; do [ -f "$l/multi_intensity" ] && LEDS="$LEDS $l"; done
BAT=/sys/class/power_supply/battery
AYNPREF=/data/user_de/0/org.lineageos.settings.ayn/shared_prefs/org.lineageos.settings.ayn_preferences.xml
ledlast=""

# Battery level on the stick LEDs, over the stock lighting setting while active.
# BATLED: 0 off, 1 always, 2 only while plugged in. The LED chip is powered down while the screen is off,
# so nothing is written then. When it stops, the stock lighting setting is put back.
ledown=0
battery_led() {
  [ -n "$LEDS" ] || return
  st=""; read -r st 2>/dev/null < "$BAT/status"
  want=0
  [ "$BATLED" = "1" ] && want=1
  if [ "$BATLED" = "2" ] && { [ "$st" = "Charging" ] || [ "$st" = "Full" ]; }; then want=1; fi
  if [ "$want" = "0" ]; then
    if [ "$ledown" = "1" ]; then ledown=0; ledlast=""; stock_led; fi
    return
  fi
  if [ "$off" = "1" ]; then ledlast=""; return; fi
  ledown=1
  cap=100; read -r cap 2>/dev/null < "$BAT/capacity"
  if [ "$cap" -lt 20 ]; then c="255 0 0"
  elif [ "$cap" -lt 50 ]; then c="255 70 0"
  elif [ "$cap" -lt 80 ]; then c="255 170 0"
  else c="0 255 0"; fi
  [ "$c" = "$ledlast" ] && return
  ledlast="$c"
  for l in $LEDS; do echo "$c" > "$l/multi_intensity"; echo "$LEDBR" > "$l/brightness"; done
}
# The stock lighting setting: rgb_lighting_enable and global_lighting = brightness:r:g:b.
stock_led() {
  set -- $(sed -n 's/.*global_lighting">\([0-9]*\):\([0-9]*\):\([0-9]*\):\([0-9]*\)<.*/\1 \2 \3 \4/p' "$AYNPREF" 2>/dev/null)
  sbr=${1:-0}
  grep -q 'rgb_lighting_enable" value="true"' "$AYNPREF" 2>/dev/null || sbr=0
  for l in $LEDS; do echo "${2:-255} ${3:-255} ${4:-255}" > "$l/multi_intensity"; echo "$sbr" > "$l/brightness"; done
}

# The app creates hs.watch while it shows readings; the daemon then writes hs.status
# ("cpu_mC gpu_mC fan cpu_max_kHz... gpu_max_Hz") so the app needs no adb shell for it.
# Reads use the shell's builtin read: no process per file.
# Files the app reads must carry the app's own label and owner (SELinux keeps root-made s0 files
# unreadable for apps); they are created once and then only rewritten in place.
CTX=$(ls -dZ "$DIR" | awk '{print $1}'); OWN=$(stat -c %u "$DIR")
for f in "$ALIVE" "$DIR/hs.status"; do
  rm -f "$f"; : > "$f"; chown "$OWN:$OWN" "$f"; chcon "$CTX" "$f"
done

status() {
  rm -f "$DIR/hs.watch"
  f=-1; [ -n "$FAN" ] && read -r f < "$FAN"
  s="$cmax $gmax $f"
  for q in $POLS; do v=0; read -r v < "$q"; s="$s $v"; done
  v=0; read -r v 2>/dev/null < "$GPU/max_gpuclk"
  echo "$s $v" > "$DIR/hs.status"
}

# CPU/GPU caps as path:value pairs from CPU_PCT / GPU_PCT, snapped to supported frequencies.
compute_limits() {
  LIMITS=""
  for p in /sys/devices/system/cpu/cpufreq/policy*; do
    max=$(cat "$p/cpuinfo_max_freq"); tgt=$((max * CPU_PCT / 100)); best=0; low=""
    for f in $(cat "$p/scaling_available_frequencies" 2>/dev/null); do
      if [ -z "$low" ] || [ "$f" -lt "$low" ]; then low=$f; fi
      if [ "$f" -le "$tgt" ] && [ "$f" -gt "$best" ]; then best=$f; fi
    done
    [ "$CPU_PCT" -ge 100 ] && best=$max
    [ "$best" -eq 0 ] && best=${low:-$max}
    LIMITS="$LIMITS $p/scaling_max_freq:$best"
  done
  gf=$(cat "$GPU/gpu_available_frequencies" 2>/dev/null)
  if [ -n "$gf" ]; then
    gmax=0; for f in $gf; do [ "$f" -gt "$gmax" ] && gmax=$f; done
    tgt=$((gmax / 100 * GPU_PCT)); best=0; low=$gmax          # /100 first: shell math is 32-bit
    for f in $gf; do
      [ "$f" -lt "$low" ] && low=$f
      if [ "$f" -le "$tgt" ] && [ "$f" -gt "$best" ]; then best=$f; fi
    done
    [ "$GPU_PCT" -ge 100 ] && best=$gmax
    [ "$best" -eq 0 ] && best=$low
    LIMITS="$LIMITS $GPU/max_gpuclk:$best $GPU/devfreq/max_freq:$best"
  fi
}
write_limits() { for pair in $LIMITS; do echo "${pair##*:}" > "${pair%:*}" 2>/dev/null; done; }
# Screen-off caps: same calculation at 50 %, the user's percentages are kept.
sleep_limits() {
  sv=$CPU_PCT; gv=$GPU_PCT; CPU_PCT=50; GPU_PCT=50
  compute_limits; write_limits
  CPU_PCT=$sv; GPU_PCT=$gv
}

# The kernel moves the fan on its own fan-only trip points (43-85 C) on every short spike. In our
# modes those trips are moved out of reach (throttling trips are separate and untouched); in AUTO the
# saved originals are put back. A reboot restores them anyway.
apply_trips() {
  if [ "$MODE" = "AUTO" ]; then
    if [ -f "$TRIPS" ]; then
      while read -r pair; do echo "${pair##*:}" > "${pair%:*}" 2>/dev/null; done < "$TRIPS"
      rm -f "$TRIPS"
    fi
    return
  fi
  if [ ! -f "$TRIPS" ]; then
    for z in /sys/class/thermal/thermal_zone*; do
      for c in "$z"/cdev[0-9]*; do
        [ -L "$c" ] || continue
        [ "$(cat "$(readlink -f "$c")/type" 2>/dev/null)" = "pwm-fan" ] || continue
        tp=$(cat "${c}_trip_point" 2>/dev/null) || continue
        echo "$z/trip_point_${tp}_temp:$(cat "$z/trip_point_${tp}_temp")" >> "$TRIPS.new"
      done
    done
    mv "$TRIPS.new" "$TRIPS" 2>/dev/null
  fi
  while read -r pair; do echo 125000 > "${pair%:*}" 2>/dev/null; done < "$TRIPS"
}

load_conf() {
  [ -f "$CONF" ] && . "$CONF"
  LASTCONF=$(cat "$CONF" 2>/dev/null)
  touch "$STAMP"
  compute_limits
  write_limits
  apply_trips
  lowfor=0
  [ "$sleeping" = "1" ] && sleep_limits
  # LED brightness: the stock lighting setting (global_lighting = brightness:r:g:b)
  LEDBR=$(sed -n 's/.*global_lighting">\([0-9]*\):.*/\1/p' "$AYNPREF" 2>/dev/null)
  { [ -z "$LEDBR" ] || [ "$LEDBR" = "0" ]; } && LEDBR=255
  ledlast=""
}

load_conf
n=0; avg=-1; out=-1; lowfor=0; hotfor=0

while true; do
  # ---- temperature (moving average) ----
  cmax=0; gmax=0
  for z in $CZ; do
    t=""; read -r t 2>/dev/null < "$z"
    case "$t" in ''|*[!0-9-]*) continue ;; esac
    [ "$t" -gt "$cmax" ] && cmax=$t
  done
  for z in $GZ; do
    t=""; read -r t 2>/dev/null < "$z"
    case "$t" in ''|*[!0-9-]*) continue ;; esac
    [ "$t" -gt "$gmax" ] && gmax=$t
  done
  max=$cmax; [ "$gmax" -gt "$max" ] && max=$gmax
  if [ "$avg" -lt 0 ]; then avg=$max; else avg=$((avg + (max - avg) / (SMOOTH + 1))); fi
  c=$((avg / 1000)); raw=$((max / 1000))

  # ---- screen off / on ----
  off=0; if [ -n "$BL" ]; then b=0; read -r b 2>/dev/null < "$BL"; [ "$b" != "0" ] && off=1; fi
  if [ "$SLEEPSAVE" = "1" ] && [ "$off" = "1" ] && [ "$sleeping" = "0" ]; then
    sleeping=1; sleep_limits
  elif [ "$sleeping" = "1" ] && { [ "$off" = "0" ] || [ "$SLEEPSAVE" != "1" ]; }; then
    sleeping=0; compute_limits; write_limits
  fi

  # ---- wanted fan level ----
  want=-1
  case "$MODE" in
    MANUAL) want=$LEVEL ;;
    SMOOTH)
      want=$(((c - 45) / 5)); [ "$c" -lt 45 ] && want=0     # 50 C -> 1 ... 85 C -> 8
      ;;
    TARGET)
      # deadband stepping: up while above target, down once below target - 3 C, hold in between
      [ "$out" -lt 0 ] && out=0
      if [ "$c" -gt "$TARGET" ]; then want=$((out + 1))
      elif [ "$c" -lt $((TARGET - 3)) ]; then want=$((out - 1))
      else want=$out; fi
      [ "$want" -lt 0 ] && want=0
      ;;
  esac
  [ "$want" -gt "$FANMAX" ] && want=$FANMAX
  [ "$sleeping" = "1" ] && [ "$want" -gt 0 ] && want=0    # screen off: quiet (safety below still applies)

  # ---- safety, then rate limiting ----
  if [ "$raw" -ge $((SAFETY + 8)) ]; then hotfor=$((hotfor + 1)); else hotfor=0; fi
  if [ "$c" -ge "$SAFETY" ] || [ "$hotfor" -ge 2 ]; then
    out=$FANMAX; lowfor=0
  elif [ "$want" -ge 0 ]; then
    if [ "$MODE" = "MANUAL" ] || [ "$out" -lt 0 ]; then
      out=$want; lowfor=0
    elif [ "$want" -gt "$out" ]; then
      out=$((out + 1)); lowfor=0                 # up one step per tick
    elif [ "$want" -lt "$out" ]; then
      lowfor=$((lowfor + 1))                     # down only after it stays lower for a while
      if [ "$lowfor" -ge "$SMOOTH" ]; then out=$((out - 1)); lowfor=0; fi
    else
      lowfor=0
    fi
  else
    out=-1                                       # AUTO: the system drives the fan
  fi

  n=$((n + 1))
  [ $((n % 15)) -eq 0 ] && ledlast=""
  battery_led
  echo "$VER" > "$ALIVE" 2>/dev/null
  # -nt has 1 s resolution: only when hs.conf and the stamp share a second, compare the content
  # (once; the stamp is then moved to a later second).
  if [ ! "$CONF" -nt "$STAMP" ] && [ ! "$STAMP" -nt "$CONF" ]; then
    if [ "$(cat "$CONF" 2>/dev/null)" != "$LASTCONF" ]; then load_conf; else touch "$STAMP"; fi
  fi
  [ "$REASSERT" = "1" ] && [ $((n % 5)) -eq 0 ] && write_limits

  # ---- newly installed apps -> forced full screen ----
  # (skipped while the screen is off; apps installed meanwhile are caught right after it turns on)
  if [ "$AUTOAPPS" = "1" ] && [ "$off" = "0" ] && [ $((n % 15)) -eq 1 ]; then
    apps=$(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>/dev/null | sed 's/^ *//' | grep / | cut -d/ -f1 | sort -u)
    [ -f "$KNOWN" ] || echo "$apps" > "$KNOWN"
    new=""
    for a in $apps; do
      grep -qx "$a" "$KNOWN" && continue
      echo "$a" >> "$KNOWN"
      case " $EXCLUDE $SELF " in *" $a "*) continue ;; esac
      new="$new $a"
    done
    if [ -n "$new" ]; then
      for a in $new; do for ch in $COMPAT; do am compat enable "$ch" "$a" >/dev/null 2>&1; done; done
      apk=$(pm path "$SELF" | head -1 | cut -d: -f2)
      CLASSPATH=$apk app_process /system/bin app.retroidsettings.lineage.RootHelper aspect 6 $new >/dev/null 2>&1
    fi
  fi

  # ---- 2 s tick, holding the fan level every 0.2 s (the kernel may write it on trip crossings)
  #      and reacting to app requests within 0.2 s ----
  # Screen off: no 0.2 s holding (the kernel's fan trips are out of the way), wake every 2 s only.
  k=0
  [ "$off" = "1" ] && k=9
  while [ "$k" -lt 10 ]; do
    [ "$out" -ge 0 ] && [ -n "$FAN" ] && echo "$out" > "$FAN" 2>/dev/null
    if [ -f "$CMD" ] && mv "$CMD" "$CMD.run" 2>/dev/null; then
      sh "$CMD.run" >/dev/null 2>&1
      rm -f "$CMD.run"
    fi
    [ -f "$DIR/hs.watch" ] && status
    if [ "$CONF" -nt "$STAMP" ]; then load_conf; k=10; fi
    if [ "$off" = "1" ]; then sleep 2; else sleep 0.2; fi
    k=$((k + 1))
  done
done
