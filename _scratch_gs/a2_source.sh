#!/system/bin/sh
# 决定性实验: A2 全屏(waveform[6] update[1]) 的真实来源
# 方法: 连续翻页 120s, 全程高频 cat dump_list 到 dmesg, 导出完整 dmesg 离线分析
#       重点: 找出【无 reset 的时段】是否也出现 A2 全屏
OUT=/data/local/tmp/a2_source.txt
: > $OUT
echo "model=$(getprop ro.product.model) uptime=$(cat /proc/uptime)" > $OUT
if [ ! -e /sys/class/sepdc/debug/dump_list ]; then echo "ABORT sepdc missing" >> $OUT; echo ALLDONE >> $OUT; exit 1; fi

# 确保阅读器在前台
am start -n com.legado.app.release/io.legado.app.ui.book.read.ReadBookActivity >/dev/null 2>&1
sleep 6
dumpsys activity activities 2>/dev/null | grep mResumedActivity | head -1 >> $OUT

echo "=== 标记 START ===" >> $OUT
dmesg -c > /dev/null 2>&1
sleep 1

# 120 轮: 每轮 swipe 翻页 + 多次 dump
i=0
while [ $i -lt 120 ]; do
  input swipe 1200 500 250 500 80
  j=0
  while [ $j -lt 3 ]; do
    cat /sys/class/sepdc/debug/dump_list >/dev/null 2>&1
    sleep 0.15
    j=$((j+1))
  done
  i=$((i+1))
done
echo "=== 标记 SWIPE_DONE (共 120 次滑) ===" >> $OUT

# 再记录 20s 纯 dump (不操作)
k=0
while [ $k -lt 20 ]; do
  cat /sys/class/sepdc/debug/dump_list >/dev/null 2>&1
  sleep 1
  k=$((k+1))
done
echo "=== 标记 END ===" >> $OUT

# 导出完整 dmesg
dmesg > /data/local/tmp/a2_source_dmesg.txt
echo "dmesg_lines=$(wc -l < /data/local/tmp/a2_source_dmesg.txt)" >> $OUT
echo "reset=$(dmesg|grep -c 'reset cause')" >> $OUT
echo ALLDONE >> $OUT
