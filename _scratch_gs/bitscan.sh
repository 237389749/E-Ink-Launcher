#!/system/bin/sh
# 位扫描: 找出哪个标志位导致 update[1] 全屏
# 候选: 4(A2裸) 2308(4|DITHER) 2305 2312 98(2|FULL|WAIT) 2 257
OUT=/data/local/tmp/bitscan.txt
: > $OUT
if [ ! -e /sys/class/sepdc/debug/dump_list ]; then echo "ABORT_NO_SEPDC" >> $OUT; echo ALLDONE >> $OUT; exit 1; fi
echo "model=$(getprop ro.product.model)" >> $OUT
am start -n com.legado.app.release/io.legado.app.ui.book.read.ReadBookActivity >/dev/null 2>&1
sleep 5

run_one() {
  V=$1
  echo "===== scope=$V =====" >> $OUT
  CLASSPATH=/data/local/tmp/sscope.dex app_process /system/bin io.onyx.SetScope "$V" >/dev/null 2>&1
  sleep 2
  dmesg -c > /dev/null 2>&1
  i=0
  while [ $i -lt 5 ]; do
    input swipe 1300 700 200 700 70
    j=0
    while [ $j -lt 3 ]; do cat /sys/class/sepdc/debug/dump_list >/dev/null 2>&1; sleep 0.12; j=$((j+1)); done
    i=$((i+1))
  done
  sleep 2
  k=0
  while [ $k -lt 6 ]; do cat /sys/class/sepdc/debug/dump_list >/dev/null 2>&1; sleep 0.2; k=$((k+1)); done
  echo "  wf:" >> $OUT
  dmesg | grep -oE 'waveform\[[0-9]+\] update\[[0-9]+\] frame_total\[[0-9]+\]' | sort | uniq -c | sort -rn | head -4 >> $OUT
  echo "  reset=$(dmesg | grep -c 'reset cause') timeout=$(dmesg | grep -c 'all_lut_free timeout')" >> $OUT
}

for v in 2 4 2308 2305 2312 257 98 1 6; do run_one $v; done
CLASSPATH=/data/local/tmp/cscope.dex app_process /system/bin io.onyx.ClearScope >/dev/null 2>&1
CLASSPATH=/data/local/tmp/sscope.dex app_process /system/bin io.onyx.SetScope 2 >/dev/null 2>&1
echo ALLDONE >> $OUT
