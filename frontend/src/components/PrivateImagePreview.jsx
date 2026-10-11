import { useEffect, useState } from 'react'
import { getPrivateImagePreview } from '../api/uploadApi.js'

function PrivateImagePreview({ imageId, alt, className }) {
  const [source, setSource] = useState(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    if (!imageId) return undefined
    let active = true
    let objectUrl
    setSource(null)
    setError(false)
    getPrivateImagePreview(imageId).then((blob) => {
      objectUrl = URL.createObjectURL(blob)
      if (active) setSource(objectUrl)
      else URL.revokeObjectURL(objectUrl)
    }).catch(() => { if (active) setError(true) })
    return () => {
      active = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [imageId])

  if (!imageId) return null
  if (error) return <p role="status">图片预览暂不可用，请检查审核记录。</p>
  return source ? <img alt={alt} className={className} src={source} /> : <p role="status">正在加载图片预览…</p>
}

export default PrivateImagePreview
