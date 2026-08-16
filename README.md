# קוֹלָן — Kolan

Native Android real-time voice changer. Kotlin + Jetpack Compose + Material 3 on top of a C++
DSP engine driven through JNI by Oboe. Fully Hebrew, right-to-left throughout.

`il.kolan` · minSdk 26 · targetSdk 35

---

## Read this first: what Android will not let any app do

**No app on stock Android can inject audio into another app's microphone.** WhatsApp, Telegram
and the phone dialer open the physical microphone through the OS and always receive your real
voice. There is no API, permission, or native trick that changes this — it is the app sandbox
working as designed, and only a custom ROM or root can get around it.

That constraint is why the app is built the way it is. There are exactly three ways another
person can hear the changed voice, and Kolan implements all three:

| Mode | Does someone else hear it? |
|---|---|
| **1. Live monitor** | No — this is you, in your own headphones, for rehearsing a preset |
| **2. Internal call** | **Yes** — the processed voice is what actually goes down the wire |
| **3. Voice message** | **Yes** — a real file, sent through WhatsApp like any other |
| **4. Loudspeaker** | Partly — played into the room during a speakerphone call, with caveats |

The onboarding says this on its second screen. Better said up front than discovered after a
call the user believed was disguised.

---

## The engine

Pitch and formants move independently. That is the entire difference between a voice that
sounds like a different person and one that sounds like a sped-up tape — a plain resampler drags
the vocal tract along with the pitch and gives you a chipmunk.

The live path models speech as **source and filter**:

1. LPC (order 20) analysis over the last 512 samples estimates the vocal tract, `A(z)`.
2. Inverse filtering by `A(z)` strips the tract off, leaving a formant-free excitation.
3. **WSOLA** shifts the pitch of that excitation — a delay line read at a fractional rate, with
   waveform-similarity splices that keep the read head from drifting into the write head.
   Because the excitation carries no formants, nothing else moves.
4. The spectral envelope implied by `A(z)` is warped along the frequency axis by an independent
   formant ratio, converted to a 128-tap minimum-phase FIR through the real cepstrum, and
   filtered back on.

Whisperisation falls out of this for free: substitute noise for the excitation and keep the
tract. That is what a whisper physically is.

Voice messages take a different path. Nothing is waiting on the result, so the engine uses a
**phase vocoder** — 2048-point window, hop 256, with Laroche–Dolson peak-based phase locking, and
cepstral-liftered envelope warping. Pitch is completed by resampling, which multiplies the
formants too, so the vocoder is told to warp by the target formant ratio divided by the pitch
ratio.

The rest of the chain: noise gate → pitch/formant stage → ring modulator → 2× oversampled
waveshaper → 300–3400 Hz telephone band → Freeverb → compressor → limiter.

### Real-time discipline

Inside the Oboe callback there are **no allocations, no locks, no JNI calls and no logging**.
Every buffer is sized when the stream opens. Parameters cross from the UI thread through a block
of `std::atomic<float>` read once per block. Input and output are joined by a lock-free
single-producer/single-consumer ring buffer, with the output callback driving, so neither stream
can block the other. Denormals are flushed at callback entry — a decaying reverb tail generates
them constantly and they cost hundreds of cycles each.

### Latency, honestly

Measured algorithmic latency of the live chain is **11.4 ms**. End to end, including the device's
own input and output buffers, expect roughly 25–35 ms on hardware that grants AAudio exclusive
mode, and 40–60 ms on mid-range devices that refuse it.

The badge in the corner shows the **measured** figure — input latency + FIFO fill + algorithmic
lookahead + output latency — not the target. It turns amber past 40 ms and red past 70 ms.

---

## Presets

גבר עמוק · אישה · ילד · רובוט · שד · חייזר · טלפון ישן · לחישה

Every one is editable, and every edit can be saved as a custom preset. Built-ins are code rather
than stored data, so they can be retuned in a future version without migrating anyone's storage,
and editing one always produces a copy instead of mutating it. Custom presets live in DataStore.

Note that the feminine and child presets move formants by *less* than pitch. Moving them in
lockstep is exactly what produces the cartoon chipmunk; a unit test enforces this.

---

## Building

```bash
./gradlew assembleRelease
```

Requires the Android SDK with platform 35, build-tools 35.0.0, NDK 27.2.12479018 and CMake
3.22.1. Point at it with `local.properties` (`sdk.dir=/path/to/sdk`) or `$ANDROID_HOME`.

Release signing reads `keystore.properties` if present — copy `keystore.properties.example` and
fill it in. Without one, the build falls back to the debug keystore so `assembleRelease` still
produces an installable APK rather than an unsigned one.

This produces one APK per ABI rather than a single fat one, because `libjingle_peerconnection_so.so`
dominates the size and shipping three copies means every device downloads two it can never run:

| Output | Size |
|---|---|
| `app-arm64-v8a-release.apk` | 14.7 MB — every modern phone |
| `app-armeabi-v7a-release.apk` | 9.2 MB — older 32-bit devices |
| `app-x86_64-release.apk` | 18.5 MB — emulators |

Each split carries its own `versionCode` (`2001`, `1001`, `3001`) so a store can tell them apart
and prefer 64-bit on a device that could run either. For a single APK that installs anywhere:

```bash
./gradlew assembleRelease -PuniversalApk    # ~39 MB
```

## Tests

```bash
./tools/run_dsp_tests.sh          # 55 DSP checks, host compiler, no device needed
./gradlew testReleaseUnitTest     # 11 JVM tests
cd server && npm install && npm run smoke   # 13 signaling protocol checks
```

The DSP tests assert the properties the product depends on, not just that the code runs: a
120 Hz source shifted +12 semitones reads 240.0 Hz while its formant stays at 843 Hz — where a
resampler would have dragged it to 1600 — and a ±7 semitone formant shift moves the spectral
envelope with the fundamental pinned at 120.0 Hz. They also check that the chain stays bounded
with every parameter at maximum, that it does not self-oscillate on silence, and that the ring
buffer survives 400k samples across two threads.

**Not verified here:** there is no Android device or emulator with working audio in the
environment this was built in. Measured end-to-end latency, headphone routing, the WhatsApp
hand-off and a real two-phone call have not been exercised on hardware.

## Internal calls need a server

Mode 2 needs a signaling server so two phones can find each other. One is included:

```bash
cd server && npm install && npm start
```

See [`server/README.md`](server/README.md) for Docker and hosting. Then put the `wss://` address
into the app's Settings screen. Until you do, mode 2 says so plainly instead of failing at
connect time. The other three modes need no server.

Audio never passes through that server. It relays the SDP offer/answer and ICE candidates, the
two phones connect directly, and nothing is stored.

### How the substitution works

`JavaAudioDeviceModule.AudioBufferCallback` hands us WebRTC's capture buffer — a direct
`ByteBuffer` of 16-bit PCM — before the Opus encoder touches it. We rewrite it in place through
the native engine, so everything downstream sees only the processed signal. Hardware AEC and
noise suppression are disabled, because both are tuned for an unmodified human voice and fight
the pitch shift.

This works because that capture stream belongs to this app. It is not a loophole that
generalises: there is no equivalent hook for another app's microphone.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | The microphone is the product |
| `FOREGROUND_SERVICE` + `..._MICROPHONE` | Android silences capture the moment the app leaves the foreground |
| `BLUETOOTH_CONNECT` | Identify and route to a connected headset |
| `POST_NOTIFICATIONS` (33+) | The silent, ongoing service notification |
| `INTERNET` | Internal calls only |

Onboarding explains each one before the system dialog appears, and offers a route into system
settings if a permission is denied permanently.

## Layout

```
app/src/main/cpp/          DSP engine, Oboe engines, JNI bridge
  core/                    lock-free ring buffer, atomic params, meters
  dsp/                     FFT, WSOLA, LPC, formant warping, phase vocoder, effects
  engine/                  LiveEngine (Oboe), InjectEngine (WebRTC), OfflineEngine
  test/                    host-side DSP tests
app/src/main/java/il/kolan/
  audio/  data/  service/  call/  message/  ui/  util/
server/                    Node.js WebSocket signaling server
tools/run_dsp_tests.sh
```

## Licence

The Freeverb topology follows Jezar at Dreampoint's public-domain design. Oboe is Apache 2.0,
and the WebRTC binaries are BSD.
