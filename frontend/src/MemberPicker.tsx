import { useEffect, useId, useRef, useState } from "react";
import { Search, Check } from "lucide-react";
export type PickedMember = { id: string; fullName: string; memberNumber: string };
export default function MemberPicker({ label = "Find member", onSelect, required = true }: {
  label?: string; onSelect: (member: PickedMember | null) => void; required?: boolean;
}) {
  const id = useId(); const wrapper = useRef<HTMLDivElement>(null);
  const [query, setQuery] = useState(""); const [selected, setSelected] = useState<PickedMember | null>(null);
  const [results, setResults] = useState<PickedMember[]>([]); const [focused, setFocused] = useState(false);
  const [loading, setLoading] = useState(false); const [error, setError] = useState(""); const [active, setActive] = useState(0);
  useEffect(() => {
    if (!focused || selected) return;
    const controller = new AbortController(); setLoading(true); setError(""); setResults([]); setActive(0);
    const timer = window.setTimeout(() => {
      fetch(`/api/v1/members?search=${encodeURIComponent(query.trim())}&size=10`, { credentials: "same-origin", signal: controller.signal })
        .then(async response => { if (!response.ok) throw new Error("Members could not be loaded. Try searching again."); return response.json() as Promise<{ items: PickedMember[] }>; })
        .then(data => { if (!controller.signal.aborted) setResults(data.items); })
        .catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "Please retry."); })
        .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    }, 250);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [query, focused, selected]);
  const choose = (member: PickedMember) => { setSelected(member); setQuery(`${member.fullName} · ${member.memberNumber}`); setFocused(false); onSelect(member); };
  const expanded = focused && !selected;
  return <div className="member-picker" ref={wrapper} onBlur={event => { if (!wrapper.current?.contains(event.relatedTarget as Node)) setFocused(false); }}>
    <label htmlFor={id}>{label}</label><div className="member-picker-control"><Search size={18} /><input id={id} role="combobox" autoComplete="off"
      aria-expanded={expanded} aria-controls={`${id}-results`} aria-autocomplete="list" aria-activedescendant={expanded && results[active] ? `${id}-${active}` : undefined}
      placeholder="Name or member number" value={query} required={required} onFocus={() => setFocused(true)}
      onChange={event => { setQuery(event.target.value); setSelected(null); setFocused(true); onSelect(null); }}
      onKeyDown={event => { if (event.key === "Escape") { setFocused(false); event.stopPropagation(); }
        if (expanded && event.key === "ArrowDown") { event.preventDefault(); setActive(value => Math.min(results.length - 1, value + 1)); }
        if (expanded && event.key === "ArrowUp") { event.preventDefault(); setActive(value => Math.max(0, value - 1)); }
        if (expanded && event.key === "Enter" && results.length) { event.preventDefault(); choose(results[Math.max(0, active)]); } }} />{selected && <Check size={18} aria-label="Member selected" />}</div>
    {expanded && <div className="member-picker-results"><p className="member-picker-feedback" role="status">{loading ? "Searching members…" : error || (!results.length ? "No matching members. Try another name or number." : `${results.length} matching members`)}</p>
      <div id={`${id}-results`} role="listbox" aria-label={`${label} results`}>{results.map((member, index) => <button type="button" role="option" aria-selected={active === index} id={`${id}-${index}`} key={member.id} aria-label={`${member.fullName} · ${member.memberNumber}`}
        onMouseDown={event => event.preventDefault()} onClick={() => choose(member)}><strong>{member.fullName}</strong><span>{member.memberNumber}</span></button>)}</div>
    </div>}
    {selected && <small>Selected: {selected.memberNumber}</small>}
  </div>;
}
