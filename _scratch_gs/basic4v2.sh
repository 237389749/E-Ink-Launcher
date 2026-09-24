#!/system/bin/sh
# 在正确设备 (Poke6 6C7F0E64) 上重测 4 个基础波形在 scope 通道的表现
# 先验证 sepdc 存在
OUT=/data/local/tmp/basic4v2.txt
: > $OUT
if [ ! -e /sys/class/sepdc/debug/dump_list ]; then
  echo "ERROR: sepdc dump_list 不存在, 设备不对!" >> $OUT
  echo ALLDONE >> $OUT
  exit 1
fi
echo "device_ok model=$(getprop ro.product.model)" >> $OUT
am start -n com.legado.app.release/io.legado.app.ui.book.read.ReadBookActivity >/dev/null 2>&1
sleep 5
for val in 1 2 4 2312; do
  echo "===== value=$val =====" >> $OUT
  CLASSPATH=/data/local/tmp/sscope.dex app_process /system/bin io.onyx.SetScope $val >/dev/null 2>&1
  sleep 2
  dmesg -c > /dev/null 2>&1
  i=0
  while [ $i -lt 8 ]; do
    cat /sys/class/sepdc/debug/dump_list > /dev/null 2>&1
    input swipe 600 800 600 300 150
    sleep 0.35
    i=$((i+1))
  done
  sleep 3
  j=0
  while [ $j -lt 8 ]; do
    cat /sys/class/sepdc/debug/dump_list > /dev/null 2>&1
    sleep 0.25
    j=$((j+1))
  done
  dmesg | grep -oE "waveform\[[0-9]+\] update\[[0-9]+\] frame_cur\[[0-9]+\] frame_total\[[0-9]+\]" | sed -E 's/frame_cur\[[0-9]+\] //' | sort | uniq -c | sort -rn | head -4 >> $OUT
  echo "reset=$(dmesg | grep -c 'reset cause') pending=$(dmesg | grep -c 'pending_list(): magic')" >> $OUT
done
CLASSPATH=/data/local/tmp/cscope.dex app_process /system/bin io.onyx.ClearScope >/dev/null 2>&1
echo ALLDONE >> $OUT
