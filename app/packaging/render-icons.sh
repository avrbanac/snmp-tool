#!/bin/sh
# Renders the application icon from snmp-tool.svg: window icons (app resources), the Linux package icon and
# the Windows .ico. Needs rsvg-convert and ImageMagick (magick).
set -eu
cd "$(dirname "$0")"
icons=../src/main/resources/hr/avrbanac/snmptool/app/icons
for size in 16 32 48 64 128 256; do
    rsvg-convert -w "$size" -h "$size" snmp-tool.svg -o "$icons/snmp-tool-$size.png"
done
cp "$icons/snmp-tool-256.png" linux/snmp-tool.png
magick "$icons/snmp-tool-16.png" "$icons/snmp-tool-32.png" "$icons/snmp-tool-48.png" "$icons/snmp-tool-256.png" \
    windows/snmp-tool.ico
