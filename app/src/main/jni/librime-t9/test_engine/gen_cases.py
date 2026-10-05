#!/usr/bin/env python3
# 从拼音词库反推九键键码，生成引擎级测试用例。
#
# 词库行格式：`词条\t音节1 音节2 ...\t权重`。九键映射是确定的（a-c→2 … w-z→9），
# 因此每个词的键码序列可以由其词库编码唯一反推；音节边界同时生成
# 带分词键（segmented）与不带分词（digits）两种输入形态。
#
# 用例按权重分层采样（固定随机种子，结果可复现），产物 cases/t9_cases.yaml
# 提交入库供 review；修改采样策略后重新运行本脚本再生成。
#
# 用法（在仓库任意位置）：
#   python3 gen_cases.py --assets <app>/src/main/assets/rime --out cases/t9_cases.yaml

import argparse
import random
import sys
from pathlib import Path

# 九宫格字母→数字映射（与 t9_pinyin.schema.yaml 的 speller/algebra derive 一致）
KEY_MAP = {}
for letters, digit in [
    ("abc", "2"), ("def", "3"), ("ghi", "4"), ("jkl", "5"),
    ("mno", "6"), ("pqrs", "7"), ("tuv", "8"), ("wxyz", "9"),
]:
    for ch in letters:
        KEY_MAP[ch] = digit


def parse_dict(path: Path, imported: set):
    """解析一个 .dict.yaml：返回 [(text, [syllables], weight)]，并递归加载 import_tables。"""
    entries = []
    imports = []
    lines = path.read_text(encoding="utf-8").splitlines()
    state = "meta"  # --- 前注释 → meta（--- 与 ... 之间）→ data（... 之后）
    for line in lines:
        stripped = line.strip()
        if state == "meta":
            if stripped == "---":
                state = "body"
            continue
        if state == "body":
            if stripped == "...":
                state = "data"
                continue
            if stripped.startswith("- "):
                name = stripped[2:].split("#")[0].strip()
                if name:
                    imports.append(name)
            continue
        # data 区：`词条\t音节...\t权重`
        if not stripped or stripped.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        text = parts[0].strip()
        syllables = parts[1].strip().split()
        weight = 0
        if len(parts) >= 3:
            try:
                weight = int(parts[2].strip())
            except ValueError:
                weight = 0
        if not text or not syllables:
            continue
        if any(not s.isascii() or not s.isalpha() for s in syllables):
            continue  # 含非拼音编码的条目（如待整理行）跳过
        entries.append((text, syllables, weight))
    # 递归加载 import_tables（同目录相对路径；条目名可带可不带 .dict.yaml 后缀）
    for name in imports:
        for candidate in (path.parent / name, path.parent / f"{name}.dict.yaml"):
            if candidate.exists() and str(candidate) not in imported:
                imported.add(str(candidate))
                entries += parse_dict(candidate, imported)
                break
    return entries


def digits_of(syllables):
    digits = "".join("".join(KEY_MAP[c] for c in s) for s in syllables)
    return digits


def segmented_of(syllables):
    return "'".join("".join(KEY_MAP[c] for c in s) for s in syllables)


def sample(entries, curated_texts, quotas):
    """按分层采样挑用例；curated 精选词不在词库时告警跳过。

    quotas: {top_weight, top_single, top_zh_ch_sh, top_3syl, top_4syl, random}；
    --all 时各档配额为 None → 不限量全量生成。
    """
    cases = []
    seen = set()

    def add(text, syllables, weight, source):
        key = (text, tuple(syllables))
        if key in seen:
            return
        seen.add(key)
        cases.append({
            "text": text,
            "syllables": list(syllables),
            "digits": digits_of(syllables),
            "segmented": segmented_of(syllables),
            "weight": weight,
            "source": source,
        })

    for text in curated_texts:
        matched = [(t, s, w) for t, s, w in entries if t == text]
        if not matched:
            print(f"warning: curated word not in dict: {text}", file=sys.stderr)
            continue
        t, s, w = max(matched, key=lambda e: e[2])
        add(t, s, w, "curated")

    def take(bucket, quota, source, desc=True):
        ordered = sorted(bucket, key=lambda e: e[2], reverse=desc)
        picked = ordered if quota is None else ordered[:quota]
        for t, s, w in picked:
            add(t, s, w, source)

    two_plus = [e for e in entries if len(e[1]) >= 2]
    singles = [e for e in entries if len(e[1]) == 1]
    zcs = [e for e in two_plus if any(s[:2] in ("zh", "ch", "sh") for s in e[1])]
    three = [e for e in entries if len(e[1]) == 3]
    four_plus = [e for e in entries if len(e[1]) >= 4]

    take(two_plus, quotas["top_weight"], "top_weight")
    take(singles, quotas["top_single"], "top_single")
    take(zcs, quotas["top_zh_ch_sh"], "top_zh_ch_sh")
    take(three, quotas["top_3syl"], "top_3syl")
    take(four_plus, quotas["top_4syl"], "top_4syl")

    if quotas["random"] is None:
        take([e for e in two_plus if (e[0], tuple(e[1])) not in seen], None, "random")
    else:
        rng = random.Random(42)  # 固定种子保证可复现
        pool = [e for e in two_plus if (e[0], tuple(e[1])) not in seen]
        for t, s, w in rng.sample(pool, min(quotas["random"], len(pool))):
            add(t, s, w, "random")
    return cases


CURATED = [
    "测试", "时间", "实现", "知道", "你好", "中国", "我们", "他们",
    "什么", "怎么", "因为", "所以", "但是", "如果", "现在", "时候",
]


def main():
    ap = argparse.ArgumentParser(description="词库反推九键键码生成引擎测试用例")
    ap.add_argument("--assets", default=None,
                    help="app/src/main/assets/rime 目录（缺省按脚本位置自动推导）")
    ap.add_argument("--out", required=True, help="输出用例 YAML 路径")
    ap.add_argument("--all", action="store_true",
                    help="全量生成（约 39 万条，供专项跑；缺省分层采样约 4000 条）")
    args = ap.parse_args()

    if args.all:
        quotas = {k: None for k in
                  ("top_weight", "top_single", "top_zh_ch_sh", "top_3syl", "top_4syl", "random")}
    else:
        quotas = {
            "top_weight": 2500,
            "top_single": 500,
            "top_zh_ch_sh": 500,
            "top_3syl": 400,
            "top_4syl": 200,
            "random": 500,
        }

    if args.assets:
        assets = Path(args.assets)
    else:
        # test_engine → librime-t9 → jni → main
        assets = Path(__file__).resolve().parent.parent.parent.parent / "assets" / "rime"
    main_dict = assets / "pinyin_simp.dict.yaml"
    imported = {str(main_dict)}
    entries = parse_dict(main_dict, imported)
    print(f"loaded {len(entries)} entries", file=sys.stderr)

    cases = sample(entries, CURATED, quotas)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        f.write("# 引擎级测试用例：由 gen_cases.py 从 pinyin_simp 词库反推键码生成，勿手改。\n")
        f.write("# 重新生成：python3 gen_cases.py --assets <assets/rime> --out <本文件>\n")
        f.write("cases:\n")
        for c in cases:
            syl = ", ".join(c["syllables"])
            f.write(f"  - text: {c['text']}\n")
            f.write(f"    syllables: [{syl}]\n")
            f.write(f'    digits: "{c["digits"]}"\n')
            f.write(f'    segmented: "{c["segmented"]}"\n')
            f.write(f"    weight: {c['weight']}\n")
            f.write(f"    source: {c['source']}\n")
    print(f"wrote {len(cases)} cases to {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
