#!/system/bin/sh
# 按 MMKV 里【实际存在】的 eac_app_<pkg> 全量重置（比 pm list 更全：含已卸载 app 的残留）
PKGS=$(CLASSPATH=/data/local/tmp/fixeac.dex app_process /system/bin io.onyx.FixEac keys eac_app_ | grep '^eac_app_' | sed 's/^eac_app_//' | tr '\n' ',')
N=$(echo "$PKGS" | tr ',' '\n' | grep -c .)
echo "=== 全量重置 $N 个 pkg -> NONE + um=5 ==="
CLASSPATH=/data/local/tmp/fixeac.dex app_process /system/bin io.onyx.FixEac fix "$PKGS" 5 | tail -3
echo
echo "=== 复核：um=9 是否清零（按 eac_app_ 与 theme 当前值）==="
CLASSPATH=/data/local/tmp/fixeac.dex app_process /system/bin io.onyx.FixEac read "$PKGS" > /data/local/tmp/rescan.txt 2>&1
echo "still-um9 行数: $(grep -c 'um=9' /data/local/tmp/rescan.txt)"
echo "still-um9 具体:"
grep 'um=9' /data/local/tmp/rescan.txt | head -10
echo
echo "updateMode 当前值分布（仅 eac_app_ 行）:"
grep 'eac_app_' /data/local/tmp/rescan.txt | grep -o 'um=[-0-9]*' | sort | uniq -c