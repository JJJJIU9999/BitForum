// API timestamps carry Z/offset. Legacy unqualified timestamps are UTC.
const shanghai = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

export function displayTime(value) {
  if (typeof value !== 'string') return '时间未知'
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})?$/.exec(value)
  if (!match) return '时间未知'
  const [, y, mo, d, h, mi, s = '00', fraction = '', zone = 'Z'] = match
  const day = new Date(Date.UTC(Number(y), Number(mo) - 1, Number(d)))
  if (day.getUTCFullYear() !== Number(y) || day.getUTCMonth() + 1 !== Number(mo)
      || day.getUTCDate() !== Number(d) || Number(h) > 23 || Number(mi) > 59 || Number(s) > 59) return '时间未知'
  if (zone !== 'Z' && (Number(zone.slice(1, 3)) > 23 || Number(zone.slice(4)) > 59)) return '时间未知'
  const instant = new Date(`${y}-${mo}-${d}T${h}:${mi}:${s}${fraction.slice(0, 4)}${zone}`)
  if (Number.isNaN(instant.getTime())) return '时间未知'
  const parts = Object.fromEntries(shanghai.formatToParts(instant).map(({ type, value }) => [type, value]))
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}
