/** 客户端压缩配图：限制最大边长并转为 WebP / JPEG Data URL，确保不超过后端 500KB 限制 */
export async function compressImageFile(file: File): Promise<string> {
  const isHeic =
    /heic|heif/i.test(file.type || '') || /\.(heic|heif)$/i.test(file.name || '');
  if (!file.type.startsWith('image/') && !isHeic) {
    throw new Error('请选择图片文件');
  }
  const url = URL.createObjectURL(file);
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const el = new Image();
      el.onload = () => resolve(el);
      el.onerror = () =>
        reject(
          new Error(
            isHeic
              ? '当前浏览器不支持直接读取 HEIC/HEIF 照片，请先转为 JPG/PNG 或直接截图粘贴'
              : '图片读取失败，请确认图片格式受支持且未损坏',
          ),
        );
      el.src = url;
    });
    const encode = (maxEdge: number, quality: number, mime: string) => {
      const ratio = Math.min(1, maxEdge / Math.max(img.naturalWidth || 1, img.naturalHeight || 1));
      const w = Math.max(1, Math.round((img.naturalWidth || 1) * ratio));
      const h = Math.max(1, Math.round((img.naturalHeight || 1) * ratio));
      const canvas = document.createElement('canvas');
      try {
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error('浏览器不支持画布压缩');
        if (mime === 'image/jpeg') {
          ctx.fillStyle = '#ffffff';
          ctx.fillRect(0, 0, w, h);
        }
        ctx.drawImage(img, 0, 0, w, h);
        const dataUrl = canvas.toDataURL(mime, quality);
        return dataUrl.startsWith('data:' + mime) ? dataUrl : '';
      } finally {
        canvas.width = 0;
        canvas.height = 0;
      }
    };
    const attempts: [number, number, string][] = [
      [1200, 0.84, 'image/webp'],
      [1200, 0.82, 'image/jpeg'],
      [960, 0.72, 'image/jpeg'],
      [720, 0.62, 'image/jpeg'],
    ];
    for (const [edge, quality, mime] of attempts) {
      const dataUrl = encode(edge, quality, mime);
      if (dataUrl && dataUrl.length <= 450000) return dataUrl;
    }
    throw new Error('图片过大，压缩后仍超过限制，请裁切后再试');
  } finally {
    URL.revokeObjectURL(url);
  }
}

export function formatImageHint(src: string): string {
  if (src.startsWith('/api/media/')) return '本地配图';
  if (src.startsWith('data:image/')) {
    const kb = Math.max(1, Math.round((src.length * 3) / 4 / 1024));
    return `${kb} KB`;
  }
  return '外链图片';
}
