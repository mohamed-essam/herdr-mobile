// Pixel size of a base64 image from its header, so the phone can lay the
// image out at its final size before the bytes arrive (no jump when it
// loads). PNG, JPEG, GIF and WebP; undefined for anything else.

// JPEG metadata (EXIF, ICC) can sit before the frame header; 64 KiB covers it.
const HEADER_BYTES = 65536

function head(data: string): Uint8Array | undefined {
  // A cut lands on a 4-char boundary (no padding mid-string); a whole string keeps its own.
  const b64 = data.slice(0, (HEADER_BYTES / 3) * 4)
  try {
    const s = atob(b64.length % 4 ? b64.slice(0, b64.length - (b64.length % 4)) : b64)
    const out = new Uint8Array(s.length)
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i)
    return out
  } catch {
    return undefined
  }
}

const be16 = (b: Uint8Array, i: number) => (b[i]! << 8) | b[i + 1]!
const le16 = (b: Uint8Array, i: number) => b[i]! | (b[i + 1]! << 8)
const be32 = (b: Uint8Array, i: number) => ((b[i]! << 24) | (b[i + 1]! << 16) | (b[i + 2]! << 8) | b[i + 3]!) >>> 0
const le24 = (b: Uint8Array, i: number) => b[i]! | (b[i + 1]! << 8) | (b[i + 2]! << 16)
const ascii = (b: Uint8Array, i: number, s: string) => [...s].every((c, k) => b[i + k] === c.charCodeAt(0))

function jpeg(b: Uint8Array): [number, number] | undefined {
  let i = 2
  while (i + 9 < b.length) {
    if (b[i] !== 0xff) return undefined
    const m = b[i + 1]!
    if (m === 0xff) { i++; continue } // fill byte
    // SOF0..SOF15, except DHT (C4), JPG (C8) and DAC (CC): length, precision, height, width.
    if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) return [be16(b, i + 7), be16(b, i + 5)]
    if (m === 0x01 || (m >= 0xd0 && m <= 0xd8)) { i += 2; continue } // no length
    i += 2 + be16(b, i + 2)
  }
  return undefined
}

function webp(b: Uint8Array): [number, number] | undefined {
  if (ascii(b, 12, 'VP8X') && b.length >= 30) return [1 + le24(b, 24), 1 + le24(b, 27)]
  if (ascii(b, 12, 'VP8 ') && b.length >= 30) return [le16(b, 26) & 0x3fff, le16(b, 28) & 0x3fff]
  if (ascii(b, 12, 'VP8L') && b.length >= 25) {
    const [b0, b1, b2, b3] = [b[21]!, b[22]!, b[23]!, b[24]!]
    return [1 + (((b1 & 0x3f) << 8) | b0), 1 + (((b3 & 0xf) << 10) | (b2 << 2) | ((b1 & 0xc0) >> 6))]
  }
  return undefined
}

export function imageSize(data: string): [number, number] | undefined {
  const b = head(data)
  if (!b) return undefined
  let size: [number, number] | undefined
  if (b.length >= 24 && b[0] === 0x89 && ascii(b, 1, 'PNG') && ascii(b, 12, 'IHDR')) size = [be32(b, 16), be32(b, 20)]
  else if (b.length >= 4 && b[0] === 0xff && b[1] === 0xd8) size = jpeg(b)
  else if (b.length >= 10 && ascii(b, 0, 'GIF8')) size = [le16(b, 6), le16(b, 8)]
  else if (ascii(b, 0, 'RIFF') && ascii(b, 8, 'WEBP')) size = webp(b)
  return size && size[0] > 0 && size[1] > 0 ? size : undefined
}
