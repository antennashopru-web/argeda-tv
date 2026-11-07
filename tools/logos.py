#!/usr/bin/env python3
"""Собирает базу логотипов для LogoDb.java из открытых данных iptv-org.

Вывод: logos.tsv.gz, строки «ключ<TAB>ссылка». Ключ строится так же, как LogoDb.key()/Epg.norm()
в приложении: без «HD», «+2», пометок в скобках и кода страны в конце, только буквы и цифры.
"""
import gzip
import json
import re
import sys
import urllib.request

API = "https://iptv-org.github.io/api/"
NOISE = re.compile(r"(\(.*?\)|\[.*?\]|\b(hd|fhd|uhd|sd|hq|hdr|4k|8k|hevc|h\.?26[45]|1080[pi]?|720p|50\s?fps|orig|original)\b)", re.I)
SHIFT = re.compile(r"\s[+-]\d+\b")
COUNTRY = re.compile(r"\s([A-Z]{2,3}|50|60)$")


def norm(s):
    return "".join(ch for ch in s.lower().replace("ё", "е") if ch.isalnum())


def key(name):
    s = SHIFT.sub(" ", NOISE.sub(" ", name))
    for _ in range(3):
        s = COUNTRY.sub("", s.strip())
    return norm(s)


def no_tv(k):
    """«матчтв» → «матч»: провайдеры часто дописывают «ТВ», а в базе его нет (и наоборот)."""
    for t in ("tv", "тв"):
        if k.endswith(t) and len(k) > len(t) + 1:
            return k[: -len(t)]
    return k


def load(name):
    with urllib.request.urlopen(API + name, timeout=120) as r:
        return json.load(r)


def main(out):
    logos = {}
    for l in load("logos.json"):
        # Общий логотип канала (не региональной версии). SVG Android не показывает без библиотек — пропускаем.
        url = l.get("url", "")
        if l.get("feed") is None and url.startswith("https://") and l.get("format") != "SVG" and not url.lower().endswith(".svg"):
            logos.setdefault(l["channel"], (url, l.get("format")))
    index = {}
    channels = [c for c in load("channels.json") if c["id"] in logos and not c.get("closed")]
    # Сначала основные названия, потом альтернативные: основное побеждает при совпадении.
    for pass_alt in (False, True):
        for c in channels:
            names = c.get("alt_names", []) if pass_alt else [c["name"]]
            for n in names:
                for k in (norm(n), key(n), no_tv(key(n))):
                    if len(k) >= 2 and k not in index:
                        index[k] = logos[c["id"]][0]
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as f:
        for k in sorted(index):
            f.write(f"{k}\t{index[k]}\n")
    print(f"{len(index)} ключей, {len(channels)} каналов → {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "logos.tsv.gz")
