"""Deterministic soundtrack for the 30 s phone film: one CC0 music track + CC0 recordings on the picture's cue times.

Rules (marketing-video skill, from earshot's launch-video-sound): music from the first frame; it opens up on the
phone reveal; one click on each press frame; a soft pop when a card or chip arrives (about 50 % of its travel);
3 whooshes only, on big moves; one reward sound; a stop-down before the logo, then one note in key; UI sounds
10-15 dB under the music; master -14 LUFS, true peak <= -1.5 dBTP.
Run from marketing/phone-film: <python with numpy, scipy, soundfile, soxr, librosa, pyloudnorm> -I audio/mix.py
Writes out/soundtrack.wav and out/cues.json.
"""
import json
import os
import numpy as np
import soundfile as sf
import librosa
import soxr
from scipy.signal import butter, sosfilt
import pyloudnorm as pyln

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'out')
os.makedirs(OUT, exist_ok=True)
SR = 48000
DUR = 30.0
A = os.path.join(HERE, 'assets') + '/'

# ---------- cue times (seconds; the same times as scene.html) ----------
TAPS = []                      # press frames of the taps in the footage (set from the recording)
REVEAL = 2.35                  # icon grows into the phone
CARD = 16.45                   # chat card arrives (50 % of its travel)
STICKER_SOUND = (15.4, 18.8)   # the sticker plays its own sound under the rings
CREATED = 14.0                 # "Sticker created" appears (the one reward sound)
CHIPS = [23.3 + 0.12 * i + 0.12 for i in range(6)]
SHRINK_TO_CHIPS = 23.15
SHRINK_TO_LOGO = 26.15
CTA = 27.35
LOGO_NOTE = 26.6


def load(path, start=None, end=None):
    y, sr = librosa.load(path, sr=None, mono=False)
    y = np.atleast_2d(y)
    if y.shape[0] == 1:
        y = np.vstack([y, y])
    if sr != SR:
        y = soxr.resample(y.T, sr, SR).T
    if start is not None:
        y = y[:, int(start * SR):int(end * SR)]
    fade = int(0.004 * SR)
    y[:, :fade] *= np.linspace(0, 1, fade)
    y[:, -fade:] *= np.linspace(1, 0, fade)
    return y / (np.abs(y).max() + 1e-9)


def onset_time(y, thr=0.3):
    env = np.abs(y).mean(0)
    return np.argmax(env > thr * env.max()) / SR


def peak_time(y):
    env = np.abs(y).mean(0)
    k = int(0.01 * SR)
    return np.argmax(np.convolve(env, np.ones(k) / k, 'same')) / SR


def lowpass(y, hz):
    return sosfilt(butter(4, hz, 'low', fs=SR, output='sos'), y, axis=1)


def highpass(y, hz):
    return sosfilt(butter(2, hz, 'high', fs=SR, output='sos'), y, axis=1)


mix = np.zeros((2, int(DUR * SR) + SR))
cues = []


def place(y, t, db, pan=0.0, align='onset', name=''):
    off = onset_time(y) if align == 'onset' else peak_time(y)
    i = int((t - off) * SR)
    g = 10 ** (db / 20)
    th = (pan + 1) * np.pi / 4
    gl, gr = np.cos(th) * np.sqrt(2), np.sin(th) * np.sqrt(2)
    seg = y.copy()
    if i < 0:
        seg, i = seg[:, -i:], 0
    n = min(seg.shape[1], mix.shape[1] - i)
    mix[0, i:i + n] += seg[0, :n] * g * gl
    mix[1, i:i + n] += seg[1, :n] * g * gr
    cues.append({'t': round(t, 3), 'sound': name, 'db': db, 'pan': pan})


# ---------- music: 30 s of the track, filtered until the phone reveal, then open ----------
music = load(A + 'fs-484844_astounded-ukulele.mp3', 4.0, 4.0 + DUR + 1)
mid, side = (music[0] + music[1]) / 2, (music[0] - music[1]) / 2   # narrow the stereo a little
music = np.vstack([mid + side * 0.6, mid - side * 0.6])
closed = lowpass(music, 900)
k = np.clip((np.arange(music.shape[1]) / SR - (REVEAL - 1.0)) / 1.0, 0, 1)   # 1 s opening into the reveal
music = closed * (1 - k) + music * k
env = np.ones(music.shape[1])
t = np.arange(music.shape[1]) / SR
env *= np.clip(t / 0.25, 0, 1)                                             # soft start
env *= np.where(t < STICKER_SOUND[0] - 0.2, 1, np.where(t < STICKER_SOUND[1], 0.45, 1))   # duck under the sticker
env *= np.where((t > SHRINK_TO_LOGO - 0.35) & (t < LOGO_NOTE - 0.05), 0.25, 1)          # stop-down before the logo
env *= np.clip((DUR - t) / 2.5, 0, 1)                                     # tail into silence
env = np.convolve(env, np.ones(2400) / 2400, 'same')
place(music * env, 0.0, -2.0, name='music')

# ---------- UI sounds ----------
click = highpass(load(A + 'fs-467951_benzix2-ui-click.mp3'), 300)
pop = load(A + 'fs-244654_greenvwbeetle-pop2.mp3')
whoosh = load(A + 'fs-60007_qubodup-swipe-whoosh.mp3')
reward = load(A + 'fs-541499_tetoszka-ukulele-bonus.mp3')
glock = load(A + 'fs-373364_sgossner-glock-c5.mp3', 0, 2.5)
sticker = load(A + 'sticker-sound-beach_party.wav', 0.0, STICKER_SOUND[1] - STICKER_SOUND[0])   # the sample clip's own sound

for tt in TAPS:
    place(click, tt - 1 / 60, -16, 0.1, name='tap')
place(whoosh, REVEAL, -14, -0.2, align='peak', name='whoosh reveal')
place(reward, CREATED, -13, 0.0, name='sticker created')
place(sticker, STICKER_SOUND[0], -8, 0.0, name='sticker sound')
place(pop, CARD, -15, 0.45, name='chat card')
place(whoosh, SHRINK_TO_CHIPS, -17, 0.15, align='peak', name='whoosh chips')
for i, tt in enumerate(CHIPS):
    place(pop, tt, -20, -0.5 if i % 2 == 0 else 0.5, name='chip')
place(whoosh, SHRINK_TO_LOGO, -15, 0.0, align='peak', name='whoosh logo')
place(glock, LOGO_NOTE, -12, 0.0, name='logo note')
place(pop, CTA, -17, 0.0, name='cta')

# ---------- master: -14 LUFS, true peak <= -1.5 dBTP ----------
y = mix[:, :int(DUR * SR)]
meter = pyln.Meter(SR)
y *= 10 ** ((-14.0 - meter.integrated_loudness(y.T)) / 20)
up = soxr.resample(y.T, SR, SR * 4).T              # true peak estimate (4x oversampling)
tp = 20 * np.log10(np.abs(up).max() + 1e-12)
if tp > -1.5:
    lim = 10 ** (-1.5 / 20)
    y = np.tanh(y / lim) * lim                      # soft limiter on the few peaks
sf.write(os.path.join(OUT, 'soundtrack.wav'), y.T, SR, subtype='PCM_24')
json.dump(cues, open(os.path.join(OUT, 'cues.json'), 'w'), indent=1)
print('LUFS', round(meter.integrated_loudness(y.T), 2), 'true peak', round(20 * np.log10(np.abs(soxr.resample(y.T, SR, SR * 4)).max()), 2))
