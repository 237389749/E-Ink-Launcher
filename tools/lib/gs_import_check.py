"""未用 import 检查（替代编译的静态检查）。"""
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

DIR = r'C:\Users\root\Documents\eink\E-Ink-Launcher\app\src\main\java\cn\modificator\launcher'
FILES = ['RefreshModeHelper.java', 'SettingFragment.java', 'Launcher.java',
         'GlobalEacRefreshHelper.java']


def strip_comments_strings(src):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


bad = 0
for f in FILES:
    p = os.path.join(DIR, f)
    src = open(p, encoding='utf8').read()
    body = strip_comments_strings(src)
    imports = re.findall(r'^import\s+(?:static\s+)?([\w.]+);', src, re.M)
    unused = []
    for imp in imports:
        simple = imp.split('.')[-1]
        if simple == '*':
            continue
        # 统计 import 行之外的出现次数
        n = len(re.findall(r'\b' + re.escape(simple) + r'\b', body))
        # import 行本身已被 strip? 不会：strip 保留 import 文本，故减 1
        if n <= 1:
            unused.append(imp)
    print(f'{f}: import {len(imports)} 条, 疑似未用 {len(unused)} 条')
    for u in unused:
        print(f'   !! 未用: {u}')
    bad += len(unused)

print()
print('未用 import 检查:', 'PASS' if bad == 0 else f'FAIL ({bad})')

# 额外：确认被删符号在代码里彻底消失
print()
for sym in ('applyFixedEac', 'FIXED_EAC_LOGIC', 'initEac', 'eacHelperClass',
            'EAC_HELPER', 'eacInited', 'runRoot', 'BufferedReader',
            'InputStreamReader', 'InputStream'):
    hits = []
    for f in FILES:
        src = open(os.path.join(DIR, f), encoding='utf8').read()
        body = strip_comments_strings(src)
        if re.search(r'\b' + re.escape(sym) + r'\b', body):
            hits.append(f)
    print(f'  {sym:<20} 代码中出现于: {hits if hits else "（无）"}')
sys.exit(0 if bad == 0 else 1)
