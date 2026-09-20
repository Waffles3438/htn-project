import type { Placement } from '../types/circuit'
import { endpointLabel } from '../lib/names'

export function StatusBanner({ text, error }: { text: string; error: boolean }) {
  return (
    <div id="status" role="status" aria-live="polite" className={error ? 'error' : undefined}>
      {text}
    </div>
  )
}

export function ValidationPanel({ placement }: { placement: Placement }) {
  const validation = placement.validation
  if (!validation) return null
  return (
    <details id="validation" className="validation">
      <summary>Validation details ({validation.checks.length} checks)</summary>
      <ul>
        {validation.checks.map((check) => (
          <li key={check}>✓ {check}</li>
        ))}
      </ul>
      {placement.nets?.length ? (
        <ul className="nets">
          {placement.nets.map((net) => (
            <li key={net.id}>
              <b>{net.id}</b> — {net.members.map((member) => endpointLabel(member, placement)).join(' · ')}
            </li>
          ))}
        </ul>
      ) : null}
      {validation.warnings?.length ? (
        <ul className="warnings">
          {validation.warnings.map((warning, i) => (
            <li key={i}>{warning}</li>
          ))}
        </ul>
      ) : null}
    </details>
  )
}
