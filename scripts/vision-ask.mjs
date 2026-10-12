#!/usr/bin/env node
//
// vision-ask.mjs — send one or more images to a multimodal model and print its reply.
//
// Why this exists
// ---------------
// TermAgent's configured provider (DeepSeek) exposes `deepseek-flash`, whose `/models`
// entry declares `"input_modalities": ["text","image"]`. That makes the OpenAI-compatible
// `image_url` content-part form usable, so a screenshot can be read without any local OCR
// (tesseract is not installed on this device).
//
// Usage:
//   node scripts/vision-ask.mjs <image> [more images...] [--prompt "..."] [--model m]
//
// The API key is taken from $DEEPSEEK_API_KEY, or read straight out of the app's
// SharedPreferences when running on-device as root.
//
import { readFileSync, statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { extname, resolve } from 'node:path';

// ---------------------------------------------------------------- args
const argv = process.argv.slice(2);
const images = [];
let prompt = 'Describe this image. Transcribe any visible text exactly.';
let model = process.env.VISION_MODEL || 'deepseek-flash';
let baseUrl = process.env.VISION_BASE_URL || 'https://api.deepseek.com';
let maxTokens = 8192;

for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--prompt') prompt = argv[++i];
  else if (a === '--model') model = argv[++i];
  else if (a === '--base-url') baseUrl = argv[++i];
  else if (a === '--max-tokens') maxTokens = Number(argv[++i]);
  else if (a.startsWith('--')) { console.error(`unknown flag: ${a}`); process.exit(2); }
  else images.push(a);
}

if (images.length === 0) {
  console.error('usage: node scripts/vision-ask.mjs <image> [...] [--prompt "..."]');
  process.exit(2);
}

// ---------------------------------------------------------------- api key
function resolveKey() {
  if (process.env.DEEPSEEK_API_KEY) return process.env.DEEPSEEK_API_KEY.trim();
  // On-device: the app stores its key in its own SharedPreferences, unreadable to a normal
  // app user but fine for root. Never printed — only its length is reported.
  try {
    const xml = execFileSync('su', ['-c', 'cat /data/data/com.tagent/shared_prefs/termagent_settings.xml'], {
      encoding: 'utf8',
    });
    const m = xml.match(/name="api_key">([^<]+)</);
    if (m) return m[1].trim();
  } catch { /* fall through */ }
  return null;
}

const apiKey = resolveKey();
if (!apiKey) {
  console.error('No API key. Set $DEEPSEEK_API_KEY.');
  process.exit(2);
}
console.error(`[key: ${apiKey.slice(0, 6)}… (${apiKey.length} chars)] [model: ${model}]`);

// ---------------------------------------------------------------- mime
const MIME = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
};

function toDataUrl(path) {
  const abs = resolve(path);
  const bytes = readFileSync(abs);
  const mime = MIME[extname(abs).toLowerCase()] || 'image/jpeg';
  console.error(`[image: ${abs} ${bytes.length} bytes, ${mime}]`);
  return `data:${mime};base64,${bytes.toString('base64')}`;
}

for (const img of images) {
  if (!statSync(img, { throwIfNoEntry: false })) {
    console.error(`no such file: ${img}`);
    process.exit(2);
  }
}

// ---------------------------------------------------------------- request
const content = images.map((p) => ({ type: 'image_url', image_url: { url: toDataUrl(p) } }));
content.push({ type: 'text', text: prompt });

const body = {
  model,
  max_tokens: maxTokens,
  messages: [{ role: 'user', content }],
};

const started = Date.now();
const res = await fetch(`${baseUrl}/chat/completions`, {
  method: 'POST',
  headers: {
    Authorization: `Bearer ${apiKey}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify(body),
});

const text = await res.text();
let json;
try { json = JSON.parse(text); } catch { json = null; }

if (!res.ok) {
  console.error(`\nHTTP ${res.status}`);
  console.error(json ? JSON.stringify(json, null, 2) : text.slice(0, 2000));
  process.exit(1);
}

const msg = json?.choices?.[0]?.message;
const out = msg?.content ?? '(empty)';
console.log(out);

const usage = json?.usage;
console.error(
  `\n--- ${Date.now() - started}ms` +
  (usage ? ` | prompt=${usage.prompt_tokens} completion=${usage.completion_tokens}` : ''),
);
