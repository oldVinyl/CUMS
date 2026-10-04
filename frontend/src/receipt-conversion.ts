export const RECEIPT_BYTES = 10 * 1024 * 1024;
export const RECEIPT_PIXELS = 25_000_000;
const brands = new Set(["heic", "heix", "hevc", "hevx"]);
const text = (bytes: Uint8Array, offset: number) =>
  String.fromCharCode(...bytes.subarray(offset, offset + 4));

/** Check bounded ISO BMFF image properties before loading the local decoder. */
export function inspectHeic(buffer: ArrayBuffer): boolean {
  const bytes = new Uint8Array(buffer),
    view = new DataView(buffer);
  if (bytes.length < 16 || text(bytes, 4) !== "ftyp") return false;
  const ftypEnd = view.getUint32(0);
  if (ftypEnd < 16 || ftypEnd > bytes.length || ftypEnd % 4)
    throw new Error("The HEIC receipt is incomplete or invalid.");
  const supported =
    brands.has(text(bytes, 8)) ||
    Array.from({ length: (ftypEnd - 16) / 4 }, (_, index) => text(bytes, 16 + index * 4)).some(
      (brand) => brands.has(brand),
    );
  if (!supported) return false;
  let inspected = 0,
    images = 0;
  function visit(start: number, end: number, depth: number) {
    if (depth > 12) throw new Error("The HEIC receipt has an unsupported structure.");
    for (let offset = start; offset < end; ) {
      if (++inspected > 10000 || end - offset < 8)
        throw new Error("The HEIC receipt is incomplete or invalid.");
      const size = view.getUint32(offset),
        kind = text(bytes, offset + 4);
      // Reject extended/unknown box lengths instead of allocating from untrusted dimensions.
      if (size < 8 || size > end - offset)
        throw new Error("The HEIC receipt is incomplete or invalid.");
      const boxEnd = offset + size;
      if (kind === "ispe") {
        if (size < 20) throw new Error("The HEIC receipt has invalid image dimensions.");
        const width = view.getUint32(offset + 12),
          height = view.getUint32(offset + 16);
        if (!width || !height || width * height > RECEIPT_PIXELS)
          throw new Error("Use a receipt image of at most 25 megapixels.");
        images++;
      } else if (["meta", "iprp", "ipco"].includes(kind)) {
        const children = offset + (kind === "meta" ? 12 : 8);
        if (children > boxEnd) throw new Error("The HEIC receipt is incomplete or invalid.");
        visit(children, boxEnd, depth + 1);
      }
      offset = boxEnd;
    }
  }
  visit(ftypEnd, bytes.length, 0);
  if (!images) throw new Error("The HEIC receipt has no readable image dimensions.");
  return true;
}

export async function prepareReceipt(file: File): Promise<File> {
  if (!file.size || file.size > RECEIPT_BYTES) throw new Error("Choose a receipt up to 10 MB.");
  const buffer = await (typeof file.arrayBuffer === "function"
    ? file.arrayBuffer()
    : new Promise<ArrayBuffer>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () =>
          reader.result instanceof ArrayBuffer
            ? resolve(reader.result)
            : reject(new Error("Receipt could not be read."));
        reader.onerror = () => reject(new Error("Receipt could not be read."));
        reader.readAsArrayBuffer(file);
      }));
  if (!inspectHeic(buffer)) {
    if (/\.hei[cf]$/i.test(file.name) || /image\/hei[cf]/i.test(file.type))
      throw new Error("The HEIC receipt is invalid or uses an unsupported format.");
    return file;
  }
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("./receipt-conversion.worker.ts", import.meta.url), {
      type: "module",
    });
    const finish = () => {
      clearTimeout(deadline);
      worker.terminate();
    };
    const deadline = setTimeout(() => {
      finish();
      reject(new Error("Receipt conversion took too long. Use a smaller image or JPEG."));
    }, 20_000);
    worker.onerror = () => {
      finish();
      reject(new Error("Receipt conversion failed. Use a JPEG or PDF copy."));
    };
    worker.onmessage = (event: MessageEvent<{ jpeg?: Blob; error?: boolean }>) => {
      finish();
      const jpeg = event.data.jpeg;
      if (!jpeg || jpeg.type !== "image/jpeg" || !jpeg.size || jpeg.size > RECEIPT_BYTES)
        reject(
          new Error(
            "Receipt conversion failed or the result exceeds 10 MB. Use a JPEG or PDF copy.",
          ),
        );
      else resolve(new File([jpeg], "receipt.jpg", { type: "image/jpeg" }));
    };
    worker.postMessage(buffer, [buffer]);
  });
}
