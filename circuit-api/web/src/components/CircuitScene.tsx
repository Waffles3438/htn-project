import { useEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'
import type { BoardMap, CircuitComponent, Placement } from '../types/circuit'
import { endpointLabel, partNames, pinNames, schematicColors, terminalOrder } from '../lib/names'
import { addressToHole, endpointParts } from '../lib/addresses'

const PITCH = 0.00254
const SCALE = 7500
const MARGIN = 40

// Display-only Uno size (53.4 × 68.6 mm at canvas scale). The placement carries a
// semantic external mount (relativeTo + side); the canvas derives a stable spot from
// it and never touches hole geometry. Unity derives its own pose the same way.
const UNO_W = 0.0534 * SCALE
const UNO_H = 0.0686 * SCALE
const UNO_GAP = 56
const PIN_ANCHOR: Record<string, number> = { D13: 0.2, GND: 0.3, '5V': 0.4, '3V3': 0.1 }

interface SceneProps {
  board: BoardMap
  placement: Placement | null
  selectedIds: string[]
  view: 'circuit' | 'full'
  onViewChange: (view: 'circuit' | 'full') => void
}

interface Callout {
  key: string
  x: number
  y: number
  labelY: number
  label: string
  detail: string
}

interface Point {
  x: number
  y: number
  pin?: string
}

function mountedHoles(component: CircuitComponent): { terminal: string; hole: string }[] {
  const mount = component.mount
  if (mount.type !== 'breadboard') return []
  return terminalOrder[component.type]
    .map((terminal) => ({ terminal, hole: addressToHole(mount.terminals[terminal] ?? '') ?? '' }))
    .filter((entry) => entry.hole !== '')
}

export function CircuitScene({ board, placement, selectedIds, view, onViewChange }: SceneProps) {
  const [zoom, setZoom] = useState(1)
  const [pan, setPan] = useState({ x: 0, y: 0 })
  const svgRef = useRef<SVGSVGElement | null>(null)
  const drag = useRef<{ x: number; y: number; panX: number; panY: number } | null>(null)

  useEffect(() => {
    const svg = svgRef.current
    if (!svg) return
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      setZoom((z) => Math.min(4, Math.max(0.5, z * Math.exp(-event.deltaY * 0.0015))))
    }
    svg.addEventListener('wheel', onWheel, { passive: false })
    return () => svg.removeEventListener('wheel', onWheel)
  }, [])

  const device = placement?.externalDevices?.[0]
  const uno = device?.mount.type === 'external' ? device : null
  const unoSide = uno?.mount.side ?? 'left'
  const hasSelection = selectedIds.length > 0

  const used = [
    ...(placement?.components ?? []).flatMap((c) =>
      mountedHoles(c)
        .map((entry) => board.holes.find((h) => h.id === entry.hole)?.position)
        .filter((p): p is NonNullable<typeof p> => Boolean(p)),
    ),
    ...(placement?.jumperWires ?? []).flatMap((w) =>
      [w.from, w.to]
        .map((endpoint) => addressToHole(endpoint))
        .map((hole) => (hole ? board.holes.find((h) => h.id === hole)?.position : undefined))
        .filter((p): p is NonNullable<typeof p> => Boolean(p)),
    ),
  ]
  const full = view === 'full'
  const minRow = full || !used.length ? 1 : Math.max(1, Math.floor(Math.min(...used.map((p) => p.z)) / PITCH) - 1)
  const maxRow = full ? 63 : used.length ? Math.min(63, Math.ceil(Math.max(...used.map((p) => p.z)) / PITCH) + 3) : 20
  const visible = board.holes.filter(
    (h) => h.position.z >= (minRow - 1) * PITCH - 1e-8 && h.position.z <= (maxRow - 1) * PITCH + 1e-8,
  )
  // Width always spans the whole board (outer rail to outer rail) so the view does not jump between row ranges.
  const minX = Math.min(...board.holes.map((h) => h.position.x))
  const maxX = Math.max(...board.holes.map((h) => h.position.x))
  // Content offset: the board shifts to make room for an external controller on one side.
  const offsetX = unoSide === 'left' ? UNO_W + UNO_GAP : 0
  const offsetY = unoSide === 'top' ? UNO_H + UNO_GAP : 0
  // One uniform scale preserves the actual reference hole geometry in both axes.
  const pt = (p: { x: number; z: number }): [number, number] => [
    offsetX + MARGIN + (p.x - minX) * SCALE,
    offsetY + MARGIN + (p.z - (minRow - 1) * PITCH) * SCALE,
  ]
  const boardWidth = (maxX - minX) * SCALE + 2 * MARGIN
  let boardHeight = (maxRow - minRow) * PITCH * SCALE + 2 * MARGIN

  // Uno display rect from its semantic external mount side, before any endpoint resolves.
  let unoX = 0
  let unoY = 0
  let extraBottom = 0
  if (uno) {
    if (unoSide === 'left') {
      boardHeight = Math.max(boardHeight, UNO_H + 16)
      unoX = MARGIN
      unoY = Math.max(offsetY + 8, Math.min(offsetY + boardHeight - UNO_H - 8, offsetY + boardHeight / 2 - UNO_H / 2))
    } else if (unoSide === 'right') {
      boardHeight = Math.max(boardHeight, UNO_H + 16)
      unoX = offsetX + MARGIN + boardWidth + UNO_GAP
      unoY = Math.max(offsetY + 8, Math.min(offsetY + boardHeight - UNO_H - 8, offsetY + boardHeight / 2 - UNO_H / 2))
    } else if (unoSide === 'top') {
      unoX = offsetX + MARGIN + boardWidth / 2 - UNO_W / 2
      unoY = MARGIN
    } else {
      unoX = offsetX + MARGIN + boardWidth / 2 - UNO_W / 2
      unoY = offsetY + boardHeight + UNO_GAP
      extraBottom = UNO_H + 8
    }
  }
  const unoPinAnchor = (pin: string): Point => {
    const fraction = PIN_ANCHOR[pin] ?? 0.5
    if (unoSide === 'right') return { x: unoX + 15, y: unoY + UNO_H * fraction }
    if (unoSide === 'top') return { x: unoX + UNO_W * fraction, y: unoY + UNO_H - 15 }
    if (unoSide === 'bottom') return { x: unoX + UNO_W * fraction, y: unoY + 15 }
    return { x: unoX + UNO_W - 15, y: unoY + UNO_H * fraction }
  }

  // Resolve a semantic endpoint to canvas coordinates. Board addresses resolve from the
  // hole map; mounted terminals from their component's mount; controller pins from this
  // scene's display layout of the external mount.
  const resolveEndpoint = (endpoint: string): Point | null => {
    const hole = addressToHole(endpoint)
    if (hole) {
      const target = board.holes.find((h) => h.id === hole)
      if (!target) return null
      const [x, y] = pt(target.position)
      return { x, y }
    }
    const parts = endpointParts(endpoint)
    if (!parts) return null
    const [componentId, terminal] = parts
    const component = placement?.components.find((c) => c.id === componentId)
    if (component?.mount.type === 'breadboard') {
      const hole = addressToHole(component.mount.terminals[terminal] ?? '')
      const target = hole ? board.holes.find((h) => h.id === hole) : null
      if (!target) return null
      const [x, y] = pt(target.position)
      return { x, y }
    }
    if (uno && uno.id === componentId) return { ...unoPinAnchor(terminal), pin: terminal }
    return null
  }

  const focused = new Set<string>([
    ...(placement?.components ?? []).flatMap((c) =>
      selectedIds.includes(c.id) ? mountedHoles(c).map((entry) => entry.hole) : [],
    ),
    ...(placement?.jumperWires ?? []).flatMap((w) =>
      selectedIds.includes(w.id)
        ? [addressToHole(w.from), addressToHole(w.to)].filter((h): h is string => h !== null)
        : [],
    ),
  ])
  const focusedNets = new Set(board.holes.filter((h) => focused.has(h.id)).map((h) => h.net))

  const rails: { key: string; x: number; y0: number; y1: number }[] = []
  for (const prefix of ['L+', 'L-', 'R+', 'R-']) {
    for (const segment of ['A', 'B']) {
      const ends = visible.filter((h) => h.net === prefix + segment)
      if (!ends.length) continue
      const [x, y0] = pt(ends[0].position)
      rails.push({ key: prefix + segment, x, y0: y0 - 8, y1: pt(ends[ends.length - 1].position)[1] + 8 })
    }
  }

  const wires = (placement?.jumperWires ?? []).map((w) => ({
    id: w.id,
    color: schematicColors[w.color] ?? '#6d9450',
    from: w.from,
    to: w.to,
    a: resolveEndpoint(w.from),
    b: resolveEndpoint(w.to),
    label: `${endpointLabel(w.from, placement)} → ${endpointLabel(w.to, placement)}`,
    first: resolveEndpoint(w.from),
    active: !hasSelection || selectedIds.includes(w.id),
  }))

  const records = [
    ...(placement?.components ?? []).map((c) => {
      const holes = mountedHoles(c).map((entry) => entry.hole)
      const points = holes
        .map((hole) => board.holes.find((h) => h.id === hole))
        .filter((h): h is NonNullable<typeof h> => Boolean(h))
        .map((h) => pt(h.position))
      const center = points.length
        ? points.reduce<[number, number]>((sum, p) => [sum[0] + p[0] / points.length, sum[1] + p[1] / points.length], [0, 0])
        : [0, 0]
      return { id: c.id, label: partNames[c.type] ?? c.type, holes, point: { x: center[0], y: center[1] } }
    }),
    ...wires.map((w) => ({
      id: w.id,
      label: w.label,
      holes: [w.from, w.to].map((e) => addressToHole(e)).filter((h): h is string => h !== null),
      point: { x: w.first?.x ?? 0, y: w.first?.y ?? 0 },
    })),
  ]
  const labelX = offsetX + boardWidth + 8
  let lastY = offsetY + 15
  const callouts: Callout[] = records
    .filter((r) => selectedIds.includes(r.id))
    .sort((a, b) => a.point.y - b.point.y)
    .map((r) => {
      const labelY = Math.max(r.point.y, lastY + 36)
      lastY = labelY
      return { key: r.id, x: r.point.x, y: r.point.y, labelY, label: r.label, detail: r.holes.join(' · ') }
    })

  const svgWidth =
    offsetX + boardWidth + (unoSide === 'right' ? UNO_W + UNO_GAP : 0) + (callouts.length ? 200 : 24)
  const svgHeight = offsetY + boardHeight + extraBottom + (lastY + 28 > offsetY + boardHeight ? lastY + 28 - offsetY - boardHeight : 0)
  const e1 = board.holes.find((h) => h.id === 'E1')
  const f1 = board.holes.find((h) => h.id === 'F1')

  const onPointerDown = (event: ReactPointerEvent<SVGSVGElement>) => {
    if (zoom === 1) return
    drag.current = { x: event.clientX, y: event.clientY, panX: pan.x, panY: pan.y }
    event.currentTarget.setPointerCapture(event.pointerId)
  }
  const onPointerMove = (event: ReactPointerEvent<SVGSVGElement>) => {
    if (!drag.current) return
    setPan({ x: drag.current.panX + (event.clientX - drag.current.x), y: drag.current.panY + (event.clientY - drag.current.y) })
  }
  const onPointerUp = () => {
    drag.current = null
  }
  const fit = () => {
    setZoom(1)
    setPan({ x: 0, y: 0 })
  }

  return (
    <>
      <div className="board-toolbar">
        <div className="board-caption">
          <strong id="board-caption">{`${full ? 'Full board' : 'Circuit detail'} · rows ${minRow}–${maxRow}`}</strong>
          <small>Top view · columns A–J across · rows 1–63 down · 2.54 mm pitch</small>
        </div>
        <label className="view-control" htmlFor="board-view">
          View
          <select id="board-view" value={view} onChange={(event) => onViewChange(event.target.value as 'circuit' | 'full')}>
            <option value="circuit">Fit circuit</option>
            <option value="full">Full board</option>
          </select>
        </label>
      </div>
      <div className="canvas-wrap">
        <div id="preview" role="region" tabIndex={0} aria-label="Breadboard layout; scroll to see the full board">
          <svg
            ref={svgRef}
            viewBox={`0 0 ${svgWidth} ${svgHeight}`}
            role="img"
            aria-label={`Breadboard, rows ${minRow} to ${maxRow}`}
            style={{ touchAction: zoom > 1 ? 'none' : 'auto', cursor: zoom > 1 ? 'grab' : 'default' }}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onPointerLeave={onPointerUp}
          >
            <g transform={`translate(${pan.x} ${pan.y}) scale(${zoom})`}>
              <rect
                x={offsetX + 8}
                y={offsetY + 8}
                width={boardWidth - 16}
                height={boardHeight - 16}
                rx={12}
                fill="#faf9f3"
                stroke="#d4d8cf"
              />
              {e1 && f1 && (
                <rect
                  x={pt(e1.position)[0] + 9}
                  y={offsetY + 24}
                  width={pt(f1.position)[0] - pt(e1.position)[0] - 18}
                  height={boardHeight - 48}
                  rx={5}
                  fill="#e4e7df"
                />
              )}
              {rails.map((r) => (
                <line
                  key={r.key}
                  data-rail={r.key}
                  x1={r.x}
                  y1={r.y0}
                  x2={r.x}
                  y2={r.y1}
                  stroke={r.key[1] === '+' ? '#d9a7a0' : '#a7c0d0'}
                  strokeWidth={1.5}
                  strokeLinecap="round"
                />
              ))}
              {(['L+', 'L-', 'R+', 'R-'] as const).map((prefix) => {
                const hole = board.holes.find((h) => h.id === prefix + 'A1')
                if (!hole) return null
                const [x] = pt(hole.position)
                return (
                  <text
                    key={prefix}
                    x={x}
                    y={offsetY + 20}
                    textAnchor="middle"
                    fontSize={13}
                    fontWeight={700}
                    fill={prefix.includes('+') ? '#b54339' : '#37769b'}
                  >
                    {prefix.includes('+') ? '+' : '−'}
                  </text>
                )
              })}
              {visible.map((h) => {
                const [x, y] = pt(h.position)
                const rail = /^[LR]/.test(h.id)
                const isFocused = focused.has(h.id)
                return (
                  <g key={h.id}>
                    {focusedNets.has(h.net) && <circle cx={x} cy={y} r={7} fill="#deefb6" />}
                    <circle
                      data-hole={h.id}
                      cx={x}
                      cy={y}
                      r={isFocused ? 4.5 : 3}
                      fill={isFocused ? '#254b39' : rail ? (h.id[1] === '+' ? '#c98a83' : '#8aa9bd') : '#b5beb1'}
                    >
                      <title>{`${h.id} · ${h.net}`}</title>
                    </circle>
                    {/^A\d+$/.test(h.id) && (
                      <text x={offsetX + 16} y={y + 3} fontSize={9} fill="#627263">
                        {h.id.slice(1)}
                      </text>
                    )}
                    {new RegExp(`^[A-J]${minRow}$`).test(h.id) && (
                      <text x={x} y={offsetY + 20} textAnchor="middle" fontSize={10} fill="#334e40">
                        {h.id[0]}
                      </text>
                    )}
                  </g>
                )
              })}
              {wires.map((w) =>
                w.a && w.b ? (
                  <g key={w.id}>
                    <path
                      d={`M ${w.a.x} ${w.a.y} Q ${(w.a.x + w.b.x) / 2 + 22} ${(w.a.y + w.b.y) / 2} ${w.b.x} ${w.b.y}`}
                      fill="none"
                      stroke={w.color}
                      strokeWidth={w.active ? 3.5 : 2}
                      opacity={w.active ? 1 : 0.22}
                    />
                    {[w.a, w.b].map((p, i) => (
                      <circle key={i} cx={p.x} cy={p.y} r={3.5} fill={w.color} stroke="#fff" strokeWidth={1} />
                    ))}
                  </g>
                ) : null,
              )}
              {(placement?.components ?? []).map((c) => {
                const color = schematicColors[c.type] ?? '#6d9450'
                const entries = mountedHoles(c)
                const points = entries
                  .map((entry) => board.holes.find((h) => h.id === entry.hole))
                  .filter((h): h is NonNullable<typeof h> => Boolean(h))
                  .map((h) => pt(h.position))
                if (!points.length) return null
                const x = points.reduce((sum, p) => sum + p[0], 0) / points.length
                const y = points.reduce((sum, p) => sum + p[1], 0) / points.length
                const [a, b] = [points[0], points[points.length - 1]]
                const dimmed = hasSelection && !selectedIds.includes(c.id)
                return (
                  <g key={c.id} opacity={dimmed ? 0.3 : 1}>
                    {c.type === 'button' && (
                      <>
                        <rect
                          x={Math.min(...points.map((p) => p[0])) - 4}
                          y={Math.min(...points.map((p) => p[1])) - 4}
                          width={Math.max(...points.map((p) => p[0])) - Math.min(...points.map((p) => p[0])) + 8}
                          height={Math.max(...points.map((p) => p[1])) - Math.min(...points.map((p) => p[1])) + 8}
                          rx={5}
                          fill="#d8e8e6"
                          stroke={color}
                        />
                        <circle cx={x} cy={y} r={12} fill={color} />
                      </>
                    )}
                    {c.type !== 'button' && c.type !== 'power_supply' && (
                      <>
                        <line x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]} stroke={color} strokeWidth={3} />
                        {c.type === 'led' ? (
                          <circle cx={x} cy={y} r={8} fill={color} />
                        ) : (
                          <rect x={x - 6} y={y - 13} width={12} height={26} rx={4} fill="#e0c77c" stroke={color} />
                        )}
                      </>
                    )}
                    {c.mount.type === 'breadboard' &&
                      entries.map((entry, i) => (
                        <circle
                          key={entry.terminal}
                          cx={points[i][0]}
                          cy={points[i][1]}
                          r={3.5}
                          fill={color}
                          stroke="#fff"
                          strokeWidth={1}
                        >
                          <title>{`${partNames[c.type] ?? c.type} · ${pinNames[entry.terminal] ?? entry.terminal} → ${entry.hole}`}</title>
                        </circle>
                      ))}
                  </g>
                )
              })}
              {uno && (
                <g
                  opacity={
                    hasSelection && !wires.some((w) => w.active && (w.a?.pin || w.b?.pin)) ? 0.35 : 1
                  }
                >
                  <rect x={unoX} y={unoY} width={UNO_W} height={UNO_H} rx={10} fill="#57a3a5" stroke="#39706f" />
                  {unoSide === 'left' && (
                    <rect x={unoX + 6} y={unoY + UNO_H * 0.16} width={30} height={46} rx={4} fill="#c8cfc9" stroke="#7d8a80" />
                  )}
                  {(unoSide === 'left' || unoSide === 'right') && (
                    <rect
                      x={unoSide === 'left' ? unoX + UNO_W - 22 : unoX + 8}
                      y={unoY + 8}
                      width={14}
                      height={UNO_H * 0.55}
                      rx={3}
                      fill="#2c3530"
                    />
                  )}
                  <rect x={unoX + 10} y={unoY + UNO_H - 22} width={UNO_W * 0.45} height={14} rx={3} fill="#2c3530" />
                  <text x={unoX + UNO_W * 0.44} y={unoY + UNO_H * 0.55} textAnchor="middle" fontSize={17} fontWeight={700} fill="#eef4ee">
                    Arduino Uno
                  </text>
                  <text x={unoX + UNO_W * 0.44} y={unoY + UNO_H * 0.55 + 20} textAnchor="middle" fontSize={12} fill="#d3e4e2">
                    R3 · ATmega328P
                  </text>
                </g>
              )}
              {wires.map((w) => {
                const pinPoint = w.a?.pin ? w.a : w.b?.pin ? w.b : null
                if (!pinPoint) return null
                return (
                  <g key={'pin-' + w.id}>
                    <circle cx={pinPoint.x} cy={pinPoint.y} r={4.5} fill={w.color} stroke="#fff" strokeWidth={1}>
                      <title>{`Uno ${pinPoint.pin}`}</title>
                    </circle>
                    {(unoSide === 'left' || unoSide === 'right') && (
                      <text
                        x={unoSide === 'left' ? pinPoint.x - 10 : pinPoint.x + 10}
                        y={pinPoint.y + 4}
                        textAnchor={unoSide === 'left' ? 'end' : 'start'}
                        fontSize={12}
                        fontWeight={700}
                        fill="#254b39"
                      >
                        {pinPoint.pin}
                      </text>
                    )}
                  </g>
                )
              })}
              {callouts.map((c) => (
                <g key={c.key}>
                  <path
                    d={`M ${c.x} ${c.y} L ${labelX - 5} ${c.labelY}`}
                    fill="none"
                    stroke="#889d83"
                    strokeWidth={1}
                    strokeDasharray="3 3"
                  />
                  <text x={labelX} y={c.labelY - 3} fontSize={11} fontWeight={600} fill="#254b39">
                    {c.label}
                  </text>
                  <text x={labelX} y={c.labelY + 12} fontSize={10} fill="#677762">
                    {c.detail}
                  </text>
                </g>
              ))}
            </g>
          </svg>
        </div>
        <div className="canvas-controls">
          <button onClick={() => setZoom((z) => Math.max(0.5, z * 0.8))} aria-label="Zoom out">−</button>
          <span>{Math.round(zoom * 100)}%</span>
          <button onClick={() => setZoom((z) => Math.min(4, z * 1.25))} aria-label="Zoom in">+</button>
          <button onClick={fit}>Fit</button>
        </div>
      </div>
      <div className="legend">
        <span><i className="red" />LED / supply</span>
        <span><i className="amber" />Resistor / signal</span>
        <span><i className="blue" />Button</span>
        <span><i className="rail-pos" />+ rail</span>
        <span><i className="rail-neg" />− rail</span>
      </div>
    </>
  )
}
