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
