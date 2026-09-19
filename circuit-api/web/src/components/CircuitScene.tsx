import { useEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'
import type { BoardMap, Placement } from '../types/circuit'
import { partNames, pinNames, schematicColors } from '../lib/names'

const PITCH = 0.00254
const SCALE = 7500
const MARGIN = 40

// Display-only Uno pose and size (53.4 × 68.6 mm at canvas scale). The placement marks the
// Uno `separate_anchor_required` — Unity measures its own anchor — so the canvas picks a
// stable spot beside the board and never touches hole geometry.
const UNO_W = 0.0534 * SCALE
const UNO_H = 0.0686 * SCALE
const UNO_GAP = 56
const PIN_ANCHOR: Record<string, number> = { D13: 0.2, GND: 0.3, '5V': 0.4 }

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

  const devices = placement?.externalDevices ?? []
  const external = placement?.externalConnections ?? []
  const uno = devices.length > 0
  const hasSelection = selectedIds.length > 0

  const used = [
    ...(placement?.components ?? []).flatMap((c) => c.terminals.map((t) => t.position)),
    ...(placement?.jumperWires ?? []).flatMap((w) => [w.startPosition, w.endPosition]),
    ...external.map((c) => c.boardPosition),
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
  const gutter = uno ? UNO_W + UNO_GAP : 0
  // One uniform scale preserves the actual reference hole geometry in both axes.
  const pt = (p: { x: number; z: number }): [number, number] => [
    gutter + MARGIN + (p.x - minX) * SCALE,
    MARGIN + (p.z - (minRow - 1) * PITCH) * SCALE,
  ]
  const boardWidth = (maxX - minX) * SCALE + 2 * MARGIN
  let height = (maxRow - minRow) * PITCH * SCALE + 2 * MARGIN
  if (uno) height = Math.max(height, UNO_H + 16)
  const unoTop = uno ? Math.max(8, Math.min(height - UNO_H - 8, height / 2 - UNO_H / 2)) : 0
  const unoRight = uno ? MARGIN + gutter - UNO_GAP : 0
  const unoLeft = unoRight - UNO_W

  const focused = new Set<string>([
    ...(placement?.components ?? []).flatMap((c) => (selectedIds.includes(c.id) ? c.terminals.map((t) => t.holeId) : [])),
    ...(placement?.jumperWires ?? []).flatMap((w) => (selectedIds.includes(w.id) ? [w.fromHole, w.toHole] : [])),
    ...external.flatMap((c) => (selectedIds.includes(c.id) ? [c.holeId] : [])),
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

  const wires = (placement?.jumperWires ?? []).map((w) => {
    const a = pt(w.startPosition)
    const b = pt(w.endPosition)
    return {
      id: w.id,
      color: schematicColors[w.color] ?? '#6d9450',
      a,
      b,
      active: !hasSelection || selectedIds.includes(w.id),
    }
  })

  const records = [
    ...(placement?.components ?? []).map((c) => ({
      id: c.id,
      label: partNames[c.type] ?? c.type,
      holes: c.terminals.map((t) => t.holeId),
      point: c.position,
    })),
    ...(placement?.jumperWires ?? []).map((w) => ({
      id: w.id,
      label: `${w.fromHole} → ${w.toHole}`,
      holes: [w.fromHole, w.toHole],
      point: w.startPosition,
    })),
    ...external.map((c) => ({ id: c.id, label: `Uno ${c.pin} → ${c.holeId}`, holes: [c.holeId], point: c.boardPosition })),
  ]
  const labelX = gutter + boardWidth + 8
  let lastY = 15
  const callouts: Callout[] = records
    .filter((r) => selectedIds.includes(r.id))
    .sort((a, b) => a.point.z - b.point.z)
    .map((r) => {
      const [x, y] = pt(r.point)
      const labelY = Math.max(y, lastY + 36)
      lastY = labelY
      return { key: r.id, x, y, labelY, label: r.label, detail: r.holes.join(' · ') }
    })

  const svgWidth = gutter + boardWidth + (callouts.length ? 200 : 24)
  const svgHeight = Math.max(height, lastY + 28)
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
              <rect x={gutter + 8} y={8} width={boardWidth - 16} height={height - 16} rx={12} fill="#faf9f3" stroke="#d4d8cf" />
              {e1 && f1 && (
                <rect
                  x={pt(e1.position)[0] + 9}
                  y={24}
                  width={pt(f1.position)[0] - pt(e1.position)[0] - 18}
                  height={height - 48}
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
                    y={20}
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
                      <text x={gutter + 16} y={y + 3} fontSize={9} fill="#627263">
                        {h.id.slice(1)}
                      </text>
                    )}
                    {new RegExp(`^[A-J]${minRow}$`).test(h.id) && (
                      <text x={x} y={20} textAnchor="middle" fontSize={10} fill="#334e40">
                        {h.id[0]}
                      </text>
                    )}
                  </g>
                )
              })}
              {wires.map((w) => (
                <g key={w.id}>
                  <path
                    d={`M ${w.a[0]} ${w.a[1]} Q ${(w.a[0] + w.b[0]) / 2 + 22} ${(w.a[1] + w.b[1]) / 2} ${w.b[0]} ${w.b[1]}`}
                    fill="none"
                    stroke={w.color}
                    strokeWidth={w.active ? 3.5 : 2}
                    opacity={w.active ? 1 : 0.22}
                  />
                  {[w.a, w.b].map((p, i) => (
                    <circle key={i} cx={p[0]} cy={p[1]} r={3.5} fill={w.color} stroke="#fff" strokeWidth={1} />
                  ))}
                </g>
              ))}
              {(placement?.components ?? []).map((c) => {
                const color = schematicColors[c.type] ?? '#6d9450'
                const [x, y] = pt(c.position)
                const points = c.terminals.map((t) => pt(t.position))
                const [a, b] = points
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
                    {c.terminals.map((t, i) => (
                      <circle key={t.id} cx={points[i][0]} cy={points[i][1]} r={3.5} fill={color} stroke="#fff" strokeWidth={1}>
                        <title>{`${partNames[c.type] ?? c.type} · ${pinNames[t.id] ?? t.id} → ${t.holeId}`}</title>
                      </circle>
                    ))}
                  </g>
                )
              })}
              {uno && (
                <g opacity={hasSelection && !external.some((c) => selectedIds.includes(c.id)) ? 0.35 : 1}>
                  <rect x={unoLeft} y={unoTop} width={UNO_W} height={UNO_H} rx={10} fill="#57a3a5" stroke="#39706f" />
                  <rect x={unoLeft + 6} y={unoTop + UNO_H * 0.16} width={30} height={46} rx={4} fill="#c8cfc9" stroke="#7d8a80" />
                  <rect x={unoRight - 22} y={unoTop + 8} width={14} height={UNO_H * 0.55} rx={3} fill="#2c3530" />
                  <rect x={unoLeft + 10} y={unoTop + UNO_H - 22} width={UNO_W * 0.45} height={14} rx={3} fill="#2c3530" />
                  <text x={unoLeft + UNO_W * 0.44} y={unoTop + UNO_H * 0.55} textAnchor="middle" fontSize={17} fontWeight={700} fill="#eef4ee">
                    Arduino Uno
                  </text>
                  <text x={unoLeft + UNO_W * 0.44} y={unoTop + UNO_H * 0.55 + 20} textAnchor="middle" fontSize={12} fill="#d3e4e2">
                    R3 · ATmega328P
                  </text>
                </g>
              )}
              {uno &&
                external.map((c) => {
                  const anchorX = unoRight - 15
                  const anchorY = unoTop + UNO_H * (PIN_ANCHOR[c.pin] ?? 0.5)
                  const b = pt(c.boardPosition)
                  const color = schematicColors[c.color] ?? '#6d9450'
                  const active = !hasSelection || selectedIds.includes(c.id)
                  return (
                    <g key={c.id}>
                      <circle cx={anchorX} cy={anchorY} r={4.5} fill={color} stroke="#fff" strokeWidth={1}>
                        <title>{`Uno ${c.pin}`}</title>
                      </circle>
                      <text x={anchorX - 10} y={anchorY + 4} textAnchor="end" fontSize={12} fontWeight={700} fill="#254b39">
                        {c.pin}
                      </text>
                      <path
                        d={`M ${anchorX} ${anchorY} Q ${(anchorX + b[0]) / 2 + 18} ${(anchorY + b[1]) / 2} ${b[0]} ${b[1]}`}
                        fill="none"
                        stroke={color}
                        strokeWidth={active ? 3.5 : 2}
                        opacity={active ? 1 : 0.22}
                      />
                      <circle cx={b[0]} cy={b[1]} r={3.5} fill={color} stroke="#fff" strokeWidth={1}>
                        <title>{`Uno ${c.pin} → ${c.holeId}`}</title>
                      </circle>
                    </g>
                  )
                })}
              {callouts.map((c) => (
                <g key={c.key}>
                  <path d={`M ${c.x} ${c.y} L ${labelX - 5} ${c.labelY}`} fill="none" stroke="#889d83" strokeWidth={1} strokeDasharray="3 3" />
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
