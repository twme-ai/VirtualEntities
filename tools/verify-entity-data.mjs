#!/usr/bin/env node

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const projectDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const dataDirectory = path.resolve(process.argv[2] ?? path.join(projectDirectory, 'src/main/resources/entity-data'))
const manifestPath = path.resolve(
  process.argv[3] ?? path.join(projectDirectory, 'data/metadata-flags/semantic-flags.json')
)

const errors = []
const readJson = file => {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch (error) {
    errors.push(`${path.relative(projectDirectory, file)}: invalid JSON (${error.message})`)
    return null
  }
}

const versions = readJson(path.join(dataDirectory, 'versions.json'))
const manifest = readJson(manifestPath)

if (!Array.isArray(versions) || versions.length === 0) {
  errors.push('versions.json must contain a non-empty array')
}

const versionList = Array.isArray(versions) ? versions : []
const versionSet = new Set(versionList)
if (versionSet.size !== versionList.length) errors.push('versions.json contains duplicate versions')

const files = fs.existsSync(dataDirectory)
  ? fs.readdirSync(dataDirectory).filter(file => file.endsWith('.json') && file !== 'versions.json')
  : []
const fileSet = new Set(files.map(file => file.slice(0, -'.json'.length)))
for (const version of versionList) {
  if (!fileSet.has(version)) errors.push(`versions.json lists ${version}, but ${version}.json is missing`)
}
for (const version of fileSet) {
  if (!versionSet.has(version)) errors.push(`${version}.json is not listed in versions.json`)
}

const snapshots = new Map()
for (const version of versionList) {
  const snapshot = readJson(path.join(dataDirectory, `${version}.json`))
  if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot)) {
    errors.push(`${version}: snapshot must be an object`)
    continue
  }
  snapshots.set(version, snapshot)
  validateSnapshot(version, snapshot)
}

validateSemanticManifest(manifest, snapshots)

if (errors.length > 0) {
  for (const error of errors) console.error(`ERROR: ${error}`)
  process.exit(1)
}

const entityCount = [...snapshots.values()].reduce((sum, snapshot) => sum + Object.keys(snapshot).length, 0)
const fieldCount = [...snapshots.values()].reduce(
  (sum, snapshot) => sum + Object.values(snapshot).reduce((entitySum, entity) => entitySum + entity.fields.length, 0),
  0
)
console.log(`Verified ${snapshots.size} snapshots, ${entityCount} entity-data classes, and ${fieldCount} declared fields.`)
console.log(`Verified ${manifest.flags.length} reviewed semantic metadata flags across every applicable snapshot.`)

function validateSnapshot(version, snapshot) {
  const entityNames = new Set(Object.keys(snapshot))
  if (!entityNames.has('Entity')) errors.push(`${version}: root Entity class is missing`)

  for (const [entityName, entity] of Object.entries(snapshot)) {
    if (!entity || typeof entity !== 'object' || Array.isArray(entity)) {
      errors.push(`${version}:${entityName} must be an object`)
      continue
    }
    if (typeof entity.superClass !== 'string' && entity.superClass !== null) {
      errors.push(`${version}:${entityName} has an invalid superclass`)
    }
    if (entity.superClass !== null && !entityNames.has(entity.superClass)) {
      errors.push(`${version}:${entityName} has unknown superclass ${entity.superClass}`)
    }
    if (!Array.isArray(entity.fields)) {
      errors.push(`${version}:${entityName} has no metadata field array`)
      continue
    }

    const localNames = new Set()
    for (const field of entity.fields) {
      if (!field || typeof field !== 'object') {
        errors.push(`${version}:${entityName} contains a non-object field`)
        continue
      }
      if (!Number.isInteger(field.index) || field.index < 0 || field.index > 254) {
        errors.push(`${version}:${entityName} has invalid index ${field.index}`)
      }
      if (typeof field.dataType !== 'string' || field.dataType.trim() === '') {
        errors.push(`${version}:${entityName} has an invalid dataType`)
      }
      if (typeof field.fieldName !== 'string' || field.fieldName.trim() === '') {
        errors.push(`${version}:${entityName} has an invalid fieldName`)
      } else if (!localNames.add(field.fieldName)) {
        errors.push(`${version}:${entityName} declares field ${field.fieldName} more than once`)
      }
    }

    const indexes = new Map()
    const names = new Map()
    const visited = new Set()
    let current = entityName
    while (current !== null) {
      if (!visited.add(current)) {
        errors.push(`${version}:${entityName} has an inheritance cycle at ${current}`)
        break
      }
      const currentEntity = snapshot[current]
      for (const field of currentEntity?.fields ?? []) {
        if (Number.isInteger(field.index)) {
          const previous = indexes.get(field.index)
          if (previous) errors.push(`${version}:${entityName} reuses index ${field.index} (${previous} and ${current}.${field.fieldName})`)
          indexes.set(field.index, `${current}.${field.fieldName}`)
        }
        if (typeof field.fieldName === 'string') {
          const previous = names.get(field.fieldName)
          if (previous) errors.push(`${version}:${entityName} redeclares field ${field.fieldName} (${previous} and ${current})`)
          names.set(field.fieldName, current)
        }
      }
      current = currentEntity?.superClass ?? null
    }
    if (entityName !== 'Entity' && !visited.has('Entity')) {
      errors.push(`${version}:${entityName} does not inherit from Entity`)
    }
  }

  const shared = snapshot.Entity?.fields?.filter(field => field.fieldName === 'SHARED_FLAGS') ?? []
  if (shared.length !== 1 || shared[0].dataType !== 'Byte' || shared[0].index !== 0) {
    errors.push(`${version}:Entity.SHARED_FLAGS must be exactly Byte at index 0`)
  }

  const textDisplay = snapshot['Text Display']
  const style = textDisplay?.fields?.filter(field => field.fieldName === 'STYLE_FLAGS') ?? []
  if (textDisplay && (style.length !== 1 || style[0].dataType !== 'Byte')) {
    errors.push(`${version}:Text Display.STYLE_FLAGS must be exactly one Byte field`)
  }
  if (!textDisplay && isAtOrAfter(version, '1.19.4')) {
    errors.push(`${version}:Text Display is missing after its 1.19.4 introduction`)
  }
  if (textDisplay && isNumeric(version) && compareVersions(version, '1.19.4') < 0) {
    errors.push(`${version}:Text Display appears before its 1.19.4 introduction`)
  }
}

function validateSemanticManifest(value, snapshotsByVersion) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    errors.push('semantic-flags.json must be an object')
    return
  }
  if (value.format !== 1) errors.push('semantic-flags.json has an unsupported format')
  const requestedRange = value.requestedRange
  if (!requestedRange || typeof requestedRange !== 'object' || Array.isArray(requestedRange)) {
    errors.push('semantic-flags.json must declare requestedRange')
  } else {
    if (!isNumeric(requestedRange.minimum) || !isNumeric(requestedRange.maximum)) {
      errors.push('requestedRange.minimum and maximum must be numeric audit labels')
    } else {
      const beyond = new Set(Array.isArray(requestedRange.bundledBeyondRequestedRange)
        ? requestedRange.bundledBeyondRequestedRange
        : [])
      const nonRelease = new Set(Array.isArray(requestedRange.bundledNonReleaseSnapshots)
        ? requestedRange.bundledNonReleaseSnapshots
        : [])
      for (const version of [...beyond, ...nonRelease]) {
        if (!snapshotsByVersion.has(version)) {
          errors.push(`requestedRange lists ${version}, but that snapshot is not bundled`)
        }
      }
      for (const version of snapshotsByVersion.keys()) {
        if (!isNumeric(version)) {
          if (!nonRelease.has(version)) {
            errors.push(`${version}: non-release snapshot must be listed in bundledNonReleaseSnapshots`)
          }
          continue
        }
        if (compareVersions(version, requestedRange.minimum) < 0) {
          errors.push(`${version}: snapshot is below the requested semantic audit range`)
        }
        if (compareVersions(version, requestedRange.maximum) > 0 && !beyond.has(version)) {
          errors.push(`${version}: snapshot beyond requested range must be listed in bundledBeyondRequestedRange`)
        }
      }
    }
  }
  if (!Array.isArray(value.flags) || value.flags.length === 0) {
    errors.push('semantic-flags.json must contain a non-empty flags array')
    return
  }
  const sourceList = Array.isArray(value.sources) ? value.sources : []
  const sourceIds = new Set(sourceList.map(source => source?.id))
  if (sourceIds.size !== sourceList.length || sourceList.some(source => typeof source?.id !== 'string')) {
    errors.push('semantic-flags.json sources must have unique string ids')
  }
  const ids = new Set()
  const constants = new Set()
  const bindings = new Set()
  for (const flag of value.flags) {
    if (!flag || typeof flag !== 'object') {
      errors.push('semantic flag must be an object')
      continue
    }
    if (typeof flag.id !== 'string' || !ids.add(flag.id)) errors.push(`duplicate or invalid semantic flag id: ${flag.id}`)
    if (typeof flag.constant !== 'string' || !constants.add(flag.constant)) errors.push(`duplicate or invalid semantic constant: ${flag.constant}`)
    if (typeof flag.owner !== 'string' || typeof flag.field !== 'string') {
      errors.push(`${flag.id}: owner and field are required`)
    } else if (!bindings.add(`${flag.owner}.${flag.field}.${flag.mask}`)) {
      errors.push(`${flag.id}: duplicate owner/field/mask binding`)
    }
    if (!isNumeric(flag.introduced)) errors.push(`${flag.id}: introduced must be numeric`)
    if (!Number.isInteger(flag.mask) || flag.mask < 1 || flag.mask > 255 || (flag.mask & (flag.mask - 1)) !== 0) {
      errors.push(`${flag.id}: mask must be one non-zero byte bit`)
    }
    if (flag.hexMask !== `0x${flag.mask?.toString(16).padStart(2, '0')}`) {
      errors.push(`${flag.id}: hexMask does not match mask`)
    }
    if (!Array.isArray(flag.evidence) || flag.evidence.length === 0 || flag.evidence.some(id => !sourceIds.has(id))) {
      errors.push(`${flag.id}: every evidence id must refer to a manifest source`)
    }
    let applicableSnapshots = 0
    for (const [version, snapshot] of snapshotsByVersion) {
      const entity = snapshot[flag.owner]
      const fields = entity?.fields?.filter(field => field.fieldName === flag.field) ?? []
      if (flag.owner === 'Text Display' && !entity) continue
      applicableSnapshots++
      if (fields.length !== 1 || fields[0].dataType !== 'Byte') {
        errors.push(`${version}:${flag.id} does not resolve to one Byte field`)
      }
    }
    if (applicableSnapshots === 0) errors.push(`${flag.id}: does not apply to any bundled snapshot`)
  }

  const assertions = Array.isArray(value.schemaAssertions) ? value.schemaAssertions : []
  for (const assertion of assertions) {
    if (!assertion || typeof assertion !== 'object'
        || typeof assertion.owner !== 'string' || typeof assertion.field !== 'string'
        || typeof assertion.dataType !== 'string'
        || !['everySnapshot', 'everySnapshotWithEntity'].includes(assertion.presence)) {
      errors.push('invalid semantic schema assertion')
      continue
    }
    if (assertion.firstSnapshot && !snapshotsByVersion.has(assertion.firstSnapshot)) {
      errors.push(`${assertion.owner}.${assertion.field}: firstSnapshot is not bundled`)
    }
    for (const [version, snapshot] of snapshotsByVersion) {
      const entity = snapshot[assertion.owner]
      const fields = entity?.fields?.filter(field => field.fieldName === assertion.field) ?? []
      if (assertion.presence === 'everySnapshot' && (fields.length !== 1 || fields[0].dataType !== assertion.dataType)) {
        errors.push(`${version}:${assertion.owner}.${assertion.field} violates semantic schema assertion`)
      }
      if (assertion.presence === 'everySnapshotWithEntity' && entity
          && (fields.length !== 1 || fields[0].dataType !== assertion.dataType)) {
        errors.push(`${version}:${assertion.owner}.${assertion.field} violates semantic schema assertion`)
      }
      if (assertion.firstSnapshot && isNumeric(version) && isNumeric(assertion.firstSnapshot)) {
        const beforeIntroduction = compareVersions(version, assertion.firstSnapshot) < 0
        if (beforeIntroduction && entity) {
          errors.push(`${version}:${assertion.owner} appears before firstSnapshot ${assertion.firstSnapshot}`)
        }
        if (!beforeIntroduction && !entity) {
          errors.push(`${version}:${assertion.owner} is missing after firstSnapshot ${assertion.firstSnapshot}`)
        }
      }
    }
  }
}

function isNumeric(version) {
  return /^\d+(?:\.\d+){0,2}$/.test(version)
}

function parseVersion(version) {
  const match = /^(\d+)(?:\.(\d+))?(?:\.(\d+))?$/.exec(version)
  return match ? [Number(match[1]), Number(match[2] ?? 0), Number(match[3] ?? 0)] : null
}

function compareVersions(left, right) {
  const a = parseVersion(left)
  const b = parseVersion(right)
  if (!a || !b) return 0
  for (let index = 0; index < a.length; index++) {
    if (a[index] !== b[index]) return a[index] - b[index]
  }
  return 0
}

function isAtOrAfter(version, minimum) {
  if (isNumeric(version)) return compareVersions(version, minimum) >= 0
  return true
}
