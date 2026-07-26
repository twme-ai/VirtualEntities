import mineflayer from 'mineflayer'

const port = Number(process.env.VE_E2E_PORT ?? 25579)
const timeoutMs = 45_000

function waitForMessage(bot, expected) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      bot.removeListener('messagestr', listener)
      reject(new Error(`Timed out waiting for message: ${expected}`))
    }, timeoutMs)
    const listener = (message) => {
      if (!message.includes(expected)) return
      clearTimeout(timeout)
      bot.removeListener('messagestr', listener)
      resolve(message)
    }
    bot.on('messagestr', listener)
  })
}

function waitForEntity(bot, entityId) {
  return new Promise((resolve, reject) => {
    const existing = bot.entities[entityId]
    if (existing) {
      resolve(existing)
      return
    }
    const timeout = setTimeout(() => {
      bot.removeListener('entitySpawn', listener)
      reject(new Error(`Timed out waiting for entity ${entityId}`))
    }, timeoutMs)
    const listener = (entity) => {
      if (entity.id !== entityId) return
      clearTimeout(timeout)
      bot.removeListener('entitySpawn', listener)
      resolve(entity)
    }
    bot.on('entitySpawn', listener)
  })
}

function waitForMetadata(bot, entity, predicate, description) {
  return new Promise((resolve, reject) => {
    if (predicate(entity)) {
      resolve()
      return
    }
    const timeout = setTimeout(() => {
      bot.removeListener('entityUpdate', listener)
      reject(new Error(`Timed out waiting for ${description}`))
    }, timeoutMs)
    const listener = (updated) => {
      if (updated.id !== entity.id || !predicate(updated)) return
      clearTimeout(timeout)
      bot.removeListener('entityUpdate', listener)
      resolve()
    }
    bot.on('entityUpdate', listener)
  })
}

const bot = mineflayer.createBot({
  host: '127.0.0.1',
  port,
  username: 'VEClient',
  version: '1.21.11',
  auth: 'offline'
})

const fatal = (error) => {
  console.error(error)
  process.exitCode = 1
  bot.quit('e2e failed')
}

try {
  await new Promise((resolve, reject) => {
    const cleanup = () => {
      clearTimeout(timeout)
      bot.removeListener('spawn', spawned)
      bot.removeListener('error', failed)
      bot.removeListener('kicked', kicked)
    }
    const spawned = () => {
      cleanup()
      resolve()
    }
    const failed = (error) => {
      cleanup()
      reject(error)
    }
    const kicked = reason => failed(new Error(`Mineflayer was kicked: ${reason}`))
    const timeout = setTimeout(() => failed(new Error('Timed out waiting for Mineflayer spawn')), timeoutMs)
    bot.once('spawn', spawned)
    bot.once('error', failed)
    bot.once('kicked', kicked)
  })

  bot.once('error', fatal)
  bot.once('kicked', reason => fatal(new Error(`Mineflayer was kicked: ${reason}`)))

  const readyMessage = waitForMessage(bot, 'VE_READY:')
  bot.chat('/vetest')
  const ready = await readyMessage
  const entityId = Number(ready.substring(ready.indexOf('VE_READY:') + 'VE_READY:'.length).trim())
  if (!Number.isInteger(entityId)) throw new Error(`Invalid virtual entity ID in message: ${ready}`)

  const entity = await waitForEntity(bot, entityId)
  if (entity.name !== 'pig') throw new Error(`Expected pig entity, received ${entity.name}`)
  const metadataKeys = bot.registry.entitiesByName.pig.metadataKeys
  const boostTimeIndex = metadataKeys.indexOf('boost_time')
  await waitForMetadata(
    bot,
    entity,
    current => current.getCustomName()?.toString() === 'VE_TEST_PIG' && current.metadata[boostTimeIndex] === 10,
    'virtual entity metadata'
  )
  const initialX = entity.position.x

  await waitForMessage(bot, 'VE_MOVED')
  if (Math.abs(entity.position.x - (initialX + 1)) > 0.01) {
    throw new Error(`Relative move mismatch: ${initialX} -> ${entity.position.x}`)
  }

  const attackMessage = waitForMessage(bot, 'VE_ATTACK_OK')
  bot.attack(entity)
  await attackMessage

  console.log(JSON.stringify({
    entityId,
    type: entity.name,
    customName: entity.getCustomName().toString(),
    boostTime: entity.metadata[boostTimeIndex],
    relativeMove: true,
    attack: true
  }))
  bot.quit('e2e complete')
} catch (error) {
  fatal(error)
}
