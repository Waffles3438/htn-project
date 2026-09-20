import { useEffect, useMemo, useRef, useState } from 'react'
import { api, downloadBlob } from './lib/api'
import { partNames } from './lib/names'
import type { BoardMap, Health, Kit, Part, PartsPlan, Placement, PlanComponent } from './types/circuit'
import { Header } from './components/Header'
import { StatusBanner, ValidationPanel } from './components/Status'
import { SessionRow } from './components/SessionRow'
import { PromptPanel } from './components/PromptPanel'
import { CircuitScene } from './components/CircuitScene'
import { BomList } from './components/BomList'
import type { BomItem } from './components/BomList'
import { ConnectionsPanel } from './components/ConnectionsPanel'
import { FirmwarePanel } from './components/FirmwarePanel'
import { ExportPanel } from './components/ExportPanel'
import { StepsPanel } from './components/StepsPanel'

const DEMO_PROMPTS = {
  button_led: 'Turn on an LED when a button is pressed',
  led: 'Turn on an LED',
  arduino_led: 'Blink an external red LED every second using an Arduino Uno R3',
} as const

const EXAMPLES = [
  'Make an LED light up while I press a button',
  'Keep an LED turned on',
  'Blink an LED every second with an Arduino Uno',
]

interface StatusState {
  text: string
  error: boolean
}

const IDLE_STATUS: StatusState = { text: 'Describe a circuit, or load a ready-made one to see its layout.', error: false }

export default function App() {
  const [board, setBoard] = useState<BoardMap | null>(null)
  const [kit, setKit] = useState<Kit | null>(null)
  const [parts, setParts] = useState<Part[] | null>(null)
  const [health, setHealth] = useState<Health | null>(null)
  const [serverError, setServerError] = useState(false)
  const [current, setCurrent] = useState<Placement | null>(null)
  const [planParts, setPlanParts] = useState<PlanComponent[] | null>(null)
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [selectedRow, setSelectedRow] = useState<string | null>(null)
  const [view, setView] = useState<'circuit' | 'full'>('circuit')
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [prompt, setPrompt] = useState('')
  const [sessionId, setSessionId] = useState('demo-button-led')
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<StatusState>(IDLE_STATUS)
  const currentRef = useRef<Placement | null>(null)
  currentRef.current = current

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const [healthResult, loadedKit] = await Promise.all([api<Health>('/api/health'), api<Kit>('/api/kit')])
        const boardMap = await api<BoardMap>('/api/breadboards/' + loadedKit.breadboardModel)
        if (cancelled) return
        const withUno = [...loadedKit.availableParts]
        if (!withUno.some((p) => p.type === 'arduino_uno')) withUno.push({ type: 'arduino_uno', value: 'Uno R3', quantity: 0 })
        setHealth(healthResult)
        setKit(loadedKit)
        setParts(withUno)
        setBoard(boardMap)
      } catch (error) {
        if (cancelled) return
        setServerError(true)
        setStatus({ text: 'Could not reach the server: ' + (error as Error).message, error: true })
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  // Small hook for tests/browser_check.py: swap the rendered placement without touching state flow.
  // Partial placements merge over the current one so scene-only tests (e.g. the rail crop) work.
  useEffect(() => {
    ;(window as unknown as Record<string, unknown>).__circuit = {
      board,
      show: (partial: Partial<Placement>) =>
        setCurrent({
          version: 3,
          sessionId: '',
          breadboardModel: '',
          title: '',
          prompt: '',
          source: 'fixture',
          breadboard: { model: '', holeMapVersion: '', physicalVerified: false },
          requiredParts: [],
          components: [],
          jumperWires: [],
          nets: [],
          instructions: [],
          ...(currentRef.current ?? {}),
          ...partial,
        } as Placement),
      restore: () => setCurrent(currentRef.current ? { ...currentRef.current } : null),
    }
  }, [board])

  function buildRequest(options?: { promptOverride?: string; partsOverride?: Part[] | null }) {
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(sessionId)) {
      throw new Error('Session ID must contain 1–64 letters, numbers, underscores or hyphens.')
    }
    const text = (options?.promptOverride ?? prompt).trim()
    if (text.length < 3) throw new Error('Describe the circuit first.')
    const requestParts = options?.partsOverride !== undefined ? options.partsOverride : parts
    if (!kit || !requestParts) throw new Error('The parts kit is still loading; try again in a moment.')
    return {
      prompt: text,
      sessionId,
      breadboardModel: kit.breadboardModel,
      availableParts: requestParts.map((p) => ({ ...p, quantity: Number(p.quantity) })),
    }
  }

  function renderPlacement(placement: Placement) {
    if (!board) throw new Error('Board map is still loading.')
    if (placement.version !== 3) {
      throw new Error('This saved circuit uses an older coordinate-based placement format. Generate it again to update it.')
    }
    if (placement.breadboard.holeMapVersion !== board.holeMapVersion) {
      throw new Error('This saved circuit uses an older board map. Generate it again before using the current board in XR.')
    }
    setCurrent(placement)
    setSelectedIds([])
    setSelectedRow(null)
    setPlanParts(null)
    const connections = placement.jumperWires.length
    setStatus({
      text: `✓ ${placement.source === 'fixture' ? 'Ready-made circuit loaded' : 'Circuit generated'}\n${placement.title} · ${connections} connections · circuit checks passed.`,
      error: false,
    })
  }

  async function run(path: string, options: { partsOnly?: boolean; promptOverride?: string; partsOverride?: Part[] | null } = {}) {
    if (busy) return
    try {
      const body = buildRequest(options)
      setBusy(true)
      setStatus({
        text: options.partsOnly ? 'Identifying the required components…' : 'Identifying parts, assigning holes, and checking the circuit…',
        error: false,
      })
      const result = await api<unknown>(path, body)
      if (options.partsOnly) {
        const plan = result as PartsPlan
        setCurrent(null)
        setSelectedIds([])
        setSelectedRow(null)
        setPlanParts(plan.components)
        setStatus({ text: plan.explanation + ' Generate the circuit to assign holes.', error: false })
      } else {
        const placement = result as Placement
        if (placement.source === 'fixture') setPrompt(placement.prompt)
        renderPlacement(placement)
      }
    } catch (error) {
      setStatus({ text: (error as Error).message + (current ? '\nThe layout shown is your previous circuit.' : ''), error: true })
    } finally {
      setBusy(false)
    }
  }

  const onGenerate = () => void run('/api/circuits/generate')
  const onAnalyze = () => void run('/api/circuits/analyze', { partsOnly: true })

  const onDemo = (name: 'button_led' | 'led' | 'arduino_led') => {
    let nextParts = parts
    if (name === 'arduino_led' && parts) {
      const index = parts.findIndex((p) => p.type === 'arduino_uno')
      if (index >= 0 && Number(parts[index].quantity) < 1) {
        nextParts = parts.map((p, i) => (i === index ? { ...p, quantity: 1 } : p))
        setParts(nextParts)
      }
    }
    const promptOverride = prompt.trim().length >= 3 ? undefined : DEMO_PROMPTS[name]
    void run(`/api/circuits/demo/${name}`, { promptOverride, partsOverride: nextParts })
  }

  const onLatest = async () => {
    if (busy) return
    try {
      setBusy(true)
      if (!/^[A-Za-z0-9_-]{1,64}$/.test(sessionId)) throw new Error('Enter a valid session ID.')
      renderPlacement(await api<Placement>(`/api/sessions/${encodeURIComponent(sessionId)}/placement`))
    } catch (error) {
      setStatus({ text: (error as Error).message, error: true })
    } finally {
      setBusy(false)
    }
  }

  const onQuantity = (index: number, value: number) => {
    if (parts) setParts(parts.map((p, i) => (i === index ? { ...p, quantity: value } : p)))
  }

  const onDownload = () => {
    if (current) downloadBlob(JSON.stringify(current, null, 2), 'placement.json', 'application/json')
  }

  const onDownloadSketch = () => {
    if (current?.firmware) downloadBlob(current.firmware.code, 'circuit.ino', 'text/plain')
  }

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(JSON.stringify(current, null, 2))
      setStatus({ text: 'Placement JSON copied.', error: false })
    } catch {
      setStatus({ text: 'Clipboard is unavailable here. Download placement.json instead.', error: true })
    }
  }

  const focusRow = (ids: string[], row: string) => {
    setSelectedIds(ids)
    setSelectedRow(row)
  }
  const focusItems = (ids: string[]) => {
    setSelectedIds(ids)
    setSelectedRow(null)
  }

  const bomItems = useMemo<BomItem[]>(() => {
    if (current) {
      const idsForPart = (type: string): string[] => {
        if (type === 'jumper_wire') return current.jumperWires.map((w) => w.id)
        if (type === 'arduino_uno') {
          const deviceIds = new Set((current.externalDevices ?? []).map((d) => d.id))
          return current.jumperWires
            .filter((w) => [w.from, w.to].some((endpoint) => deviceIds.has(endpoint.split(':')[0] ?? '')))
            .map((w) => w.id)
        }
        return current.components.filter((c) => c.type === type).map((c) => c.id)
      }
      return current.requiredParts.map((p) => ({
        label: partNames[p.type] ?? p.type,
        value: p.value,
        quantity: p.quantity,
        ids: idsForPart(p.type),
      }))
    }
    if (planParts) {
      return planParts.map((p) => ({
        label: partNames[p.type] ?? p.type,
        value: p.value,
        quantity: p.quantity ?? 1,
        ids: [],
      }))
    }
    return []
  }, [current, planParts])

  const connection = health
    ? health.liveConfigured
      ? '● Generation ready'
      : '● Ready-made circuits only'
    : serverError
      ? '● Server unavailable'
      : 'Connecting…'
  const sourceLabel = current
    ? current.source === 'fixture'
      ? 'Ready-made circuit'
      : 'Generated circuit'
    : planParts
      ? 'Parts identified'
      : 'No circuit yet'
  const ready = Boolean(kit)

  return (
    <>
      <Header connection={connection} />
      <main>
        {current || planParts ? (
          <div className="workspace">
            <PromptPanel
              prompt={prompt}
              onPromptChange={setPrompt}
              busy={busy}
              ready={ready}
              parts={parts}
              boardModel={kit?.breadboardModel ?? null}
              advancedOpen={advancedOpen}
              onAdvancedToggle={setAdvancedOpen}
              onQuantity={onQuantity}
              onGenerate={onGenerate}
              onAnalyze={onAnalyze}
              onDemo={onDemo}
            />
            <section className="panel result" aria-labelledby="result-title">
              <div className="panel-heading">
                <span className="step">2</span>
                <h2 id="result-title">Layout</h2>
                <span id="source" className="pill">
                  {sourceLabel}
                </span>
              </div>
              <StatusBanner text={status.text} error={status.error} />
              {current && <ValidationPanel placement={current} />}
              {board && (
                <>
                  <SessionRow sessionId={sessionId} onSessionChange={setSessionId} onLatest={() => void onLatest()} latestDisabled={busy} />
                  <CircuitScene board={board} placement={current} selectedIds={selectedIds} view={view} onViewChange={setView} />
                </>
              )}
              <BomList items={bomItems} selectedIds={selectedIds} onFocus={focusItems} />
              {current && (
                <>
                  <ConnectionsPanel
                    placement={current}
                    selectedRow={selectedRow}
                    onFocus={focusRow}
                    onClear={() => focusItems([])}
                  />
                  <FirmwarePanel firmware={current.firmware} onDownload={onDownloadSketch} />
                  <StepsPanel placement={current} selectedIds={selectedIds} onFocus={focusItems} />
                </>
              )}
              <ExportPanel current={current} busy={busy} onDownload={onDownload} onCopy={() => void onCopy()} />
              <p className="footnote">
                Before powering on, check polarity, pin spacing, and rail continuity on your board. This layout is a wiring guide,
                not a physical fit certification.
              </p>
            </section>
          </div>
        ) : (
          <section className="hero">
            <h1>Build a breadboard circuit</h1>
            <p>Describe what you want to make — the parts, wiring, and build steps follow.</p>
            <PromptPanel
              prompt={prompt}
              onPromptChange={setPrompt}
              busy={busy}
              ready={ready}
              parts={parts}
              boardModel={kit?.breadboardModel ?? null}
              advancedOpen={advancedOpen}
              onAdvancedToggle={setAdvancedOpen}
              onQuantity={onQuantity}
              onGenerate={onGenerate}
              onAnalyze={onAnalyze}
              onDemo={onDemo}
            />
            <div className="examples">
              <span>Try:</span>
              {EXAMPLES.map((example) => (
                <button key={example} className="example" onClick={() => setPrompt(example)}>
                  {example}
                </button>
              ))}
            </div>
            <SessionRow sessionId={sessionId} onSessionChange={setSessionId} onLatest={() => void onLatest()} latestDisabled={busy} />
          </section>
        )}
        <footer>
          <span>Circuit Lab</span>
          <span>2.54 mm grid · Board-local coordinates in meters</span>
        </footer>
      </main>
    </>
  )
}
