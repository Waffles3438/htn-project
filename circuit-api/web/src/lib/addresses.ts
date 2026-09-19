// Semantic board addresses ↔ board-map hole ids.
//   BB1:A15               strip hole A15
//   BB1:RAIL:L:+:A:12     rail hole L+A12 (rail L, polarity +, segment A, hole index 12)
// Component endpoints ("led_1:anode") are handled by endpointParts.

const STRIP = /^BB1:([A-J])([1-9][0-9]*)$/
const RAIL = /^BB1:RAIL:(L|R):(\+|-):(A|B):([1-9][0-9]*)$/

export function isBoardAddress(endpoint: string): boolean {
  return endpoint.startsWith('BB1:')
}

export function addressToHole(address: string): string | null {
  const strip = STRIP.exec(address)
  if (strip) return strip[1] + strip[2]
  const rail = RAIL.exec(address)
  if (rail && Number(rail[5]) <= 25) return rail[1] + rail[2] + rail[3] + rail[4]
  return null
}

export function endpointParts(endpoint: string): [string, string] | null {
  if (isBoardAddress(endpoint)) return null
  const index = endpoint.indexOf(':')
  return index > 0 && index < endpoint.length - 1 ? [endpoint.slice(0, index), endpoint.slice(index + 1)] : null
}
