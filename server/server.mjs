import http from 'node:http';
import crypto from 'node:crypto';
import { sosHandler } from './sos.mjs';

const port = Number(process.env.PORT || 8080);
const rooms = new Map();
const handleSos = sosHandler();

function roomFor(id) {
  if (!rooms.has(id)) rooms.set(id, { baby: null, parents: new Set() });
  return rooms.get(id);
}

function frame(opcode, payload = Buffer.alloc(0)) {
  const size = payload.length;
  let header;
  if (size < 126) {
    header = Buffer.from([0x80 | opcode, size]);
  } else if (size <= 0xffff) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(size, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(size), 2);
  }
  return Buffer.concat([header, payload]);
}

function send(client, opcode, payload) {
  if (!client || client.socket.destroyed || !client.socket.writable) return;
  client.socket.write(frame(opcode, payload));
}

function sendText(client, text) {
  send(client, 1, Buffer.from(text, 'utf8'));
}

function closeClient(client, code = 1000) {
  if (!client || client.closed) return;
  client.closed = true;
  const payload = Buffer.alloc(2);
  payload.writeUInt16BE(code);
  try { send(client, 8, payload); } catch {}
  client.socket.end();
}

function cleanup(client) {
  if (client.cleaned) return;
  client.cleaned = true;
  const room = rooms.get(client.roomId);
  if (!room) return;
  if (client.role === 'baby' && room.baby === client) {
    room.baby = null;
    for (const parent of room.parents) sendText(parent, 'BABY_OFFLINE');
  } else {
    room.parents.delete(client);
  }
  if (!room.baby && room.parents.size === 0) rooms.delete(client.roomId);
}

function processFrames(client, incoming) {
  client.buffer = Buffer.concat([client.buffer, incoming]);
  while (client.buffer.length >= 2) {
    const first = client.buffer[0];
    const second = client.buffer[1];
    const opcode = first & 0x0f;
    const masked = (second & 0x80) !== 0;
    let length = second & 0x7f;
    let offset = 2;
    if (length === 126) {
      if (client.buffer.length < 4) return;
      length = client.buffer.readUInt16BE(2);
      offset = 4;
    } else if (length === 127) {
      if (client.buffer.length < 10) return;
      const longLength = client.buffer.readBigUInt64BE(2);
      if (longLength > 1_048_576n) return closeClient(client, 1009);
      length = Number(longLength);
      offset = 10;
    }
    if (!masked || length > 1_048_576) return closeClient(client, 1002);
    if (client.buffer.length < offset + 4 + length) return;
    const mask = client.buffer.subarray(offset, offset + 4);
    offset += 4;
    const payload = Buffer.from(client.buffer.subarray(offset, offset + length));
    client.buffer = client.buffer.subarray(offset + length);
    for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

    if (opcode === 8) return closeClient(client);
    if (opcode === 9) {
      send(client, 10, payload);
      continue;
    }
    if (opcode !== 2 || client.role !== 'baby') continue;
    const room = rooms.get(client.roomId);
    if (!room || room.baby !== client) continue;
    for (const parent of room.parents) send(parent, 2, payload);
  }
}

const server = http.createServer((request, response) => {
  if (request.url === '/sos' || request.url?.startsWith('/sos/')) return handleSos(request, response);
  if (request.url === '/health') {
    response.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    return response.end(JSON.stringify({ ok: true, rooms: rooms.size }));
  }
  response.writeHead(200, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' });
  response.end('Audio baby monitor relay is running. Audio is never stored.\n');
});

server.on('upgrade', (request, socket) => {
  try {
    const url = new URL(request.url, `http://${request.headers.host || 'localhost'}`);
    if (url.pathname === '/sos' || url.pathname.startsWith('/sos/')) {
      socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
      return;
    }
    const role = url.searchParams.get('role');
    const roomId = url.searchParams.get('room');
    const key = request.headers['sec-websocket-key'];
    if (!['baby', 'parent'].includes(role) || !/^[a-f0-9]{32}$/.test(roomId || '') || !key) {
      socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
      return;
    }
    const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\n'
      + 'Upgrade: websocket\r\n'
      + 'Connection: Upgrade\r\n'
      + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);

    const client = { socket, role, roomId, buffer: Buffer.alloc(0), closed: false, cleaned: false };
    const room = roomFor(roomId);
    if (role === 'baby') {
      if (room.baby) closeClient(room.baby, 1008);
      room.baby = client;
      for (const parent of room.parents) sendText(parent, 'BABY_ONLINE');
    } else {
      if (room.parents.size >= 4) return closeClient(client, 1008);
      room.parents.add(client);
      sendText(client, room.baby ? 'BABY_ONLINE' : 'BABY_OFFLINE');
    }

    socket.setNoDelay(true);
    socket.setTimeout(90_000);
    socket.on('timeout', () => closeClient(client, 1001));
    socket.on('data', data => processFrames(client, data));
    socket.on('close', () => cleanup(client));
    socket.on('error', () => cleanup(client));
  } catch {
    socket.destroy();
  }
});

setInterval(() => {
  for (const room of rooms.values()) {
    if (room.baby) send(room.baby, 9, Buffer.from('keepalive'));
    for (const parent of room.parents) send(parent, 9, Buffer.from('keepalive'));
  }
}, 30_000).unref();

server.listen(port, '0.0.0.0', () => {
  console.log(`Audio relay listening on port ${server.address().port}`);
});
