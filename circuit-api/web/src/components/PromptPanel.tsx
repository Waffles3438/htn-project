import type { Part } from '../types/circuit'
import { partNames } from '../lib/names'

interface Props {
  prompt: string
  onPromptChange: (value: string) => void
  busy: boolean
  ready: boolean
  parts: Part[] | null
  boardModel: string | null
  advancedOpen: boolean
  onAdvancedToggle: (open: boolean) => void
  onQuantity: (index: number, value: number) => void
  onGenerate: () => void
  onAnalyze: () => void
  onDemo: (name: 'button_led' | 'led' | 'arduino_led') => void
}

export function PromptPanel({
  prompt,
  onPromptChange,
  busy,
  ready,
  parts,
  boardModel,
  advancedOpen,
  onAdvancedToggle,
  onQuantity,
  onGenerate,
  onAnalyze,
  onDemo,
}: Props) {
  return (
    <section className="panel controls" aria-labelledby="design-title">
      <div className="panel-heading">
        <span className="step">1</span>
        <h2 id="design-title">Circuit</h2>
      </div>
      <label htmlFor="prompt">What do you want to build?</label>
      <textarea
        id="prompt"
        rows={4}
        maxLength={1000}
        value={prompt}
        onChange={(event) => onPromptChange(event.target.value)}
        placeholder={'Describe a circuit… e.g. "Use an Arduino Uno and a button to turn on an LED"'}
      />
      <button className="primary" id="generate" disabled={busy || !ready} onClick={onGenerate}>
        Generate circuit <span>↗</span>
      </button>
      <details
        className="advanced"
        id="advanced"
        open={advancedOpen}
        onToggle={(event) => onAdvancedToggle(event.currentTarget.open)}
      >
        <summary>Advanced options</summary>
        <div className="advanced-body">
          <label htmlFor="board">Breadboard</label>
          <select id="board" disabled={busy}>
            {boardModel ? (
              <option value={boardModel} title={boardModel}>
                Auto (830-hole breadboard)
              </option>
            ) : (
              <option>Loading…</option>
            )}
          </select>
          <div className="inventory-title">
            <h3>Parts kit</h3>
            <span>Quantity</span>
          </div>
          <div id="inventory">
            {(parts ?? []).map((part, index) => (
              <div className="part-row" key={`${part.type}-${index}`}>
                <label htmlFor={`qty-${index}`}>
                  {partNames[part.type] ?? part.type}
                  <small>{part.value}</small>
                </label>
                <input
                  id={`qty-${index}`}
                  type="number"
                  min={0}
                  max={30}
                  value={part.quantity}
                  disabled={busy}
                  onChange={(event) => onQuantity(index, Number(event.target.value))}
                />
              </div>
            ))}
          </div>
          <button className="secondary" id="analyze" disabled={busy || !ready} onClick={onAnalyze}>
            Identify parts only
          </button>
          <div className="offline">
            <p>Ready-made circuits</p>
            <div>
              <button id="demo-button" disabled={busy || !ready} onClick={() => onDemo('button_led')}>
                Button + LED
              </button>
              <button id="demo-led" disabled={busy || !ready} onClick={() => onDemo('led')}>
                LED
              </button>
              <button id="demo-arduino" disabled={busy || !ready} onClick={() => onDemo('arduino_led')}>
                Arduino blink
              </button>
            </div>
          </div>
        </div>
      </details>
    </section>
  )
}
