#!/bin/sh
#
# Turns a recording into the gif the README shows inline.
#
#   tools/media/to-gif.sh video/tour.webm docs/media/tour.gif
#
# The settings are a compromise found by measuring rather than by taste. GitHub renders a
# gif inline up to ten megabytes and links to it beyond that, which is the difference
# between a reader seeing the thing and a reader deciding not to click. At the recording's
# native 1280 the same clip is over six megabytes; at 820 it is three and a half, and the
# caption strip -- which is the whole reason a silent loop is worth anything -- is still
# legible. Eight frames a second is enough for a walk through an interface and is where
# most of the saving comes from.
#
# The two-pass palette matters more than any of that. A gif has 256 colours, and letting
# ffmpeg pick them from this clip rather than from a fixed table is what keeps a dark
# interface from banding into mud.
set -eu

src=${1:?usage: to-gif.sh <input.webm> <output.gif> [fps] [width]}
dst=${2:?usage: to-gif.sh <input.webm> <output.gif> [fps] [width]}
fps=${3:-8}
width=${4:-820}

ffmpeg -v error -i "$src" -vf "
    fps=${fps},
    scale=${width}:-1:flags=lanczos,
    split[a][b];
    [a]palettegen=max_colors=96[p];
    [b][p]paletteuse=dither=bayer:bayer_scale=4
  " -loop 0 "$dst" -y

bytes=$(wc -c < "$dst")
printf '%s  %s bytes' "$dst" "$bytes"
if [ "$bytes" -gt 10485760 ]; then
  printf '  <-- over 10 MB; GitHub will link it rather than render it\n'
  exit 1
fi
printf '  (renders inline)\n'
