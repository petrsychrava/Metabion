import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../msw/server'
import { contentEducationApi } from '@/api/contentEducation'

describe('contentEducationApi', () => {
  it('requests the version form for the editor', async () => {
    let seenUrl = ''
    server.use(
      http.get('/api/content/education/modules/ibd-basics/versions/2/form', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json({
          slug: 'ibd-basics', topic: 'IBD', sortOrder: 10,
          englishTitle: 'T', englishSummary: 'S', czechTitle: '', czechSummary: '', lessons: [],
        })
      }),
    )
    await contentEducationApi.getVersionForm('ibd-basics', 2)
    expect(seenUrl).toContain('/api/content/education/modules/ibd-basics/versions/2/form')
  })

  it('puts the full draft form', async () => {
    let received: unknown
    const form = {
      slug: 'ibd-basics', topic: 'IBD', sortOrder: 10,
      englishTitle: 'T', englishSummary: 'S', czechTitle: '', czechSummary: '',
      lessons: [{
        slug: 'intro', sortOrder: 10, englishTitle: 'I', englishSummary: 'IS', englishBodyMarkdown: 'B',
        czechTitle: '', czechSummary: '', czechBodyMarkdown: '',
      }],
    }
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.put('/api/content/education/modules/ibd-basics/versions/2', async ({ request }) => {
        received = await request.json()
        return HttpResponse.json({})
      }),
    )
    await contentEducationApi.updateVersion('ibd-basics', 2, form)
    expect(received).toEqual(form)
  })

  it('posts review decisions and markdown previews', async () => {
    let reviewUrl = ''
    let previewBody: unknown
    server.use(
      http.get('/api/csrf', () => HttpResponse.json({ token: 't', headerName: 'X-XSRF-TOKEN' })),
      http.post('/api/content/education/modules/ibd-basics/versions/2/approve', ({ request }) => {
        reviewUrl = request.url
        return HttpResponse.json({})
      }),
      http.post('/api/content/education/markdown-preview', async ({ request }) => {
        previewBody = await request.json()
        return HttpResponse.json({ html: '<h1>Hi</h1>' })
      }),
    )
    await contentEducationApi.review('ibd-basics', 2, 'approve', 'ok')
    expect(reviewUrl).toContain('/approve')
    const preview = await contentEducationApi.previewMarkdown('# Hi')
    expect(previewBody).toEqual({ markdown: '# Hi' })
    expect(preview.html).toBe('<h1>Hi</h1>')
  })
})
