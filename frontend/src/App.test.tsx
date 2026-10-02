import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from './App'

beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('matchMedia', () => ({ matches: false, addListener: () => {}, removeListener: () => {} }))
})
afterEach(cleanup)

describe('workspace preview', () => {
  it('labels sample financial data and disables posting', () => {
    render(<MemoryRouter><App /></MemoryRouter>)
    expect(screen.getByText('Design preview')).toBeTruthy()
    expect((screen.getByRole('button', { name: /new contribution/i }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('filters the sample member directory', () => {
    render(<MemoryRouter><App /></MemoryRouter>)
    fireEvent.click(screen.getAllByRole('link', { name: 'Members' })[0])
    fireEvent.change(screen.getByRole('textbox', { name: 'Search members' }), { target: { value: 'Ama' } })
    expect(screen.getAllByText('Ama Example').length).toBeGreaterThan(0)
    expect(screen.queryByText('Kojo Sample')).toBeNull()
  })
})
