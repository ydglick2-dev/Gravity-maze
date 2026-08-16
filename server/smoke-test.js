#!/usr/bin/env node
'use strict';

/**
 * End-to-end check of the signaling protocol.
 *
 * Starts the server as a child process, connects two clients to one room, and asserts that the
 * initiator flag, the peer-joined notice, the offer/answer relay, ICE forwarding, room capacity
 * and peer-left all behave. Run with `npm run smoke`.
 */

const { spawn } = require('child_process');
const WebSocket = require('ws');

const PORT = process.env.SMOKE_PORT || 18081;
const URL = `ws://127.0.0.1:${PORT}`;

let failures = 0;

function check(condition, what) {
  if (condition) {
    console.log(`  ok    ${what}`);
  } else {
    console.log(`  FAIL  ${what}`);
    failures += 1;
  }
}

function connect() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL);
    ws.inbox = [];
    ws.on('message', (raw) => ws.inbox.push(JSON.parse(raw.toString())));
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}

function nextMessage(ws, type, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    const existing = ws.inbox.findIndex((m) => m.type === type);
    if (existing >= 0) return resolve(ws.inbox.splice(existing, 1)[0]);

    const timer = setTimeout(() => {
      ws.off('message', onMessage);
      reject(new Error(`timed out waiting for "${type}"`));
    }, timeoutMs);

    function onMessage(raw) {
      const message = JSON.parse(raw.toString());
      if (message.type !== type) return;
      clearTimeout(timer);
      ws.off('message', onMessage);
      const index = ws.inbox.findIndex((m) => m === message);
      if (index >= 0) ws.inbox.splice(index, 1);
      resolve(message);
    }

    ws.on('message', onMessage);
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const child = spawn(process.execPath, [require.resolve('./server.js')], {
    env: { ...process.env, PORT: String(PORT) },
    stdio: ['ignore', 'pipe', 'inherit'],
  });

  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('server did not start')), 5000);
    child.stdout.on('data', (data) => {
      if (data.toString().includes('listening')) {
        clearTimeout(timer);
        resolve();
      }
    });
  });

  console.log('Kolan signaling smoke test\n==========================\n');

  try {
    const room = '482913';

    const alice = await connect();
    alice.send(JSON.stringify({ type: 'join', room }));
    const aliceJoined = await nextMessage(alice, 'joined');
    check(aliceJoined.initiator === true, 'first client in the room is the initiator');
    check(aliceJoined.room === room, 'joined echoes the room code');

    const bob = await connect();
    bob.send(JSON.stringify({ type: 'join', room }));
    const bobJoined = await nextMessage(bob, 'joined');
    check(bobJoined.initiator === false, 'second client is not the initiator');

    const peerJoined = await nextMessage(alice, 'peer-joined');
    check(peerJoined.room === room, 'initiator is told when the peer arrives');

    alice.send(JSON.stringify({ type: 'offer', sdp: 'v=0 fake-offer' }));
    const offer = await nextMessage(bob, 'offer');
    check(offer.sdp === 'v=0 fake-offer', 'offer is relayed to the peer intact');

    bob.send(JSON.stringify({ type: 'answer', sdp: 'v=0 fake-answer' }));
    const answer = await nextMessage(alice, 'answer');
    check(answer.sdp === 'v=0 fake-answer', 'answer is relayed back intact');

    bob.send(
      JSON.stringify({ type: 'ice', candidate: 'candidate:1 1 udp', sdpMid: '0', sdpMLineIndex: 0 }),
    );
    const ice = await nextMessage(alice, 'ice');
    check(ice.candidate === 'candidate:1 1 udp', 'ICE candidate is relayed');
    check(ice.sdpMLineIndex === 0, 'ICE line index survives the relay');

    // The offer must not be echoed back to its sender.
    check(
      !alice.inbox.some((m) => m.type === 'offer'),
      'a sender never receives its own message back',
    );

    const carol = await connect();
    carol.send(JSON.stringify({ type: 'join', room }));
    const full = await nextMessage(carol, 'error');
    check(full.reason === 'room-full', 'a third client is refused');

    const badRoom = await connect();
    badRoom.send(JSON.stringify({ type: 'join', room: 'abc' }));
    const bad = await nextMessage(badRoom, 'error');
    check(bad.reason === 'bad-room', 'a malformed room code is rejected');

    const stray = await connect();
    stray.send(JSON.stringify({ type: 'offer', sdp: 'x' }));
    const notJoined = await nextMessage(stray, 'error');
    check(notJoined.reason === 'not-joined', 'signalling before joining is rejected');

    bob.close();
    const left = await nextMessage(alice, 'peer-left');
    check(left.type === 'peer-left', 'the remaining peer is told when the other leaves');

    for (const ws of [alice, carol, badRoom, stray]) ws.close();
    await sleep(100);
  } catch (error) {
    console.log(`  FAIL  ${error.message}`);
    failures += 1;
  } finally {
    child.kill('SIGTERM');
  }

  console.log(`\n${failures} failures`);
  process.exit(failures === 0 ? 0 : 1);
}

main();
