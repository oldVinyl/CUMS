# Legacy workbook import

## Current status

The workbooks remain local and ignored by Git. Private staging files are in `private-migration/2026/`; no member values are printed by the converter. Do not share or commit that directory or the source workbooks. The converter does not call a network service.

**No real balances have been posted.** The owner instructed on 2026-10-03 that the historical income should be treated as received. This is an explicit assumption, since receipt evidence is unavailable. The investment sheets calculate income as future value minus cost; they contain no date cells or received/matured status markers. That calculation cannot establish receipt or maturity. Future investment records must capture purchase/maturity dates and actual proceeds.

The 2026 dashboard reconciles to:

| Component | Amount |
| --- | ---: |
| Balance-forward principal | 225,311.5000 |
| Balance-forward income | 23,478.5000 |
| YTD principal | 25,500.0000 |
| YTD income (assumed received) | 15,376.0000 |
| Closing equity | 289,666.0000 |

There are 16 named members and 54 nonzero summary ledger entries. The earlier workbook's closing principal/income match the 2026 balance-forward totals. Importing both sets or posting the investment sheets as well would double-count balances.

## Prepare locally

Use a Python environment with `openpyxl`:

```powershell
python scripts/prepare-legacy-import.py --source "docs/FUND MANAGEMENT TEMPLATE-2026.xlsx" --as-at 2026-10-03 --assume-income-received --output private-migration/2026
python scripts/test_prepare_legacy_import.py
```

The as-at date is a **summary cutoff**, not a transaction date. The prepared cutoff currently uses the date of preparation; confirm the workbook's actual cutoff before posting and regenerate if different. Balance-forward entries use the preceding December 31 based on the owner's 2025-to-2026 period clarification.

Outputs:

- `members.csv`: stable short legacy numbers, source names, blank unknown contact/identity details, and `SOURCE_BASELINE` membership dates.
- `ledger.csv`: separately labelled principal/income balance-forward and YTD summaries, unique references, and a control total.
- `reconciliation.json`: numeric totals, source hash, tiny rounding adjustments, and limitations; no member names.

Income values retain four decimals. Cached workbook totals differ from rounded individual rows by 0.0001 in BF income and 0.0002 in YTD income. The converter distributes only these representation residuals deterministically and rejects material discrepancies. It reads cached values, never evaluates formulas. Recalculate and save Excel locally if cached values are missing.

## Replace development fixtures safely

Use a **fresh local database**, preserving the existing development database for inspection. `DEV_SAMPLE_DATA=false` prevents new mock seeding; it does not remove already posted immutable entries. Do not mix the real migration with mock transactions or delete ledger history to make space.

When posting the reviewed migration:

1. Create a separate database owned by the application's database user. Configure the local backend's `DB_URL` for it and set `DEV_SAMPLE_DATA=false`.
2. Start the backend so migrations and the development administrator bootstrap run. Configure a separate authorized checker account.
3. In Ledger, choose **Set up historical year**, enter 2025 and a source reason, then have the independent checker approve. This creates an open fiscal period without posting balances. Repeat for any other missing historical year required by a reviewed source.
4. In Imports, dry-run `members.csv` as MEMBERS. Review baseline-date warnings, submit, and have the independent checker approve.
5. Dry-run `ledger.csv` as LEDGER after membership approval. Verify the component and closing totals, submit, and have the independent checker approve.
6. Check Overview, individual statements, and data exports. Collect missing contact and identity details through the member forms later.

A development administrator still cannot approve their own financial request. Imported joining dates are explicitly unconfirmed. Historical charts display a summary-history notice instead of inventing monthly growth. Statements identify migration summaries in their narrations. Unknown historical investment transactions and maturity receipts need a separate reviewed migration; these prepared files do not create investment cycles.

MailHog and live email delivery remain deferred at the owner's request.

## Correcting the assumed income later

Posted amounts are immutable. Use the import reversal workflow with an independent checker, then submit a corrected replacement file with new unique references. Reversal of a ledger batch compensates all its entries, so prepare and reconcile the full replacement before approving that reversal. If investment allocations or a closed fiscal period depend on the entries, resolve those dependencies and use the authorized historical correction process first. Keep membership imports intact unless the memberships themselves were wrong. Do not directly edit SQL amounts or overwrite posted history.
