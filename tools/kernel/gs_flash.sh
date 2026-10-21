#!/system/bin/sh
# 刷入 boot_b 并回读校验。用法: sh gs_flash.sh <img_on_sdcard> <expected_md5>
IMG=${1:?usage: gs_flash.sh <img> <md5>}
EXP=${2:?need md5}
DEV=/dev/block/by-name/boot_b
SZ=$(stat -c %s /sdcard/$IMG)

echo "=== before ==="
echo "target      : $DEV ($(readlink -f $DEV))"
echo "image       : /sdcard/$IMG  size=$SZ"
echo "image md5   : $(md5sum /sdcard/$IMG | cut -d' ' -f1)  expect=$EXP"
[ "$(md5sum /sdcard/$IMG | cut -d' ' -f1)" = "$EXP" ] || { echo "ABORT: image md5 mismatch"; exit 1; }

echo
echo "=== writing ==="
dd if=/sdcard/$IMG of=$DEV bs=4M 2>&1 | tail -3
sync

echo
echo "=== readback verify ==="
dd if=$DEV of=/data/local/tmp/rb.img bs=4096 count=$(( (SZ + 4095) / 4096 )) 2>/dev/null
RB=$(md5sum /data/local/tmp/rb.img | cut -d' ' -f1)
echo "readback md5: $RB"
[ "$RB" = "$EXP" ] && echo "WRITE OK" || { echo "WRITE MISMATCH -- 立即回滚: dd if=/sdcard/boot_patched_a2_v6.img of=$DEV bs=4M"; exit 2; }
rm -f /data/local/tmp/rb.img
echo
echo "刷入完成，未重启。回滚命令："
echo "  su -c \"dd if=/sdcard/boot_patched_a2_v6.img of=$DEV bs=4M\""