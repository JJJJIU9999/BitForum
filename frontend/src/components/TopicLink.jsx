import { Link } from 'react-router'
export default function TopicLink({ article }) {
  const name = article.categoryName || '社区内容'
  return article.categoryAvailable === true && article.categoryId
    ? <Link className="topic-link" to={`/topics/${article.categoryId}`}>{name}</Link>
    : <span>{name}</span>
}
