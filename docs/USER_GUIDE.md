# User guide

The examples below use an isolated synthetic operator fixture. No client workbook/member details appear in the screenshots. Actual menus and controls follow each account's current permissions.

## Treasurer

Use Financial operations → Ledger to record a contribution, Investments to preview treasury-bill allocations, and Reconciliation to compare holdings/cash. A money movement is pending until independent approval. Manual receipts are verified in Payments against the actual evidence/amount; the verifier cannot supply their own independent approval. Never use the labelled development payment simulator for real money.

![Ledger with empty-state and contribution controls](screenshots/ledger.png)

## Approver

Open Financial operations → Approvals, review the exact proposed amount, source and consequences, then approve or reject. The maker cannot approve their own request; rejection needs a reason. Role and import approvals appear in their respective authorized screens. Recheck stale-source warnings instead of repeatedly retrying.

![Independent approval inbox](screenshots/approvals.png)

## President and vice president

Use Overview/Reports for as-at balances and oversight. Permissions determine whether approval controls are available. Authorized period unlocks require a reason and independent approval; review corrected snapshots before reclosing a year. Current production leadership roles differ from the developer's all-permission override.

![Financial report downloads](screenshots/reports.png)

## Administrator

Use Administration → Accounts and roles for email invitations and reviewed role assignments. An invitation can start a prospective membership; do not invent a member record merely to send an invitation. Recipients choose their own password and protected method. Initial leadership roles cannot expire automatically, and linked leadership members cannot be deactivated until their guarded role removal is approved. Settings contains union policies and separate personal security cards. Audit access follows AUDIT_READ; request addresses are restricted investigation metadata.

![Accounts and invitation controls](screenshots/accounts.png)

## Member

My membership shows your balances, contact details, statement downloads and transactions. Payments provides the union's published bank/wallet instructions. Generate a transfer reference, include it in your transfer, then submit your receipt; your balance changes after staff verification and independent approval. Security methods and recovery codes are managed in Settings. Monthly charts are suppressed where imported summaries cannot establish monthly history.

![Member balances, details and statement sections](screenshots/portal.png)

![Bank details and receipt-submission controls](screenshots/payments.png)

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

## Historical period correction

An authorized PRESIDENT can request a closed year's unlock from Ledger, with a reason. A different eligible approver must review it; the maker cannot approve it. Rejection or cancellation leaves the period closed. Unlock preserves earlier snapshots and recomputes that year's balance forward and all later recorded snapshots. Submit the reviewed correction through Imports, obtain independent approval, verify the updated balances, then request year-end close again.

An import date that would change an existing or archived investment cycle's ratios is blocked. Explicit investment correction/repost and historical investment/allocation imports remain pending; do not alter dates to bypass the warning.

Overview's Fiscal year selector changes the shared as-at date. Balance-forward verification identifies missing or mismatched prior-year snapshots and open correction periods. The Ledger balance-forward panel shows each member's snapshot, revision and ledger comparison. Overview figures always derive from the immutable ledger.

## Download your data

In My membership, use Download my data for a JSON file containing your profile, financial records, own allocation revisions, snapshot history and account metadata. Decimal amounts remain exact strings. It contains personal information; keep it securely. Receipt metadata is included; download the actual files separately from Payments. Passwords, authenticator secrets and session tokens are excluded.

Backdating impact preview and both cycle-handling options, historical investment/allocation imports, full recovery administration and production delivery gates remain incomplete. See PROGRESS and CHECKLIST.

## Account security and lost devices

Open Settings to manage your own security methods. Passkeys lists registration/last-use dates and whether your provider reports a backup. Add a spare passkey before relying on a single device.

To remove a lost passkey, change your password or replace recovery codes, confirm your current password and either a fresh authenticator/recovery code or a verified passkey. Removing a passkey or changing the password signs out every device. You cannot remove your last protected sign-in method. If the last passkey was your preference and an authenticator remains, the preference changes to authenticator.

If your authenticator is lost, sign in using your password, choose **Use a recovery code**, and enter one of your saved codes. Each code works once. If you can still use a passkey, that also provides an independent sign-in route. In Settings, **Replace recovery codes** invalidates every old code; save the new codes securely before closing the panel. They are not saved in browser storage or shown again.

Recovery after losing every protected method and every recovery code remains an administrative recovery requirement; do not treat a password alone as sufficient proof for removing security methods.
