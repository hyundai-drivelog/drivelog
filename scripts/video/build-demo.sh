#!/usr/bin/env bash
# Builds the 90-second DRIVELOG demo video from the raw screen recordings.
# Edit the CAPTIONS, SCENES and AUDIO blocks below, then run:
#   bash scripts/video/build-demo.sh            # video + audio
#   bash scripts/video/build-demo.sh --tts      # also remake the narration with Gemini TTS (needs GEMINI_API_KEY)
#   TTS=heami bash scripts/video/build-demo.sh --tts   # offline Windows voice instead
# Guide: docs/video/DEMO_VIDEO.md (local only, not in git)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${WORK:-$ROOT/.local-qa/video-2026-11-13}"   # raw/ lives here; git-ignored
OUT="${OUT:-$WORK/out/drivelog-demo.mp4}"
VID="$ROOT/scripts/video"
RAWSND="$ROOT/app/src/main/res/raw"
LOGO="$ROOT/docs/design/drivelog-launcher.png"
cd "$WORK"
mkdir -p seg txt audio/fit out music
rm -f seg/*.mp4

if [ "${1:-}" = "--tts" ] || [ ! -f audio/n1.wav ]; then
  if [ "${TTS:-gemini}" = heami ]; then
    powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$VID/tts.ps1")" \
      -Lines "$(cygpath -w "$VID/narration.txt")" -OutDir "$(cygpath -w "$WORK/audio")"
  else
    python "$VID/tts_gemini.py" "$VID/narration.txt" audio ${TTS_VOICE:+--voice "$TTS_VOICE"}
  fi
fi

# Background music: an NCS track, credited on the outro (ncs.io/usage-policy).
MUSIC="music/elektronomia-sky-high.mp3"; MUSIC_START=0      # seconds into the track
MUSIC_URL="https://ncs.io/track/download/6d744632-0472-463c-8a13-da071971fbdf"   # from ncs.io/SkyHigh
[ -f "$MUSIC" ] || curl -sfL -A "Mozilla/5.0" -e https://ncs.io/SkyHigh "$MUSIC_URL" -o "$MUSIC"

# ---------- LAYOUT ----------
FB="C\\:/Windows/Fonts/NotoSansKR-Bold.ttf"
FR="C\\:/Windows/Fonts/NotoSansKR-Regular.ttf"
BG="0x131417"; FPS=30
CAPTION_SIZE=62; STEP_SIZE=30
CW=1138; CH=820; VY=30            # car panel (crop of the app area) and its top
PW=420                            # phone panel width, same height
CAR_SOLO_X=$(( (1920-CW)/2 ))
CAR_PAIR_X=$(( (1920-CW-40-PW)/2 )); PHONE_X=$((CAR_PAIR_X+CW+40))
CARCROP="crop=1260:908:640:67,scale=${CW}:${CH}:flags=lanczos"   # app area of a 1920x1080 car recording
PHCROP="crop=1080:2110:0:110,scale=${PW}:${CH}:flags=lanczos"    # phone without status and nav bars
GEAR="crop=124:46:503:12,scale=248:92:flags=lanczos"             # P R N D from the car cluster
# Hides the navigation's distance/time on the place screen (the emulator nav starts from its own default spot).
BLUR="split[x][y];[y]crop=540:100:18:290,boxblur=18:2[bl];[x][bl]overlay=18:290"

cap() { printf '%s' "$2" > "txt/$1.txt"; }

# ---------- CAPTIONS ----------
cap h1 "드라이브가 끝나면, 무엇이 남을까요?"
cap h2 "DRIVELOG는 달린 길을 카드와 스탬프로 남겨 주는 Pleos 차량 앱"
cap h3 "목적지 하나로 안내·기록"
cap h4 "주행 중엔 최소 정보만"
cap h5 "정차하면 카드·스탬프"
cap st1 "STEP 1   휴대폰 연결"
cap st2 "STEP 2   출발"
cap st3 "STEP 3   주행"
cap st4 "STEP 4   도착과 기록"
cap c03 "연결은 선택, 서버 전송은 동의한 뒤에만"
cap c04 "차량의 6자리 코드를 휴대폰에 입력"
cap c05 "목적지 검색, 찾기를 누르면 기본 내비"
cap c06 "길 안내는 내비가, 기록은 DRIVELOG가"
cap c07 "기록 시작"
cap c08 "주행 중엔 장소·시간·거리만 크게"
cap c09 "목적지 근처에 오면 알려줘요"
cap c10 "P에 두면 달린 경로를 저장"
cap c11 "기록은 연결된 휴대폰으로 바로"
cap c11b "휴대폰에서 카드를 꾸미고 저장"
cap c11c "이번 주 날씨와 세차하기 좋은 날"
cap c12 "카드를 펼치면 시각·구간 기록·스탬프"
cap c13 "가는 곳마다 전국 56곳 스탬프"
cap o1 "DRIVELOG"
cap o2 "달린 길이 카드로 남는 드라이브"
cap credit "Music: Elektronomia - Sky High [NCS Release] · Music provided by NoCopyrightSounds"
cap footer "Pleos 에뮬레이터 시연 화면"
cap footer_phone "휴대폰 앱 실제 화면"

# ---------- SCENE BUILDERS ----------
footer="drawtext=fontfile='$FR':textfile='txt/footer.txt':fontcolor=0x7d828b:fontsize=22:x=w-tw-36:y=h-38"
footer_phone="${footer/footer.txt/footer_phone.txt}"
labels() { # caption-id step-id
  echo "drawtext=fontfile='$FB':textfile='txt/$2.txt':fontcolor=0x9aa0a8:fontsize=$STEP_SIZE:x=(w-tw)/2:y=$((VY+CH+22)),drawtext=fontfile='$FB':textfile='txt/$1.txt':fontcolor=white:fontsize=$CAPTION_SIZE:x=(w-tw)/2:y=$((VY+CH+66))"
}
enc() { echo "-c:v libx264 -preset medium -crf 18 -r $FPS"; }
have() { [ -f "raw/$2.mp4" ] || { echo "skip $1: raw/$2.mp4 missing"; return 1; }; }

car() { # name clip start end speed caption step [extra-filter] [gear 0/1]
  local n=$1 clip=$2 ss=$3 to=$4 sp=$5 c=$6 st=$7 extra=${8:-} gear=${9:-0}
  have "$n" "$clip" || return 0
  local dur; dur=$(awk "BEGIN{print ($to-$ss)/$sp}")
  local cf="[0:v]trim=start=$ss:end=$to,setpts=(PTS-STARTPTS)/$sp,fps=$FPS,split[a][b];[a]$CARCROP${extra:+,$extra}[car]" g
  if [ "$gear" = 1 ]; then cf="$cf;[b]$GEAR[gear]"; g=";[o1][gear]overlay=$((CAR_SOLO_X-280)):$((VY+30))[o2]"
  else cf="$cf;[b]nullsink"; g=";[o1]null[o2]"; fi
  ffmpeg -v error -y -i "raw/$clip.mp4" -f lavfi -i "color=c=$BG:s=1920x1080:r=$FPS:d=$dur" -filter_complex \
    "$cf;[1:v][car]overlay=$CAR_SOLO_X:$VY:shortest=1[o1]$g;[o2]$(labels $c $st),$footer,format=yuv420p[v]" \
    -map "[v]" -t "$dur" $(enc) "seg/$n.mp4"
  echo "$n ${dur}s"
}

pair() { # name carclip cs ce phoneclip ps pe caption step duration
  local n=$1 cc=$2 cs=$3 ce=$4 pc=$5 ps=$6 pe=$7 c=$8 st=$9 dur=${10}
  have "$n" "$cc" && have "$n" "$pc" || return 0
  local csp psp; csp=$(awk "BEGIN{print ($ce-$cs)/$dur}"); psp=$(awk "BEGIN{print ($pe-$ps)/$dur}")
  ffmpeg -v error -y -i "raw/$cc.mp4" -i "raw/$pc.mp4" -f lavfi -i "color=c=$BG:s=1920x1080:r=$FPS:d=$dur" -filter_complex \
    "[0:v]trim=start=$cs:end=$ce,setpts=(PTS-STARTPTS)/$csp,fps=$FPS,$CARCROP[car];\
[1:v]trim=start=$ps:end=$pe,setpts=(PTS-STARTPTS)/$psp,fps=$FPS,$PHCROP[ph];\
[2:v][car]overlay=$CAR_PAIR_X:$VY:eof_action=repeat[o1];[o1][ph]overlay=$PHONE_X:$VY:eof_action=repeat[o2];\
[o2]$(labels $c $st),$footer,format=yuv420p[v]" \
    -map "[v]" -t "$dur" $(enc) "seg/$n.mp4"
  echo "$n ${dur}s"
}

phone() { # name clip start end speed caption step [hold]  -> phone alone, centred; length = (end-start)/speed + hold
  local n=$1 clip=$2 ss=$3 to=$4 sp=$5 c=$6 st=$7 hold=${8:-0}
  have "$n" "$clip" || return 0
  local dur; dur=$(awk "BEGIN{print ($to-$ss)/$sp+$hold}")
  ffmpeg -v error -y -i "raw/$clip.mp4" -f lavfi -i "color=c=$BG:s=1920x1080:r=$FPS:d=$dur" -filter_complex \
    "[0:v]trim=start=$ss:end=$to,setpts=(PTS-STARTPTS)/$sp,fps=$FPS,tpad=stop_mode=clone:stop_duration=$hold,$PHCROP[ph];\
[1:v][ph]overlay=$(( (1920-PW)/2 )):$VY:eof_action=repeat[o];[o]$(labels $c $st),$footer_phone,format=yuv420p[v]" \
    -map "[v]" -t "$dur" $(enc) "seg/$n.mp4"
  echo "$n ${dur}s"
}

chip() { echo "drawtext=fontfile='$FB':textfile='txt/$1.txt':fontcolor=white:fontsize=36:box=1:boxcolor=0x2a2d33:boxborderw=26:x=$2-tw/2:y=$3:alpha='min(1,max(0,(t-$4)/0.4))'"; }

hook() { # name duration
  local n=$1 dur=$2
  ffmpeg -v error -y -f lavfi -i "color=c=$BG:s=1920x1080:r=$FPS:d=$dur" -loop 1 -i "$LOGO" -filter_complex \
    "[1:v]crop=330:330:91:91,scale=200:200,format=rgba,fade=t=in:st=2.8:d=0.4:alpha=1[l];\
[0:v][l]overlay=(W-w)/2:150:shortest=1,\
drawtext=fontfile='$FB':textfile='txt/h1.txt':fontcolor=white:fontsize=68:x=(w-tw)/2:y=390:alpha='min(1,max(0,(t-0.2)/0.4))',\
drawtext=fontfile='$FR':textfile='txt/h2.txt':fontcolor=0xd3d7dd:fontsize=46:x=(w-tw)/2:y=520:alpha='min(1,max(0,(t-3)/0.4))',\
$(chip h3 470 700 4.6),$(chip h4 960 700 5.0),$(chip h5 1450 700 5.4),\
fade=t=out:st=$(awk "BEGIN{print $dur-0.35}"):d=0.35,format=yuv420p[v]" \
    -map "[v]" -t "$dur" $(enc) "seg/$n.mp4"
  echo "$n ${dur}s"
}

outro() { # name duration
  local n=$1 dur=$2
  ffmpeg -v error -y -f lavfi -i "color=c=$BG:s=1920x1080:r=$FPS:d=$dur" -loop 1 -i "$LOGO" -filter_complex \
    "[1:v]crop=330:330:91:91,scale=260:260[l];[0:v][l]overlay=(W-w)/2:220:shortest=1,\
drawtext=fontfile='$FB':textfile='txt/o1.txt':fontcolor=white:fontsize=60:x=(w-tw)/2:y=560,\
drawtext=fontfile='$FR':textfile='txt/o2.txt':fontcolor=0xb8bcc4:fontsize=32:x=(w-tw)/2:y=660,\
drawtext=fontfile='$FR':textfile='txt/credit.txt':fontcolor=0x7d828b:fontsize=22:x=(w-tw)/2:y=h-60,\
fade=t=in:st=0:d=0.4,fade=t=out:st=$(awk "BEGIN{print $dur-0.6}"):d=0.6,format=yuv420p[v]" \
    -map "[v]" -t "$dur" $(enc) "seg/$n.mp4"
  echo "$n ${dur}s"
}

# ---------- SCENES (rendered in name order) ----------
#   car  name clip  start end speed caption step [extra] [gear]   -> length = (end-start)/speed
#   pair name car cs ce phone ps pe caption step length           -> both sped up to fit length
#   phone name clip start end speed caption step [hold]           -> phone alone; hold freezes the last frame
# A scene whose raw file is missing is skipped, with the narration and sounds placed in it.
hook  01 9.0
car   03 c1 34 64 10 c03 st1
pair  04 c1 66 140 p1 31 105 c04 st1 9.0
car   05 c2 75 141 10.1538 c05 st2
car   06 c4 0 4 1.142857 c06 st2 "$BLUR"
car   07 c4 39 44 2.5 c07 st2 "" 1
car   08 c4 66 174 19.6364 c08 st3 "" 1
car   09 c5 0 100 22.2222 c09 st3 "" 1
car   10 c5 119 134 3 c10 st4 "" 1
pair  11 c5 136 175 p2 72 134 c11 st4 8.0
phone 11b p3 1.6 19.0 2.2 c11b st4 1.2
phone 11c p4 0 6 1 c11c st4
car   12a c8 9 14 2 c12 st4
car   12b c8 24 31 2 c12 st4
car   12c c8 31 40 2 c12 st4
car   13 c6 138 172 4.857 c13 st4
outro 14 5.0

(cd seg && ls [0-9]*.mp4 | sed 's/^/file /' > list.txt)
ffmpeg -v error -y -f concat -safe 0 -i seg/list.txt -c copy out/video.mp4
VD=$(ffprobe -v error -show_entries format=duration -of csv=p=0 out/video.mp4)
echo "video ${VD}s"

# ---------- AUDIO (times are seconds from the start of a scene) ----------
#   narration: "id scene offset max-length". Silence is trimmed and the line sped up (at most 1.35x) to fit.
NARRATION=( "n1 01 0.3 8.4" "n2 03 0.3 11.4" "n3 05 0.3 11.4" "n4 08 0.3 9.4" "n5 10 0.2 6.6"
            "n6 11 2.0 5.8" "n6b 11b 0.3 8.4" "n6c 11c 0.2 5.6" "n7 12a 0.3 10.0" "n8 13 0.3 6.5" "n9 14 0.3 4.5" )
SOUNDS=( "sound_confirm 04 8.7" "sound_start 07 0" "sound_saved 11 0.1" )   # app sounds: pairing done, recording start, saved
MUSIC_VOLUME=0.12; NARRATION_VOLUME=1.5; SOUND_VOLUME=0.8; MAX_TEMPO=1.35

declare -A START; t=0
for f in $(cd seg && ls [0-9]*.mp4); do
  START[${f%.mp4}]=$t
  t=$(awk "BEGIN{print $t+$(ffprobe -v error -show_entries format=duration -of csv=p=0 "seg/$f")}")
done
at_ms() { [ -n "${START[$1]:-}" ] && awk "BEGIN{printf \"%d\", (${START[$1]}+$2)*1000}"; }

fit() { # id max-length -> audio/fit/id.wav
  ffmpeg -v error -y -i "audio/$1.wav" -af "silenceremove=start_periods=1:start_threshold=-45dB:stop_periods=-1:stop_duration=0.3:stop_threshold=-45dB:stop_silence=0.22" "audio/fit/$1.trim.wav"
  local d tempo; d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "audio/fit/$1.trim.wav")
  tempo=$(awk "BEGIN{x=$d/$2; if (x<1) x=1; if (x>$MAX_TEMPO) x=$MAX_TEMPO; printf \"%.3f\", x}")
  ffmpeg -v error -y -i "audio/fit/$1.trim.wav" -af "atempo=$tempo" "audio/fit/$1.wav"
  awk "BEGIN{o=$d/$tempo; printf \"%s %.2fs -> %.2fs (x%s, max %ss)%s\n\", \"$1\", $d, o, \"$tempo\", $2, (o>$2+0.05 ? \"  TOO LONG: shorten the line\" : \"\")}"
}

inputs=(); fc=""; labs=""; i=0
for e in "${NARRATION[@]}"; do set -- $e
  ms=$(at_ms "$2" "$3") || { echo "skip narration $1: scene $2 missing"; continue; }
  fit "$1" "$4"
  inputs+=(-i "audio/fit/$1.wav"); fc+="[$i:a]aresample=48000,aformat=channel_layouts=stereo,adelay=$ms|$ms[n$i];"; labs+="[n$i]"; i=$((i+1)); done
nn=$i
fc+="${labs}amix=inputs=$nn:normalize=0,volume=$NARRATION_VOLUME,apad=whole_dur=$VD,asplit[nar][key];"   # pad: the ducker stops when its key ends
inputs+=(-ss "$MUSIC_START" -i "$MUSIC")
fc+="[$i:a]aresample=48000,aformat=channel_layouts=stereo,afade=t=in:d=0.5,volume=$MUSIC_VOLUME[bgm];[bgm][key]sidechaincompress=threshold=0.02:ratio=8:attack=30:release=450[duck];"; i=$((i+1))
slabs=""; ns=0; for e in "${SOUNDS[@]}"; do set -- $e
  ms=$(at_ms "$2" "$3") || { echo "skip sound $1: scene $2 missing"; continue; }
  inputs+=(-i "$RAWSND/$1.wav"); fc+="[$i:a]aresample=48000,aformat=channel_layouts=stereo,adelay=$ms|$ms[s$i];"; slabs+="[s$i]"; i=$((i+1)); ns=$((ns+1)); done
fc+="${slabs}amix=inputs=$ns:normalize=0,volume=$SOUND_VOLUME[sfx];"
fc+="[duck][nar][sfx]amix=inputs=3:normalize=0,atrim=0:$VD,afade=t=out:st=$(awk "BEGIN{print $VD-1.5}"):d=1.5,loudnorm=I=-16:TP=-1.5:LRA=11[out]"
ffmpeg -v error -y "${inputs[@]}" -filter_complex "$fc" -map "[out]" -ar 48000 -c:a pcm_s16le out/audio.wav
ffmpeg -v error -y -i out/video.mp4 -i out/audio.wav -map 0:v -map 1:a -c:v copy -c:a aac -b:a 192k -shortest "$OUT"
echo "done: $OUT ($(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT")s)"
