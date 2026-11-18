import http from 'node:http';
import { readFile } from 'node:fs/promises';

const port = Number(process.env.CATSCO_ICON_PREVIEW_PORT || 5191);
const pages = new Map([
  ['/', './icon-library-preview.html'],
  ['/index.html', './icon-library-preview.html'],
  ['/e-review', './icon-e-review.html'],
  ['/e-review.html', './icon-e-review.html'],
]);

http.createServer(async (req, res) => {
  const pathname = new URL(req.url, `http://127.0.0.1:${port}`).pathname;
  const pagePath = pages.get(pathname);
  if (req.method !== 'GET' || !pagePath) {
    res.writeHead(404).end();
    return;
  }
  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Content-Security-Policy': "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self'; frame-ancestors 'self'",
  });
  res.end(await readFile(new URL(pagePath, import.meta.url)));
}).listen(port, '127.0.0.1', () => console.log(`CatsCo icon library preview http://127.0.0.1:${port}/`));
