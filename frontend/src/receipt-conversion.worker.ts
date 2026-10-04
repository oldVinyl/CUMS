import { heicTo } from "heic-to/csp";
const RECEIPT_BYTES = 10 * 1024 * 1024;
const RECEIPT_PIXELS = 25_000_000;
// Keep decoder diagnostics out of browser logs; show only bounded application feedback.
console.log = console.info = console.warn = console.error = () => {};

self.onmessage = async (event: MessageEvent<ArrayBuffer>) => {
  let bitmap: ImageBitmap | undefined;
  try {
    bitmap = await heicTo({ blob: new Blob([event.data]), type: "bitmap" });
    if (!bitmap.width || !bitmap.height || bitmap.width * bitmap.height > RECEIPT_PIXELS)
      throw new Error("Invalid image dimensions");
    const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Canvas unavailable");
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(bitmap, 0, 0);
    const jpeg = await canvas.convertToBlob({ type: "image/jpeg", quality: 0.9 });
    if (jpeg.size > RECEIPT_BYTES) throw new Error("Result too large");
    self.postMessage({ jpeg });
    canvas.width = 1;
    canvas.height = 1;
  } catch {
    // Decoder diagnostics and source bytes never enter application feedback/logs.
    self.postMessage({ error: true });
  } finally {
    bitmap?.close();
  }
};
