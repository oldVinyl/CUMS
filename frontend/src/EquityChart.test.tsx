import { afterEach, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import EquityChart from "./EquityChart";
afterEach(cleanup);
it("does not invent monthly growth from legacy summary dates", () => {
 render(<EquityChart points={[{date:"2026-01-31",equity:"10"},{date:"2026-10-03",equity:"18"}]} summarizedHistory />);
 expect(screen.getByText("Historical balances imported as summaries")).toBeTruthy();
 expect(screen.queryByRole("img")).toBeNull();
 expect(screen.getByText(/Latest ledger balance/)).toBeTruthy();
});
it("retains the correct empty state for a zero legacy balance", () => {
 render(<EquityChart points={[{date:"2026-10-03",equity:"0"}]} summarizedHistory />);
 expect(screen.getByText("No equity history yet")).toBeTruthy();
});
