// Mirror of the placement JSON the Python API returns and Unity/XR consumes.
// Keep field names identical to schemas/placement.schema.json.

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

export interface Terminal {
  id: string
  holeId: string
  position: Vec3
}

export interface CircuitComponent {
  id: string
  type: string
  value: string
  assetId: string
  terminals: Terminal[]
  position: Vec3
  rotation?: { x: number; y: number; z: number; w: number }
  buildStep: number
}

export interface JumperWire {
  id: string
  fromHole: string
  toHole: string
  color: string
  buildStep: number
  startPosition: Vec3
  endPosition: Vec3
}

export interface ExternalDevice {
  id: string
  type: string
  model: string
  assetId: string
  placementMode: string
}

export interface ExternalConnection {
  id: string
  deviceId: string
  pin: string
  holeId: string
  boardPosition: Vec3
  color: string
  buildStep: number
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
  version: number
  sessionId: string
  breadboardModel: string
  title: string
  prompt: string
  source: string
  breadboard: { model: string; holeMapVersion: string; physicalVerified: boolean }
  requiredParts: Part[]
  components: CircuitComponent[]
  jumperWires: JumperWire[]
  validation?: { valid: boolean; checks: string[]; warnings: string[] }
  instructions: Instruction[]
  externalDevices?: ExternalDevice[]
  externalConnections?: ExternalConnection[]
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
