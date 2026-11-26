// Deterministic renderer: every frame is scene.html's seek(t) (it awaits the phone frame), screenshotted by Chromium
// and piped to ffmpeg. Same method as the DigitalPartner billing film (marketing-video skill). Run from this folder:
//   node render.mjs frames                      → frames/<clip>/NNNN.jpg from footage/<clip>.mp4 (the real app recording)
//   node render.mjs stills 1.2,6.8,12.6         → out/stills/t-<time>.png (check the look first)
//   node render.mjs video out/picture.mp4 60 2  → H.264; `sub` samples per frame blended into motion blur
//   node render.mjs mux                         → out/picture.mp4 + out/soundtrack.wav → StickerStudio-30s.mp4
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

const dir = path.dirname(new URL(import.meta.url).pathname);
const out = path.join(dir, 'out');
mkdirSync(out, { recursive: true });
const [mode, arg, fpsArg, subArg] = process.argv.slice(2);
const FILM = 'StickerStudio-30s.mp4';

if (mode === 'frames') {
  // The phone shows the recording at 540 px wide (the screen is about 560 px in the film): 60 frames per second of footage.
  for (const f of readdirSync(path.join(dir, 'footage')).filter((n) => n.endsWith('.mp4'))) {
    const name = f.replace(/\.mp4$/, '');
    mkdirSync(path.join(dir, 'frames', name), { recursive: true });
    const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-i', path.join(dir, 'footage', f),
      '-vf', 'fps=60,scale=540:-2:flags=lanczos', '-q:v', '3', path.join(dir, 'frames', name, '%05d.jpg')], { stdio: 'inherit' });
    if (r.status) process.exit(r.status);
    console.log('frames', name);
  }
  process.exit(0);
}

if (mode === 'mux') {
  const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-i', path.join(out, 'picture.mp4'),
    '-i', path.join(out, 'soundtrack.wav'), '-map', '0:v', '-map', '1:a', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '256k',
    '-ar', '48000', '-shortest', '-movflags', '+faststart', path.join(dir, FILM)], { stdio: 'inherit' });
  process.exit(r.status ?? 1);
}

let chromium;
try { ({ chromium } = await import('playwright-core')); } catch { ({ chromium } = createRequire(import.meta.url)('playwright')); }
const executablePath = process.env.CHROMIUM ?? (existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined);
const browser = await chromium.launch({ executablePath, args: ['--no-sandbox', '--allow-file-access-from-files'] });
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
await page.goto('file://' + path.join(dir, 'scene.html'));
await page.evaluate(() => window.ready);
const shot = async (t) => {
  await page.evaluate((x) => window.seek(x), t);
  return page.screenshot({ type: 'png' });
};

if (mode === 'stills') {
  mkdirSync(path.join(out, 'stills'), { recursive: true });
  for (const t of arg.split(',').map(Number)) {
    await page.evaluate((x) => window.seek(x), t);
    await page.screenshot({ path: path.join(out, 'stills', `t-${t.toFixed(2)}.png`) });
  }
} else if (mode === 'video') {
  const fps = Number(fpsArg || 60), sub = Number(subArg || 2);
  const dur = await page.evaluate(() => window.DURATION);
  const n = Math.round(dur * fps);
  const vf = sub > 1 ? `tmix=frames=${sub}:weights='${Array(sub).fill(1).join(' ')}',select='not(mod(n\\,${sub}))',` : '';
  const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps * sub), '-i', '-',
    '-vf', `${vf}fps=${fps},format=yuv420p`, '-c:v', 'libx264', '-preset', 'slow', '-crf', '17', '-movflags', '+faststart',
    path.resolve(dir, arg)], { stdio: ['pipe', 'inherit', 'inherit'] });
  const started = Date.now();
  for (let i = 0; i < n; i++) {
    for (let s = 0; s < sub; s++) {
      const buf = await shot((i + s / sub) / fps);
      if (!ff.stdin.write(buf)) await new Promise((r) => ff.stdin.once('drain', r));
    }
    if (i % 120 === 0) console.log(`frame ${i}/${n} ${((Date.now() - started) / 1000).toFixed(0)}s`);
  }
  ff.stdin.end();
  await new Promise((r, j) => ff.on('close', (c) => (c === 0 ? r() : j(new Error('ffmpeg ' + c)))));
} else {
  console.error('usage: node render.mjs frames | stills <t,t,...> | video <out.mp4> [fps] [sub] | mux');
  process.exitCode = 1;
}
await browser.close();
