#!/usr/bin/env python3
"""Генератор иконок интерфейса ARGEDA TV (векторные drawable 24×24).

Все иконки — в одном стиле: контур 2 dp, скруглённые концы. Цвет берётся из
@color/text, поэтому иконки сами меняются в светлой и тёмной теме.
Запуск: python3 tools/icons/gen_icons.py  (пишет app/src/main/res/drawable/ic_*.xml
и tools/icons/preview.html для просмотра).
"""
import math, os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")


def f(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def circle(cx, cy, r):
    return "M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 Z" % (
        f(cx - r), f(cy), f(r), f(r), f(2 * r), f(r), f(r), f(-2 * r))


def poly(pts):
    return "M" + " L".join("%s,%s" % (f(x), f(y)) for x, y in pts) + " Z"


def gear():
    # 8 зубцов: внешние и внутренние точки, контур со скруглёнными углами.
    pts = []
    n = 8
    for i in range(n):
        a0 = 2 * math.pi * i / n
        for da, r in ((-0.20, 7.4), (-0.13, 9.6), (0.13, 9.6), (0.20, 7.4)):
            a = a0 + da
            pts.append((12 + r * math.cos(a), 12 + r * math.sin(a)))
    return poly(pts)


def star(r1=9.2, r2=4.0):
    pts = []
    for i in range(10):
        a = -math.pi / 2 + math.pi * i / 5
        r = r1 if i % 2 == 0 else r2
        pts.append((12 + r * math.cos(a), 12.6 + r * math.sin(a)))
    return poly(pts)


S = "stroke"  # контур
F = "fill"    # заливка

ICONS = {
    # Настройки — шестерёнка
    "ic_settings": [(S, gear()), (S, circle(12, 12, 3.1))],
    # Поиск — лупа
    "ic_search": [(S, circle(10.5, 10.5, 6.2)), (S, "M15.2,15.2 L20.5,20.5")],
    # Плейлист — строки и кнопка «play»
    "ic_playlist": [(S, "M3.5,6 L17,6 M3.5,11 L17,11 M3.5,16 L10.5,16"),
                    (S, "M14,14 L14,20.5 L19.5,17.25 Z")],
    # Каналы — список
    "ic_list": [(S, "M8.5,6.5 L20,6.5 M8.5,12 L20,12 M8.5,17.5 L20,17.5"),
                (F, circle(4.5, 6.5, 1.3)), (F, circle(4.5, 12, 1.3)), (F, circle(4.5, 17.5, 1.3))],
    # Телепрограмма — календарь-сетка
    "ic_epg": [(S, "M5.5,4.5 L18.5,4.5 Q20.5,4.5 20.5,6.5 L20.5,18.5 Q20.5,20.5 18.5,20.5 L5.5,20.5 "
                   "Q3.5,20.5 3.5,18.5 L3.5,6.5 Q3.5,4.5 5.5,4.5 Z"),
               (S, "M3.5,9.5 L20.5,9.5 M8,2.5 L8,6 M16,2.5 L16,6"),
               (S, "M7.5,13.5 L11,13.5 M13,13.5 L16.5,13.5 M7.5,17 L11,17")],
    # Избранное — звезда (контур) и звезда (залита)
    "ic_star_outline": [(S, star())],
    "ic_star": [(F, star(), "@color/fav"), (S, star(), "@color/fav")],
    # Формат картинки — уголки рамки
    "ic_fit": [(S, "M3.5,9 L3.5,5.5 Q3.5,4 5,4 L8.5,4 M15.5,4 L19,4 Q20.5,4 20.5,5.5 L20.5,9 "
                   "M20.5,15 L20.5,18.5 Q20.5,20 19,20 L15.5,20 M8.5,20 L5,20 Q3.5,20 3.5,18.5 L3.5,15")],
    # Поворот экрана — круговая стрелка
    "ic_rotate": [(S, "M19.5,12 A7.5,7.5 0 1,1 16.8,6.2"), (S, "M17.5,2.8 L17.3,6.7 L13.4,6.6")],
    # Звук — динамик и волны
    "ic_audio": [(S, "M4,9.5 L7.5,9.5 L12,5.5 L12,18.5 L7.5,14.5 L4,14.5 Z"),
                 (S, "M15.5,9 Q17.3,12 15.5,15 M18.3,6.5 Q22,12 18.3,17.5")],
    # Прямой эфир — красная точка
    "ic_live": [(F, circle(12, 12, 5.5), "@color/live")],
    # Добавить — белый плюс (на фиолетовой кнопке)
    "ic_plus": [(S, "M12,5 L12,19 M5,12 L19,12", "#FFFFFF")],
}


def vector(paths):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Создано tools/icons/gen_icons.py — правьте генератор, а не этот файл. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"',
           '    android:autoMirrored="false">']
    for p in paths:
        kind, d = p[0], p[1]
        color = p[2] if len(p) > 2 else "@color/text"
        if kind == S:
            out.append('    <path android:pathData="%s"\n        android:strokeColor="%s" android:strokeWidth="2"'
                       ' android:strokeLineCap="round" android:strokeLineJoin="round" />' % (d, color))
        else:
            out.append('    <path android:pathData="%s" android:fillColor="%s" />' % (d, color))
    out.append("</vector>")
    return "\n".join(out) + "\n"


def svg(paths, fg, fav, live):
    def col(c):
        return {"@color/text": fg, "@color/fav": fav, "@color/live": live}.get(c, c)
    body = []
    for p in paths:
        kind, d = p[0], p[1]
        c = col(p[2] if len(p) > 2 else "@color/text")
        d = d.replace(",", " ")
        if kind == S:
            body.append('<path d="%s" fill="none" stroke="%s" stroke-width="2" stroke-linecap="round" '
                        'stroke-linejoin="round"/>' % (d, c))
        else:
            body.append('<path d="%s" fill="%s"/>' % (d, c))
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="72" height="72">%s</svg>' % "".join(body)


def main():
    for name, paths in ICONS.items():
        with open(os.path.join(OUT, name + ".xml"), "w", encoding="utf-8") as fh:
            fh.write(vector(paths))
    rows = []
    for bg, fg, fav, live, title in (("#F4F2FA", "#1B1033", "#F59E0B", "#E11D48", "Светлая"),
                                     ("#0D0A1A", "#F3F0FF", "#FBBF24", "#F43F5E", "Тёмная")):
        cells = "".join('<div style="text-align:center;margin:10px">%s<div style="font:12px sans-serif;color:%s">%s</div></div>'
                        % (svg(p, fg, fav, live), fg, n) for n, p in ICONS.items())
        rows.append('<div style="background:%s;padding:16px;display:flex;flex-wrap:wrap"><b style="color:%s;'
                    'font:14px sans-serif;width:100%%">%s</b>%s</div>' % (bg, fg, title, cells))
    with open(os.path.join(os.path.dirname(__file__), "preview.html"), "w", encoding="utf-8") as fh:
        fh.write("<!doctype html><meta charset=utf-8><body style='margin:0'>" + "".join(rows))
    print("ok:", len(ICONS), "иконок")


if __name__ == "__main__":
    main()
