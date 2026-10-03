import { ChevronLeft, ChevronRight, ChevronsLeft } from "lucide-react";

type Props = { page: number; hasNext: boolean; loading?: boolean; onPageChange: (page: number) => void; label: string; count?: number; pageSize?: number };
export default function Pagination({ page, hasNext, loading = false, onPageChange, label, count, pageSize = 50 }: Props) {
  return <nav className="pagination" aria-label={label} aria-busy={loading}>
    <span className="pagination-summary" role="status">{loading ? "Loading…" : count === undefined ? "" : count === 0 ? "No results on this page" : `${page * pageSize + 1}–${page * pageSize + count} shown`}</span>
    <div className="pagination-controls">
      {page > 1 && <button type="button" className="pagination-button pagination-first" aria-label="First page" disabled={loading} onClick={() => onPageChange(0)}><ChevronsLeft size={18} /></button>}
      <button type="button" className="pagination-button" disabled={loading || page === 0} onClick={() => onPageChange(page - 1)}><ChevronLeft size={18} /><span>Previous</span></button>
      <span className="pagination-current" aria-current="page">Page {page + 1}</span>
      <button type="button" className="pagination-button" disabled={loading || !hasNext} onClick={() => onPageChange(page + 1)}><span>Next</span><ChevronRight size={18} /></button>
    </div>
  </nav>;
}
