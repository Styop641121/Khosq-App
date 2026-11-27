// KHOSQ Web, stage 5: a photo and a voice note, made the way the phone makes them (MediaUtil.compressImage, VoiceRecorder).
//  - A photo: at most 1280 px on the longer side, a JPEG of about 700 KB at most (quality 80, 70 ... 30), refused above 900 KB. Drawing it on a
//    canvas also drops everything else the file held (the place where it was taken, the camera's name).
//  - A voice note: AAC in an MP4 file, which is what the phone records and plays. The page records it only where the browser can make exactly
//    that (Safari and a current Chrome); otherwise the microphone button explains why it is off. The loudness bars are made by the
//    same rule as the phone's (waveOf).
export const PHOTO_SIDE = 1280;
export const PHOTO_GOAL = 700000; // the quality goes down until the picture is this small
export const PHOTO_MAX = 900000; // and a picture still bigger than this is refused
export const PICK_MAX = 40 * 1000 * 1000; // a file bigger than this is not even opened
export const VOICE_CAP = 850000; // the recording stops by itself at this size (a voice note may not pass 900 KB)
export const VOICE_BPS = 32000; // the phone's bit rate: about 4 KB per second
const QUALITIES = [0.8, 0.7, 0.6, 0.5, 0.4, 0.3];

/** The size of a picture shrunk so that its longer side is at most [limit]; never bigger than it is. */
export function fitSide(w, h, limit) {
  const side = Math.max(w, h);
  if (!(side > limit)) return { w, h };
  const k = limit / side;
  return { w: Math.max(1, Math.round(w * k)), h: Math.max(1, Math.round(h * k)) };
}

const jpegOf = (canvas, q) => new Promise((res) => canvas.toBlob((b) => res(b), "image/jpeg", q));

/**
 * A picture file as a small JPEG. Throws an Error whose message is "notImage" (not a picture), "unreadable" (the browser cannot open it),
 * "big" (still too big at the lowest quality).
 * @param {File} file
 * @returns {Promise<{bytes: Uint8Array, w: number, h: number}>}
 */
export async function photoBytes(file) {
  if (!file || !/^image\//.test(file.type || "")) throw new Error("notImage");
  if (file.size > PICK_MAX) throw new Error("big");
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    try {
      await img.decode();
    } catch (e) {
      throw new Error("unreadable");
    }
    const w0 = img.naturalWidth;
    const h0 = img.naturalHeight;
    if (!w0 || !h0) throw new Error("unreadable");
    const { w, h } = fitSide(w0, h0, PHOTO_SIDE);
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const g = canvas.getContext("2d");
    if (!g) throw new Error("unreadable");
    g.fillStyle = "#fff"; // a picture with see-through parts gets a white background, not black
    g.fillRect(0, 0, w, h);
    g.drawImage(img, 0, 0, w, h);
    let out = null;
    for (const q of QUALITIES) {
      const blob = await jpegOf(canvas, q);
      if (!blob) throw new Error("unreadable");
      out = new Uint8Array(await blob.arrayBuffer());
      if (out.length <= PHOTO_GOAL) break;
    }
    if (!out || out.length > PHOTO_MAX) throw new Error("big");
    return { bytes: out, w, h };
  } finally {
    URL.revokeObjectURL(url);
  }
}

export const AVATAR_SIDES = [512, 384, 256]; // the phone's MediaUtil.avatar: a square of 512 px, then 384, then 256 when the picture is too detailed
export const AVATAR_GOAL = 70000; // the picture is saved at the best quality (85, 75 ... 45) that fits in this many bytes; the rules refuse 90 KB
const AVATAR_QUALITIES = [0.85, 0.75, 0.65, 0.55, 0.45];

/** The middle square of a picture: where it starts and how long its side is. */
export function squareOf(w, h) {
  const side = Math.min(w, h);
  return { sx: Math.floor((w - side) / 2), sy: Math.floor((h - side) / 2), side };
}

/**
 * A picture file as the round picture of a group (what the phone's MediaUtil.avatar makes): the middle square, 512 x 512 px, a JPEG of at most
 * 70 KB (smaller squares of 384 and 256 px when a detailed picture does not fit). Throws an Error whose message is "notImage", "unreadable" or
 * "big" (even the smallest does not fit).
 * @param {File} file
 * @returns {Promise<Uint8Array>}
 */
export async function avatarBytes(file) {
  if (!file || !/^image\//.test(file.type || "")) throw new Error("notImage");
  if (file.size > PICK_MAX) throw new Error("big");
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    try {
      await img.decode();
    } catch (e) {
      throw new Error("unreadable");
    }
    if (!img.naturalWidth || !img.naturalHeight) throw new Error("unreadable");
    const { sx, sy, side } = squareOf(img.naturalWidth, img.naturalHeight);
    for (const px of AVATAR_SIDES) {
      const canvas = document.createElement("canvas");
      canvas.width = px;
      canvas.height = px;
      const g = canvas.getContext("2d");
      if (!g) throw new Error("unreadable");
      g.imageSmoothingQuality = "high";
      g.fillStyle = "#fff"; // see-through parts get a white background, not black
      g.fillRect(0, 0, px, px);
      g.drawImage(img, sx, sy, side, side, 0, 0, px, px);
      for (const q of AVATAR_QUALITIES) {
        const blob = await jpegOf(canvas, q);
        if (!blob) throw new Error("unreadable");
        if (blob.size <= AVATAR_GOAL) return new Uint8Array(await blob.arrayBuffer());
      }
    }
    throw new Error("big");
  } finally {
    URL.revokeObjectURL(url);
  }
}

/**
 * About 40 bars from the loudness samples, each one a digit 1..9 (quiet to loud): the phone's VoiceRecorder.wave(), the same arithmetic.
 * [levels] are the loudest points of short moments, any scale (the phone's go to 32767). "" when there is nothing.
 */
export function waveOf(levels) {
  if (!levels || !levels.length) return "";
  const n = 40;
  const peaks = new Array(n).fill(0);
  for (let i = 0; i < n; i++) {
    const from = Math.floor((i * levels.length) / n);
    const to = Math.min(levels.length, Math.max(from + 1, Math.floor(((i + 1) * levels.length) / n)));
    let mx = 0;
    for (let k = from; k < to; k++) mx = Math.max(mx, levels[k]);
    peaks[i] = mx;
  }
  const top = Math.max(1, ...peaks);
  let out = "";
  for (const v of peaks) {
    // the square root lifts quiet speech so the bars are not all tiny
    const lvl = 1 + Math.round(Math.sqrt(v / top) * 8);
    out += String(Math.min(9, Math.max(1, lvl)));
  }
  return out;
}

/** The recording format the page can make that the phone plays (AAC in MP4), or "" when this browser cannot. */
export function voiceMime(MR = typeof MediaRecorder !== "undefined" ? MediaRecorder : null) {
  if (!MR || typeof MR.isTypeSupported !== "function") return "";
  for (const m of ["audio/mp4;codecs=mp4a.40.2", "audio/mp4"]) {
    try {
      if (MR.isTypeSupported(m)) return m;
    } catch (e) {
      // try the next one
    }
  }
  return "";
}

/**
 * Starts recording a voice note. Rejects with the browser's error when the microphone is refused or missing.
 * The returned controller: [elapsed()] seconds so far, [stop()] resolves with {bytes, sec, wave} (null when nothing usable was recorded),
 * [cancel()] throws the recording away. The microphone is let go in every case. [onEnd] is called when the recording stops by itself because
 * it has reached the size limit; stop() then still gives what was recorded.
 * @param {{mime: string, onEnd?: () => void}} o
 */
export async function startVoice({ mime, onEnd }) {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
  let ctx = null;
  let timer = 0;
  const levels = [];
  const chunks = [];
  let size = 0;
  let startedAt = 0;
  let endedAt = 0;
  let cancelled = false;
  let limited = false;
  let rec;
  let released = false;
  const release = () => {
    if (released) return;
    released = true;
    clearInterval(timer);
    for (const t of stream.getTracks()) t.stop();
    if (ctx) {
      try {
        ctx.close();
      } catch (e) {
        // already closed
      }
      ctx = null;
    }
  };
  try {
    rec = new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: VOICE_BPS });
  } catch (e) {
    release();
    throw e;
  }
  // the loudness, a few times a second (a browser without this still records: the note then has no bars of its own)
  try {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (AC) {
      ctx = new AC();
      const src = ctx.createMediaStreamSource(stream);
      const an = ctx.createAnalyser();
      an.fftSize = 1024;
      src.connect(an);
      const buf = new Uint8Array(an.fftSize);
      timer = setInterval(() => {
        an.getByteTimeDomainData(buf);
        let peak = 0;
        for (const v of buf) peak = Math.max(peak, Math.abs(v - 128));
        levels.push(Math.round((peak / 128) * 32767));
      }, 100);
    }
  } catch (e) {
    // no bars
  }
  let resolve;
  const result = new Promise((res) => (resolve = res));
  rec.ondataavailable = (e) => {
    if (!e.data || !e.data.size) return;
    chunks.push(e.data);
    size += e.data.size;
    if (size >= VOICE_CAP && rec.state === "recording") {
      limited = true;
      endedAt = performance.now();
      rec.stop();
      if (onEnd) onEnd();
    }
  };
  rec.onstop = () => {
    release();
    if (cancelled || !chunks.length) return resolve(null);
    if (!endedAt) endedAt = performance.now();
    const sec = Math.floor((endedAt - startedAt) / 1000);
    new Blob(chunks, { type: mime })
      .arrayBuffer()
      .then((ab) => resolve({ bytes: new Uint8Array(ab), sec, wave: waveOf(levels), limited }))
      .catch(() => resolve(null));
  };
  rec.onerror = () => {
    cancelled = true;
    release();
    resolve(null);
  };
  startedAt = performance.now();
  try {
    rec.start(500);
  } catch (e) {
    release();
    throw e;
  }
  return {
    elapsed: () => Math.floor(((endedAt || performance.now()) - startedAt) / 1000),
    get limited() {
      return limited;
    },
    stop() {
      if (rec.state !== "inactive") {
        endedAt = performance.now();
        rec.stop();
      }
      return result;
    },
    cancel() {
      cancelled = true;
      if (rec.state !== "inactive") rec.stop();
      release(); // at once: the browser's "microphone in use" sign must go off now, not when the recorder has finished stopping
      return result;
    },
  };
}
