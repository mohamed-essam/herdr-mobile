import { describe, expect, test } from 'claude-code/testing'
import { imageSize } from '../hooks/imagesize'
import { normalizeBlocks } from '../hooks/normalize'

const b64 = (bytes: number[]) => btoa(String.fromCharCode(...bytes))
const be16 = (n: number) => [(n >> 8) & 0xff, n & 0xff]
const le16 = (n: number) => [n & 0xff, (n >> 8) & 0xff]
const be32 = (n: number) => [(n >>> 24) & 0xff, (n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff]
const le24 = (n: number) => [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff]
const ascii = (s: string) => [...s].map((c) => c.charCodeAt(0))

const png = (w: number, h: number) =>
  b64([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...be32(13), ...ascii('IHDR'), ...be32(w), ...be32(h), 8, 6, 0, 0, 0])
// SOI, an APP0 segment to skip, then SOF2 (progressive).
const jpeg = (w: number, h: number) =>
  b64([0xff, 0xd8, 0xff, 0xe0, ...be16(6), 1, 2, 3, 4, 0xff, 0xc2, ...be16(11), 8, ...be16(h), ...be16(w), 3, 0, 0, 0])
const gif = (w: number, h: number) => b64([...ascii('GIF89a'), ...le16(w), ...le16(h), 0, 0, 0])
const webpX = (w: number, h: number) =>
  b64([...ascii('RIFF'), 0, 0, 0, 0, ...ascii('WEBP'), ...ascii('VP8X'), 10, 0, 0, 0, 0, 0, 0, 0, ...le24(w - 1), ...le24(h - 1)])
const webpLossy = (w: number, h: number) =>
  b64([...ascii('RIFF'), 0, 0, 0, 0, ...ascii('WEBP'), ...ascii('VP8 '), 0, 0, 0, 0, 0, 0, 0, 0x9d, 0x01, 0x2a, ...le16(w), ...le16(h)])
const webpLossless = (w: number, h: number) => {
  const a = w - 1, b = h - 1
  return b64([...ascii('RIFF'), 0, 0, 0, 0, ...ascii('WEBP'), ...ascii('VP8L'), 0, 0, 0, 0, 0x2f,
    a & 0xff, ((a >> 8) & 0x3f) | ((b & 0x3) << 6), (b >> 2) & 0xff, (b >> 10) & 0xf])
}

describe('imageSize', () => {
  test('reads PNG, JPEG, GIF and WebP headers', () => {
    expect(imageSize(png(1170, 2532))).toEqual([1170, 2532])
    expect(imageSize(jpeg(640, 480))).toEqual([640, 480])
    expect(imageSize(gif(32, 16))).toEqual([32, 16])
    expect(imageSize(webpX(1920, 1080))).toEqual([1920, 1080])
    expect(imageSize(webpLossy(300, 200))).toEqual([300, 200])
    expect(imageSize(webpLossless(4000, 3000))).toEqual([4000, 3000])
  })
  test('anything else, truncated or broken: undefined', () => {
    expect(imageSize('AAA')).toBeUndefined()
    expect(imageSize('')).toBeUndefined()
    expect(imageSize(png(10, 10).slice(0, 12))).toBeUndefined()
    expect(imageSize('!!!not base64!!!')).toBeUndefined()
    expect(imageSize(png(0, 10))).toBeUndefined()
  })
})

describe('normalizeBlocks: image sizes', () => {
  const img = (data: string) => ({ type: 'image', source: { type: 'base64', media_type: 'image/png', data } })
  test('events carry the size of each image whose header parses', () => {
    const out = normalizeBlocks('assistant', [
      { type: 'tool_result', tool_use_id: 't1', content: [img(png(800, 600)), img('AAA')] },
    ], 'u1')
    expect(out.events[0]).toEqual({
      type: 'tool_result', toolUseId: 't1', isError: false, preview: '',
      images: ['u1#0.0', 'u1#0.1'], imageSizes: { 'u1#0.0': [800, 600] },
    })
    const user = normalizeBlocks('user', [img(jpeg(64, 48))], 'u2')
    expect(user.events[0]).toEqual({ type: 'user_text', uuid: 'u2', text: '', images: ['u2#0'], imageSizes: { 'u2#0': [64, 48] } })
  })
})
