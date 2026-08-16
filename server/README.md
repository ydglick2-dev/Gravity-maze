# Kolan signaling server

Internal calls (mode 2) need this. Two phones cannot find each other on the internet without an
introduction, and this is the introduction: it relays the SDP offer/answer and ICE candidates
that let the two devices open a direct peer-to-peer connection.

**Audio never passes through this server.** Once the two peers are connected, media flows
directly between them and this process is idle. Nothing is stored — rooms live in memory and are
discarded a minute after they empty.

Until you point the app at a running instance, mode 2 will tell you no server is configured. The
other three modes work without it.

## Running it

```bash
cd server
npm install
npm start          # listens on :8080, override with PORT
```

Check it is alive:

```bash
curl http://localhost:8080/health   # {"ok":true,"rooms":0}
```

Run the protocol tests:

```bash
npm run smoke
```

## Docker

```bash
docker build -t kolan-signaling ./server
docker run -p 8080:8080 kolan-signaling
```

## Hosting

The server is a single file with one dependency and idles at a few megabytes of memory, so the
free tier of essentially any platform is enough.

- **Railway / Render / Fly.io** — point at this directory. They provide TLS automatically, which
  matters: Android blocks cleartext by default, so a deployed server must be reachable over
  `wss://` rather than `ws://`.
- **Your own VPS** — run it behind nginx or Caddy with a TLS certificate and proxy the WebSocket
  upgrade through to port 8080.

Then open Kolan, go to Settings, and enter the address including the scheme:

```
wss://your-server.example.com
```

`ws://` is accepted too, but only works against `localhost` or with a network security config
that permits cleartext — fine for testing on the same machine, not for real use.

## Protocol

JSON over WebSocket. Six-digit room codes, two clients per room.

| Direction | Message |
|---|---|
| client → server | `{"type":"join","room":"123456"}` |
| client → server | `{"type":"offer","sdp":"..."}` |
| client → server | `{"type":"answer","sdp":"..."}` |
| client → server | `{"type":"ice","candidate":"...","sdpMid":"0","sdpMLineIndex":0}` |
| server → client | `{"type":"joined","room":"123456","initiator":true}` |
| server → client | `{"type":"peer-joined","room":"123456"}` |
| server → client | `offer` / `answer` / `ice`, relayed verbatim to the other peer |
| server → client | `{"type":"peer-left"}` |
| server → client | `{"type":"error","reason":"room-full"\|"bad-room"\|"not-joined"}` |

The server decides which side is the initiator — the first client into a room — so the two peers
never send offers simultaneously and collide.

## Operational notes

- Clients that stop answering pings are dropped after 30 seconds. Mobile connections vanish
  without closing far more often than they close cleanly; without this, rooms would fill with
  ghosts.
- An emptied room is held for 60 seconds before deletion, so a peer that drops off a flaky
  connection can rejoin the same code and still be treated as the initiator.
- There is no authentication. Room codes are the only secret, and a six-digit code is guessable
  by a determined attacker. If you are running this for more than friends and testing, put it
  behind an authenticating proxy.
