#!/usr/bin/env python3
"""合并 rime-frost base.dict.yaml 到 pinyin_simp 词库（生成独立扩展词库文件）。

用法：
    python3 scripts/merge_base_dict.py \
        --base /path/to/rime-frost-schemas/cn_dicts/base.dict.yaml \
        --main app/src/main/assets/rime/pinyin_simp.dict.yaml \
        --out app/src/main/assets/rime/pinyin_simp_ext.dict.yaml

规则：
- 去重键 = (词条, 规范化编码)；已有词条（含不同注音）全部保留，只追加 base 中
  主词库没有的 (词条, 编码) 组合；base 内部重复取先出现者（该词库已按拼音+词频排序）。
- 词频保留 base 原值（两个词库同一频次量纲，中位数不倒挂，无需缩放）。
- 音节校验：新条目的每个音节必须存在于主词库既有音节集（保证 T9 数字棱镜
  编译与 PinyinToDigitCode 显示链不引入未知音节），不满足者丢弃并计数。
- 输出为独立文件 pinyin_simp_ext.dict.yaml，由 pinyin_simp.dict.yaml 通过
  import_tables 引用，不改动主词库本体。
"""

import argparse
import re
import sys
from collections import OrderedDict

DICT_EXTENSION = ".dict.yaml"

# 来源词库注音约定 → 主词库注音约定的音节重映射
# （rime-ice 用 nve/lve 表示 üe，pinyin_simp 用 nue/lue）
SYLLABLE_REMAP = {
    "nve": "nue",
    "lve": "lue",
}


def parse_entries(path):
    """解析 rime 词库：返回 (yaml_header_lines, entries)。

    entries 为 (word, code, weight_or_None, line_no) 四元组；
    跳过注释行、表头分隔符与空行。编码做空白规范化。
    """
    header = []
    entries = []
    in_body = False
    seen_body_start = False
    with open(path, encoding="utf-8") as f:
        for line_no, raw in enumerate(f, 1):
            line = raw.rstrip("\n")
            if not in_body:
                header.append(line)
                if line.strip() == "...":
                    in_body = True
                    seen_body_start = True
                continue
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            word = parts[0].strip()
            code = re.sub(r"\s+", " ", parts[1].strip())
            weight = None
            if len(parts) >= 3:
                w = parts[2].strip()
                if re.fullmatch(r"-?\d+(\.\d+)?", w):
                    weight = w
            entries.append((word, code, weight, line_no))
    if not seen_body_start:
        # pinyin_simp 式文件：表头以 ... 结尾后直接跟条目（in_body 已覆盖）；
        # 若整个文件都没有 ... 分隔符，视为纯条目文件
        if not entries:
            raise SystemExit(f"{path}: 未找到表头分隔符 '...' 且无条目")
    return header, entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="rime-frost base.dict.yaml")
    parser.add_argument("--main", required=True, help="主词库 pinyin_simp.dict.yaml")
    parser.add_argument("--out", required=True, help="输出扩展词库路径")
    parser.add_argument(
        "--name", default=None,
        help="扩展词库名（默认取输出文件名去掉 .dict.yaml 后缀）")
    args = parser.parse_args()

    main_header, main_entries = parse_entries(args.main)
    base_header, base_entries = parse_entries(args.base)

    main_keys = {(w, c) for w, c, _, _ in main_entries}
    main_syllables = set()
    for _, code, _, _ in main_entries:
        main_syllables.update(code.split(" "))

    out_name = args.name or (
        args.out.rsplit("/", 1)[-1][: -len(DICT_EXTENSION)])

    new_entries = OrderedDict()
    seen_out = set()
    dup_main = dup_base = bad_syllable = 0
    bad_syl_samples = []

    for word, code, weight, line_no in base_entries:
        key = (word, code)
        if key in main_keys:
            dup_main += 1
            continue
        if key in seen_out:
            dup_base += 1
            continue
        syllables = code.split(" ")
        unknown = [s for s in syllables if s not in main_syllables]
        if unknown:
            remapped = [SYLLABLE_REMAP.get(s, s) for s in syllables]
            if any(s not in main_syllables for s in remapped):
                bad_syllable += 1
                if len(bad_syl_samples) < 10:
                    bad_syl_samples.append(f"{word}\t{code}  (未知音节: {unknown})")
                continue
            code = " ".join(remapped)
            key = (word, code)
            if key in main_keys or key in seen_out:
                dup_main += 1
                continue
        seen_out.add(key)
        new_entries[key] = (word, code, weight)

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("# Rime dictionary\n")
        f.write("# encoding: utf-8\n")
        f.write("#\n")
        f.write("# pinyin_simp 扩展词库（自动生成，请勿手工编辑）\n")
        f.write("# 来源：rime-frost / rime-ice cn_dicts/base.dict.yaml 合并去重\n")
        f.write("# 生成：scripts/merge_base_dict.py\n")
        f.write("\n")
        f.write("---\n")
        f.write(f"name: {out_name}\n")
        f.write('version: "1.0"\n')
        f.write("sort: by_weight\n")
        f.write("...\n")
        for word, code, weight in new_entries.values():
            if weight is None:
                f.write(f"{word}\t{code}\n")
            else:
                f.write(f"{word}\t{code}\t{weight}\n")

    print(f"主词库条目:        {len(main_entries)}")
    print(f"base 条目:         {len(base_entries)}")
    print(f"与主词库重复跳过:  {dup_main}")
    print(f"base 内部重复跳过: {dup_base}")
    print(f"未知音节丢弃:      {bad_syllable}")
    print(f"新增条目:          {len(new_entries)} -> {args.out} (name={out_name})")
    if bad_syl_samples:
        print("未知音节样例:")
        for s in bad_syl_samples:
            print(f"  {s}")

    # 提示主词库需要的 import_tables 接线
    if "import_tables" not in "\n".join(main_header):
        print(
            f"\n提示：请在 {args.main} 的表头（... 之前）加入：\n"
            f"import_tables:\n  - {out_name}\n"
        )


if __name__ == "__main__":
    main()
