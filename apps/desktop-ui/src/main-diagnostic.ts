// Minimal diagnostic entry point
window.__main_errors = []

try {
  import('./main.ts').catch(e => {
    const msg = e instanceof Error ? e.message : String(e)
    window.__main_errors.push('import failed: ' + msg)
    // Fallback: try direct mount
    const el = document.getElementById('app')
    if (el) el.innerHTML = '<div style="padding:20px;color:#ff6b6b">SCRIPT ERROR: ' + msg.replace(/</g, '&lt;') + '</div>'
  })
} catch (e) {
  const msg = e instanceof Error ? e.message : String(e)
  window.__main_errors.push('sync: ' + msg)
  const el = document.getElementById('app')
  if (el) el.innerHTML = '<div style="padding:20px;background:#1a1a2e">Error: ' + msg.replace(/</g, '&lt;') + '</div>'
}