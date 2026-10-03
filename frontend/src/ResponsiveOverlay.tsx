import * as Dialog from "@radix-ui/react-dialog";
import { useRef, useState } from "react";
import type { ReactNode, PointerEvent } from "react";
import { X } from "lucide-react";

export default function ResponsiveOverlay({ open, onClose, title, description, children }: {
  open: boolean; onClose: () => void; title: string; description?: string; children: ReactNode;
}) {
  const [offset, setOffset] = useState(0);
  const drag = useRef<{ y: number; time: number } | null>(null);
  const dragged = useRef(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const opener = useRef<HTMLElement | null>(null);
  const start = (event: PointerEvent<HTMLButtonElement>) => {
    if (window.matchMedia("(min-width: 1024px)").matches) return;
    dragged.current = false;
    drag.current = { y: event.clientY, time: performance.now() };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const end = (event: PointerEvent<HTMLButtonElement>) => {
    const movement = drag.current ? Math.max(0, event.clientY - drag.current.y) : 0;
    const elapsed = drag.current ? performance.now() - drag.current.time : 1000;
    drag.current = null; setOffset(0);
    if (movement > 100 || (movement > 35 && movement / Math.max(1, elapsed) > .6)) onClose();
  };
  return <Dialog.Root open={open} onOpenChange={value => { if (!value) { setOffset(0); onClose(); } }}>
    <Dialog.Portal><Dialog.Overlay className="overlay-backdrop" />
      <Dialog.Content className="responsive-overlay" style={{ transform: offset ? `translateY(${offset}px)` : undefined }}
        onOpenAutoFocus={event => { opener.current = document.activeElement as HTMLElement; event.preventDefault(); heading.current?.focus(); }}
        onCloseAutoFocus={event => { event.preventDefault(); if (opener.current?.isConnected) opener.current.focus(); }}>
        <button className="sheet-drag-handle" aria-label={`Close ${title}`} onClick={() => { if (!dragged.current) onClose(); }} onPointerDown={start}
          onPointerMove={event => { if (drag.current) { const movement = Math.max(0, event.clientY - drag.current.y); if (movement > 6) dragged.current = true; setOffset(movement); } }}
          onPointerUp={end} onPointerCancel={() => { drag.current = null; setOffset(0); }}><span /></button>
        <div className="overlay-heading"><div><Dialog.Title ref={heading} tabIndex={-1}>{title}</Dialog.Title>
          <Dialog.Description className={description ? "" : "sr-only"}>{description ?? `Review ${title.toLowerCase()} details.`}</Dialog.Description></div>
          <Dialog.Close asChild><button className="icon-button" aria-label={`Close ${title} panel`}><X size={21} /></button></Dialog.Close></div>
        <div className="overlay-body">{children}</div>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>;
}
