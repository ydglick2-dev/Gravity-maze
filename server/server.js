#!/usr/bin/env node
'use strict';

/**
 * Kolan signaling server.
 *
 * Two phones cannot find each other on the internet without an introduction. This relays the
 * SDP offer/answer and the ICE candidates they need to establish a direct peer connection, then
 * gets out of the way — audio never passes through here, and nothing is stored.
 *
 * Protocol (JSON over WebSocket):
 *   client -> server  {type:"join",  room:"123456"}
 *                     {type:"offer", sdp:"..."}
 *                     {type:"answer", sdp:"..."}
 *                     {type:"ice",   candidate:"...", sdpMid:"...", sdpMLineIndex:0}
 *   server -> client  {type:"joined", room:"123456", initiator:true|false}
 *                     {type:"peer-joined", room:"123456"}
 *                     {type:"offer"|"answer"|"ice", ...}   (relayed verbatim to the other peer)
 *                     {type:"peer-left"}
 *                     {type:"error", reason:"room-full"|"bad-room"|"not-joined"}
 */

const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8080);
const ROOM_CAPACITY = 2;
const HEARTBEAT_MS = 30000;
const EMPTY_ROOM_TTL_MS = 60000;

/** roomCode -> { clients: Set<WebSocket>, emptySince: number|null } */
const rooms = new Map();

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
    return;
  }
  res.writeHead(404);
  res.end();
});

const wss = new WebSocketServer({ server });

function send(ws, payload) {
  if (ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

function isValidRoom(code) {
  return typeof code === 'string' && /^[0-9]{6}$/.test(code);
}

function leaveRoom(ws) {
  const code = ws.roomCode;
  if (!code) return;
  const room = rooms.get(code);
  if (!room) return;

  room.clients.delete(ws);
  ws.roomCode = null;

  for (const peer of room.clients) {
    send(peer, { type: 'peer-left' });
  }

  // Rooms are kept briefly after emptying so a peer that drops off a flaky mobile connection
  // can rejoin the same code and still be treated as the initiator.
  room.emptySince = room.clients.size === 0 ? Date.now() : null;
}

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.roomCode = null;
  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (!message || typeof message.type !== 'string') return;

    if (message.type === 'join') {
      if (!isValidRoom(message.room)) {
        send(ws, { type: 'error', reason: 'bad-room' });
        return;
      }
      if (ws.roomCode) leaveRoom(ws);

      let room = rooms.get(message.room);
      if (!room) {
        room = { clients: new Set(), emptySince: null };
        rooms.set(message.room, room);
      }
      if (room.clients.size >= ROOM_CAPACITY) {
        send(ws, { type: 'error', reason: 'room-full' });
        return;
      }

      // The first client in the room makes the offer. Deciding it here rather than on the
      // clients is what stops both sides offering simultaneously and colliding.
      const isInitiator = room.clients.size === 0;
      room.clients.add(ws);
      room.emptySince = null;
      ws.roomCode = message.room;

      send(ws, { type: 'joined', room: message.room, initiator: isInitiator });
      for (const peer of room.clients) {
        if (peer !== ws) send(peer, { type: 'peer-joined', room: message.room });
      }
      return;
    }

    if (message.type === 'offer' || message.type === 'answer' || message.type === 'ice') {
      const room = ws.roomCode && rooms.get(ws.roomCode);
      if (!room) {
        send(ws, { type: 'error', reason: 'not-joined' });
        return;
      }
      for (const peer of room.clients) {
        if (peer !== ws) send(peer, message);
      }
    }
  });

  ws.on('close', () => leaveRoom(ws));
  ws.on('error', () => leaveRoom(ws));
});

// Drop connections that stop answering pings. Mobile clients disappear without closing far more
// often than they close cleanly, and without this the rooms would fill with ghosts.
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }

  const now = Date.now();
  for (const [code, room] of rooms) {
    if (room.clients.size === 0 && room.emptySince && now - room.emptySince > EMPTY_ROOM_TTL_MS) {
      rooms.delete(code);
    }
  }
}, HEARTBEAT_MS);

wss.on('close', () => clearInterval(heartbeat));

server.listen(PORT, () => {
  console.log(`Kolan signaling server listening on :${PORT}`);
});

function shutdown() {
  clearInterval(heartbeat);
  for (const ws of wss.clients) ws.close(1001, 'server shutting down');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
