"""Local-only workbook conversion. Never prints member values or evaluates formulas.

Run with the bundled Python runtime (openpyxl). Output is private import staging,
not a database seed: use the application's dry-run and independent approval.
"""
import argparse
import csv
from datetime import date
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path
import sys
import openpyxl

MEMBERS = ['member_number', 'full_name', 'email', 'phone', 'id_type', 'id_number',
           'date_of_birth', 'address', 'next_of_kin_name', 'next_of_kin_phone',
           'next_of_kin_relationship', 'join_date', 'join_date_basis']
LEDGER = ['member_number', 'type', 'component', 'amount', 'effective_date', 'reference', 'narration']


class ValidationError(Exception):
    """Only fixed, non-identifying validation messages may reach the console."""


def amount(value):
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        raise ValidationError('Missing or nonnumeric cached balance. Recalculate and save Excel locally.')
    value = Decimal(str(value))
    if not value.is_finite():
        raise ValidationError('A balance is not finite.')
    return value


def reconcile(values, target):
    """Keep four places; reconcile only sub-cent representation residuals."""
    unit = Decimal('0.0001')
    if abs(sum(values) - target) > Decimal('0.005'):
        raise ValidationError('Source component totals do not reconcile. No import files were written.')
    rounded = [value.quantize(unit, rounding=ROUND_HALF_UP) for value in values]
    steps = int((target - sum(rounded)) / unit)
    if abs(steps) > len(values):
        raise ValidationError('Rounding residual exceeds the conversion limit.')
    order = sorted(range(len(values)), key=lambda i: values[i] - rounded[i], reverse=steps > 0)
    for i in order[:abs(steps)]:
        rounded[i] += unit if steps > 0 else -unit
    return rounded, str(target - sum(v.quantize(unit, rounding=ROUND_HALF_UP) for v in values))


def prepare(source, output, as_at, income_assumed_received=False):
    # Cached balances are read locally. Formula text and names never leave this process.
    workbook = openpyxl.load_workbook(source, data_only=True, read_only=False)
    candidates = [sheet for sheet in workbook.worksheets if
                  [str(sheet.cell(3, c).value or '').strip().upper() for c in [4, 7, 10]] == ['PRINCIPAL'] * 3
                  and str(sheet.cell(3, 3).value or '').strip().upper() == 'NAME']
    if len(candidates) != 1:
        raise ValidationError('Expected one dashboard worksheet matching the current template.')
    sheet = candidates[0]
    total_rows = [r for r in range(4, sheet.max_row + 1) if
                  str(sheet.cell(r, 3).value or '').strip().upper() == 'TOTAL']
    if len(total_rows) != 1:
        raise ValidationError('Expected one dashboard TOTAL row.')
    end = total_rows[0]
    records, numbers, names = [], set(), set()
    for r in range(4, end):
        name = str(sheet.cell(r, 3).value or '').strip()
        values = [amount(sheet.cell(r, c).value or 0) for c in range(4, 13)]
        if not name:
            if any(values):
                raise ValidationError(f'Dashboard row {r} has balances without a member name.')
            continue
        number = sheet.cell(r, 2).value
        if not isinstance(number, (int, float)) or int(number) != number or number <= 0 or number in numbers:
            raise ValidationError(f'Dashboard row {r} needs a unique positive source number.')
        normalized = ' '.join(name.casefold().split())
        if normalized in names:
            raise ValidationError(f'Dashboard row {r} repeats a member name. Resolve the source locally.')
        numbers.add(number); names.add(normalized)
        # Check each source roll-forward without importing its derived totals or ratios.
        for expected, actual in [(values[0]+values[1], values[2]), (values[3]+values[4], values[5]),
                                 (values[0]+values[3], values[6]), (values[1]+values[4], values[7]),
                                 (values[6]+values[7], values[8])]:
            if abs(expected-actual) > Decimal('0.005'):
                raise ValidationError(f'Dashboard row {r} does not reconcile. No import files were written.')
        if values[0] < 0 or values[3] < 0:
            raise ValidationError(f'Dashboard row {r} has negative principal requiring manual review.')
        records.append({'number': f'LCU-{int(number):06d}', 'name': name, 'values': values})
    if not records or len(records) > 250:
        raise ValidationError('Expected 1–250 named dashboard members.')
    totals = [amount(sheet.cell(end, c).value).quantize(Decimal('.0001'), rounding=ROUND_HALF_UP) for c in [4,5,7,8]]
    columns, residuals = [], []
    for index, column in enumerate([0,1,3,4]):
        values, residual = reconcile([r['values'][column] for r in records], totals[index])
        columns.append(values); residuals.append(residual)
    expected_equity = sum(totals)
    if abs(expected_equity - amount(sheet.cell(end, 12).value)) > Decimal('.005'):
        raise ValidationError('Dashboard closing equity differs from its component totals.')
    baseline = date(as_at.year-1, 12, 31)
    entries = []
    for i, record in enumerate(records):
        for j, (kind, component, effective, suffix) in enumerate([
            ('OPENING_BALANCE','PRINCIPAL',baseline,'BF-P'), ('OPENING_BALANCE','INCOME',baseline,'BF-I'),
            ('ADDITION','PRINCIPAL',as_at,'YTD-P'), ('OPENING_BALANCE','INCOME',as_at,'YTD-I')]):
            value = columns[j][i]
            if not value:
                continue
            if j == 2 and value != value.quantize(Decimal('.01')):
                raise ValidationError('YTD principal contains sub-cent values requiring manual review.')
            entries.append([record['number'],kind,component,format(value,'f'),effective.isoformat(),
                            f'LEGACY-{as_at.year}-{record["number"]}-{suffix}',
                            f'Legacy {"balance forward" if j<2 else "YTD summary"}; source period {as_at.year-1 if j<2 else as_at.year}; transaction dates unavailable.'])
    output.mkdir(parents=True, exist_ok=True)
    for filename, header, rows in [
        ('members.csv', MEMBERS, [[r['number'],r['name'],*(['']*9),baseline.isoformat(),'SOURCE_BASELINE'] for r in records]),
        ('ledger.csv', LEDGER, entries + [['TOTAL','','',format(expected_equity,'f'),'','','']])]:
        with (output/filename).open('w',encoding='utf-8',newline='') as stream:
            writer=csv.writer(stream); writer.writerow(header); writer.writerows(rows)
    report = {'source_sha256':hashlib.sha256(Path(source).read_bytes()).hexdigest(), 'fiscal_year':as_at.year,
              'summary_cutoff':as_at.isoformat(), 'income_assumed_received':income_assumed_received, 'members':len(records), 'ledger_entries':len(entries),
              'bf_principal':str(totals[0]), 'bf_income':str(totals[1]), 'ytd_principal':str(totals[2]),
              'ytd_income':str(totals[3]), 'closing_equity':str(expected_equity),
              'four_decimal_rounding_residuals':residuals,
              'limitations':['Owner instructed that historical income be treated as received; original maturity and receipt evidence is unavailable.' if income_assumed_received else 'Income must be confirmed as earned before approval.',
                             'No monthly transaction history can be inferred.',
                             'Membership dates are source baselines, not confirmed joining dates.',
                             'Investment worksheets are not posted; doing so would double-count balances.']}
    (output/'reconciliation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    workbook.close()
    return report


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=Path('private-migration/2026'))
    parser.add_argument('--as-at',type=date.fromisoformat,required=True)
    parser.add_argument('--assume-income-received',action='store_true',help='Record the owner-approved assumption; does not post balances.')
    args=parser.parse_args()
    try:
        if args.as_at > date.today():
            raise ValidationError('Summary cutoff cannot be in the future.')
        print(json.dumps(prepare(args.source,args.output,args.as_at,args.assume_income_received)))
    except Exception as error:
        # Library exception messages may contain source values: do not expose them.
        print(str(error) if isinstance(error,ValidationError) else 'Local workbook conversion failed; source contents were not logged.',file=sys.stderr)
        sys.exit(1)
