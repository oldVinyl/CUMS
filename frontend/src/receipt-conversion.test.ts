import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { File as NodeFile, Blob as NodeBlob } from "node:buffer";
import { inspectHeic, prepareReceipt } from "./receipt-conversion";

function box(type: string, payload: number[]): number[] {
  const length = payload.length + 8;
  return [
    length >>> 24,
    (length >>> 16) & 255,
    (length >>> 8) & 255,
    length & 255,
    ...Array.from(type, (value) => value.charCodeAt(0)),
    ...payload,
  ];
}
function source(width = 32, height = 32): ArrayBuffer {
  const dimensions = new Uint8Array(12),
    view = new DataView(dimensions.buffer);
  view.setUint32(4, width);
  view.setUint32(8, height);
  return Uint8Array.from([
    ...box("ftyp", [...Array.from("heic", (value) => value.charCodeAt(0)), 0, 0, 0, 0]),
    ...box("meta", [0, 0, 0, 0, ...box("iprp", box("ipco", box("ispe", [...dimensions])))]),
  ]).buffer;
}
class FixtureWorker {
  static last: FixtureWorker;
  onmessage?: (event: { data: { jpeg: Blob } }) => void;
  onerror?: () => void;
  terminate = vi.fn();
  postMessage = vi.fn();
  constructor() {
    FixtureWorker.last = this;
  }
}
beforeEach(() => {
  vi.stubGlobal("File", NodeFile);
  vi.stubGlobal("Blob", NodeBlob);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
describe("local receipt conversion", () => {
  it("accepts bounded HEIC image properties and rejects oversized/truncated containers", () => {
    expect(inspectHeic(source())).toBe(true);
    expect(() => inspectHeic(source(10000, 10000))).toThrow("25 megapixels");
    expect(() => inspectHeic(source().slice(0, 18))).toThrow("invalid");
    expect(inspectHeic(new ArrayBuffer(3))).toBe(false);
  });
  it("passes normal receipts through and bounds size before decoding", async () => {
    const file = new File(["%PDF-synthetic"], "receipt.pdf", { type: "application/pdf" });
    expect(await prepareReceipt(file)).toBe(file);
    await expect(prepareReceipt(new File([], "empty.heic"))).rejects.toThrow("10 MB");
    await expect(prepareReceipt(new File(["wrong"], "broken.heic"))).rejects.toThrow("invalid");
  });
  it("terminates decoding on success and returns an anonymous JPEG filename", async () => {
    vi.stubGlobal("Worker", FixtureWorker);
    const result = prepareReceipt(
      new File([source()], "private-name.heic", { type: "image/heic" }),
    );
    await vi.waitFor(() => expect(FixtureWorker.last?.postMessage).toHaveBeenCalled());
    FixtureWorker.last.onmessage?.({ data: { jpeg: new Blob(["jpeg"], { type: "image/jpeg" }) } });
    const converted = await result;
    expect(converted.name).toBe("receipt.jpg");
    expect(FixtureWorker.last.terminate).toHaveBeenCalledOnce();
  });
  it("bounds decoder time and terminates its worker on failure", async () => {
    vi.stubGlobal("Worker", FixtureWorker);
    vi.useFakeTimers();
    const result = prepareReceipt(new File([source()], "receipt.heic"));
    const rejected = expect(result).rejects.toThrow("too long");
    await vi.advanceTimersByTimeAsync(20_001);
    await rejected;
    expect(FixtureWorker.last.terminate).toHaveBeenCalledOnce();
  });
});
