import { WalletCards } from "lucide-react";
import { formatMoney } from "./money";

export type EquityPoint = { date: string; equity: string };
const dateLabel = (date: string) => new Date(`${date}T12:00:00`).toLocaleDateString("en-GH", { day: "numeric", month: "short", year: "numeric" });
const axisMoney = (value: number) => `GH₵ ${new Intl.NumberFormat("en-GH", { notation: "compact", maximumFractionDigits: 1 }).format(value)}`;
export default function EquityChart({ points, summarizedHistory = false }: { points: EquityPoint[]; summarizedHistory?: boolean }) {
  const ordered = [...points].filter(point => Number.isFinite(Number(point.equity))).sort((a, b) => a.date.localeCompare(b.date));
  if (!ordered.length || ordered.every(point => Number(point.equity) === 0)) return <div className="dashboard-empty"><WalletCards size={30} /><h3>No equity history yet</h3><p>Your posted contributions and income will build this chart.</p></div>;
  if (summarizedHistory) return <div className="dashboard-empty"><WalletCards size={30} /><h3>Historical balances imported as summaries</h3><p>The source workbook has no transaction dates. A monthly growth chart would be misleading. Your balances and statement include the dated migration summaries.</p><p>Latest ledger balance: <strong>{formatMoney(ordered.at(-1)!.equity)}</strong> as at {dateLabel(ordered.at(-1)!.date)}.</p></div>;
  const values = ordered.map(point => Number(point.equity));
  const minimum = Math.min(0, ...values), maximum = Math.max(1, ...values), range = maximum - minimum;
  const y = (value: number) => 190 - (value - minimum) / range * 160;
  const coordinates = values.map((value, index) => `${20 + index * 560 / Math.max(1, values.length - 1)},${y(value)}`);
  const labels = ordered.filter((_, index) => index === 0 || index === Math.floor((ordered.length - 1) / 2) || index === ordered.length - 1);
  return <div className="equity-chart"><p>Approved ledger equity in Ghana cedis at each month end, with the last point through the selected as-at date.</p>
    <div className="equity-plot"><div className="equity-axis" aria-label="Equity scale in Ghana cedis">{[maximum, (maximum + minimum) / 2, minimum].map((value, index) => <span key={index} title={formatMoney(String(value))}>{axisMoney(value)}</span>)}</div>
      <svg viewBox="0 0 600 220" preserveAspectRatio="none" role="img" aria-label={`Approved ledger equity from ${dateLabel(ordered[0].date)}: ${formatMoney(ordered[0].equity)} to ${dateLabel(ordered.at(-1)!.date)}: ${formatMoney(ordered.at(-1)!.equity)}`}>
        {[maximum, (maximum + minimum) / 2, minimum].map((value, index) => <line key={index} x1="20" x2="580" y1={y(value)} y2={y(value)} className="trend-grid" />)}
        {minimum < 0 && <line x1="20" x2="580" y1={y(0)} y2={y(0)} className="equity-zero" />}
        <polyline points={coordinates.join(" ")} fill="none" stroke="var(--accent-text)" strokeWidth="3" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
        {coordinates.map((coordinate, index) => { const [cx, cy] = coordinate.split(","); return <circle key={ordered[index].date} cx={cx} cy={cy} r="4" fill="var(--accent-text)"><title>{dateLabel(ordered[index].date)}: {formatMoney(ordered[index].equity)}</title></circle>; })}
      </svg></div>
    <div className="equity-dates">{labels.map(point => <span key={point.date}>{dateLabel(point.date)}</span>)}</div>
    <details className="equity-values"><summary>View exact monthly balances</summary><table><thead><tr><th scope="col">As at</th><th scope="col">Equity (GH₵)</th></tr></thead><tbody>{ordered.map(point => <tr key={point.date}><th scope="row">{dateLabel(point.date)}</th><td>{formatMoney(point.equity)}</td></tr>)}</tbody></table></details>
  </div>;
}
