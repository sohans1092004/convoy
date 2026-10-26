import type { MetadataRoute } from 'next';

// PWA (P5.B): installable; the service worker (public/sw.js) adds the offline page and push.
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: 'Convoy',
    short_name: 'Convoy',
    description: 'Group car buying with sealed, itemised dealer offers.',
    start_url: '/',
    scope: '/',
    display: 'standalone',
    background_color: '#f7f7f5',
    theme_color: '#0f5c4d',
    icons: [
      { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
      { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
  };
}
