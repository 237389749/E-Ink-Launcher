#!/system/bin/sh
echo "=== 健康检查 ==="
dmesg > /data/local/tmp/hc.txt 2>&1
echo "  uptime            = $(cut -d' ' -f1 /proc/uptime)"
echo "  reset cause       = $(grep -ac 'reset cause' /data/local/tmp/hc.txt)"
echo "  wait all_lut_free = $(grep -ac 'wait all_lut_free timeout' /data/local/tmp/hc.txt)"
echo "  wait lut_free     = $(grep -ac 'wait lut_free timeout' /data/local/tmp/hc.txt)"
echo "  epdc power error  = $(grep -ac 'epdc power error' /data/local/tmp/hc.txt)"
echo "  waveform NULL     = $(grep -ac 'waveform_desc is NULL' /data/local/tmp/hc.txt)"
echo "  update_err        = $(cat /sys/class/sepdc/debug/update_err)"
echo "  Reg PowerGood     = $(grep -ac 'Reg PowerGood' /data/local/tmp/hc.txt) 行, 取值: $(grep -a 'Reg PowerGood' /data/local/tmp/hc.txt | sed 's/.*Reg PowerGood/Reg PowerGood/' | sort -u | tr '\n' ';')"
echo
echo "=== 状态 ==="
cat /sys/class/sepdc/debug/status
echo "  vcom = $(cat /sys/class/hwmon/hwmon0/device/vcom_value)   input_suspend = $(cat /sys/class/power_supply/battery/input_suspend)"
echo
echo "=== scope（launcher 配置 = DU）==="
CLASSPATH=/data/local/tmp/checkmode.dex app_process /system/bin io.onyx.CheckMode 2>&1 | head -2
echo
echo "=== 当前前台 ==="
dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity | sed 's/.*u0 //;s/ .*//'
echo
echo "=== 备份（回滚用）==="
echo "  eac_bak 个数 = $(ls -1 /data/local/tmp/eac_bak/ 2>/dev/null | wc -l)"
echo "  mmkv 快照    = $(ls -l /sdcard/onyx_config.before /sdcard/onyx_config.after 2>/dev/null | wc -l) 份"