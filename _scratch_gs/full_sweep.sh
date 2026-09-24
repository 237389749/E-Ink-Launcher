#!/system/bin/sh
# 全面刷新 (FULL 位) 覆盖测试: 波形号 0-12 各 | FULL(32), 看落哪些槽
# 目的: 是否激活局部刷新见不到的槽 (如 [0] INIT / [3] / [5])
OUT=/data/local/tmp/full_sweep.txt
: > $OUT

am start -n com.legado.app.release/io.legado.app.ui.book.read.ReadBookActivity >/dev/null 2>&1
sleep 5

# 32=INIT|FULL 33=DU|FULL 34=GC16|FULL 35=GC4|FULL 36=ANIM|FULL 37=AUTO|FULL
# 38=REAGL|FULL 39=DU4|FULL 40=REAGL_PLUS|FULL 43=GCC16|FULL 44=DEEP_GC16|FULL
for val in 32 33 34 35 36 37 38 39 40 43 44; do
  echo "===== value=$val (wave=$((val & 15)) | FULL) =====" >> $OUT
  dmesg -c > /dev/null 2>&1
  CLASSPATH=/data/local/tmp/tw.dex app_process /system/bin io.onyx.TestWaveform $val 2 >/dev/null 2>&1 &
  PID=$!
  j=0
  while [ $j -lt 40 ]; do
    cat /sys/class/sepdc/debug/dump_list > /dev/null 2>&1
    sleep 0.1
    j=$((j+1))
  done
  wait $PID 2>/dev/null
  sleep 0.5
  dmesg | grep -oE "waveform\[[0-9]+\] update\[[0-9]+\] frame_cur\[[0-9]+\] frame_total\[[0-9]+\]" | sed -E 's/frame_cur\[[0-9]+\] //' | sort | uniq -c | sort -rn | head -5 >> $OUT
  echo "reset=$(dmesg | grep -c 'reset cause')" >> $OUT
done
CLASSPATH=/data/local/tmp/cscope.dex app_process /system/bin io.onyx.ClearScope >/dev/null 2>&1
echo ALLDONE >> $OUT
