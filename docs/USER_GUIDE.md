# User guide

## Membership and sign-in

Open the invitation link, set a strong password, then sign in and choose TOTP, a passkey, or configured Google sign-in. TOTP includes a QR code and manual key; keep recovery codes privately. Google remains gated until credentials are configured.

My membership shows only the account's linked member: principal, income, equity, fiscal-year figures, monthly history, allocations and posted transactions. Select an as-at date and download a ranged statement. Contact email, phone and next-of-kin details can be edited; contact email is separate from sign-in email.

## Contributions

Payments offers secure hosted checkout when configured. A browser return does not confirm payment: posting follows signed webhook and server verification. Pending payments can be checked or resumed. The development simulator is labelled and collects no real funds.

For manual transfers, choose published instructions, enter amount/purpose, and create a reference before sending funds. Include it in transfer narration. Enter the actual paid date, attach JPEG/PNG/plain PDF proof up to 10 MB, then submit. A treasurer verifies the evidence and actual amount. A different approver must approve before principal is credited. Original submitted values remain traceable. Confirmation PDF is available after posting.

## Staff workflows

Members supports generated unique numbers, search, pagination, versioned edits and status changes. Accounts and roles supports invitations and reviewed role changes. Replacing an invitation invalidates the old link. Recipients choose their password; no default password is shipped.

Ledger supports current-day contributions, exact compensating reversals and prior-year close. Requests affect balances only after the required distinct approvals. Makers cannot approve their own requests. Rejection requires a reason; makers can cancel pending requests.

Investments supports exact allocation previews, reasoned participation edits, posting, actual maturity gains/losses, rollover and settlement. Approved current-day reversal retains revision history and exact income compensation. Returned drafts require fresh posting approval; active rollover dependencies must be resolved first.

Reconciliation compares current ledger equity/open investment cost with actual holdings/cash. Notes and differences are retained in an immutable snapshot. A separate approver finalizes it; changed sources require a fresh review. Bank settlement matching remains pending.

Reports exports dashboard, allocation, ledger, public member directory and authorized statements to Excel/PDF. Private downloads and report generation are audited.

Audit log is available with AUDIT_READ permission. Choose a date range and optional exact action, entity type, actor ID or record ID, then apply filters. CSV downloads use the applied filters across all pages, with a 5,000-event limit; narrow the filters for larger results. The screen and export contain event metadata, excluding sensitive change payloads. System events have no actor account.

## Settings, errors and development

Organization policies share one Settings block, including union details, payment limits, approvals, fee policy and rollover. Personal security settings retain their own cards. Required approvals apply to new requests; pending requests retain their original count. Online payments retain their starting fee policy. Vinette by Vinyl links to the creator's GitHub profile.

Read toast details before retrying. Stale edits require reload; money is never credited optimistically. Offline mode requests reconnection and stores no account data or receipts. DEMO memberships and simulator postings are fictitious. The local dev account's permission override still preserves maker-checker separation.

## Imports

Open Imports with import permission, download the appropriate CSV template and fill its columns in order. Keep member numbers as text, dates as YYYY-MM-DD and amounts as plain decimals. Excel users can save a single worksheet as XLSX, replacing formulas with values. Limits are 5 MB and 1,000 data rows. Member numbers can be blank for generation at approval; private contact/identity fields are encrypted in staging.

Ledger templates accept OPENING_BALANCE (PRINCIPAL or INCOME) and SEED/ADDITION (PRINCIPAL). Opening amounts support four decimal places; contributions support two. Only opening income may be negative. References must be unique. An optional final TOTAL row has TOTAL in member_number and the source total in amount. All other totals-row fields should be blank.

Run the dry run, resolve every row error and compare the calculated total with your source. Enter the source/reason, then confirm the exact checked file. No memberships or entries are created until independent approval. Review batch shows the submitted public impact; private contact/identity values are excluded. Makers can cancel pending requests. Reversal requires a reason and independent approval: ledger entries are exactly compensated today, while unused imported members are deactivated. Accounts or financial dependencies block member-batch reversal.

Historical corrections/unlock, investment/allocation imports, full recovery administration and production delivery gates remain incomplete. See PROGRESS and CHECKLIST.
