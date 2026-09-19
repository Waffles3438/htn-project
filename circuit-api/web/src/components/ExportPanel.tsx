import type { Placement } from '../types/circuit'

interface Props {
  current: Placement | null
  busy: boolean
  onDownload: () => void
  onCopy: () => void
}

export function ExportPanel({ current, busy, onDownload, onCopy }: Props) {
  return (
    <section aria-label="Export">
      <div className="export">
        <button id="download" disabled={busy || !current} onClick={onDownload}>
          Download placement.json ↓
        </button>
        <button id="copy" disabled={busy || !current} onClick={onCopy}>
          Copy JSON
        </button>
      </div>
      <details>
        <summary>Placement JSON</summary>
        <pre id="json">{current ? JSON.stringify(current, null, 2) : 'No circuit yet.'}</pre>
      </details>
    </section>
  )
}
