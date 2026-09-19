interface Props {
  connection: string
}

export function Header({ connection }: Props) {
  return (
    <header>
      <a href="/" className="brand">
        <span className="logo">⌁</span> Circuit Lab
      </a>
      <span id="connection" role="status">
        {connection}
      </span>
    </header>
  )
}
