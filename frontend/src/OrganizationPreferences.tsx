import { useEffect, useState } from "react";
import { csrfCookie } from "./auth-api";
import { useOrganization } from "./OrganizationBranding";
import { usePromiseToast } from "./toast-context";
type Settings = {
  passwordMinLength: number;
  sessionIdleMinutes: number;
  primaryColour: string;
  currencyDisplay: string;
  logoDataUrl: string;
};
export default function OrganizationPreferences() {
  const [settings, setSettings] = useState<Settings | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [newLogo, setNewLogo] = useState<string | null>(null),
    [removeLogo, setRemoveLogo] = useState(false),
    [retry, setRetry] = useState(0);
  const toast = usePromiseToast(),
    organization = useOrganization();
  useEffect(() => {
    const controller = new AbortController();
    setError("");
    fetch("/api/v1/organization", { credentials: "same-origin", signal: controller.signal })
      .then(async (r) => {
        if (!r.ok) throw new Error("Organization preferences could not be loaded.");
        return r.json() as Promise<Settings>;
      })
      .then(setSettings)
      .catch((e) => {
        if (!controller.signal.aborted) setError(e.message);
      });
    return () => controller.abort();
  }, [retry]);
  if (error)
    return (
      <div className="organization-preferences" role="alert">
        <p>{error}</p>
        <button className="secondary-button" onClick={() => setRetry((n) => n + 1)}>
          Retry preferences
        </button>
      </div>
    );
  if (!settings)
    return (
      <p className="organization-preferences" role="status">
        Loading branding and security policies…
      </p>
    );
  const save = async () => {
    setBusy(true);
    try {
      await toast(
        (async () => {
          const r = await fetch("/api/v1/organization", {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() },
            body: JSON.stringify({ ...settings, logoDataUrl: newLogo, removeLogo }),
          });
          if (!r.ok) {
            const e = await r.json();
            throw new Error(e.message ?? "Preferences could not be saved.");
          }
          setSettings(await r.json());
          setNewLogo(null);
          setRemoveLogo(false);
          await organization.refresh();
        })(),
        {
          loading: "Saving organization preferences…",
          success: "Organization preferences saved",
          error: "Could not save preferences",
        },
      );
    } catch {
      /*toast*/
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="organization-preferences">
      <h3>Branding and account policies</h3>
      <form
        className="investment-form"
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <fieldset disabled={busy}>
          <legend>Branding</legend>
          <div className="investment-form-grid">
            <label>
              Primary colour
              <input
                type="color"
                value={settings.primaryColour}
                onChange={(e) => setSettings({ ...settings, primaryColour: e.target.value })}
              />
              <span className="field-help">
                Must meet 4.5:1 contrast against white button text.
              </span>
            </label>
            <label>
              Currency display
              <select
                value={settings.currencyDisplay}
                onChange={(e) => setSettings({ ...settings, currencyDisplay: e.target.value })}
              >
                <option value="SYMBOL">GH₵ — Ghana cedis</option>
                <option value="CODE">GHS — Ghana cedis</option>
              </select>
              <span className="field-help">
                Changes display only; all financial values remain in GHS.
              </span>
            </label>
            <label>
              Public organization logo
              <input
                type="file"
                accept="image/png"
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (!file) return;
                  void toast(
                    (async () => {
                      if (file.size > 524288 || file.type !== "image/png")
                        throw new Error("Use a PNG up to 512 KB.");
                      const data = await new Promise<string>((resolve, reject) => {
                        const reader = new FileReader();
                        reader.onload = () => resolve(String(reader.result));
                        reader.onerror = () => reject(new Error("Logo could not be read."));
                        reader.readAsDataURL(file);
                      });
                      setNewLogo(data);
                      setRemoveLogo(false);
                    })(),
                    {
                      loading: "Preparing logo…",
                      success: "Logo ready to save",
                      error: "Could not prepare logo",
                    },
                  ).catch(() => {});
                }}
              />
              <span className="field-help">
                PNG, at most 512 KB and 2048 × 2048 pixels. Publicly visible on the landing page.
              </span>
            </label>
          </div>
          {!removeLogo && (newLogo || settings.logoDataUrl) && (
            <img
              className="organization-logo"
              src={newLogo ?? settings.logoDataUrl}
              alt="Organization logo preview"
            />
          )}
          <label>
            <input
              type="checkbox"
              checked={removeLogo}
              onChange={(e) => {
                setRemoveLogo(e.target.checked);
                if (e.target.checked) setNewLogo(null);
              }}
            />{" "}
            Remove organization logo on save
          </label>
        </fieldset>
        <fieldset disabled={busy}>
          <legend>Account security</legend>
          <div className="investment-form-grid">
            <label>
              Minimum password length
              <input
                type="number"
                min={12}
                max={64}
                required
                value={settings.passwordMinLength}
                onChange={(e) =>
                  setSettings({ ...settings, passwordMinLength: Number(e.target.value) })
                }
              />
              <span className="field-help">
                12–64 characters; common passwords are always rejected. Applies when setting a
                password.
              </span>
            </label>
            <label>
              Session idle timeout (minutes)
              <input
                type="number"
                min={5}
                max={120}
                required
                value={settings.sessionIdleMinutes}
                onChange={(e) =>
                  setSettings({ ...settings, sessionIdleMinutes: Number(e.target.value) })
                }
              />
              <span className="field-help">
                5–120 minutes. Applies to current sessions on their next request. Access-token
                lifetime remains 15 minutes.
              </span>
            </label>
          </div>
        </fieldset>
        <button className="primary-button" disabled={busy}>
          Save branding and account policies
        </button>
      </form>
    </div>
  );
}
