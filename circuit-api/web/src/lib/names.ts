import { endpointParts } from './addresses'

export const partNames: Record<string, string> = {
  led: 'Red LED',
  resistor: 'Resistor',
  button: 'Pushbutton',
  power_supply: '5 V supply',
  arduino_uno: 'Arduino Uno R3',
  jumper_wire: 'Jumper wires',
}

// Schematic body/wire colors; not hardware color measurements.
export const schematicColors: Record<string, string> = {
  led: '#c7645a',
  resistor: '#b6953c',
  button: '#6497a1',
  power_supply: '#9a6859',
  red: '#c96559',
  black: '#52645c',
  yellow: '#bfa242',
  blue: '#6497a1',
  green: '#6d9450',
}

export const terminalOrder: Record<string, string[]> = {
  power_supply: ['positive', 'negative'],
  led: ['anode', 'cathode'],
  resistor: ['a', 'b'],
  button: ['a1', 'a2', 'b1', 'b2'],
}

export const pinNames: Record<string, string> = {
  positive: '+5V (+)',
  negative: 'GND (−)',
  anode: 'Anode (+, long leg)',
  cathode: 'Cathode (−, short leg)',
  a: 'Lead A',
  b: 'Lead B',
  a1: 'A1 · paired with A2',
  a2: 'A2 · paired with A1',
  b1: 'B1 · paired with B2',
  b2: 'B2 · paired with B1',
}

const pinShortNames: Record<string, string> = {
  positive: '+5V',
  negative: 'GND',
  anode: 'anode (+)',
  cathode: 'cathode (−)',
  a: 'lead A',
  b: 'lead B',
  a1: 'A1',
  a2: 'A2',
  b1: 'B1',
  b2: 'B2',
}

export function pinShort(id: string): string {
  return pinShortNames[id] ?? id
}

// Human label for a semantic endpoint: board holes render as their hole id,
// controller pins as "Uno D13", mounted terminals as "Red LED anode (+)".
export function endpointLabel(endpoint: string, placement: { components: { id: string; type: string }[]; externalDevices?: { id: string }[] } | null): string {
  if (endpoint.startsWith('BB1:')) return endpoint.slice(4)
  const parts = endpointParts(endpoint)
  if (!parts) return endpoint
  const [componentId, terminal] = parts
  if (placement?.externalDevices?.some((d) => d.id === componentId)) return `Uno ${terminal}`
  const component = placement?.components.find((c) => c.id === componentId)
  if (component) return `${partNames[component.type] ?? component.type} ${pinShort(terminal)}`
  return endpoint
}
