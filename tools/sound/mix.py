"""Сводит звук заставки ARGEDA TV из материалов ElevenLabs.

Вариант N: мощный звук tools/sound/v/fx_N.mp3 (его пик совпадает с «ударом» анимации, Splash.HIT = 1,30 с;
перед ним — обратный «засос» этого же звука) и басовый голос tools/sound/v/voice_N.mp3 сразу после удара.
Запуск из корня репозитория:
  python3 tools/sound/mix.py N out.mp3        — собрать вариант N в файл
  python3 tools/sound/mix.py N                — собрать вариант N в res/raw/argeda_tv.mp3
"""
import subprocess
import sys

import numpy as np

SR = 44100
HIT = 1.30
VOICE_AT = HIT + 0.33


def load(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def trim(x, db=-45):
    thr = 10 ** (db / 20)
    idx = np.where(np.abs(x) > thr)[0]
    return x[idx[0]: idx[-1] + 1] if len(idx) else x


def peak_time(x):
    w = int(0.02 * SR)
    e = np.convolve(x * x, np.ones(w) / w, mode="same")
    return int(np.argmax(e))


def place(buf, sig, at, gain=1.0):
    i = int(round(at * SR))
    if i < 0:
        sig, i = sig[-i:], 0
    j = min(len(buf), i + len(sig))
    buf[i:j] += gain * sig[: j - i]


def build(n, out):
    fx, voice = trim(load(f"tools/sound/v/fx_{n}.mp3")), trim(load(f"tools/sound/v/voice_{n}.mp3"))
    voice /= np.abs(voice).max() + 1e-9
    fx /= np.abs(fx).max() + 1e-9
    total = VOICE_AT + len(voice) / SR + 0.3
    mus = np.zeros(int(total * SR))
    p = peak_time(fx)
    fx_at = HIT - p / SR
    # «зарядка» перед ударом: тот же звук задом наперёд, нарастающий около секунды
    body = fx[p:]
    rev = np.tile(body, int(np.ceil(SR * 1.1 / max(len(body), 1))))[: int(1.1 * SR)][::-1].copy()
    rev *= np.linspace(0, 1, len(rev)) ** 3
    place(mus, rev, HIT - len(rev) / SR, 0.55 if p / SR < 0.25 else 0.3)
    place(mus, fx, fx_at, 1.0)
    # хвост-эхо делает удар больше
    echo = np.zeros_like(mus)
    for k, g in ((0.09, 0.3), (0.19, 0.18), (0.31, 0.1)):
        place(echo, mus, k, g)
    mus += echo
    t = np.arange(len(mus)) / SR
    mus *= 1 - 0.55 * np.clip((t - (VOICE_AT - 0.05)) / 0.1, 0, 1)  # тише под голосом
    mus = mus / (np.abs(mus).max() + 1e-9) * 0.85
    mix = mus.copy()
    place(mix, voice, VOICE_AT, 1.0)
    mix *= np.clip((total - t) / 0.25, 0, 1)
    mix *= 0.97 / np.abs(mix).max()
    pcm = (mix * 32767).astype("<i2").tobytes()
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", "-",
                    "-af", "acompressor=threshold=-14dB:ratio=3:attack=5:release=120,alimiter=limit=0.95",
                    "-c:a", "libmp3lame", "-b:a", "128k", out], input=pcm, check=True)
    print(f"{out}: {total:.2f} с")


if __name__ == "__main__":
    build(int(sys.argv[1]), sys.argv[2] if len(sys.argv) > 2 else "app/src/main/res/raw/argeda_tv.mp3")
