#!/usr/bin/env node

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const projectDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const upstreamDirectory = path.resolve(process.argv[2] ?? '')
const outputDirectory = path.resolve(process.argv[3] ?? '')
const legacyDirectory = path.join(projectDirectory, 'data', 'legacy-entity-data', 'raw')

if (!process.argv[2] || !process.argv[3]) {
  throw new Error('Usage: merge-entity-data.mjs <upstream-dir> <output-dir>')
}

const classAliases = new Map(Object.entries({
  'Entity Arrow': 'Abstract Arrow',
  'Entity Ender Crystal': 'End Crystal',
  'Entity Ender Eye': 'Eye Of Ender',
  'Entity Falling Block': 'Falling Block Entity',
  'Entity Fireball': 'Abstract Hurting Projectile',
  'Entity Firework Rocket': 'Firework Rocket Entity',
  'Entity Fish Hook': 'Fishing Hook',
  'Entity Hanging': 'Hanging Entity',
  'Entity Item': 'Item Entity',
  'Entity Living Base': 'Living Entity',
  'Entity Living': 'Mob',
  'Entity Mob': 'Monster',
  'Entity Minecart': 'Abstract Minecart',
  'Entity Minecart Container': 'Abstract Minecart Container',
  'Entity Minecart Empty': 'Minecart',
  'Entity Minecart Mob Spawner': 'Minecart Spawner',
  'Entity TNTPrimed': 'Primed Tnt',
  'Entity Throwable': 'Throwable Projectile',
  'Entity XPOrb': 'Experience Orb',
  'Multi Part Entity Part': 'Ender Dragon Part',
  'Entity Egg': 'Thrown Egg',
  'Entity Ender Pearl': 'Thrown Enderpearl',
  'Entity Exp Bottle': 'Thrown Experience Bottle',
  'Entity Leash Knot': 'Leash Fence Knot Entity',
  'Entity Potion': 'Thrown Potion',
  'Entity Tipped Arrow': 'Arrow',
  'Entity Creature': 'Pathfinder Mob',
  'Entity Dragon': 'Ender Dragon',
  'Entity Flying': 'Flying Mob',
  'Entity Water Mob': 'Water Animal',
  'Entity Ageable': 'Agable Mob',
  'Entity Golem': 'Abstract Golem',
  'Entity Giant Zombie': 'Giant',
  'Entity Snowman': 'Snow Golem',
  'Entity Wither': 'Wither Boss',
  'Entity Tameable': 'Tamable Animal',
  'Abstract Chest Horse': 'Abstract Chested Horse',
  'Entity Illusion Illager': 'Illusioner',
  'Entity Mooshroom': 'Mushroom Cow',
  'Entity Shoulder Riding': 'Shoulder Riding Entity'
}))

const fieldAliases = new Map(Object.entries({
  Entity: {
    FLAGS: 'SHARED_FLAGS',
    AIR: 'AIR_SUPPLY'
  },
  'Living Entity': {
    HAND_STATES: 'LIVING_ENTITY_FLAGS',
    POTION_EFFECTS: 'EFFECT_COLOR',
    HIDE_PARTICLES: 'EFFECT_AMBIENCE',
    ARROW_COUNT_IN_ENTITY: 'ARROW_COUNT'
  },
  Mob: { AI_FLAGS: 'MOB_FLAGS' },
  Boat: {
    TIME_SINCE_HIT: 'HURT',
    FORWARD_DIRECTION: 'HURTDIR',
    DAMAGE_TAKEN: 'DAMAGE',
    BOAT_TYPE: 'TYPE'
  },
  'Abstract Minecart': {
    ROLLING_AMPLITUDE: 'HURT',
    ROLLING_DIRECTION: 'HURTDIR',
    DISPLAY_TILE: 'DISPLAY_BLOCK',
    DISPLAY_TILE_OFFSET: 'DISPLAY_OFFSET',
    SHOW_BLOCK: 'CUSTOM_DISPLAY'
  },
  'Falling Block Entity': { ORIGIN: 'START_POS' },
  'Firework Rocket Entity': {
    FIREWORK_ITEM: 'FIREWORKS_ITEM',
    BOOSTED_ENTITY: 'ATTACHED_TO_TARGET'
  },
  Player: {
    ABSORPTION: 'PLAYER_ABSORPTION',
    PLAYER_SCORE: 'SCORE',
    PLAYER_MODEL_FLAG: 'PLAYER_MODE_CUSTOMISATION',
    MAIN_HAND: 'PLAYER_MAIN_HAND',
    LEFT_SHOULDER_ENTITY: 'SHOULDER_LEFT',
    RIGHT_SHOULDER_ENTITY: 'SHOULDER_RIGHT'
  },
  'Armor Stand': {
    STATUS: 'CLIENT_FLAGS',
    HEAD_ROTATION: 'HEAD_POSE',
    BODY_ROTATION: 'BODY_POSE',
    LEFT_ARM_ROTATION: 'LEFT_ARM_POSE',
    RIGHT_ARM_ROTATION: 'RIGHT_ARM_POSE',
    LEFT_LEG_ROTATION: 'LEFT_LEG_POSE',
    RIGHT_LEG_ROTATION: 'RIGHT_LEG_POSE'
  },
  'Minecart Command Block': { COMMAND: 'COMMAND_NAME' },
  'Minecart Furnace': { POWERED: 'FUEL' },
  Arrow: { COLOR: 'ID_EFFECT_COLOR' },
  'Wither Skull': { INVULNERABLE: 'DANGEROUS' },
  Slime: { SLIME_SIZE: 'ID_SIZE' },
  Bat: { HANGING: 'FLAGS' },
  Blaze: { ON_FIRE: 'FLAGS' },
  Creeper: { STATE: 'SWELL_DIR', POWERED: 'IS_POWERED', IGNITED: 'IS_IGNITED' },
  'Ender Man': { SCREAMING: 'CREEPY' },
  Guardian: { TARGET_ENTITY: 'ATTACK_TARGET' },
  'Iron Golem': { PLAYER_CREATED: 'FLAGS' },
  Shulker: { ATTACHED_FACE: 'ATTACH_FACE', PEEK_TICK: 'PEEK' },
  'Snow Golem': { PUMPKIN_EQUIPPED: 'PUMPKIN' },
  Spider: { CLIMBING: 'FLAGS' },
  'Wither Boss': {
    FIRST_HEAD_TARGET: 'TARGET_A',
    SECOND_HEAD_TARGET: 'TARGET_B',
    THIRD_HEAD_TARGET: 'TARGET_C',
    INVULNERABILITY_TIME: 'INV'
  },
  Zombie: { IS_CHILD: 'BABY', VILLAGER_TYPE: 'SPECIAL_TYPE' },
  'Abstract Horse': { STATUS: 'FLAGS' },
  Pig: { SADDLED: 'SADDLE' },
  Sheep: { DYE_COLOR: 'WOOL' },
  'Tamable Animal': { TAMED: 'FLAGS' },
  Wolf: { BEGGING: 'INTERESTED' },
  Llama: { COLOR: 'SWAG' },
  'Spellcaster Illager': { SPELL: 'SPELL_CASTING' }
}))

const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'))
const normalize = value => value.replace(/[^A-Za-z0-9]/g, '').toLowerCase()
const latestNames = Object.keys(readJson(path.join(upstreamDirectory, '1.14.4.json')))
const latestByNormalizedName = new Map(latestNames.map(name => [normalize(name), name]))

function canonicalClassName(name) {
  if (!name) return null
  const alias = classAliases.get(name)
  if (alias) return alias
  const withoutPrefix = name.replace(/^Entity\s+/, '')
  return latestByNormalizedName.get(normalize(withoutPrefix))
    ?? latestByNormalizedName.get(normalize(name))
    ?? name
}

function canonicalizeLegacy(snapshot) {
  const result = {}
  for (const [rawName, entity] of Object.entries(snapshot)) {
    const name = canonicalClassName(rawName)
    if (result[name]) {
      throw new Error(`Legacy entity class collision: ${rawName} -> ${name}`)
    }
    const aliases = fieldAliases.get(name) ?? {}
    result[name] = {
      superClass: canonicalClassName(entity.superClass),
      fields: (entity.fields ?? []).map(field => ({
        ...field,
        fieldName: aliases[field.fieldName] ?? field.fieldName
      }))
    }
  }
  return result
}

function descendants(snapshot, root) {
  const result = new Set([root])
  let changed = true
  while (changed) {
    changed = false
    for (const [name, entity] of Object.entries(snapshot)) {
      if (!result.has(name) && result.has(entity.superClass)) {
        result.add(name)
        changed = true
      }
    }
  }
  return result
}

function removeField(snapshot, owner, fieldName) {
  snapshot[owner].fields = snapshot[owner].fields.filter(field => field.fieldName !== fieldName)
}

function shiftAfter(snapshot, owners, index) {
  for (const owner of owners) {
    for (const field of snapshot[owner]?.fields ?? []) {
      if (field.index > index) field.index--
    }
  }
}

function nextIndex(snapshot, owner) {
  let maximum = -1
  for (let current = owner; current; current = snapshot[current]?.superClass) {
    for (const field of snapshot[current]?.fields ?? []) maximum = Math.max(maximum, field.index)
  }
  return maximum + 1
}

function legacy113(includeArrowOwner) {
  const snapshot = structuredClone(readJson(path.join(upstreamDirectory, '1.14.4.json')))
  for (const entityName of [
    'Abstract Villager',
    'Cat',
    'Fox',
    'Panda',
    'Patrolling Monster',
    'Pillager',
    'Raider',
    'Ravager',
    'Trader Llama',
    'Wandering Trader'
  ]) {
    delete snapshot[entityName]
  }

  snapshot.Villager.superClass = 'Agable Mob'
  snapshot['Abstract Illager'].superClass = 'Monster'
  snapshot.Witch.superClass = 'Monster'

  removeField(snapshot, 'Entity', 'POSE')
  shiftAfter(snapshot, new Set(Object.keys(snapshot)), 6)

  removeField(snapshot, 'Living Entity', 'SLEEPING_POS')
  shiftAfter(snapshot, descendants(snapshot, 'Living Entity'), 11)

  removeField(snapshot, 'Abstract Arrow', 'PIERCE_LEVEL')
  shiftAfter(snapshot, descendants(snapshot, 'Abstract Arrow'), 8)
  if (!includeArrowOwner) {
    removeField(snapshot, 'Abstract Arrow', 'OWNERUUID')
    shiftAfter(snapshot, descendants(snapshot, 'Abstract Arrow'), 7)
  }

  const firework = snapshot['Firework Rocket Entity'].fields.find(field => field.fieldName === 'ATTACHED_TO_TARGET')
  if (firework) {
    firework.dataType = 'Integer'
    firework.defaultValue = '0'
  }

  snapshot['Abstract Skeleton'].fields = [{
    index: 12,
    dataType: 'Boolean',
    fieldName: 'SWINGING_ARMS',
    defaultValue: 'false'
  }]
  snapshot.Zombie.fields = [
    { index: 12, dataType: 'Boolean', fieldName: 'BABY', defaultValue: 'false' },
    { index: 13, dataType: 'Integer', fieldName: 'SPECIAL_TYPE', defaultValue: '0' },
    { index: 14, dataType: 'Boolean', fieldName: 'ARMS_RAISED', defaultValue: 'false' },
    { index: 15, dataType: 'Boolean', fieldName: 'DROWNED_CONVERSION', defaultValue: 'false' }
  ]

  snapshot['Abstract Illager'].fields = [{
    index: 12,
    dataType: 'Byte',
    fieldName: 'AGGRESSIVE',
    defaultValue: '(byte)0'
  }]
  snapshot['Spellcaster Illager'].fields = [{
    index: 13,
    dataType: 'Byte',
    fieldName: 'SPELL_CASTING',
    defaultValue: '(byte)0'
  }]

  snapshot.Horse.fields.push({
    index: nextIndex(snapshot, 'Horse'),
    dataType: 'Integer',
    fieldName: 'ARMOR',
    defaultValue: '0'
  })

  snapshot.Villager.fields = [{
    index: 13,
    dataType: 'Integer',
    fieldName: 'PROFESSION',
    defaultValue: '0'
  }]
  snapshot['Zombie Villager'].fields = [
    { index: 16, dataType: 'Boolean', fieldName: 'CONVERTING', defaultValue: 'false' },
    { index: 17, dataType: 'Integer', fieldName: 'PROFESSION', defaultValue: '0' }
  ]

  snapshot.Ocelot.superClass = 'Tamable Animal'
  snapshot.Ocelot.fields = [{
    index: nextIndex(snapshot, 'Ocelot'),
    dataType: 'Integer',
    fieldName: 'TYPE',
    defaultValue: '0'
  }]
  return snapshot
}

function legacy114(includeUnhappyCounter) {
  const snapshot = structuredClone(readJson(path.join(upstreamDirectory, '1.14.4.json')))
  if (!includeUnhappyCounter) {
    removeField(snapshot, 'Abstract Villager', 'UNHAPPY_COUNTER')
    shiftAfter(snapshot, descendants(snapshot, 'Abstract Villager'), 15)
  }
  return snapshot
}

function validateSnapshot(version, snapshot) {
  for (const [entityName, entity] of Object.entries(snapshot)) {
    if (!Array.isArray(entity.fields)) {
      throw new Error(`${version}:${entityName} has no metadata field array`)
    }
    if (entity.superClass && !snapshot[entity.superClass]) {
      throw new Error(`${version}:${entityName} has unknown superclass ${entity.superClass}`)
    }
    for (const field of entity.fields) {
      if (!Number.isInteger(field.index) || typeof field.dataType !== 'string'
          || typeof field.fieldName !== 'string') {
        throw new Error(`${version}:${entityName} contains an invalid metadata field`)
      }
    }

    const indexes = new Set()
    for (let current = entityName; current; current = snapshot[current]?.superClass) {
      for (const field of snapshot[current]?.fields ?? []) {
        if (indexes.has(field.index)) {
          throw new Error(`${version}:${entityName} reuses metadata index ${field.index}`)
        }
        indexes.add(field.index)
      }
    }
  }
}

fs.mkdirSync(outputDirectory, { recursive: true })
for (const entry of fs.readdirSync(outputDirectory)) {
  if (entry.endsWith('.json')) fs.rmSync(path.join(outputDirectory, entry))
}

const upstreamVersions = readJson(path.join(upstreamDirectory, 'versions.json'))
for (const version of upstreamVersions) {
  fs.copyFileSync(path.join(upstreamDirectory, `${version}.json`), path.join(outputDirectory, `${version}.json`))
}

const legacyVersions = ['1.9.4', '1.10', '1.11', '1.11.2', '1.12', '1.12.2']
const derivedVersions = ['1.13', '1.13.1', '1.13.2', '1.14', '1.14.1']
const evidence = readJson(path.join(projectDirectory, 'data', 'legacy-entity-data', 'sources.json'))
const evidenceVersions = evidence.map(source => source.snapshot)
if (JSON.stringify(evidenceVersions) !== JSON.stringify([...legacyVersions, ...derivedVersions])) {
  throw new Error('Legacy source evidence must cover every snapshot in version order')
}
for (const source of evidence) {
  if (!/^[0-9a-f]{40}$/.test(source.serverSha1)
      || !/^[0-9a-f]{40}$/.test(source.buildDataCommit)) {
    throw new Error(`Invalid source evidence for ${source.snapshot}`)
  }
}

for (const version of legacyVersions) {
  const rawSnapshot = readJson(path.join(legacyDirectory, `${version}.json`))
  validateSnapshot(version, rawSnapshot)
  const snapshot = canonicalizeLegacy(rawSnapshot)
  validateSnapshot(version, snapshot)
  fs.writeFileSync(path.join(outputDirectory, `${version}.json`), `${JSON.stringify(snapshot, null, 2)}\n`)
}

for (const [version, snapshot] of [
  ['1.13', legacy113(false)],
  ['1.13.1', legacy113(true)],
  ['1.13.2', legacy113(true)],
  ['1.14', legacy114(false)],
  ['1.14.1', legacy114(true)]
]) {
  validateSnapshot(version, snapshot)
  fs.writeFileSync(path.join(outputDirectory, `${version}.json`), `${JSON.stringify(snapshot, null, 2)}\n`)
}
fs.writeFileSync(
  path.join(outputDirectory, 'versions.json'),
  `${JSON.stringify([...legacyVersions, ...derivedVersions, ...upstreamVersions], null, 2)}\n`
)
