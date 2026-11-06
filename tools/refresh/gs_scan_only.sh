#!/system/bin/sh
# 纯只读复核：当前所有 eac_app_<pkg> 的 refreshConfig（不做任何写入）
# ⚠️ 必须排除 eac_app_pkg_set：它是系统内部"包名集合"键（值为裸包名列表，非 JSON 配置），
#    误当 pkg 传入 FixEac 会抛 fastjson JSONException（2026-11-06 故障机实测）。
PKGS=$(CLASSPATH=/data/local/tmp/fixeac.dex app_process /system/bin io.onyx.FixEac keys eac_app_ | grep '^eac_app_' | grep -v '^eac_app_pkg_set$' | sed 's/^eac_app_//' | tr '\n' ',')
CLASSPATH=/data/local/tmp/fixeac.dex app_process /system/bin io.onyx.FixEac read "$PKGS" > /data/local/tmp/scan2.txt 2>&1
echo "pkg 数: $(echo "$PKGS" | tr ',' '\n' | grep -c .)"
echo "um=9 行数: $(grep -c 'um=9' /data/local/tmp/scan2.txt)"
echo "eac_app_ 行的 updateMode 分布:"
grep 'eac_app_' /data/local/tmp/scan2.txt | grep -o 'um=[-0-9]*' | sort | uniq -c
echo
echo "非 um=5 的 eac_app_ 行（最多 12 条）:"
grep 'eac_app_' /data/local/tmp/scan2.txt | grep -v 'um=5' | head -12
echo
echo "theme 行里出现的异常值（非 0/5）:"
grep 'theme\[' /data/local/tmp/scan2.txt | grep -oE 'um=[-0-9]+' | sort | uniq -c | grep -vE 'um=(0|5)$'