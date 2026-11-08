"""Сводит звук заставки ARGEDA TV из материалов ElevenLabs.

Вариант N: мощный звук tools/sound/v/fx_N.mp3 (его пик совпадает с «ударом» анимации, Splash.HIT = 1,30 с;
перед ним — обратный «засос» этого же звука) и басовый голос tools/sound/v/voice_N.mp3 сразу после удара.
Запуск из корня репозитория:
  python3 tools/sound/mix.py N out.mp3        — собрать вариант N в файл
  python3 tools/sound/mix.py N                — собрать вариант N в res/raw/argeda_tv.mp3
  python3 tools/sound/mix.py N out.mp3 голос.mp3 — удар варианта N с другим голосом
Сейчас в приложении: удар 3 + голос «Библия — Бог» (god_1.mp3, ElevenLabs eleven_v3, [торжественно, мощно, с величием]).
После голоса — хвост зала (hall) и плавное затухание TAIL секунд, чтобы звук не обрывался.
"""
import subprocess
import sys

import numpy as np

SR = 44100
HIT = 1.30
VOICE_AT = HIT + 0.33
TAIL = 1.6


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


def hall(x, rt=2.0):
    """Мягкий хвост зала: свёртка с затухающим шумом (RT60 ≈ rt), без высоких частот."""
    rng = np.random.default_rng(7)
    n = int(rt * SR)
    ir = rng.standard_normal(n) * np.exp(-6.9 * np.arange(n) / n)
    ir[: int(0.02 * SR)] = 0
    k = int(0.004 * SR)
    ir = np.convolve(ir, np.ones(k) / k, mode="same")
    m = len(x) + n
    y = np.fft.irfft(np.fft.rfft(x, m) * np.fft.rfft(ir, m), m)
    return y / (np.abs(y).max() + 1e-9)


def build(n, out, voice_path=None):
    fx = trim(load(f"tools/sound/v/fx_{n}.mp3"))
    voice = trim(load(voice_path or f"tools/sound/v/voice_{n}.mp3"))
    voice /= np.abs(voice).max() + 1e-9
    fx /= np.abs(fx).max() + 1e-9
    # после голоса звук не обрывается: хвост зала и плавное затухание
    total = VOICE_AT + len(voice) / SR + TAIL
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
    place(mix, hall(voice), VOICE_AT, 0.35)
    end = VOICE_AT + len(voice) / SR
    fade = np.clip((t - (end - 0.25)) / (total - end + 0.25), 0, 1)
    mix *= np.cos(fade * np.pi / 2) ** 2
    mix *= 0.97 / np.abs(mix).max()
    pcm = (mix * 32767).astype("<i2").tobytes()
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", "-",
                    "-af", "acompressor=threshold=-14dB:ratio=3:attack=5:release=120,alimiter=limit=0.95",
                    "-c:a", "libmp3lame", "-b:a", "128k", out], input=pcm, check=True)
    print(f"{out}: {total:.2f} с")


if __name__ == "__main__":
    build(int(sys.argv[1]), sys.argv[2] if len(sys.argv) > 2 else "app/src/main/res/raw/argeda_tv.mp3",
          sys.argv[3] if len(sys.argv) > 3 else None)
