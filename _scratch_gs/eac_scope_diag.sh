#!/system/bin/sh
# 决定性实验: (1) EAC 实际配置 (2) 空闲 vs 操作的 reset 差异 (3) wf[6] 真实来源
OUT=/data/local/tmp/eac_scope_diag.txt
: > $OUT

echo "=== 设备身份校验 ===" >> $OUT
echo "model=$(getprop ro.product.model) device=$(getprop ro.product.device) uptime=$(cat /proc/uptime)" >> $OUT
if [ -e /sys/class/sepdc/debug/dump_list ]; then echo "sepdc_OK" >> $OUT; else echo "sepdc_MISSING_ABORT" >> $OUT; echo ALLDONE >> $OUT; exit 1; fi

echo "=== EAC 配置路径探测 ===" >> $OUT
ls -d /data/data/*onyx* /data/onyx 2>/dev/null >> $OUT
echo "--- eac prefs 文件 ---" >> $OUT
find /data/data -maxdepth 3 -iname "*eac*" 2>/dev/null | head -15 >> $OUT
echo "--- eac_app / eac_theme 存储 ---" >> $OUT
grep -rl "eac_app\|eac_theme\|refreshModeIndex" /data/data/*/shared_prefs/ 2>/dev/null | head -10 >> $OUT

echo "=== 当前 mode (note: currentTop 可能 null) ===" >> $OUT
CLASSPATH=/data/local/tmp/checkmode.dex app_process /system/bin io.onyx.CheckMode >> $OUT 2>&1

echo "=== 前台 activity ===" >> $OUT
dumpsys activity activities 2>/dev/null | grep mResumedActivity | head -2 >> $OUT

echo "=== 实验A: 空闲 45s (完全不操作, 高频 dump) ===" >> $OUT
dmesg -c > /dev/null 2>&1
i=0
while [ $i -lt 45 ]; do
  cat /sys/class/sepdc/debug/dump_list >/dev/null 2>&1
  sleep 1
  i=$((i+1))
done
echo "空闲: reset=$(dmesg|grep -c 'reset cause') pending=$(dmesg|grep -c 'pending_list(): magic') timeout=$(dmesg|grep -c 'all_lut_free timeout') powererr=$(dmesg|grep -c 'epdc power error')" >> $OUT
echo "空闲 wf 分布:" >> $OUT
dmesg | grep -oE 'waveform\[[0-9]+\] update\[[0-9]+\] frame_total\[[0-9]+\]' | sort | uniq -c | sort -rn | head -8 >> $OUT

echo "=== 实验B: 操作 40s (反复翻页 + 高频 dump) ===" >> $OUT
dmesg -c > /dev/null 2>&1
i=0
while [ $i -lt 20 ]; do
  input tap 1300 500
  sleep 0.4
  cat /sys/class/sepdc/debug/dump_list >/dev/null 2>&1
  sleep 0.6
  i=$((i+1))
done
echo "操作: reset=$(dmesg|grep -c 'reset cause') pending=$(dmesg|grep -c 'pending_list(): magic') timeout=$(dmesg|grep -c 'all_lut_free timeout') powererr=$(dmesg|grep -c 'epdc power error')" >> $OUT
echo "操作 wf 分布:" >> $OUT
dmesg | grep -oE 'waveform\[[0-9]+\] update\[[0-9]+\] frame_total\[[0-9]+\]' | sort | uniq -c | sort -rn | head -8 >> $OUT

echo "=== 实验C: 主动全屏刷新 5 次 (看 wf 与 flags) ===" >> $OUT
dmesg -c > /dev/null 2>&1
CLASSPATH=/data/local/tmp/tw.dex app_process /system/bin io.onyx.TestWaveform 2 1 >/dev/null 2>&1
sleep 3
echo "全屏刷新: reset=$(dmesg|grep -c 'reset cause')" >> $OUT
dmesg | grep -oE 'waveform\[[0-9]+\] update\[[0-9]+\] frame_total\[[0-9]+\]' | sort | uniq -c | sort -rn | head -6 >> $OUT
dmesg | grep -oE 'SET_EBC_SEND_UPDATE.*flags = 0x[0-9a-f]+' | tail -4 >> $OUT

echo ALLDONE >> $OUT
