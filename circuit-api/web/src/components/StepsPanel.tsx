import { useEffect, useState } from 'react'
import type { Placement } from '../types/circuit'

interface Props {
  placement: Placement
  selectedIds: string[]
  onFocus: (ids: string[]) => void
}

export function StepsPanel({ placement, selectedIds, onFocus }: Props) {
  const [index, setIndex] = useState(0)
  useEffect(() => setIndex(0), [placement])
  const steps = placement.instructions
  if (!steps.length) return null
  const current = steps[Math.min(index, steps.length - 1)]
  return (
    <section id="steps" aria-label="Build steps">
      <div className="step-card" onClick={() => onFocus(current.componentIds)}>
        <div className="step-meta">
          Step {current.step} of {steps.length}
        </div>
        <p>{current.text}</p>
        <div className="step-nav">
          <button
            disabled={index === 0}
            onClick={(event) => {
              event.stopPropagation()
              setIndex(index - 1)
            }}
          >
            Previous
          </button>
          <button
            disabled={index >= steps.length - 1}
            onClick={(event) => {
              event.stopPropagation()
              setIndex(index + 1)
            }}
          >
            Next
          </button>
        </div>
      </div>
      <details className="all-steps">
        <summary>All steps</summary>
        <ol>
          {steps.map((s) => (
            <li key={s.step}>
              <button
                className={s.componentIds.some((id) => selectedIds.includes(id)) ? 'selected' : ''}
                data-item-ids={JSON.stringify(s.componentIds)}
                onClick={() => onFocus(s.componentIds)}
              >
                <b>{String(s.step).padStart(2, '0')}</b>
                <span>{s.text}</span>
              </button>
            </li>
          ))}
        </ol>
      </details>
    </section>
  )
}
