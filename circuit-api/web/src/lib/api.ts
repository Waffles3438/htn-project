export async function api<T>(path: string, data?: unknown): Promise<T> {
  const response = await fetch(
    path,
    data !== undefined
      ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }
      : undefined,
  )
  const result = await response.json()
  if (!response.ok) {
    const detail = result.error?.details?.length ? '\n' + JSON.stringify(result.error.details) : ''
    throw new Error((result.error?.message ?? 'Request failed.') + detail)
  }
  return result as T
}

export function downloadBlob(content: string, filename: string, type: string): void {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
