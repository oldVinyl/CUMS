// Run against a disposable dev database and a Chromium CDP endpoint on port 9222.
const email = process.env.CUMS_E2E_EMAIL;
const password = process.env.CUMS_E2E_PASSWORD;
const nextPassword = process.env.CUMS_E2E_NEW_PASSWORD;
if (!email || !password || !nextPassword)
  throw new Error("E2E account variables are required");

const tabs = await fetch("http://127.0.0.1:9222/json/list").then((response) =>
  response.json(),
);
const tab = tabs.find((item) => item.type === "page");
if (!tab) throw new Error("No Chromium page is available on CDP port 9222");
const socket = new WebSocket(tab.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, { once: true });
  socket.addEventListener("error", reject, { once: true });
});

let nextId = 1;
let primaryAuthenticator, spareAuthenticator;
const pending = new Map();
socket.addEventListener("message", async (event) => {
  const message = JSON.parse(event.data);
  if (message.method === "Runtime.bindingCalled" && message.params.name === "__switchAuthenticator") {
    const primary = message.params.payload === "primary";
    await send("WebAuthn.setAutomaticPresenceSimulation", { authenticatorId: primaryAuthenticator, enabled: primary });
    await send("WebAuthn.setAutomaticPresenceSimulation", { authenticatorId: spareAuthenticator, enabled: !primary });
    await send("Runtime.evaluate", { expression: "window.__switchResolved()" });
    return;
  }
  if (!message.id) return;
  const item = pending.get(message.id);
  if (!item) return;
  pending.delete(message.id);
  if (message.error) item.reject(new Error(message.error.message));
  else item.resolve(message.result);
});
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}

try {
  await send("Page.enable");
  await send("Page.navigate", { url: "http://localhost:5173/sign-in" });
  for (let attempt = 0; attempt < 30; attempt++) {
    const result = await send("Runtime.evaluate", {
      expression: "location.origin",
      returnByValue: true,
    });
    if (result.result.value === "http://localhost:5173") break;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  await send("WebAuthn.enable");
  const primary = await send("WebAuthn.addVirtualAuthenticator", {
    options: {
      protocol: "ctap2",
      transport: "usb",
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      automaticPresenceSimulation: true,
    },
  });

  primaryAuthenticator = primary.authenticatorId;
  const spareDevice = await send("WebAuthn.addVirtualAuthenticator", { options: { protocol: "ctap2", transport: "usb", hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: false } });
  spareAuthenticator = spareDevice.authenticatorId;
  await send("Runtime.addBinding", { name: "__switchAuthenticator" });
  const expression = `(${async function run(account) {
    const encode = (buffer) =>
      buffer == null
        ? null
        : btoa(String.fromCharCode(...new Uint8Array(buffer)))
            .replace(/\+/g, "-")
            .replace(/\//g, "_")
            .replace(/=+$/g, "");
    const decode = (value) =>
      Uint8Array.from(
        atob(value.replace(/-/g, "+").replace(/_/g, "/")),
        (character) => character.charCodeAt(0),
      ).buffer;
    async function post(path, body) {
      const response = await fetch(`/api/v1/auth/${path}`, {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-Token":
            document.cookie
              .split("; ")
              .find((item) => item.startsWith("cu_csrf="))
              ?.split("=")[1] ?? "",
        },
        body: JSON.stringify(body),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok)
        throw new Error(`${path} returned ${response.status}: ${data.code}`);
      return data;
    }
    const first = await post("login", {
      email: account.email,
      password: account.password,
    });
    if (first.stage !== "PASSWORD_CHANGE")
      throw new Error(`Expected password change, got ${first.stage}`);
    const setup = await post("password", {
      challenge: first.challenge,
      newPassword: account.nextPassword,
    });
    if (setup.stage !== "TOTP_SETUP")
      throw new Error(`Expected setup, got ${setup.stage}`);
    const start = await post("passkey/register/options", {
      challenge: setup.challenge,
    });
    const creation = start.options.publicKey;
    const credential = await navigator.credentials.create({
      publicKey: {
        ...creation,
        challenge: decode(creation.challenge),
        user: { ...creation.user, id: decode(creation.user.id) },
        excludeCredentials: creation.excludeCredentials?.map((item) => ({
          ...item,
          id: decode(item.id),
        })),
      },
    });
    if (!credential)
      throw new Error("Virtual authenticator did not create a credential");
    const attestation = credential.response;
    const registered = await post("passkey/register/finish", {
      challenge: setup.challenge,
      token: start.token,
      credential: {
        id: credential.id,
        rawId: encode(credential.rawId),
        type: credential.type,
        response: {
          attestationObject: encode(attestation.attestationObject),
          clientDataJSON: encode(attestation.clientDataJSON),
          transports: attestation.getTransports?.() ?? [],
        },
        clientExtensionResults: credential.getClientExtensionResults(),
        authenticatorAttachment: credential.authenticatorAttachment,
      },
    });
    if (registered.stage !== "AUTHENTICATED")
      throw new Error("Registration did not sign in");
    const profile = await fetch("/api/v1/auth/me", {
      credentials: "same-origin",
    });
    if (!profile.ok) throw new Error("Registered session was not accepted");
    if ((await profile.json()).preferredSignInMethod !== "PASSKEY") {
      throw new Error("Passkey was not saved as the first sign-in preference");
    }
    const logout = await fetch("/api/v1/auth/logout", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "X-CSRF-Token":
          document.cookie
            .split("; ")
            .find((item) => item.startsWith("cu_csrf="))
            ?.split("=")[1] ?? "",
      },
    });
    if (!logout.ok) throw new Error("Logout failed");
    const assertionStart = await post("passkey/login/options", {});
    const request = assertionStart.options.publicKey;
    const asserted = await navigator.credentials.get({
      publicKey: {
        ...request,
        challenge: decode(request.challenge),
        allowCredentials: request.allowCredentials?.map((item) => ({
          ...item,
          id: decode(item.id),
        })),
      },
    });
    if (!asserted)
      throw new Error("Virtual authenticator did not assert a credential");
    const assertion = asserted.response;
    const assertionBody = {
      token: assertionStart.token,
      credential: {
        id: asserted.id,
        rawId: encode(asserted.rawId),
        type: asserted.type,
        response: {
          authenticatorData: encode(assertion.authenticatorData),
          clientDataJSON: encode(assertion.clientDataJSON),
          signature: encode(assertion.signature),
          userHandle: encode(assertion.userHandle),
        },
        clientExtensionResults: asserted.getClientExtensionResults(),
        authenticatorAttachment: asserted.authenticatorAttachment,
      },
    };
    const signedIn = await post("passkey/login/finish", assertionBody);
    if (signedIn.stage !== "AUTHENTICATED")
      throw new Error("Assertion did not sign in");
    const replay = await fetch("/api/v1/auth/passkey/login/finish", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(assertionBody),
    });
    if (replay.status !== 401)
      throw new Error(`Replay returned ${replay.status}`);
    const before = await fetch("/api/v1/auth/security", { credentials: "same-origin" }).then(response => response.json());
    if (before.passkeys.length !== 1) throw new Error("Expected one initial passkey");
    await new Promise(resolve => { window.__switchResolved = resolve; window.__switchAuthenticator("spare"); });
    const spareStart = await post("passkey/register/options", {});
    const spareOptions = spareStart.options.publicKey;
    const spare = await navigator.credentials.create({ publicKey: {
      ...spareOptions, challenge: decode(spareOptions.challenge), user: { ...spareOptions.user, id: decode(spareOptions.user.id) },
      excludeCredentials: spareOptions.excludeCredentials?.map(item => ({ ...item, id: decode(item.id) })),
    } });
    const spareResponse = spare.response;
    await post("passkey/register/finish", { token: spareStart.token, credential: {
      id: spare.id, rawId: encode(spare.rawId), type: spare.type,
      response: { attestationObject: encode(spareResponse.attestationObject), clientDataJSON: encode(spareResponse.clientDataJSON), transports: spareResponse.getTransports?.() ?? [] },
      clientExtensionResults: spare.getClientExtensionResults(), authenticatorAttachment: spare.authenticatorAttachment,
    } });
    async function prove(id, device = "primary") {
      await new Promise(resolve => { window.__switchResolved = resolve; window.__switchAuthenticator(device); });
      const ceremony = await post("passkey/login/options", {});
      const asserted = await navigator.credentials.get({ publicKey: { ...ceremony.options.publicKey, challenge: decode(ceremony.options.publicKey.challenge), allowCredentials: [{ type: "public-key", id }] } });
      const response = asserted.response;
      return { token: ceremony.token, credential: {
        id: asserted.id, rawId: encode(asserted.rawId), type: asserted.type,
        response: { authenticatorData: encode(response.authenticatorData), clientDataJSON: encode(response.clientDataJSON), signature: encode(response.signature), userHandle: encode(response.userHandle) },
        clientExtensionResults: asserted.getClientExtensionResults(), authenticatorAttachment: asserted.authenticatorAttachment,
      } };
    }
    const fresh = await prove(credential.rawId);
    await post(`security/passkeys/${before.passkeys[0].id}/remove`, { proof: { password: account.nextPassword, passkeyToken: fresh.token, credential: fresh.credential } });
    const revokedSession = await fetch("/api/v1/auth/me", { credentials: "same-origin" });
    if (revokedSession.status !== 401) throw new Error("Removal did not revoke the session");
    const revokedProof = await prove(credential.rawId);
    const revokedAssertion = await fetch("/api/v1/auth/passkey/login/finish", { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify(revokedProof) });
    if (revokedAssertion.status !== 401) throw new Error("Removed passkey was accepted");
    const spareLogin = await post("passkey/login/finish", await prove(spare.rawId, "spare"));
    if (spareLogin.stage !== "AUTHENTICATED") throw new Error("Spare passkey was not accepted");
    const after = await fetch("/api/v1/auth/security", { credentials: "same-origin" }).then(response => response.json());
    if (after.passkeys.length !== 1) throw new Error("Revoked passkey remains active");
    const last = await fetch(`/api/v1/auth/security/passkeys/${after.passkeys[0].id}/remove`, { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json", "X-CSRF-Token": document.cookie.split("; ").find(item => item.startsWith("cu_csrf="))?.split("=")[1] ?? "" }, body: JSON.stringify({ proof: { password: account.nextPassword } }) });
    if (last.status !== 409) throw new Error("Last protected method was removable");
    return {
      registered: registered.stage,
      signedIn: signedIn.stage,
      replay: replay.status,
      removedCredentialRejected: revokedAssertion.status,
      removedSessionRejected: revokedSession.status,
      sparePasskey: spareLogin.stage,
      lastMethodProtected: last.status,
    };
  }.toString()})(${JSON.stringify({ email, password, nextPassword })})`;
  const result = await send("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  if (result.exceptionDetails)
    throw new Error(
      result.exceptionDetails.text +
        ": " +
        (result.exceptionDetails.exception?.description ?? ""),
    );
  process.stdout.write(JSON.stringify(result.result.value) + "\n");
} finally {
  await send("Browser.close").catch(() => {});
  socket.close();
}
