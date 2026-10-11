function PageHeader({ title, description, badge, actions, level = 1 }) {
  const Heading = level === 1 ? 'h1' : 'h2'

  return (
    <header className="section-heading">
      <div>
        <Heading>{title}</Heading>
        {description && <p>{description}</p>}
      </div>
      <div className="section-heading-actions">
        {badge !== undefined && badge !== null && <span className="section-badge">{badge}</span>}
        {actions}
      </div>
    </header>
  )
}

export default PageHeader
