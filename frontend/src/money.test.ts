import { describe, expect, it } from "vitest";
import { formatMoney } from "./money";

describe("formatMoney", () => {
  it("formats exact decimal strings as cedis", () => {
    expect(formatMoney("12345.67")).toBe("GH₵ 12,345.67");
    expect(formatMoney("-1.25")).toBe("−GH₵ 1.25");
  });
});
