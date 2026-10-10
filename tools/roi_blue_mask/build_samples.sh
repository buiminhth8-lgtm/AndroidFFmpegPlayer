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
echo "=============================================================================="
echo "Slice 5（矩形恢复 + 触摸命中）样本"
echo "=============================================================================="
#
# 与 Slice 4 样本的区别：这里期望的不再是"ROI 里有没有蓝"，而是
# 「这次点击命中了哪个框」。于是每个样本额外记下**目标框的视频坐标**，
# 让 smoke test 能把恢复出的 rectInVideo 与它逐边比对 ——
# 只看 HIT/MISS 两个字母是验不出"框的位置对不对"的，而位置错了比漏检更危险。
#
# 几何约定（重要）：drawbox 的边框画在 w×h 区域内侧，所以
#   drawbox=x=X:y=Y:w=W:h=H:t=4
# 的最外层蓝像素落在 [X, X+W-1] × [Y, Y+H-1]，而检测器返回的 rectInVideo
# 用"外边界"语义（left/top = 第一条蓝线，right/bottom = 最后一条蓝线 + 1），
# 因此样本里记的 boxRight 应当写 X+W、boxBottom 写 Y+H。
#
# expect 取三种值：
#   HIT_BOX  点击落在目标框内 → 必须命中，且选中框要与 boxLeft..boxBottom 吻合
#   MISS     找到了候选框，但点击点不在里面（含容差）→ 必须不命中
#   NO_BOX   ROI 里压根没有框 → 必须不命中，且结果为 NO_BOX
#
BOX_OUT_DIR="${BOX_OUT_DIR:-artifacts/target_box_samples}"
mkdir -p "$BOX_OUT_DIR"
rm -f "$BOX_OUT_DIR"/*.rgba "$BOX_OUT_DIR"/*.txt

# emit_box_sample <name> <touchX> <touchY> <expect> <box> <clipped> <note> <filter...>
#   box     : "left,top,right,bottom"（视频像素）或 "-"（本样本没有目标框）
#   clipped : yes/no —— 目标框是否被 ROI 边界截断（截断时只有部分边可信）
emit_box_sample() {
  local name="$1" touch_x="$2" touch_y="$3" expect="$4" box="$5" clipped="$6" note="$7"
  shift 7
  local filters="$*"

  "$FFMPEG" -v error -y -f rawvideo -pix_fmt rgba -s "${WIDTH}x${HEIGHT}" -i "$GRAY" \
    -vf "$filters" -frames:v 1 -f rawvideo -pix_fmt rgba "$TMP/$name.comp.rgba"

  "$FFMPEG" -v error -y -f rawvideo -pix_fmt rgba -s "${WIDTH}x${HEIGHT}" -i "$TMP/$name.comp.rgba" \
    -frames:v 1 -c:v libx265 -crf "$CRF" -pix_fmt yuv420p \
    -x265-params "log-level=error" "$TMP/$name.mp4"

  "$FFMPEG" -v error -y -i "$TMP/$name.mp4" -frames:v 1 \
    -f rawvideo -pix_fmt rgba "$BOX_OUT_DIR/$name.rgba"

  cat > "$BOX_OUT_DIR/$name.txt" <<EOF
# 由 tools/roi_blue_mask/build_samples.sh 生成（Slice 5），请勿手工编辑
source=$BASE_VIDEO
sourceTimeSeconds=$BASE_TIME
codec=hevc
crf=$CRF
base=desaturated
width=$WIDTH
height=$HEIGHT
touchX=$touch_x
touchY=$touch_y
expect=$expect
clipped=$clipped
note=$note
EOF
  if [ "$box" != "-" ]; then
    IFS=',' read -r bx1 by1 bx2 by2 <<< "$box"
    {
      echo "boxLeft=$bx1"
      echo "boxTop=$by1"
      echo "boxRight=$bx2"
      echo "boxBottom=$by2"
    } >> "$BOX_OUT_DIR/$name.txt"
  fi

  printf '  %-26s touch=(%s,%s) expect=%-8s box=%-18s %s\n' \
    "$name" "$touch_x" "$touch_y" "$expect" "$box" "$note"
}

echo "生成样本 :"

# 尺寸约束（重要，别随手把框画大）：
#   ROI 是 2*roiRadius（默认 400x400）。要恢复一个矩形，ROI 里必须同时看到**对边**
#   —— 两条水平边（定上下）或两条垂直边（定左右）。因此目标框在至少一个方向上
#   必须小于 ROI；比 ROI 还大的框，点击其内部时 ROI 里根本看不到成对的边，
#   几何上就是不可恢复的（这不是实现的缺陷，是"只看 ROI"这一约定的固有边界）。
#   所以这里的目标框都取真实目标量级：几十到三百像素。
#   由此也得出点击点的放置规则：点击 x 落在 [boxRight-roiRadius, boxLeft+roiRadius]
#   内，且 y 落在 [boxBottom-roiRadius, boxTop+roiRadius] 内，才能同时框住两条对边。
#   下面每个点击点都按这条规则挑过（默认 roiRadius=200）。

# 1) 人物蓝框（300x240），点击框内 → HIT_BOX
emit_box_sample person_box_hit 270 160 HIT_BOX "120,40,420,280" no "蓝色人物框，点击框内" \
  "drawbox=x=120:y=40:w=300:h=240:color=$BLUE:t=4"

# 2) 车辆蓝框（320x200，不同长宽比），点击框内 → HIT_BOX
emit_box_sample vehicle_box_hit 800 560 HIT_BOX "640,460,960,660" no "蓝色车辆框，点击框内" \
  "drawbox=x=640:y=460:w=320:h=200:color=$BLUE:t=4"

# 3) 点击落在画面另一侧，ROI 完全不含任何框 → NO_BOX（不是"有框但没点中"）
emit_box_sample far_from_box_no_box 1200 120 NO_BOX "-" no "点击处 ROI 内没有框，应报 NO_BOX" \
  "drawbox=x=120:y=40:w=300:h=240:color=$BLUE:t=4"

# 4) 点击落在小框外侧 70px：ROI 能把整个框包进来（候选找得到），但点不在框内 → MISS
emit_box_sample outside_box_edge_miss 720 335 MISS "560,300,650,370" no \
  "点在框右侧外面 70px：候选能找到，但必须判 MISS" \
  "drawbox=x=560:y=300:w=90:h=70:color=$BLUE:t=4"

# 5) 多框同屏：点击落在下面那个框内，选中框必须是它（而不是上面那个或合并框）
emit_box_sample multi_box_hit 910 490 HIT_BOX "760,380,1060,600" no "多框同屏，点击下面那个框" \
  "drawbox=x=120:y=40:w=260:h=200:color=$BLUE:t=4,\
drawbox=x=760:y=380:w=300:h=220:color=$BLUE:t=4"

# 6) 小目标：90x70 的框，刚过最小尺寸门槛，点击框中心
emit_box_sample small_box_hit 605 335 HIT_BOX "560,300,650,370" no "小目标（90x70），点击框中心" \
  "drawbox=x=560:y=300:w=90:h=70:color=$BLUE:t=4"

# 7) 部分断裂的框：上边线中间整段缺失 40px（模拟 HEVC 把一条边吃掉）。
#    用 5 条独立线段拼出来 —— 缺口远大于任何去噪/连通性修补能力，几何必须靠"并集跨度"保住。
emit_box_sample broken_edge_box_hit 270 160 HIT_BOX "120,40,420,280" no \
  "上边线中间断掉 40px，几何仍须恢复出整框" \
  "drawbox=x=120:y=40:w=140:h=4:color=$BLUE:t=fill,\
drawbox=x=300:y=40:w=120:h=4:color=$BLUE:t=fill,\
drawbox=x=120:y=276:w=300:h=4:color=$BLUE:t=fill,\
drawbox=x=120:y=40:w=4:h=240:color=$BLUE:t=fill,\
drawbox=x=416:y=40:w=4:h=240:color=$BLUE:t=fill"

# 8) 目标框被 ROI 边界截断：右框线在 ROI 之外（ROI 右界 = touchX+roiRadius = 1220），
#    几何只能给到 ROI 边界，因此必须如实标记 clippedByRoi。
emit_box_sample roi_edge_box_hit 1020 330 HIT_BOX "980,200,1260,460" yes \
  "框跨出 ROI 右边界：左/上/下边可信，右边被夹取到 ROI 界" \
  "drawbox=x=980:y=200:w=280:h=260:color=$BLUE:t=4"

# 9) 只有 id:1963 蓝字（无框）：字高约 34px < 最小框高 40px，绝不能单独成框
emit_box_sample id_text_only_no_box 610 267 NO_BOX "-" no \
  "只有 id 蓝字，字高不足最小框高，不得误判为框" \
  "drawtext=fontfile='$FONT_ESC':text='id\\:1963':x=520:y=250:fontsize=34:fontcolor=$BLUE"

# 10) id:1963 蓝字 + 它下方的蓝框：点击框内必须命中那个框（不要求识别出 1963）。
#     文字落在同一个 ROI 里，正是"文字不能把框带偏"的压力场景。
emit_box_sample id_1963_with_box_hit 270 160 HIT_BOX "120,40,420,280" no \
  "示例 id:1963 与其蓝框，点击框内返回该框候选" \
  "drawbox=x=120:y=40:w=300:h=240:color=$BLUE:t=4,\
drawtext=fontfile='$FONT_ESC':text='id\\:1963':x=140:y=290:fontsize=30:fontcolor=$BLUE"

echo
echo "=============================================================================="
echo "Slice 6（多目标 / 重叠目标选择策略）样本"
echo "=============================================================================="
#
# 与 Slice 5 样本的区别：Slice 5 期望的是「命中了哪一个框」，本段期望的是
# 「多个框同时命中时，最终选了哪一个，以及凭什么」。于是除了 expect / box 之外，
# 每个样本还记下 hitType（严格包含还是容差）、hits（同时命中的候选数）、
# 期望的候选框数，以及一条"不许越界"的几何约束。
#
# 为什么单独一个目录：Slice 5 样本的期望是"命中/不命中"，本段的期望多出若干字段。
# 分开以后，两批样本各自可独立复跑，Slice 5 的验收结论不会被本段的期望改写。
#
# 场景覆盖（对应需求里的 A~H）：
#   A 两个完全独立目标、B 两框边缘接近、C 两框部分重叠、D 大框包含小框、
#   E 触摸点在重叠区、F 仅靠容差命中多个框、G 小目标与大目标同时包含触摸点、
#   H ROI 边缘的不完整候选框；另外覆盖"点框边缘""点两框之间""点 id 文字附近"。
#
# 几何前提（默认 roiRadius=200，即 ROI 为 400x400）：
#   要恢复一个矩形，ROI 里必须看到**成对的边**。所以每个目标框在至少一个方向上
#   必须小于 400px。点击点的放置也照着这条约束挑过：
#     touchX ∈ [boxRight-200, boxLeft+200]、touchY ∈ [boxBottom-200, boxTop+200]
#   才能同时框住两条对边。下面每个点击点都满足这条（脚本末尾的注释里逐个标了）。
#
# 默认 touchTolerancePx=12：容差命中与"点不中"之间只差 12 像素，
#   所以下面凡是故意做"容差命中"的样本，点都放在离框边 11px 以内；
#   凡是故意做"点不中"的样本，点都离边框 30px 以上。这样期望值不会骑在边界上。
#
# 一个曾经放不进来、现在靠检测层修复才成立的场景：**大框包含小框（场景 D）**。
#   Slice 5 的矩形恢复原先按"该行所有蓝像素的并集"取边带跨度，于是内层小框的边会被
#   外层大框的两条竖边把跨度从 110px 撑到 270px，实心度 1.0 → 0.44 < minEdgeSolidity(0.5)，
#   内层框的边被当成"借像素凑出来的假边"丢掉 —— 内层框永远不会成为候选，
#   用户点它只会选中外面的大框（而框的位置还是对的，属于最难被发现的那类错误）。
#   修复后跨度只统计"比线宽上限还长的段"，嵌套框可以正常恢复，样本 nested_boxes_prefer_the_smaller
#   因此既能验选择策略，又能当这条修复的回归哨兵（候选数必须为 2）。
SELECT_OUT_DIR="${SELECT_OUT_DIR:-artifacts/target_selection_samples}"
mkdir -p "$SELECT_OUT_DIR"
rm -f "$SELECT_OUT_DIR"/*.rgba "$SELECT_OUT_DIR"/*.txt

# render_overlay <name> <out_dir> <filters...>
#
# 合成叠加层 → 强制 yuv420p 的 HEVC 往返 → 解码回 RGBA。
# 为什么不回头把 Slice 4/5 的两段也改成调它：那两批样本已经被验证过、写进了各自的
# 验收结论，动它们会让"上一次验过的输入还是不是同一份"无法追溯。此处只服务新样本。
render_overlay() {
  local name="$1" out_dir="$2"
  shift 2
  local filters="$*"

  "$FFMPEG" -v error -y -f rawvideo -pix_fmt rgba -s "${WIDTH}x${HEIGHT}" -i "$GRAY" \
    -vf "$filters" -frames:v 1 -f rawvideo -pix_fmt rgba "$TMP/$name.comp.rgba"

  "$FFMPEG" -v error -y -f rawvideo -pix_fmt rgba -s "${WIDTH}x${HEIGHT}" -i "$TMP/$name.comp.rgba" \
    -frames:v 1 -c:v libx265 -crf "$CRF" -pix_fmt yuv420p \
    -x265-params "log-level=error" "$TMP/$name.mp4"

  "$FFMPEG" -v error -y -i "$TMP/$name.mp4" -frames:v 1 \
    -f rawvideo -pix_fmt rgba "$out_dir/$name.rgba"
}

# opt_meta <file> <key> <value>：value 为 "-" 时不写这一行（表示"本样本不校验该项"）
opt_meta() {
  if [ "$3" != "-" ]; then
    echo "$2=$3" >> "$1"
  fi
}

# emit_selection_sample <name> <touchX> <touchY> <expect> <box> <hitType> <hits>
#                       <cand> <clipped> <maxBottom> <note> <filter...>
#   expect    : HIT_BOX（选了 box） / MISS（没命中） / NO_BOX（没有候选框）
#   box       : "left,top,right,bottom"（期望被选中的那个框）或 "-"
#   hitType   : EXACT_HIT / TOLERANCE_HIT / "-"（不校验）
#   hits      : 期望的同时命中候选个数 / "-"
#   cand      : 期望的候选框总数 / "-"
#   maxBottom : 任何候选框的下边界不得超过它 / "-"（用来证明 id 文字没被吃进框里）
emit_selection_sample() {
  local name="$1" touch_x="$2" touch_y="$3" expect="$4" box="$5"
  local hit_type="$6" hits="$7" cand="$8" clipped="$9" max_bottom="${10}" note="${11}"
  shift 11
  local filters="$*"

  render_overlay "$name" "$SELECT_OUT_DIR" "$filters"

  local meta="$SELECT_OUT_DIR/$name.txt"
  cat > "$meta" <<EOF
# 由 tools/roi_blue_mask/build_samples.sh 生成（Slice 6），请勿手工编辑
source=$BASE_VIDEO
sourceTimeSeconds=$BASE_TIME
codec=hevc
crf=$CRF
base=desaturated
width=$WIDTH
height=$HEIGHT
touchX=$touch_x
touchY=$touch_y
expect=$expect
clipped=$clipped
note=$note
EOF
  opt_meta "$meta" hitType "$hit_type"
  opt_meta "$meta" hits "$hits"
  opt_meta "$meta" expectCandidates "$cand"
  opt_meta "$meta" maxCandidateBottom "$max_bottom"
  if [ "$box" != "-" ]; then
    IFS=',' read -r bx1 by1 bx2 by2 <<< "$box"
    {
      echo "boxLeft=$bx1"
      echo "boxTop=$by1"
      echo "boxRight=$bx2"
      echo "boxBottom=$by2"
    } >> "$meta"
  fi

  printf '  %-42s touch=(%s,%s) expect=%-8s hits=%-2s hitType=%-13s box=%-20s %s\n' \
    "$name" "$touch_x" "$touch_y" "$expect" "$hits" "$hit_type" "$box" "$note"
}

echo "生成样本 :"

# 1) 【场景 A/B】多车辆相邻：两个完全独立的框，点严格落在左边那个里面。
#    点 (320,315) 在 A[240..400]×[250..380] 内；到 B.left=460 的距离 140px ≫ 容差 12，
#    所以 B 连容差都够不到 —— 命中数必须是 1，且必须选 A。
#
#    右侧那个框只有 70x70 是有意的：两个框的"上边线/下边线"若落在同一个 ROI 行上，
#    边带会互相配对造出一个"从 A 的左边界跨到 B 的右边"的假框（它不是虚的 ——
#    它的四条边都是真实蓝像素，只是分属两个目标）。用 70px 的小框能让那个假框的
#    左右边覆盖率掉到 0.26 以下、置信度 0.28 < minBoxConfidence(0.3)，从而被自然淘汰。
#    这也解释了为什么"多目标"样本在这里要特意避免两个等宽框并排。
emit_selection_sample adjacent_boxes_pick_the_clicked_one 320 315 HIT_BOX "240,250,400,380" \
  EXACT_HIT 1 2 no - "多车辆相邻：点左边那个框，必须只选它" \
  "drawbox=x=240:y=250:w=160:h=130:color=$BLUE:t=4,\
drawbox=x=460:y=300:w=70:h=70:color=$BLUE:t=4"

# 2) 【点两个框之间】两个框相距 140px，点(510,350) 落在正中间，离两边各 70px。
#    容差只有 12px，所以两侧都够不到 —— 必须谁都不选（MISS），而不是"就近吸附"。
#    这条样本是需求里"点击两个目标中间时不要错误吸附"的最强形式：
#    真正的吸附错误发生在容差刚好够到两边时，而那时两框必然相距 < 24px，
#    也就必然落进上面说的"边带互相配对造出假框"的地带，样本本身无法干净地隔离。
#    "两侧都被容差命中、必须选更近的那个"这条由纯几何的单元测试穷举覆盖
#    （TargetCandidateSelectorTest#toleranceOnlyHitsPickTheCloserOne）。
#    两个框上下错开 110px，是为了让"从 A 右边界跨到 B 右边界"的假框因
#    纵向重叠不足 50% 被配对规则直接拒掉。
emit_selection_sample click_between_two_boxes_selects_neither 510 350 MISS - \
  - - 2 no - "点两个框正中间：谁都不许被选中（不许就近吸附）" \
  "drawbox=x=280:y=230:w=160:h=130:color=$BLUE:t=4,\
drawbox=x=580:y=340:w=160:h=140:color=$BLUE:t=4"

# 3) 【场景 C/E 的小框版】两个大小不同的框部分重叠，点(540,400) 落在重叠区里，
#    所以两者都是严格命中。小框（140x120）在面积上明显更"具体"，
#    在归一化中心距离上也更近（0.54 对 0.60）→ 两项判据一致指向小框，必须选小框。
#    注意大框在 ROI 里被左边界截断（它的左边线在 ROI 之外），
#    因此落到比较里的是"裁剪后的大框"——这正是场景 H 的真实形态。
emit_selection_sample overlapping_boxes_prefer_the_smaller_one 540 400 HIT_BOX "520,340,660,460" \
  EXACT_HIT 2 - no - "大小不同的两框重叠，点在重叠区：必须选更小更具体的小框" \
  "drawbox=x=300:y=220:w=260:h=260:color=$BLUE:t=4,\
drawbox=x=520:y=340:w=140:h=120:color=$BLUE:t=4"

# 4) 【场景 G】小目标贴着一个大框（大框在其左上方）：点(610,380) 落在小框内，
#    到大框最近边 131px，连容差都够不到 → 命中数必须是 1，必须选小框。
#    大框在 ROI 里只剩一条右边线和两条水平边（左边线在 ROI 之外），
#    被恢复成一个"裁剪框"——它照样不该把点击吸走。
emit_selection_sample small_target_next_to_a_large_box 610 380 HIT_BOX "560,340,660,420" \
  EXACT_HIT 1 2 no - "小目标贴大框：点小目标必须选小目标，不许被大框吸走" \
  "drawbox=x=260:y=240:w=220:h=200:color=$BLUE:t=4,\
drawbox=x=560:y=340:w=100:h=80:color=$BLUE:t=4"

# 5) 【点击 bbox 边缘】点(285,335) 落在框左边线内侧 5px（框 120x170）。
#    点"贴着框线"是真实点击习惯，必须仍然是严格包含命中，不能退化成容差命中。
#    框取得窄（120px）是为了让 ROI 右界(touch+200=485)离框右边线(400)有 85px 余量，
#    不会因为"边线正好压住 ROI 边界"而把框标成 clippedByRoi。
emit_selection_sample click_just_inside_the_box_border 285 335 HIT_BOX "280,250,400,420" \
  EXACT_HIT 1 1 no - "点框线内侧 5px：必须仍算严格包含命中" \
  "drawbox=x=280:y=250:w=120:h=170:color=$BLUE:t=4"

# 6) 【点 id 文字区域附近】框(260,220,545,360) 下方 35px 处画 id:1963 蓝字，
#    点(355,411) 落在文字块正中，离框下边线 51px → 既不在框内也够不到容差 → MISS。
#    这条样本真正要证明的是：**文字没有变成一个候选框**。于是显式断言候选数 = 1
#    （只有那个框），并断言任何候选框的下边界都不得越过 380（越过就说明文字行被
#    当成了一条边、和框的边配出了一个"更高的框"）。
#    文字宽度约 103px，只覆盖框宽 285px 的 36% < minEdgeCoverage(0.5)，
#    所以它连"一条边"都算不上；把它当"边带"看时厚度又超过 16px 上限，同样被拒。
emit_selection_sample click_on_the_id_text_below_a_box 355 411 MISS - \
  - - 1 no 380 "点 id:1963 文字区域：文字不得成为候选框，也不得并入上面的框" \
  "drawbox=x=260:y=220:w=285:h=140:color=$BLUE:t=4,\
drawtext=fontfile='$FONT_ESC':text='id\\:1963':x=300:y=395:fontsize=30:fontcolor=$BLUE"

# 7) 【场景 C/E】两个同尺寸框部分重叠，点(450,435) 落在重叠区
#    （x 440..500 × y 420..440）内，因此两者都是严格命中、面积相同。
#    此时唯一能分胜负的是"离谁的中心更近"：A 中心(400,340) 距 107px、B 中心(540,520) 距 124px，
#    归一化后 0.76 对 0.88 → 必须选 A。
#    同尺寸是刻意的：面积项因此抵消，样本测的就只剩几何这一件事。
#    两个框在 x 方向只重叠 60px（不足各自宽度的 50%），于是"跨框假框"会被
#    配对规则（重叠需达到较长边的 minEdgeCoverage）直接拒掉，样本因此干净。
emit_selection_sample overlapping_boxes_pick_the_nearer_center 450 435 HIT_BOX "300,240,500,440" \
  EXACT_HIT 2 - no - "两框部分重叠，点在重叠区：必须选中心更近的那个" \
  "drawbox=x=300:y=240:w=200:h=200:color=$BLUE:t=4,\
drawbox=x=440:y=420:w=200:h=200:color=$BLUE:t=4"

# 8) 【需求 3 的真实像素版】严格包含 vs 容差命中，且容差那个框在软性指标上更"讨喜"。
#    A 是 190x190 的框，点(535,375) 在它内部、离右边线 5px；
#    B 是 70x70 的小框，紧贴在 A 右边线外 6px 处，点离它 11px → 容差命中。
#    软性指标对比：B 面积只有 A 的 14%（更小更具体，偏好小框的策略会站它那边），
#    B 的归一化中心距离 0.92 也差于 A 的 0.67 —— 也就是说 B 面积占优、A 几何占优。
#    结论必须由硬主键给出：A 是"点在框里"，B 只是"够到了" → 必须选 A。
#    这条样本与单元测试 exactHitAlwaysBeatsToleranceHitEvenWhenTheToleranceBoxScoresHigher
#    是同一命题的两个层面：那里用"把权重全清零"构造反例，这里用真实压缩过的像素。
emit_selection_sample exact_containment_beats_the_smaller_box_beside_it 535 375 HIT_BOX \
  "350,280,540,470" EXACT_HIT 2 2 no - \
  "严格包含 vs 邻框容差命中：面积更小者也不许抢走真正被点中的框" \
  "drawbox=x=350:y=280:w=190:h=190:color=$BLUE:t=4,\
drawbox=x=546:y=345:w=70:h=70:color=$BLUE:t=4"

# 9) 【场景 D】大框套小框：点(385,320) 同时在大框与小框内，两者都是严格命中。
#    小框（110x100）在面积（11000 vs 72900）与归一化中心距离（0.067 vs 0.079）两项上都占优
#    → 必须选小框。"大框面积更大因而更可信"这种想法在这里被明确否掉。
#
#    这条样本还兼任一个检测层的回归哨兵：内层框的两条水平边如果被外框竖边"借走跨度"，
#    实心度会掉到 0.44 而被丢弃，候选数就只剩 1 —— 于是本样本会以"期望 2 个候选、实际 1 个"
#    的形式失败，直接把"嵌套框被吞掉"这件事顶到台面上，而不是悄悄变成一次错误的选择。
emit_selection_sample nested_boxes_prefer_the_smaller 385 320 HIT_BOX "330,270,440,370" \
  EXACT_HIT 2 2 no - "大框套小框：两者都包含点击点，必须选更小更具体的" \
  "drawbox=x=250:y=200:w=270:h=270:color=$BLUE:t=4,\
drawbox=x=330:y=270:w=110:h=100:color=$BLUE:t=4"

echo
echo "完成：$(ls -1 "$OUT_DIR"/*.rgba | wc -l) 个 Slice 4 样本 → $OUT_DIR"
echo "      $(ls -1 "$BOX_OUT_DIR"/*.rgba | wc -l) 个 Slice 5 样本 → $BOX_OUT_DIR"
echo "      $(ls -1 "$SELECT_OUT_DIR"/*.rgba | wc -l) 个 Slice 6 样本 → $SELECT_OUT_DIR"
echo "跑验证："
echo "  ./gradlew :targetselection:testDebugUnitTest --tests '*BlueMaskSampleSmokeTest*' \\"
echo "      -Dtargetselection.sampleDir=$OUT_DIR \\"
echo "      -Dtargetselection.sampleReport=$OUT_DIR/report.txt"
echo "  ./gradlew :targetselection:testDebugUnitTest --tests '*TargetBoxSampleSmokeTest*' \\"
echo "      -Dtargetselection.boxSampleDir=$BOX_OUT_DIR \\"
echo "      -Dtargetselection.boxSampleReport=$BOX_OUT_DIR/report.txt"
echo "  ./gradlew :targetselection:testDebugUnitTest --tests '*TargetBoxSampleSmokeTest*' \\"
echo "      -Dtargetselection.boxSampleDir=$SELECT_OUT_DIR \\"
echo "      -Dtargetselection.boxSampleReport=$SELECT_OUT_DIR/report.txt"

