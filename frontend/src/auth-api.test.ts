import { expect, it } from "vitest";
import { totpUri } from "./auth-api";

it("builds an authenticator URI with encoded account and standard TOTP settings", () => {
  expect(totpUri("ABCDEF234567", "member+test@example.com")).toBe(
    "otpauth://totp/Harbour%20Credit%20Union:member%2Btest%40example.com" +
      "?secret=ABCDEF234567&issuer=Harbour%20Credit%20Union" +
      "&algorithm=SHA1&digits=6&period=30",
  );
});
