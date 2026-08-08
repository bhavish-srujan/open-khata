/**
 * Content sniffing from the leading bytes.
 *
 * The multipart Content-Type is supplied by the client and trivially forged, so
 * the extension filter alone let any bytes through under a .png name. A file
 * that is actually HTML is the dangerous case: served inline it executes in the
 * origin that serves it. Uploads are also stored and returned as attachments,
 * so this is defence in depth rather than the only control.
 */
export type AllowedMime = 'image/jpeg' | 'image/png' | 'application/pdf';

const SIGNATURES: { mime: AllowedMime; magic: number[]; offset?: number }[] = [
  { mime: 'image/jpeg', magic: [0xff, 0xd8, 0xff] },
  { mime: 'image/png', magic: [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a] },
  { mime: 'application/pdf', magic: [0x25, 0x50, 0x44, 0x46] }, // %PDF
];

/** The real type of these bytes, or null if it is not one we accept. */
export function detectMime(buffer: Buffer): AllowedMime | null {
  for (const { mime, magic, offset = 0 } of SIGNATURES) {
    if (buffer.length < offset + magic.length) continue;
    if (magic.every((byte, i) => buffer[offset + i] === byte)) return mime;
  }
  return null;
}

/** File extension for a detected type. Never taken from the uploaded name. */
export function extensionFor(mime: AllowedMime): string {
  switch (mime) {
    case 'image/jpeg':
      return '.jpg';
    case 'image/png':
      return '.png';
    case 'application/pdf':
      return '.pdf';
  }
}
