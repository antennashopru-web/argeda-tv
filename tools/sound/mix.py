"""Сводит звук заставки: вступление ElevenLabs (sting_1 — нарастание, sting_2 — звон на «удар»)
и голос «ARGEDA TV» в app/src/main/res/raw/argeda_tv.mp3.
Время «удара» совпадает со Splash.HIT (0,62 с). Запуск из корня репозитория: python3 tools/sound/mix.py
"""
import subprocess

import numpy as np

SR = 44100
D = "tools/sound/"
RISE_AT, HIT_AT, VOICE_AT = 0.20, 0.60, 1.05


def load(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def place(buf, sig, at, gain=1.0):
    i = int(at * SR)
    j = min(len(buf), i + len(sig))
    buf[i:j] += gain * sig[: j - i]


def main():
    rise, hit, voice = load(D + "sting_1.mp3"), load(D + "sting_2.mp3"), load(D + "voice.mp3")
    rise = rise[: int(0.42 * SR)]
    rise *= np.clip((0.42 * SR - np.arange(len(rise))) / (0.03 * SR), 0, 1)  # мягкий обрез хвоста
    total = VOICE_AT + len(voice) / SR + 0.25
    mus = np.zeros(int(total * SR))
    place(mus, rise, RISE_AT, 0.8)
    place(mus, hit, HIT_AT, 1.0)
    # простое эхо даёт звону «зал»
    echo = np.zeros_like(mus)
    for k, g in ((0.11, 0.35), (0.23, 0.2), (0.37, 0.1)):
        place(echo, mus, k, g)
    mus += echo
    t = np.arange(len(mus)) / SR
    mus *= 1 - 0.5 * np.clip((t - (VOICE_AT - 0.06)) / 0.1, 0, 1)  # музыка тише под голосом
    mix = mus / (np.abs(mus).max() + 1e-9) * 0.6
    place(mix, voice / (np.abs(voice).max() + 1e-9), VOICE_AT, 0.95)
    mix *= np.clip((total - t) / 0.2, 0, 1)
    mix *= 0.97 / np.abs(mix).max()
    pcm = (mix * 32767).astype("<i2").tobytes()
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", "-",
                    "-c:a", "libmp3lame", "-b:a", "96k", "app/src/main/res/raw/argeda_tv.mp3"], input=pcm, check=True)
    print(f"argeda_tv.mp3: {total:.2f} с, удар {HIT_AT} с, голос {VOICE_AT} с")


if __name__ == "__main__":
    main()
