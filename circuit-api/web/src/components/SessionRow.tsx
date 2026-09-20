interface Props {
  sessionId: string
  onSessionChange: (value: string) => void
  onLatest: () => void
  latestDisabled: boolean
}

export function SessionRow({ sessionId, onSessionChange, onLatest, latestDisabled }: Props) {
  return (
    <div className="session-row">
      <label className="xr-session" htmlFor="session">
        XR session
        <input
          id="session"
          value={sessionId}
          pattern="[A-Za-z0-9_-]{1,64}"
          maxLength={64}
          onChange={(event) => onSessionChange(event.target.value)}
        />
      </label>
      <button id="latest" onClick={onLatest} disabled={latestDisabled}>
        Load saved session
      </button>
    </div>
  )
}
