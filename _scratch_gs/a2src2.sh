#!/system/bin/sh
# A2 来源判定 — 语法严谨版
# 目的: 找出【无 reset 窗口】是否也出现 waveform[6] update[1] (A2 全屏)
OUT=/data/local/tmp/a2src2.txt
: > "$OUT"
echo "model=$(getprop ro.product.model)" > "$OUT"
if [ ! -e /sys/class/sepdc/debug/dump_list ]; then echo "ABORT_NO_SEPDC" >> "$OUT"; echo ALLDONE >> "$OUT"; exit 1; fi
am start -n com.legado.app.release/io.legado.app.ui.book.read.ReadBookActivity >/dev/null 2>&1
sleep 6
echo "resumed=$(dumpsys activity activities 2>/dev/null | grep -c 'mResumedActivity.*legado')" >> "$OUT"

# 清空 dmesg 并记录基线
dmesg -c >/dev/null 2>&1
sleep 1
BASE=$(dmesg | wc -l)
echo "baseline_lines=$BASE" >> "$OUT"

# 120 轮翻页, 每轮 dump 3 次
i=0
while [ "$i" -lt 120 ]; do
  input swipe 1200 500 250 500 80
  j=0
  while [ "$j" -lt 3 ]; do
    cat /sys/class/sepdc/debug/dump_list >/dev/null 2>&1
    sleep 0.15
    j=$((j+1))
  done
  i=$((i+1))
done
echo "swipe_done" >> "$OUT"

# 20s 静置 dump
k=0
while [ "$k" -lt 20 ]; do
  cat /sys/class/sepdc/debug/dump_list >/dev/null 2>&1
  sleep 1
  k=$((k+1))
done
echo "end" >> "$OUT"

# 导出完整 dmesg 到文件
dmesg > /data/local/tmp/a2src2_dmesg.txt 2>/dev/null
echo "dmesg_lines=$(wc -l < /data/local/tmp/a2src2_dmesg.txt)" >> "$OUT"
echo "reset=$(grep -c 'reset cause' /data/local/tmp/a2src2_dmesg.txt)" >> "$OUT"
echo "a2full=$(grep -c 'waveform\[6\] update\[1\]' /data/local/tmp/a2src2_dmesg.txt)" >> "$OUT"
echo "localu0=$(grep -c 'update\[0\]' /data/local/tmp/a2src2_dmesg.txt)" >> "$OUT"
echo ALLDONE >> "$OUT"
