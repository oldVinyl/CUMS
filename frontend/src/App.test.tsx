import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from './App'
import { ToastProvider } from './toast'

const renderApp = (path = "/") => render(<MemoryRouter initialEntries={[path]}><ToastProvider><App /></ToastProvider></MemoryRouter>)

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
    renderApp("/members")
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

describe('sign in', () => {
  it('shows a useful error when the backend rejects credentials', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false, json: async () => ({ message: 'Email or password was not accepted.' }),
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter initialEntries={['/sign-in']}><ToastProvider><App /></ToastProvider></MemoryRouter>)
    expect(fetchMock).not.toHaveBeenCalledWith('/api/v1/auth/me', expect.anything())
    fireEvent.change(screen.getByLabelText('Email address'), { target: { value: 'member@invalid.example' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Could not sign in')).toBeTruthy()
    expect(await screen.findByText('Email or password was not accepted.')).toBeTruthy()
  })

  it('stays on sign-in when the completed flow has no browser session', async () => {
    const fetchMock = vi.fn((input: string) => {
      if (input.endsWith('/auth/login')) return Promise.resolve({ ok: true,
        json: async () => ({ stage: 'AUTHENTICATED', challenge: null }) })
      return Promise.resolve({ ok: false, json: async () => ({ code: 'AUTH_REQUIRED' }) })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter initialEntries={['/sign-in']}><ToastProvider><App /></ToastProvider></MemoryRouter>)
    fireEvent.change(screen.getByLabelText('Email address'), { target: { value: 'member@invalid.example' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'test password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Your session could not be confirmed. Please sign in again.')).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Welcome back' })).toBeTruthy()
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth/me', { credentials: 'same-origin' })
  })
})

describe('live member directory', () => {
  it('generates member numbers on the server and rotates the add icon', async () => {
    const fetchMock = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/auth/me')) return Promise.resolve({ ok: true, json: async () => ({
        userId: 'officer-1', email: 'officer@invalid.example',
        permissions: ['MEMBER_READ', 'MEMBER_WRITE'],
      }) })
      if (init?.method === 'POST') return Promise.resolve({ ok: true,
        json: async () => ({ id: 'member-2' }) })
      return Promise.resolve({ ok: true, json: async () => ({
        items: [], page: 0, size: 25, hasNext: false,
      }) })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter initialEntries={['/members']}><ToastProvider><App /></ToastProvider></MemoryRouter>)
    await screen.findByText('No members yet')
    const add = await screen.findByRole('button', { name: 'Add member' })
    fireEvent.click(add)
    expect(screen.getByRole('button', { name: 'Close form' }).querySelector('svg')
      ?.classList.contains('open')).toBe(true)
    expect(screen.queryByLabelText('Member number')).toBeNull()
    fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'New Member' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save member' }))
    expect(await screen.findByText('Member added')).toBeTruthy()
    const post = fetchMock.mock.calls.find(call => call[1]?.method === 'POST')
    expect(JSON.parse(post?.[1]?.body as string)).not.toHaveProperty('memberNumber')
  })

  it('searches server records and moves between pages', async () => {
    const fetchMock = vi.fn((input: string) => {
      if (input.endsWith('/auth/me')) return Promise.resolve({ ok: true, json: async () => ({
        userId: 'officer-1', email: 'officer@invalid.example', permissions: ['MEMBER_READ'],
      }) })
      const page = new URL(input, 'http://localhost').searchParams.get('page')
      return Promise.resolve({ ok: true, json: async () => ({ items: [
        { id: 'member-1', memberNumber: 'CU-101', fullName: 'Esi Live', status: 'ACTIVE' },
      ], page: Number(page), size: 25, hasNext: page === '0' }) })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter initialEntries={['/members']}><ToastProvider><App /></ToastProvider></MemoryRouter>)
    expect((await screen.findAllByText('Esi Live')).length).toBeGreaterThan(0)
    fireEvent.change(screen.getByLabelText('Search by name or member number'),
      { target: { value: 'CU-101' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/members?page=0&size=25&search=CU-101', { credentials: 'same-origin' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/members?page=1&size=25&search=CU-101', { credentials: 'same-origin' }))
  })

  it('uses server records after authentication and opens an audited detail route', async () => {
    const fetchMock = vi.fn((input: string) => {
      if (input.endsWith('/auth/me')) return Promise.resolve({ ok: true, json: async () => ({
        userId: 'officer-1', email: 'officer@invalid.example', permissions: ['MEMBER_READ'],
      }) })
      if (input.startsWith('/api/v1/members?')) return Promise.resolve({ ok: true, json: async () => ({ items: [
        { id: 'member-1', memberNumber: 'CU-101', fullName: 'Esi Live', status: 'ACTIVE' },
      ], page: 0, size: 25, hasNext: false }) })
      if (input.includes('/balance?')) return Promise.resolve({ ok: true, json: async () => ({
        asAt: '2025-01-01', principal: '1234.5000', income: '100.0000', equity: '1334.5000',
      }) })
      return Promise.resolve({ ok: true, json: async () => ({ id: 'member-1',
        memberNumber: 'CU-101', fullName: 'Esi Live', status: 'ACTIVE', phone: '0201234999',
        joinDate: '2025-01-01' }) })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter initialEntries={['/members']}><ToastProvider><App /></ToastProvider></MemoryRouter>)
    expect((await screen.findAllByText('Esi Live')).length).toBeGreaterThan(0)
    fireEvent.click(screen.getAllByRole('button', { name: /view/i })[0])
    expect(await screen.findByText('0201234999')).toBeTruthy()
    expect(await screen.findByText('GH₵ 1,334.50')).toBeTruthy()
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/members/member-1', { credentials: 'same-origin' })
  })

  it('saves an edited record with its version and gives feedback', async () => {
    let memberName = 'Esi Live'
    const fetchMock = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/auth/me')) return Promise.resolve({ ok: true, json: async () => ({
        userId: 'officer-1', email: 'officer@invalid.example',
        permissions: ['MEMBER_READ', 'MEMBER_WRITE'],
      }) })
      if (input.startsWith('/api/v1/members?')) return Promise.resolve({ ok: true, json: async () => ({ items: [
        { id: 'member-1', memberNumber: 'CU-101', fullName: memberName, status: 'ACTIVE' },
      ], page: 0, size: 25, hasNext: false }) })
      if (input.includes('/balance?')) return Promise.resolve({ ok: true, json: async () => ({
        asAt: '2025-01-01', principal: '0', income: '0', equity: '0',
      }) })
      if (init?.method === 'PUT') {
        memberName = 'Esi Updated'
        return Promise.resolve({ ok: true, json: async () => ({ version: 1 }) })
      }
      return Promise.resolve({ ok: true, json: async () => ({ id: 'member-1',
        memberNumber: 'CU-101', fullName: memberName, status: 'ACTIVE', phone: null,
        joinDate: '2025-01-01', version: memberName === 'Esi Live' ? 0 : 1 }) })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter initialEntries={['/members']}><ToastProvider><App /></ToastProvider></MemoryRouter>)
    expect((await screen.findAllByText('Esi Live')).length).toBeGreaterThan(0)
    fireEvent.click(screen.getAllByRole('button', { name: /view/i })[0])
    fireEvent.click(await screen.findByRole('button', { name: 'Edit member' }))
    fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'Esi Updated' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('Member updated')).toBeTruthy()
    const put = fetchMock.mock.calls.find(call => call[1]?.method === 'PUT')
    expect(JSON.parse(put?.[1]?.body as string).version).toBe(0)
  })

  it('confirms deactivation and shows the API result', async () => {
    let status = 'ACTIVE'
    const fetchMock = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/auth/me')) return Promise.resolve({ ok: true, json: async () => ({
        userId: 'officer-1', email: 'officer@invalid.example',
        permissions: ['MEMBER_READ', 'MEMBER_WRITE'],
      }) })
      if (input.startsWith('/api/v1/members?')) return Promise.resolve({ ok: true, json: async () => ({
        items: [{ id: 'member-1', memberNumber: 'CU-101', fullName: 'Esi Live', status }],
        page: 0, size: 25, hasNext: false,
      }) })
      if (input.includes('/balance?')) return Promise.resolve({ ok: true, json: async () => ({
        asAt: '2025-01-01', principal: '0', income: '0', equity: '0',
      }) })
      if (init?.method === 'PATCH') {
        status = 'INACTIVE'
        return Promise.resolve({ ok: true, json: async () => ({ version: 1 }) })
      }
      return Promise.resolve({ ok: true, json: async () => ({ id: 'member-1',
        memberNumber: 'CU-101', fullName: 'Esi Live', status, joinDate: '2025-01-01',
        version: status === 'ACTIVE' ? 0 : 1 }) })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter initialEntries={['/members']}><ToastProvider><App /></ToastProvider></MemoryRouter>)
    expect((await screen.findAllByText('Esi Live')).length).toBeGreaterThan(0)
    fireEvent.click(screen.getAllByRole('button', { name: /view/i })[0])
    fireEvent.click(await screen.findByRole('button', { name: 'Deactivate' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm deactivation' }))
    expect(await screen.findByText('Member deactivated')).toBeTruthy()
    const patch = fetchMock.mock.calls.find(call => call[1]?.method === 'PATCH')
    expect(JSON.parse(patch?.[1]?.body as string)).toEqual({ version: 0, status: 'INACTIVE' })
  })
})
