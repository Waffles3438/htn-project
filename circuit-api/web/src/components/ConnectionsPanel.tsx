import type { Placement } from '../types/circuit'
import { partNames, pinNames, pinShort } from '../lib/names'

interface Props {
  placement: Placement
  selectedRow: string | null
  onFocus: (ids: string[], row: string) => void
  onClear: () => void
}

interface Row {
  ids: string[]
  title: string
  detail: string
}

export function ConnectionsPanel({ placement, selectedRow, onFocus, onClear }: Props) {
  const rows: Row[] = []
  placement.externalConnections?.forEach((c) =>
    rows.push({
      ids: [c.id],
      title: `Arduino ${c.pin} → Breadboard ${c.holeId}`,
      detail: c.pin === 'GND' ? 'Ground pin (−)' : 'Digital output pin',
    }),
  )
  placement.components.forEach((c) =>
    c.terminals.forEach((t) =>
      rows.push({
        ids: [c.id],
        title: `${partNames[c.type] ?? c.type} ${pinShort(t.id)} → Breadboard ${t.holeId}`,
        detail: `${pinNames[t.id] ?? t.id} · ${c.value}`,
      }),
    ),
  )
  placement.jumperWires.forEach((w, i) =>
    rows.push({ ids: [w.id], title: `Jumper ${i + 1} · ${w.color}`, detail: `${w.fromHole} → ${w.toHole}` }),
  )
  return (
    <section className="wiring" aria-labelledby="conn-title">
      <div className="wiring-heading">
        <div>
          <h3 id="conn-title">Connections</h3>
          <p className="hint">Select a connection or build step to highlight it on the board.</p>
        </div>
        <button id="clear-focus" onClick={onClear}>
          Show all
        </button>
      </div>
      <div id="connections">
        {rows.map((row, i) => {
          const key = String(i)
          return (
            <button
              key={key}
              className={`connection-row${selectedRow === key ? ' selected' : ''}`}
              data-item-ids={JSON.stringify(row.ids)}
              onClick={() => onFocus(row.ids, key)}
            >
              <span className="row-n">{i + 1}</span>
              <strong>{row.title}</strong>
              <span>{row.detail}</span>
            </button>
          )
        })}
      </div>
      {selectedRow !== null && (
        <p className="net-note">
          Holes A–E in a numbered row share one strip; F–J share another. Each + and − rail has two separate sections and needs a
          supply connection to carry power.
        </p>
      )}
    </section>
  )
}
