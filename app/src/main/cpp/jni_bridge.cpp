// All JNI entry points for libkolan.
//
// Nothing in here is ever called from an Oboe callback. The audio thread talks to the engine
// exclusively through the atomics in ParamBlock and Meters, which is why it can stay free of
// allocations, locks and JNI transitions.

#include <jni.h>

#include <memory>
#include <mutex>
#include <vector>

#include "core/Meters.h"
#include "core/ParamBlock.h"
#include "engine/InjectEngine.h"
#include "engine/LiveEngine.h"
#include "engine/OfflineEngine.h"

using namespace kolan;

namespace {

/**
 * Process-wide engine state.
 *
 * There is exactly one microphone and one preset, and the foreground service guarantees a
 * single owner, so a singleton is the honest model here rather than an artefact of
 * convenience. The mutex only guards construction and lifecycle calls from the UI thread.
 */
struct EngineHolder {
    ParamBlock params;
    Meters meters;
    LiveEngine live{params, meters};
    InjectEngine inject{params, meters};
};

EngineHolder& holder() {
    static EngineHolder instance;
    return instance;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_il_kolan_audio_NativeEngine_nativeStart(JNIEnv* /*env*/,
                                                                       jobject /*thiz*/,
                                                                       jint route,
                                                                       jint preferredDeviceId) {
    const auto outputRoute = route == 1 ? OutputRoute::kSpeaker : OutputRoute::kHeadphones;
    return holder().live.start(outputRoute, preferredDeviceId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_il_kolan_audio_NativeEngine_nativeStop(JNIEnv* /*env*/,
                                                                  jobject /*thiz*/) {
    holder().live.stop();
}

JNIEXPORT jboolean JNICALL Java_il_kolan_audio_NativeEngine_nativeIsRunning(JNIEnv* /*env*/,
                                                                           jobject /*thiz*/) {
    return holder().live.isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_il_kolan_audio_NativeEngine_nativeSetMuted(JNIEnv* /*env*/,
                                                                      jobject /*thiz*/,
                                                                      jboolean muted) {
    holder().live.setMuted(muted == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_il_kolan_audio_NativeEngine_nativeSetParam(JNIEnv* /*env*/,
                                                                      jobject /*thiz*/, jint id,
                                                                      jfloat value) {
    holder().params.set(id, value);
}

JNIEXPORT jfloat JNICALL Java_il_kolan_audio_NativeEngine_nativeGetParam(JNIEnv* /*env*/,
                                                                        jobject /*thiz*/,
                                                                        jint id) {
    return holder().params.get(id);
}

/**
 * Fills a five-element float array with the visualiser and status telemetry:
 * raw peak, processed peak, raw RMS, processed RMS, latency in milliseconds.
 *
 * One call per animation frame beats five separate JNI round trips.
 */
JNIEXPORT void JNICALL Java_il_kolan_audio_NativeEngine_nativeReadMeters(JNIEnv* env,
                                                                        jobject /*thiz*/,
                                                                        jfloatArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 5) return;

    const Meters& meters = holder().meters;
    jfloat values[5];
    values[0] = meters.rawPeak();
    values[1] = meters.processedPeak();
    values[2] = meters.rawRms();
    values[3] = meters.processedRms();
    values[4] = meters.latencyMs();
    env->SetFloatArrayRegion(out, 0, 5, values);
}

JNIEXPORT jint JNICALL Java_il_kolan_audio_NativeEngine_nativeXrunCount(JNIEnv* /*env*/,
                                                                       jobject /*thiz*/) {
    return holder().meters.xruns();
}

/**
 * Rewrites WebRTC's capture buffer in place.
 *
 * `buffer` is the direct ByteBuffer handed to JavaAudioDeviceModule.AudioBufferCallback. Taking
 * its address directly avoids a copy in each direction on every 10 ms block.
 */
JNIEXPORT jboolean JNICALL Java_il_kolan_audio_NativeEngine_nativeProcessCallBuffer(
    JNIEnv* env, jobject /*thiz*/, jobject buffer, jint byteOffset, jint byteCount,
    jint channelCount, jint sampleRate) {
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) return JNI_FALSE;

    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0 || byteOffset < 0 || byteCount <= 0 ||
        static_cast<jlong>(byteOffset) + byteCount > capacity) {
        return JNI_FALSE;
    }

    auto* samples = reinterpret_cast<int16_t*>(base + byteOffset);
    const size_t totalSamples = static_cast<size_t>(byteCount) / sizeof(int16_t);
    const size_t frames = totalSamples / static_cast<size_t>(channelCount > 0 ? channelCount : 1);

    holder().inject.processInterleavedInt16(samples, frames, channelCount, sampleRate);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_il_kolan_audio_NativeEngine_nativeResetCallProcessor(JNIEnv* /*env*/,
                                                                                jobject /*thiz*/) {
    holder().inject.reset();
}

/**
 * Offline processing for voice messages. Takes the whole recording as floats and returns a new
 * array; the caller runs this off the main thread.
 */
JNIEXPORT jfloatArray JNICALL Java_il_kolan_audio_NativeEngine_nativeProcessOffline(
    JNIEnv* env, jobject /*thiz*/, jfloatArray input, jint sampleRate) {
    if (input == nullptr) return nullptr;

    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return env->NewFloatArray(0);

    std::vector<float> samples(static_cast<size_t>(length));
    env->GetFloatArrayRegion(input, 0, length, samples.data());

    const Params params = holder().params.snapshot();
    const std::vector<float> result =
        OfflineEngine::process(samples, static_cast<float>(sampleRate), params);

    jfloatArray output = env->NewFloatArray(static_cast<jsize>(result.size()));
    if (output == nullptr) return nullptr;
    env->SetFloatArrayRegion(output, 0, static_cast<jsize>(result.size()), result.data());
    return output;
}

}  // extern "C"
