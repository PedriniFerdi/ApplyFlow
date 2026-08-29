import { readdir, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import ts from 'typescript'

const allowedBrowserVariables = new Set(['VITE_BACKEND_BASE_URL'])
const environmentDeclarationPattern = /^[ \t]*(?:export[ \t]+)?(VITE_[A-Z0-9_]+)[ \t]*=/gm
const htmlPlaceholderPattern = /%(VITE_[A-Z0-9_]+)%/g

async function filesUnder(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map((entry) => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? filesUnder(path) : [path]
  }))
  return nested.flat()
}

function rejectUnexpectedVariables(names) {
  const unexpected = [...new Set(names)].filter((name) => !allowedBrowserVariables.has(name)).sort()
  if (unexpected.length > 0) {
    throw new Error(`Browser environment variable is not allowlisted: ${unexpected.join(', ')}`)
  }
}

function isImportMetaEnv(node) {
  return ts.isPropertyAccessExpression(node)
    && node.name.text === 'env'
    && ts.isMetaProperty(node.expression)
    && node.expression.keywordToken === ts.SyntaxKind.ImportKeyword
    && node.expression.name.text === 'meta'
}

function browserVariablesInSource(file, source) {
  const kind = file.endsWith('.tsx') || file.endsWith('.jsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS
  const tree = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, kind)
  const variables = []
  const unsupported = []
  const recordVariable = (name) => { if (name.startsWith('VITE_')) variables.push(name) }

  function visit(node) {
    if (ts.isPropertyAccessExpression(node) && isImportMetaEnv(node.expression)) {
      recordVariable(node.name.text)
    } else if (ts.isElementAccessExpression(node) && isImportMetaEnv(node.expression)) {
      const argument = node.argumentExpression
      if (ts.isStringLiteralLike(argument)) recordVariable(argument.text)
      else unsupported.push(node)
    } else if (isImportMetaEnv(node)) {
      const parent = node.parent
      const isDirectAccess = (ts.isPropertyAccessExpression(parent) || ts.isElementAccessExpression(parent)) && parent.expression === node
      if (!isDirectAccess && ts.isVariableDeclaration(parent) && ts.isObjectBindingPattern(parent.name)) {
        for (const element of parent.name.elements) {
          if (element.dotDotDotToken) {
            unsupported.push(element)
            continue
          }
          const exposedName = element.propertyName ?? element.name
          if (ts.isIdentifier(exposedName) || ts.isStringLiteralLike(exposedName)) recordVariable(exposedName.text)
          else unsupported.push(element)
        }
      } else if (!isDirectAccess) {
        unsupported.push(node)
      }
    }
    ts.forEachChild(node, visit)
  }

  visit(tree)
  if (unsupported.length > 0) throw new Error(`Unsupported dynamic import.meta.env access in ${file}; use an allowlisted static property.`)
  return variables
}

async function verifyAllowlist() {
  const root = resolve('..')
  const environmentFiles = (await readdir(root, { withFileTypes: true }))
    .filter((entry) => entry.isFile() && (entry.name === '.env' || entry.name.startsWith('.env.')))
    .map((entry) => resolve(root, entry.name))
  const declaredVariables = []
  for (const file of environmentFiles) {
    const source = await readFile(file, 'utf8')
    declaredVariables.push(...[...source.matchAll(environmentDeclarationPattern)].map(([, name]) => name))
  }

  const html = await readFile(resolve('index.html'), 'utf8')
  declaredVariables.push(...[...html.matchAll(htmlPlaceholderPattern)].map(([, name]) => name))

  const sourceFiles = (await filesUnder(resolve('src'))).filter((file) => /\.[cm]?[jt]sx?$/.test(file))
  for (const file of sourceFiles) {
    declaredVariables.push(...browserVariablesInSource(file, await readFile(file, 'utf8')))
  }
  rejectUnexpectedVariables([...Object.keys(process.env).filter((name) => name.startsWith('VITE_')), ...declaredVariables])
}

async function verifyBundle() {
  const publicCanary = process.env.VITE_BACKEND_BASE_URL
  const privateCanary = process.env.APPLYFLOW_PRIVATE_CANARY
  if (!publicCanary || !privateCanary) throw new Error('Bundle canaries must be configured by CI.')

  const bundleFiles = await filesUnder(resolve('dist'))
  const bundle = (await Promise.all(bundleFiles.map((file) => readFile(file)))).map((content) => content.toString()).join('\n')
  if (!bundle.includes(publicCanary)) throw new Error('Public canary was not found; the bundle scan did not inspect the expected output.')
  if (bundle.includes(privateCanary)) throw new Error('Private synthetic canary leaked into the production bundle.')
}

const mode = process.argv[2]
if (mode === 'allowlist') await verifyAllowlist()
else if (mode === 'bundle') await verifyBundle()
else throw new Error('Expected mode: allowlist or bundle.')
