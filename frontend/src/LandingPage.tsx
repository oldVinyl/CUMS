import { useOrganization } from "./OrganizationBranding";
import { NavLink } from "react-router-dom";
import { ArrowRight, Landmark, Mail, ShieldCheck, WalletCards } from "lucide-react";

export default function LandingPage() {
  const organization = useOrganization();
  return <div className="landing-page">
    <section className="landing-intro">
      <div className="landing-mark" aria-hidden="true">{organization.logoDataUrl ? <img className="organization-logo" src={organization.logoDataUrl} alt="" /> : <Landmark size={42} strokeWidth={1.5} />}</div>
      <span className="eyebrow">{organization.organisationName}</span>
      <h1>{organization.organisationName}</h1>
      <p>Sign in to view your membership, follow contributions and manage your account.</p>
      <NavLink className="primary-button landing-login" to="/sign-in">Log in <ArrowRight size={20} /></NavLink>
      <p className="landing-access">Need an account? Ask the union for an email invitation.</p>
    </section>
    <section className="landing-services" aria-label="Member services">
      <article><WalletCards size={24} /><h2>Your membership</h2><p>Balances, statements and investment participation.</p></article>
      <article><Mail size={24} /><h2>Your contributions</h2><p>Payment instructions, receipt submissions and review status.</p></article>
      <article><ShieldCheck size={24} /><h2>Your account</h2><p>Contact details and your preferred sign-in method.</p></article>
    </section>
    <footer className="landing-credit">
      <div><span className="eyebrow">Design & development</span><h2>Built by Vinette, by Vinyl.</h2><p>Looking for a website or an application for your organization?</p></div>
      <a className="secondary-button" href="https://github.com/oldVinyl" target="_blank" rel="noopener noreferrer">View my work on GitHub <ArrowRight size={18} /></a>
    </footer>
  </div>;
}
