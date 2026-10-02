import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from './App'
import { ToastProvider } from './toast'

const renderApp = () => render(<MemoryRouter><ToastProvider><App /></ToastProvider></MemoryRouter>)

beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('matchMedia', () => ({ matches: false, addListener: () => {}, removeListener: () => {} }))
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('workspace preview', () => {
  it('labels sample financial data and disables posting', () => {
    renderApp()
    expect(screen.getByText('Design preview')).toBeTruthy()
    expect((screen.getByRole('button', { name: /new contribution/i }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('filters the sample member directory', () => {
    renderApp()
    fireEvent.click(screen.getAllByRole('link', { name: 'Members' })[0])
    fireEvent.change(screen.getByRole('textbox', { name: 'Search members' }), { target: { value: 'Ama' } })
    expect(screen.getAllByText('Ama Example').length).toBeGreaterThan(0)
    expect(screen.queryByText('Kojo Sample')).toBeNull()
  })

  it('shows feedback when switching theme', () => {
    renderApp()
    fireEvent.click(screen.getByRole('button', { name: 'Switch to dark theme' }))
    expect(screen.getByText('Dark theme on')).toBeTruthy()
  })

  it('explains preview date changes and rejects an empty date', () => {
    renderApp()
    const date = screen.getByLabelText('As at date')
    const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10)
    fireEvent.change(date, { target: { value: yesterday } })
    expect(screen.getByText('Preview date changed')).toBeTruthy()
    fireEvent.change(date, { target: { value: '' } })
    expect(screen.getByText('Choose a valid as-at date')).toBeTruthy()
  })

  it('credits Vinette and links to Vinyl’s GitHub', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    renderApp()
    fireEvent.click(screen.getAllByRole('link', { name: 'Settings' })[0])
    expect(screen.getByRole('heading', { name: 'Built by Vinette' })).toBeTruthy()
    expect(screen.getByRole('link', { name: /github.com\/oldVinyl/i }).getAttribute('href')).toBe('https://github.com/oldVinyl')
    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }))
    expect(await screen.findByText('GitHub link copied')).toBeTruthy()
    expect(writeText).toHaveBeenCalledWith('https://github.com/oldVinyl')
  })
})
