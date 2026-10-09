#!/usr/bin/env bash
#
# Slice 4（Touch ROI + Blue Mask）的离线验证样本生成器。
#
# 为什么要有这个脚本：
#   蓝色掩码必须用「真实画面 + 真实压缩」验证过才算数。仓库里唯一可用的真实素材是
#   一条 HEVC 采集片段，它本身没有推流端叠加层，所以这里做的是：
#
#     真实解码帧  ──▶  去饱和（保住真实亮度纹理）──▶  合成蓝色框/蓝色 id 文字
#                 ──▶  HEVC 编码（yuv420p，crf 可调）──▶ 解码
#                 ──▶  <name>.rgba + <name>.txt  给 BlueMaskSampleSmokeTest 消费
#
#   关键收益：样本带上了**真实的量化与 4:2:0 色度子采样偏色**（1~4px 的细蓝线在
#   4:2:0 下掉饱和度最厉害），而不是"理想纯蓝"这种自欺欺人的输入。
#
# 为什么底图要去饱和（这一步很关键，别省）：
#   原始采样帧本身含大量饱和蓝（实测 1280x720 里有 154353 px 命中蓝判据，占 16.7%，
#   集中在画面上部 y=0..313）。如果直接在原图上画叠加层，那么"这块 ROI 里有蓝像素"
#   这个断言会被**底图自身的蓝色**满足 —— 测出来的是"视频里有蓝"，而不是"我画的那条
#   4px 细蓝线扛住了 HEVC 压缩"。去饱和后画面里唯一的饱和色就是叠加层，HAS_BLUE 的
#   归因才是唯一的：只可能来自被压缩过的细线/文字。
#   去饱和只丢色度，保留真实亮度纹理与既有压缩痕迹，所以压缩难度并没有被放水。
#
#   唯一例外是 real_frame_blue_region：它故意用**未改动**的原帧，用来证明检测器在
#   完全真实的相机内容上也能提取到蓝色区域（不再有合成可控性，也不声称识别到目标）。
#
# 样本不进版本库：由本脚本现场生成，同步脚本即可复现。
#
# 用法：
#   tools/roi_blue_mask/build_samples.sh [真实视频或图片] [输出目录]
#   默认视频：artifacts/hevc_bframe_ab/device_capture/R06/fd601106/media/segment_001.mp4
#   默认输出：artifacts/roi_blue_mask_samples
#
# 环境变量：
#   FFMPEG / FFPROBE  工具路径（默认取 PATH）
#   BASE_TIME         取第几秒的帧（默认 5）
#   FONT              字体文件（默认 C:/Windows/Fonts/arial.ttf，仅用于画 id 文字）
#   CRF               默认编码质量（默认 28；每个样本可单独覆盖）
#
set -euo pipefail

FFMPEG="${FFMPEG:-ffmpeg}"
FFPROBE="${FFPROBE:-ffprobe}"
BASE_VIDEO="${1:-artifacts/hevc_bframe_ab/device_capture/R06/fd601106/media/segment_001.mp4}"
OUT_DIR="${2:-artifacts/roi_blue_mask_samples}"
BASE_TIME="${BASE_TIME:-5}"
FONT="${FONT:-C:/Windows/Fonts/arial.ttf}"
CRF="${CRF:-28}"

# ffmpeg 的滤镜串用 ':' 分隔选项，所以字体路径里的盘符冒号必须转义
FONT_ESC="${FONT//:/\\:}"

BLUE="0x0000FF@1.0"
# 饱和红：用来验证"背景里有饱和的非蓝颜色时，不该大面积进 mask"（色相门限生效）
RED="0xFF0000@1.0"
# 偏灰的"伪蓝"背景（约 rgb(140,150,190)）：色调对、饱和度过低（S≈0.26 < 0.30），必须被拒
WASHED="0x8C96BE@1.0"

if [ ! -f "$BASE_VIDEO" ]; then
  echo "找不到基准视频/图片：$BASE_VIDEO" >&2
  exit 1
fi

SIZE=$("$FFPROBE" -v error -select_streams v:0 \
  -show_entries stream=width,height -of csv=p=0:s=x "$BASE_VIDEO" | head -1)
WIDTH="${SIZE%x*}"
HEIGHT="${SIZE#*x}"
if ! [ "$WIDTH" -gt 0 ] 2>/dev/null || ! [ "$HEIGHT" -gt 0 ] 2>/dev/null; then
  echo "无法从 $BASE_VIDEO 解析宽高：$SIZE" >&2
  exit 1
fi

TMP="$(mktemp -d)"
# 临时目录清理失败（例如被沙箱/安全策略拦截）不该改变脚本的退出码，否则会掩盖真实结果
trap 'rm -rf "$TMP" 2>/dev/null || true' EXIT

mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR"/*.rgba "$OUT_DIR"/*.txt

echo "基准素材 : $BASE_VIDEO  (${WIDTH}x${HEIGHT}, t=${BASE_TIME}s)"

# 1) 取一帧真实画面（保留真实纹理与已有的压缩痕迹）—— 两个版本：
#    base_color.rgba : 原帧，仅给 real_frame_blue_region 用
#    base_gray.rgba  : 去饱和帧（hue=s=0），其余所有样本的底图
"$FFMPEG" -v error -y -ss "$BASE_TIME" -i "$BASE_VIDEO" -frames:v 1 \
  -f rawvideo -pix_fmt rgba "$TMP/base_color.rgba"
"$FFMPEG" -v error -y -f rawvideo -pix_fmt rgba -s "${WIDTH}x${HEIGHT}" -i "$TMP/base_color.rgba" \
  -vf "hue=s=0" -frames:v 1 -f rawvideo -pix_fmt rgba "$TMP/base_gray.rgba"
echo "基础帧   : base_color.rgba（原帧）/ base_gray.rgba（去饱和）"

# emit_sample <base> <name> <touchX> <touchY> <expect> <crf> <note> <filter...>
emit_sample() {
  local base="$1" name="$2" touch_x="$3" touch_y="$4" expect="$5" crf="$6" note="$7"
  shift 7
  local filters="$*"

  # 2) 合成叠加层（无损）
  "$FFMPEG" -v error -y -f rawvideo -pix_fmt rgba -s "${WIDTH}x${HEIGHT}" -i "$base" \
    -vf "$filters" -frames:v 1 -f rawvideo -pix_fmt rgba "$TMP/$name.comp.rgba"

  # 3) 真实压缩往返：强制 yuv420p（与推流一致），单帧仍是 I 帧量化
  "$FFMPEG" -v error -y -f rawvideo -pix_fmt rgba -s "${WIDTH}x${HEIGHT}" -i "$TMP/$name.comp.rgba" \
    -frames:v 1 -c:v libx265 -crf "$crf" -pix_fmt yuv420p \
    -x265-params "log-level=error" "$TMP/$name.mp4"

  # 4) 解码回 RGBA 作为测试输入
  "$FFMPEG" -v error -y -i "$TMP/$name.mp4" -frames:v 1 \
    -f rawvideo -pix_fmt rgba "$OUT_DIR/$name.rgba"

  cat > "$OUT_DIR/$name.txt" <<EOF
# 由 tools/roi_blue_mask/build_samples.sh 生成，请勿手工编辑
source=$BASE_VIDEO
sourceTimeSeconds=$BASE_TIME
codec=hevc
crf=$crf
base=$([ "$base" = "$TMP/base_gray.rgba" ] && echo desaturated || echo original)
width=$WIDTH
height=$HEIGHT
touchX=$touch_x
touchY=$touch_y
expect=$expect
note=$note
EOF

  printf '  %-24s touch=(%s,%s) crf=%-3s expect=%-8s %s\n' \
    "$name" "$touch_x" "$touch_y" "$crf" "$expect" "$note"
}

GRAY="$TMP/base_gray.rgba"
COLOR="$TMP/base_color.rgba"

echo "生成样本 :"

# 蓝色人物框：点击落在框内靠近左边线处（真实点击习惯），ROI 必然覆盖框线
emit_sample "$GRAY" person_box 150 255 HAS_BLUE "$CRF" "蓝色人物框，点击框内靠左边线" \
  "drawbox=x=120:y=40:w=700:h=430:color=$BLUE:t=4"

# 蓝色车辆框：点击落在框内靠近下边线处
emit_sample "$GRAY" vehicle_box 850 640 HAS_BLUE "$CRF" "蓝色车辆框，点击框内靠下边线" \
  "drawbox=x=640:y=460:w=420:h=210:color=$BLUE:t=4"

# 2px 细框线：验证去噪步骤不会把细线腐蚀掉（去噪只删孤立点，不腐蚀）
emit_sample "$GRAY" thin_box_2px 150 255 HAS_BLUE "$CRF" "2px 细蓝框线，去噪后必须仍在" \
  "drawbox=x=120:y=40:w=700:h=430:color=$BLUE:t=2"

# 蓝色 id:xxxx 文字：点击落在文字块中部
emit_sample "$GRAY" id_label 580 140 HAS_BLUE "$CRF" "蓝色 id:xxxx 文字" \
  "drawtext=fontfile='$FONT_ESC':text='id\\:7391':x=500:y=118:fontsize=34:fontcolor=$BLUE"

# 多目标：两个框 + 一个 id 文字，点击最靠上的那个框
emit_sample "$GRAY" multi_target 150 255 HAS_BLUE "$CRF" "多目标（两框一文字）中的框" \
  "drawbox=x=120:y=40:w=700:h=430:color=$BLUE:t=4,\
drawbox=x=640:y=460:w=420:h=210:color=$BLUE:t=4,\
drawtext=fontfile='$FONT_ESC':text='id\\:7391':x=500:y=118:fontsize=34:fontcolor=$BLUE"

# 无目标：画面里没有任何叠加层，去饱和底图保证 ROI 内确实没有饱和色
emit_sample "$GRAY" no_target 400 640 EMPTY "$CRF" "点击附近没有目标（无叠加层）" \
  "null"

# 边缘目标：框的左/上边线正好压在画面边界上，点击贴边处，ROI 必须被正确夹取
emit_sample "$GRAY" edge_target 2 150 HAS_BLUE "$CRF" "贴着画面左上边缘的目标框" \
  "drawbox=x=0:y=0:w=520:h=300:color=$BLUE:t=4"

# 强压缩：同样的框用 crf 42 压一遍，颜色偏移与细线掉饱和更严重
emit_sample "$GRAY" compressed_shift 150 255 HAS_BLUE 42 "强压缩（crf=42）后的同一个框" \
  "drawbox=x=120:y=40:w=700:h=430:color=$BLUE:t=4"

# 饱和但非蓝：红框线，色相门限必须把它挡在 mask 外
emit_sample "$GRAY" red_box 150 255 EMPTY "$CRF" "饱和红框线，色相不符必须被拒" \
  "drawbox=x=120:y=40:w=700:h=430:color=$RED:t=4"

# 偏灰的蓝背景（色调对、饱和度过低）：必须被判为没有蓝色，避免误检
emit_sample "$GRAY" washed_blue_background 400 200 EMPTY "$CRF" "偏灰的蓝背景，饱和度过低应被拒" \
  "drawbox=x=60:y=60:w=1160:h=300:color=$WASHED:t=fill"

# 框 + 紧贴框下方的 id 文字（无人机常见样式），点击落在框下边线附近
emit_sample "$GRAY" box_with_label 620 455 HAS_BLUE "$CRF" "框与其下方 id 文字的组合" \
  "drawbox=x=120:y=40:w=700:h=430:color=$BLUE:t=4,\
drawtext=fontfile='$FONT_ESC':text='id\\:2408':x=140:y=474:fontsize=30:fontcolor=$BLUE"

# 完全未改动的真实帧：证明检测器在真实相机内容上也能提取蓝色区域（无合成可控性）。
# 这一条同时暴露一个必须记住的事实：该采集场景整体偏冷色调（显示器/天花板/柜体都带蓝），
# 单靠颜色门限会把这~24%的冷色像素全算进来 —— 所以"HAS_BLUE"绝不等于"识别到目标"，
# 后面必须有矩形恢复才能下结论。
emit_sample "$COLOR" real_frame_blue_region 600 100 HAS_BLUE "$CRF" \
  "未改动原帧：办公冷色调本身就带蓝，ROI 命中的全是真实内容，不等于识别到目标" \
  "null"

echo
echo "完成：$(ls -1 "$OUT_DIR"/*.rgba | wc -l) 个样本 → $OUT_DIR"
echo "跑验证："
echo "  ./gradlew :targetselection:testDebugUnitTest --tests '*BlueMaskSampleSmokeTest*' \\"
echo "      -Dtargetselection.sampleDir=$OUT_DIR \\"
echo "      -Dtargetselection.sampleReport=$OUT_DIR/report.txt"
