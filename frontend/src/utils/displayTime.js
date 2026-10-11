export function displayTime(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/.exec(value || '')
  if (!match) return '时间未知'
  const [, year, month, day, hour, minute] = match
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)))
  if (date.getUTCFullYear() !== Number(year) || date.getUTCMonth() + 1 !== Number(month)
      || date.getUTCDate() !== Number(day) || Number(hour) > 23 || Number(minute) > 59) return '时间未知'
  return `${year}-${month}-${day} ${hour}:${minute}`
}
