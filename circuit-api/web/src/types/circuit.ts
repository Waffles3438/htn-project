// Semantic circuit interchange — the canonical source of truth shared with Unity/XR.
// Addresses and mounts describe what connects where; every renderer derives its own
// geometry from the board map and component definitions. No XYZ coordinates here.
export interface Vec3 {
  x: number
  y: number
  z: number
}

export interface Hole {
  id: string
  net: string
  position: Vec3
}

export interface BoardMap {
  id: string
  model: string
  holeMapVersion: string
  physicalVerified: boolean
  holes: Hole[]
}

export interface Part {
  type: string
  value: string
  quantity: number
}

// "BB1:E5" (board hole) or "led_1:anode" (component terminal).
export type Endpoint = string

export interface BreadboardMount {
  type: 'breadboard'
  board: string
  terminals: Record<string, Endpoint>
  // Reserved for footprint-based mounting (breadboard-compatible controllers):
  // anchor + orientation determine the remaining terminal addresses from the definition.
  anchor?: Endpoint
  orientation?: 'north' | 'south' | 'east' | 'west'
}

export interface ExternalMount {
  type: 'external'
  relativeTo: string
  side: 'left' | 'right' | 'top' | 'bottom'
}

export type Mount = BreadboardMount | ExternalMount

export interface CircuitComponent {
  id: string
  type: string
  value: string
  assetId: string
  mount: Mount
  buildStep: number
}

export interface JumperWire {
  id: string
  from: Endpoint
  to: Endpoint
  color: string
  buildStep: number
}

export interface CircuitNet {
  id: string
  members: Endpoint[]
}

export interface ExternalDevice {
  id: string
  type: string
  model: string
  assetId: string
  mount: ExternalMount
}

export interface Firmware {
  filename: string
  board: string
  code: string
  uploadInstructions: string
}

export interface Instruction {
  step: number
  componentIds: string[]
  text: string
}

export interface Placement {
  version: 3
  sessionId: string
  breadboardModel: string
  title: string
  prompt: string
  source: string
  breadboard: { model: string; holeMapVersion: string; physicalVerified: boolean }
  requiredParts: Part[]
  components: CircuitComponent[]
  jumperWires: JumperWire[]
  nets: CircuitNet[]
  validation?: { valid: boolean; checks: string[]; warnings: string[] }
  instructions: Instruction[]
  externalDevices?: ExternalDevice[]
  firmware?: Firmware
}

export interface PlanComponent {
  id?: string
  type: string
  value: string
  purpose?: string
  quantity?: number
}

export interface PartsPlan {
  behavior?: string
  components: PlanComponent[]
  explanation: string
}

export interface Kit {
  breadboardModel: string
  availableParts: Part[]
}

export interface Health {
  ok: boolean
  provider: string
  model: string
  liveConfigured: boolean
  breadboardModel: string
}
