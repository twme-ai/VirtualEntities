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

  const packetTrace = []
  bot._client.on('packet', (data, metadata) => {
    packetTrace.push({ name: metadata.name, entityId: data?.entityId })
  })

  const readyMessage = waitForMessage(bot, 'VE_READY:')
  bot.chat('/vetest')
  const ready = await readyMessage
  const entityIds = ready
    .substring(ready.indexOf('VE_READY:') + 'VE_READY:'.length)
    .trim()
    .split(':')
    .map(Number)
  if (entityIds.length !== 3 || entityIds.some(id => !Number.isInteger(id))) {
    throw new Error(`Invalid virtual entity IDs in message: ${ready}`)
  }
  const [entityId, rootId, childId] = entityIds

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

  const root = await waitForEntity(bot, rootId)
  const child = await waitForEntity(bot, childId)
  if (root.name !== 'text_display' || child.name !== 'text_display') {
    throw new Error(`Expected Text Displays, received ${root.name} and ${child.name}`)
  }
  const textMetadataKeys = bot.registry.entitiesByName.text_display.metadataKeys
  const textIndex = textMetadataKeys.indexOf('text')
  const translationIndex = textMetadataKeys.indexOf('translation')
  await waitForMetadata(
    bot,
    child,
    current => current.metadata[textIndex]?.value === 'VE_TEST_TEXT' &&
      Math.abs(current.metadata[translationIndex]?.x - 2) < 0.01,
    'Text Display metadata'
  )
  const rootInitialX = root.position.x
  packetTrace.length = 0

  await Promise.all([
    waitForMessage(bot, 'VE_MOVED'),
    waitForMessage(bot, 'VE_RELOCATED')
  ])
  if (Math.abs(entity.position.x - (initialX + 1)) > 0.01) {
    throw new Error(`Relative move mismatch: ${initialX} -> ${entity.position.x}`)
  }
  if (Math.abs(root.position.x - (rootInitialX + 1)) > 0.01) {
    throw new Error(`Root-anchor teleport mismatch: ${rootInitialX} -> ${root.position.x}`)
  }
  if (Math.abs(child.metadata[translationIndex]?.x - 1) > 0.01) {
    throw new Error(`Text Display translation mismatch: ${child.metadata[translationIndex]?.x}`)
  }

  const bundleSequenceFound = packetTrace.some((packet, index) =>
    packet.name === 'bundle_delimiter' &&
    packetTrace[index + 1]?.name === 'entity_metadata' &&
    packetTrace[index + 1]?.entityId === childId &&
    packetTrace[index + 2]?.name === 'sync_entity_position' &&
    packetTrace[index + 2]?.entityId === rootId &&
    packetTrace[index + 3]?.name === 'bundle_delimiter'
  )
  if (!bundleSequenceFound) {
    throw new Error(`Atomic relocation bundle was not observed: ${JSON.stringify(packetTrace)}`)
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
    attack: true,
    textDisplay: child.metadata[textIndex].value,
    rootAnchorRelocation: true,
    bundle: true
  }))
  bot.quit('e2e complete')
} catch (error) {
  fatal(error)
}
