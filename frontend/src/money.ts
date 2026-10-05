import Decimal from 'decimal.js'

let currencyDisplay = "SYMBOL";
export function setCurrencyDisplay(display: string) { currencyDisplay = display === "CODE" ? "CODE" : "SYMBOL"; }

export function formatMoney(value: string | Decimal): string {
  const amount = new Decimal(value)
  const absolute = amount.abs().toDecimalPlaces(2, Decimal.ROUND_HALF_UP)
  const parts = absolute.toFixed(2).split('.')
  const grouped = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return `${amount.isNegative() ? '−' : ''}${currencyDisplay === "CODE" ? "GHS" : "GH₵"} ${grouped}.${parts[1]}`
}

