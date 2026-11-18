import http from 'node:http';
import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const execute = promisify(execFile);
const adb = 'E:/CodexProjects/.tools/android/sdk/platform-tools/adb.exe';
const serial = process.env.CATSCO_ADB_SERIAL || 'emulator-5556';
const port = Number(process.env.CATSCO_PREVIEW_PORT || 5189);
const deviceWidth = Number(process.env.CATSCO_DEVICE_WIDTH || 540);
const deviceHeight = Number(process.env.CATSCO_DEVICE_HEIGHT || 1200);
const videoWidth = Number(process.env.CATSCO_VIDEO_WIDTH || 540);
const videoHeight = Number(process.env.CATSCO_VIDEO_HEIGHT || 1200);
if (!/^[a-zA-Z0-9.:-]+$/.test(serial) || !Number.isInteger(port) || port < 1024 || port > 65535 ||
    ![deviceWidth, deviceHeight, videoWidth, videoHeight].every(value => Number.isInteger(value) && value > 0 && value <= 4096)) {
  throw Error('Invalid preview device configuration');
}
const phone = !serial.startsWith('emulator-');
const origin = `http://127.0.0.1:${port}`;
const videoClients = new Set();
let recording, videoPending = Buffer.alloc(0), sps, pps;
function wsSend(socket, bytes) {
  if (socket.writableLength > 2 * 1024 * 1024) return;
  const header = Buffer.alloc(bytes.length < 65536 ? 4 : 10); header[0] = 0x82;
  if (bytes.length < 65536) { header[1] = 126; header.writeUInt16BE(bytes.length, 2); }
  else { header[1] = 127; header.writeBigUInt64BE(BigInt(bytes.length), 2); }
  socket.write(Buffer.concat([header, bytes]));
}
function startVideo() {
  if (recording) return;
  sps = null; pps = null; videoPending = Buffer.alloc(0);
  recording = spawn(adb, ['-s', serial, 'exec-out', 'screenrecord', '--output-format=h264', '--bit-rate', '1500000', '--size', `${videoWidth}x${videoHeight}`, '-'], { windowsHide: true, stdio: ['ignore','pipe','ignore'] });
  function delimiter(buffer, offset) {
    for (let i = offset; i < buffer.length - 3; i++) {
      if (buffer[i] === 0 && buffer[i + 1] === 0 && (buffer[i + 2] === 1 || (buffer[i + 2] === 0 && buffer[i + 3] === 1))) return i;
    }
    return -1;
  }
  recording.stdout.on('data', data => {
    videoPending = Buffer.concat([videoPending, data]);
    let next;
    while ((next = delimiter(videoPending, 4)) >= 0) {
      const nal = videoPending.subarray(0, next); videoPending = videoPending.subarray(next);
      const start = nal[2] === 1 ? 3 : 4, type = nal[start] & 31;
      if (type === 7) sps = nal;
      else if (type === 8) pps = nal;
      else if ((type === 5 || type === 1) && sps && pps) {
        const payload = type === 5 ? Buffer.concat([sps, pps, nal]) : nal;
        for (const client of videoClients) wsSend(client, payload);
      }
    }
    if (videoPending.length > 8 * 1024 * 1024) videoPending = Buffer.alloc(0);
  });
  recording.on('exit', () => { recording = null; if (videoClients.size) setTimeout(startVideo, 300); });
}
let frame, inFlight;
async function run(args, binary = false) {
  return (await execute(adb, ['-s', serial, ...args], { encoding: binary ? 'buffer' : 'utf8', timeout: 15_000, maxBuffer: 16 * 1024 * 1024, windowsHide: true })).stdout;
}
async function screenshot() {
  if (!inFlight) inFlight = run(['exec-out', 'screencap', '-p'], true).then(bytes => {
    if (bytes.readUInt32BE(0) !== 0x89504e47) throw Error('Invalid device image');
    frame = { bytes, width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
    return frame;
  }).finally(() => { inFlight = null; });
  return inFlight;
}
const server = http.createServer(async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'self'");
  try {
    if (req.headers.host !== `127.0.0.1:${port}`) { res.writeHead(403); res.end(); return; }
    const url = new URL(req.url, origin);
    if (req.method === 'GET' && url.pathname === '/') {
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.end(await readFile(new URL('./native-preview.html', import.meta.url))); return;
    }
    if (req.method === 'GET' && url.pathname === '/config') {
      res.setHeader('Content-Type', 'application/json; charset=utf-8');
      res.end(JSON.stringify({ deviceWidth, deviceHeight, videoWidth, videoHeight, phone })); return;
    }
    if (req.method === 'GET' && url.pathname === '/frame.png') {
      const current = await screenshot(); res.setHeader('Content-Type', 'image/png'); res.end(current.bytes); return;
    }
    if (req.method === 'POST' && req.headers.origin === origin) {
      if (url.pathname === '/tap') {
        const x = Number(url.searchParams.get('x')), y = Number(url.searchParams.get('y'));
        if (!Number.isInteger(x) || !Number.isInteger(y) || x < 0 || y < 0 || x >= deviceWidth || y >= deviceHeight) throw Error('Invalid position');
        await run(['shell', 'input', 'tap', String(x), String(y)]);
      } else if (url.pathname === '/swipe') {
        const values = ['x','y','endX','endY'].map(key => Number(url.searchParams.get(key)));
        if (!values.every((value, index) => Number.isInteger(value) && value >= 0 && value < (index % 2 ? deviceHeight : deviceWidth))) throw Error('Invalid position');
        await run(['shell', 'input', 'swipe', ...values.map(String), '180']);
      } else if (url.pathname === '/back') await run(['shell', 'input', 'keyevent', '4']);
      else if (url.pathname === '/launch') await run(['shell', 'am', 'start', '-n', 'cc.catsco.android.debug/cc.catsco.android.MainActivity']);
      else if (url.pathname === '/theme' && !phone) {
        const mode = url.searchParams.get('mode');
        if (!['yes', 'no'].includes(mode)) throw Error('Invalid theme');
        await run(['shell', 'cmd', 'uimode', 'night', mode]);
      } else { res.writeHead(404); res.end(); return; }
      res.writeHead(204); res.end(); return;
    }
    res.writeHead(404); res.end();
  } catch (error) { res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' }); res.end('安卓设备暂未就绪，请检查连接后重试。'); }
});
server.listen(port, '127.0.0.1', () => console.log(`CatsCo native ${phone ? 'phone' : 'emulator'} preview ${origin}`));
server.on('upgrade', (req, socket) => {
  if (req.headers.origin !== origin || req.url !== '/video' || typeof req.headers['sec-websocket-key'] !== 'string') { socket.destroy(); return; }
  const accept = createHash('sha1').update(req.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
  socket.on('error', () => socket.destroy());
  socket.on('data', data => { if ((data[0] & 15) === 8) socket.end(); });
  videoClients.add(socket);
  if (recording) recording.kill(); else startVideo();
  socket.on('close', () => { videoClients.delete(socket); if (!videoClients.size) recording?.kill(); });
});

