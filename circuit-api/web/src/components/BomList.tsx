export interface BomItem {
  label: string
  value: string
  quantity: number
  ids: string[]
}

interface Props {
  items: BomItem[]
  selectedIds: string[]
  onFocus: (ids: string[]) => void
}

export function BomList({ items, selectedIds, onFocus }: Props) {
  return (
    <section id="parts" aria-label="Bill of materials">
      <h3>Components</h3>
      <div className="bom">
        {items.map((item, i) => (
          <button
            key={i}
            className={`bom-row${item.ids.some((id) => selectedIds.includes(id)) ? ' selected' : ''}`}
            data-item-ids={JSON.stringify(item.ids)}
            onClick={() => onFocus(item.ids)}
          >
            <span>
              {item.label} <small>{item.value}</small>
            </span>
            <b>×{item.quantity}</b>
          </button>
        ))}
      </div>
    </section>
  )
}
