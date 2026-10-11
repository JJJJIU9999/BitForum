import { useEffect, useState } from 'react'
import { Link } from 'react-router'

export function AuthorAvatar({ avatar, name = '社区用户', className = 'author-avatar', alt = '' }) {
  const [failedAvatar, setFailedAvatar] = useState(null)
  useEffect(() => setFailedAvatar(null), [avatar])
  return <span className={className} aria-hidden={alt ? undefined : true}>
    {avatar && failedAvatar !== avatar
      ? <img alt={alt} src={avatar} loading="lazy" onError={() => setFailedAvatar(avatar)} />
      : <span>{name.slice(0, 1)}</span>}
  </span>
}

export default function AuthorIdentity({ author, userId, compact = false }) {
  const id = author?.userId ?? userId
  const name = author?.displayName || '社区用户'
  const avatar = /^\/api\/images\/[1-9][0-9]*\/content$/.test(author?.avatar || '') ? author.avatar : null
  const available = Number(id) > 0 && (author ? author.profileAvailable : true)
  const content = <><AuthorAvatar key={id} avatar={avatar} name={name} /><span className="author-name">{name}</span></>
  const className = `author-identity${compact ? ' author-compact' : ''}`
  return available
    ? <Link className={className} to={`/users/${id}`} aria-label={`查看${name}的主页`}>{content}</Link>
    : <span className={className}>{content}</span>
}
