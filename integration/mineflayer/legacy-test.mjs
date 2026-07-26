import assert from 'node:assert/strict'
import mineflayer from 'mineflayer'

const port = Number(process.env.VE_E2E_PORT)
const version = process.env.VE_LEGACY_VERSION
if (!Number.isInteger(port) || !version) throw new Error('VE_E2E_PORT and VE_LEGACY_VERSION are required')

const bot = mineflayer.createBot({
  host: '127.0.0.1',
  port,
  username: 'VELegacyTest',
  version,
  auth: 'offline'
})

const chatMessages = []
const packets = []
bot.on('messagestr', message => chatMessages.push(message))
bot._client.on('packet', (data, meta) => {
  if (['spawn_entity_living', 'entity_metadata', 'entity_equipment', 'set_passengers', 'entity_move'].includes(meta.name)) {
    packets.push({ name: meta.name, data })
  }
})

function waitFor(predicate, timeoutMs, description) {
  return new Promise((resolve, reject) => {
    const started = Date.now()
    const timer = setInterval(() => {
      try {
        const value = predicate()
        if (value) {
          clearInterval(timer)
          resolve(value)
        } else if (Date.now() - started >= timeoutMs) {
          clearInterval(timer)
          reject(new Error(`Timed out waiting for ${description}`))
        }
      } catch (error) {
        clearInterval(timer)
        reject(error)
      }
    }, 50)
  })
}

function canonicalEntityName(name) {
  return String(name).toLowerCase().replace(/[^a-z0-9]/g, '')
}

try {
  await new Promise((resolve, reject) => {
    bot.once('spawn', resolve)
    bot.once('error', reject)
    bot.once('kicked', reason => reject(new Error(`Kicked: ${reason}`)))
  })
  bot.chat('/velegacytest')

  const ready = await waitFor(
    () => chatMessages.find(message => message.includes('VE_LEGACY_READY:')),
    30_000,
    'legacy virtual entities'
  )
  const match = ready.match(/VE_LEGACY_READY:(\d+):(\d+)/)
  assert.ok(match, `Unexpected ready message: ${ready}`)
  const pigId = Number(match[1])
  const passengerId = Number(match[2])

  const pig = await waitFor(() => bot.entities[pigId], 10_000, 'legacy pig spawn')
  const passenger = await waitFor(() => bot.entities[passengerId], 10_000, 'legacy passenger spawn')
  assert.equal(canonicalEntityName(pig.name), 'pig')
  assert.equal(canonicalEntityName(passenger.name), 'armorstand')
  await waitFor(() => pig.equipment?.[0]?.name === 'stone', 10_000, 'legacy equipment packet')
  assert.ok(packets.some(packet => packet.name === 'entity_equipment'
    && packet.data.entityId === pigId
    && packet.data.slot === 0))
  assert.ok(packets.some(packet => packet.name === 'set_passengers'
    && packet.data.entityId === pigId
    && packet.data.passengers?.includes(passengerId)))

  const initialX = pig.position.x
  await waitFor(() => chatMessages.some(message => message.includes('VE_LEGACY_MOVED')), 10_000, 'legacy move')
  await waitFor(() => bot.entities[pigId]?.position.x >= initialX + 0.9, 10_000, 'legacy relative move packet')

  bot.attack(pig)
  await waitFor(
    () => chatMessages.some(message => message.includes('VE_LEGACY_ATTACK_OK')),
    10_000,
    'legacy attack callback'
  )

  console.log(`Legacy E2E passed for ${version}: pig=${pigId}, passenger=${passengerId}`)
} finally {
  bot.quit()
}
