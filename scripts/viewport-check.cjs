// Local visual QA helper. Run with Chrome headless on port 9223 and Vite on 5173.
const fs = require('node:fs')
const path = require('node:path')

const pause = ms => new Promise(resolve => setTimeout(resolve, ms))

async function main() {
  const route = process.argv[2] || '/'
  const captureName = route === '/' ? 'ui-preview' : `ui-${route.replace(/^\/+|\/+$/g, '').replaceAll('/', '-')}`
  const tabs = await (await fetch('http://127.0.0.1:9223/json')).json()
  const tab = tabs.find(item => item.type === 'page')
  if (!tab) throw new Error('No Chrome page is available')

  const socket = new WebSocket(tab.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    socket.onopen = resolve
    socket.onerror = reject
  })
  let nextId = 0
  const pending = new Map()
  socket.onmessage = event => {
    const response = JSON.parse(event.data)
    if (response.id && pending.has(response.id)) {
      pending.get(response.id)(response)
      pending.delete(response.id)
    }
  }
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId
    pending.set(id, response => response.error ? reject(new Error(response.error.message)) : resolve(response.result))
    socket.send(JSON.stringify({ id, method, params }))
  })

  await call('Page.enable')
  for (const width of [320, 375, 768, 1024, 1440]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: width < 768 })
    await call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'light' }] })
    await call('Page.navigate', { url: `http://127.0.0.1:5173${route}` })
    await pause(1000)
    const result = await call('Runtime.evaluate', {
      expression: 'JSON.stringify({ viewport: innerWidth, document: document.documentElement.scrollWidth, body: document.body.scrollWidth })',
      returnByValue: true,
    })
    console.log(`${width}px: ${result.result.value}`)
    if ([375, 1440].includes(width)) {
      const shot = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
      fs.writeFileSync(path.join(__dirname, '..', 'docs', `${captureName}-${width}.png`), Buffer.from(shot.data, 'base64'))
    }
  }
  socket.close()
}

main().catch(error => { console.error(error); process.exitCode = 1 })
