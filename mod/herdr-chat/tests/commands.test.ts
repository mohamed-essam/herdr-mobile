import { describe, expect, test } from 'claude-code/testing'
import { DESCRIPTION_MAX, parseSlash, toCommands } from '../hooks/commands'

describe('toCommands', () => {
  test('keeps name, description and source, in order', () => {
    expect(toCommands([
      { name: 'compact', description: 'Clear history but keep a summary', source: 'builtin' },
      { name: 'brainstorming', description: 'Explore intent', source: 'plugin', plugin: 'superpowers' },
    ])).toEqual([
      { name: 'compact', description: 'Clear history but keep a summary', source: 'builtin' },
      { name: 'brainstorming', description: 'Explore intent', source: 'plugin' },
    ])
  })

  test('drops repeats and nameless rows, clips long descriptions to one line', () => {
    const long = 'x'.repeat(DESCRIPTION_MAX + 50)
    const out = toCommands([
      { name: 'a', description: 'first\nsecond line', source: 'user' },
      { name: 'a', description: 'dup', source: 'user' },
      { name: '', description: 'none', source: 'user' },
      { name: 'b', description: long, source: 'mcp' },
    ])
    expect(out.map(c => c.name)).toEqual(['a', 'b'])
    expect(out[0]!.description).toBe('first second line')
    expect(out[1]!.description.length).toBe(DESCRIPTION_MAX)
    expect(out[1]!.description.endsWith('…')).toBe(true)
  })
})

describe('parseSlash', () => {
  const names = new Set(['compact', 'superpowers:brainstorming'])

  test('a known command, bare or with args', () => {
    expect(parseSlash('/compact', names)).toEqual({ command: 'compact', args: '' })
    expect(parseSlash('  /compact  keep the plan\nand tests ', names)).toEqual({ command: 'compact', args: 'keep the plan\nand tests' })
    expect(parseSlash('/superpowers:brainstorming idea', names)).toEqual({ command: 'superpowers:brainstorming', args: 'idea' })
  })

  test('anything else is a prompt', () => {
    expect(parseSlash('/nope', names)).toBeNull()
    expect(parseSlash('please /compact', names)).toBeNull()
    expect(parseSlash('/', names)).toBeNull()
    expect(parseSlash('/compact', new Set())).toBeNull()
    expect(parseSlash('/compactx', names)).toBeNull()
  })
})
