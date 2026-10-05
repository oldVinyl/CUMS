import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { setCurrencyDisplay } from "./money";
import "./organization-branding.css";
let currentName = "Harbour Credit Union";
export function currentOrganizationName() {
  return currentName;
}
const defaults = {
  organisationName: "Harbour Credit Union",
  primaryColour: "#083a4f",
  currencyDisplay: "SYMBOL",
  logoDataUrl: "",
  passwordMinLength: 12,
};
type Branding = typeof defaults;
const Context = createContext({ ...defaults, refresh: () => Promise.resolve() });
export function useOrganization() {
  return useContext(Context);
}
export function OrganizationProvider({ children }: { children: ReactNode }) {
  const [branding, setBranding] = useState<Branding>(defaults);
  const refresh = useCallback(async () => {
    try {
      const r = await fetch("/api/v1/organization/branding", { cache: "no-store" });
      if (r.ok) {
        const next = (await r.json()) as Branding;
        currentName = next.organisationName;
        setCurrencyDisplay(next.currencyDisplay);
        setBranding(next);
      }
    } catch {
      /* Keep public defaults during a network outage. */
    }
  }, []);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  useEffect(() => {
    document.documentElement.style.setProperty("--brand-primary", branding.primaryColour);
    document.title = branding.organisationName;
  }, [branding]);
  return <Context.Provider value={{ ...branding, refresh }}>{children}</Context.Provider>;
}

export function OrganizationName() {
  return <>{useOrganization().organisationName}</>;
}
