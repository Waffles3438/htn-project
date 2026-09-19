import type { Firmware } from '../types/circuit'

interface Props {
  firmware: Firmware | undefined
  onDownload: () => void
}

export function FirmwarePanel({ firmware, onDownload }: Props) {
  return (
    <section id="firmware" hidden={!firmware}>
      <h3>Arduino sketch</h3>
      <p id="upload-help">{firmware?.uploadInstructions}</p>
      <pre id="sketch">{firmware?.code}</pre>
      <button id="download-sketch" onClick={onDownload}>
        Download circuit.ino ↓
      </button>
    </section>
  )
}
