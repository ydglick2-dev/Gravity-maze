# Subway Surfers — 3D endless runner

A personal, non-commercial fan project: a 3-lane endless runner rendered in real 3D
with Three.js, built with no build step, no npm and no external assets.

> **Disclaimer.** This project is **not affiliated with, endorsed by, or connected to
> SYBO Games or Kiloo in any way.** "Subway Surfers" is their trademark. This is not the
> original game and contains no part of it. All code, art and audio here are original and
> generated procedurally at runtime — there are no imported models, textures, sprites,
> fonts or sound files. For personal, non-commercial use only. See `about.html`.

## Running it

```bash
python3 -m http.server 8000     # then open http://localhost:8000/
```

Opening `index.html` directly from disk also works — the scripts are classic
`<script>` tags, not ES modules, so `file://` is fine. Only the service worker needs
`http(s)`, and its registration is guarded.

## Controls

| Action | Touch | Keyboard |
|---|---|---|
| Change lane | swipe ← → | `←` `→` / `A` `D` |
| Jump | swipe ↑ | `↑` / `W` / `Space` |
| Roll (slam down in mid-air) | swipe ↓ | `↓` / `S` |
| Hoverboard | double tap or the 🛹 button | `Shift` |
| Pause | — | `Esc` / `P` |

Swipes fire the moment the threshold is crossed during `touchmove` rather than on
`touchend`, and re-arm afterwards, so one continuous drag can chain several inputs.

## What's in it

- **Power-ups** — coin magnet, score multiplier (x2, x4 when stacked), jetpack,
  super sneakers, and a hoverboard that absorbs one crash instead of ending the run.
- **Shop** — 9 characters, 6 boards, and 4 upgradable power-up durations (5 levels each).
- **Meta** — three daily missions with rerolls, a daily word challenge whose letters
  spawn in the track, a local top-10 leaderboard, and a stats screen.
- **Revive** — spend keys to continue, at a doubling cost, up to three times per run.
- **🔓 UNLOCK ALL** — the single deliberate deviation from the original design. The
  top-right button unlocks every character, board and upgrade and grants unlimited coins
  and keys. The flag persists and is re-applied on load, so later content stays unlocked.

## Layout

```
index.html            shell: markup, all CSS, every overlay
about.html            the full disclaimer, EN + HE
three.min.js          Three.js r128, unmodified, MIT header intact
js/00-core.js         math, seeded RNG, device tier, save/load, Web Audio synthesis
js/10-world.js        renderer, scene, chunk streaming, pooling, procedural props
js/20-gen.js          pattern library, solvability validator, difficulty ramp
js/30-player.js       character rig, state machine, lane tween, AABB collision
js/40-power.js        power-up timers, magnet fly-coins, jetpack, particles
js/50-ui.js           missions, leaderboard, shop + 3D preview, HUD, unlock-all
js/60-main.js         input, RAF loop, run lifecycle, dev overlay, self-test
sw.js                 offline cache (`subway-surfers-v1`)
```

Scripts share one global namespace, `window.SS`, and load in filename order.

## Design notes

**The world moves; the player does not.** The runner is pinned at `z ≈ 0` and chunks
stream toward him. This keeps the directional light, shadow camera and fog completely
static, bounds floating-point precision over a 20 km run, and makes an obstacle's world
`z` its distance from the player.

**Solvability is guaranteed, not hoped for.** Generation works on a 6×3 grid of
passability classes. Every authored pattern is validated at boot, and every generated
chunk is run through a forward reachability pass before it is emitted; a chunk with no
route is regenerated up to four times, then replaced by a safe breather. The exit lane
mask is threaded into the next chunk, so the guarantee holds across boundaries too.
`SOLID` counts as impassable even where a roof exists, so the guaranteed route is always
a *ground* route and roofs are a bonus.

**Vertical motion uses exact constant-acceleration integration**
(`y += v·dt + ½·g·dt²`), not semi-implicit Euler. Euler loses `½·g·dt²` of height per
step, which at 30fps drops the base jump from 2.18 m to 1.93 m — below the 2.00 m train
roof — silently making roofs unreachable on slow devices.

**Nothing is allocated in the hot loop.** Props come from typed pools, coins are a
per-chunk `InstancedMesh`, and vectors/matrices are module-level scratch. Coin spin
re-composes instance matrices rather than rotating the mesh, which would swing every
coin around the chunk origin.

## Verifying

- `?selftest=1` — pure-logic tests, no WebGL needed: pattern-library validity, chunk
  solvability over 5000 chunks, save round-trip and corrupt-blob recovery, the swipe
  classifier, jump-arc invariants including frame-rate independence, economy limits, and
  unlock-all. Results print to the console and on-screen.
- `?dev=1` — FPS, frame time, draw calls, triangles, live chunks, player state, plus
  `window.__ss` handles (`giveCoins`, `giveKeys`, `godMode`, `forcePower`, `setDist`, …).

There is no test framework and none should be added; the zero-toolchain property is
deliberate.

## Third-party

[Three.js](https://threejs.org/) r128 — © 2010-2021 three.js authors, MIT License,
bundled unmodified. It is the only dependency.
