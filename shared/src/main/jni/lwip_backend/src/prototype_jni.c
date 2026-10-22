#include "diplay_prototype.h"
#include <jni.h>

#define JNI_NAME(name) Java_com_shilapi_xcertplay_network_backend_lwip_LwipNativeBindings_##name
#define NO_CONTEXT                                                                                 \
    (void)env;                                                                                     \
    (void)self

static void erase(uint8_t *bytes, size_t length) {
    volatile uint8_t *p = bytes;
    while (length--)
        *p++ = 0;
}
static dp_result copy_exact(JNIEnv *env, jbyteArray source, uint8_t *target, jsize size) {
    if (!source || (*env)->GetArrayLength(env, source) != size)
        return DP_INVALID_ARGUMENT;
    (*env)->GetByteArrayRegion(env, source, 0, size, (jbyte *)target);
    return (*env)->ExceptionCheck(env) ? DP_INTERNAL : DP_OK;
}
static dp_result copy_entropy(JNIEnv *env, jbyteArray source, uint8_t *target, size_t *length) {
    *length = 0;
    if (!source)
        return DP_OK;
    jsize size = (*env)->GetArrayLength(env, source);
    if (size > DP_PROTO_ENTROPY_BYTES || size % 4)
        return DP_INVALID_ARGUMENT;
    if (size)
        (*env)->GetByteArrayRegion(env, source, 0, size, (jbyte *)target);
    if ((*env)->ExceptionCheck(env))
        return DP_INTERNAL;
    *length = (size_t)size;
    return DP_OK;
}
JNIEXPORT jint JNICALL JNI_NAME(abiVersion)(JNIEnv *env, jobject self) {
    NO_CONTEXT;
    return 4;
}
JNIEXPORT jlong JNICALL JNI_NAME(create)(JNIEnv *env, jobject self, jbyteArray mac_array,
                                         jbyteArray ip_array, jint mtu, jlong now,
                                         jbyteArray entropy_array) {
    (void)self;
    if (now < 0 || mtu < 1280 || mtu > 1500)
        return DP_INVALID_ARGUMENT;
    uint8_t mac[6], ip[16], entropy[DP_PROTO_ENTROPY_BYTES] = {0};
    size_t length = 0;
    uint64_t handle = 0;
    dp_result s = copy_exact(env, mac_array, mac, 6);
    if (s != DP_OK)
        goto done;
    s = copy_exact(env, ip_array, ip, 16);
    if (s != DP_OK)
        goto done;
    s = copy_entropy(env, entropy_array, entropy, &length);
    if (s != DP_OK)
        goto done;
    s = dp_proto_create(mac, ip, (uint16_t)mtu, (uint64_t)now, entropy, length, &handle);
done:
    erase(entropy, sizeof(entropy));
    return s == DP_OK ? (jlong)handle : (jlong)s;
}
JNIEXPORT jint JNICALL JNI_NAME(supply)(JNIEnv *env, jobject self, jlong h, jlong now,
                                        jbyteArray entropy_array) {
    (void)self;
    if (now < 0)
        return DP_INVALID_ARGUMENT;
    uint8_t entropy[DP_PROTO_ENTROPY_BYTES] = {0};
    size_t length;
    dp_result s = copy_entropy(env, entropy_array, entropy, &length);
    if (s == DP_OK)
        s = dp_proto_supply((uint64_t)h, (uint64_t)now, entropy, length);
    erase(entropy, sizeof(entropy));
    return s;
}
JNIEXPORT jint JNICALL JNI_NAME(prepareEcho)(JNIEnv *env, jobject self, jlong h) {
    NO_CONTEXT;
    return dp_proto_prepare_echo((uint64_t)h);
}
JNIEXPORT jint JNICALL JNI_NAME(setReady)(JNIEnv *env, jobject self, jlong h, jboolean ready) {
    NO_CONTEXT;
    return dp_proto_set_link_ready((uint64_t)h, ready == JNI_TRUE);
}
JNIEXPORT jint JNICALL JNI_NAME(input)(JNIEnv *env, jobject self, jlong h, jbyteArray source) {
    (void)self;
    if (!source)
        return DP_INVALID_ARGUMENT;
    jsize length = (*env)->GetArrayLength(env, source);
    if (length < 14 || length > 1514)
        return DP_INVALID_ARGUMENT;
    uint8_t bytes[1514];
    (*env)->GetByteArrayRegion(env, source, 0, length, (jbyte *)bytes);
    if ((*env)->ExceptionCheck(env))
        return DP_INTERNAL;
    return dp_proto_input((uint64_t)h, bytes, (size_t)length);
}
JNIEXPORT jint JNICALL JNI_NAME(poll)(JNIEnv *env, jobject self, jlong h) {
    NO_CONTEXT;
    return dp_proto_poll((uint64_t)h);
}
JNIEXPORT jlong JNICALL JNI_NAME(nextTimeout)(JNIEnv *env, jobject self, jlong h) {
    NO_CONTEXT;
    uint32_t timeout;
    dp_result s = dp_proto_next_timeout((uint64_t)h, &timeout);
    return s == DP_OK ? (jlong)timeout : (jlong)s;
}
JNIEXPORT jint JNICALL JNI_NAME(takeOutput)(JNIEnv *env, jobject self, jlong h, jbyteArray target) {
    (void)self;
    if (!target)
        return DP_INVALID_ARGUMENT;
    jsize capacity = (*env)->GetArrayLength(env, target);
    uint8_t bytes[1514];
    size_t length;
    dp_result s = dp_proto_take_output((uint64_t)h, bytes,
                                       capacity > 1514 ? 1514 : (size_t)capacity, &length);
    if (s != DP_OK)
        return s;
    if (length)
        (*env)->SetByteArrayRegion(env, target, 0, (jsize)length, (jbyte *)bytes);
    return (*env)->ExceptionCheck(env) ? DP_INTERNAL : (jint)length;
}
JNIEXPORT jlongArray JNICALL JNI_NAME(stats)(JNIEnv *env, jobject self, jlong h) {
    (void)self;
    dp_proto_stats stats;
    jlong values[11] = {0};
    values[0] = dp_proto_get_stats((uint64_t)h, &stats);
    if (values[0] == DP_OK) {
        values[1] = stats.tx_frames;
        values[2] = stats.entropy_remaining;
        values[3] = stats.configured_bytes;
        values[4] = stats.core.heap_used;
        values[5] = stats.core.pbuf_used;
        values[6] = stats.core.tcp_listeners;
        values[7] = stats.core.udp_pcbs;
        values[8] = stats.failure;
        values[9] = stats.core.tcp_active;
        values[10] = (jlong)stats.tx_rejected;
    }
    jlongArray result = (*env)->NewLongArray(env, 11);
    if (!result)
        return NULL; /* Preserve the pending allocation exception. */
    (*env)->SetLongArrayRegion(env, result, 0, 11, values);
    return (*env)->ExceptionCheck(env) ? NULL : result;
}
JNIEXPORT jint JNICALL JNI_NAME(entropyRemaining)(JNIEnv *env, jobject self, jlong h) {
    NO_CONTEXT;
    return dp_proto_entropy_remaining((uint64_t)h);
}
JNIEXPORT jint JNICALL JNI_NAME(destroy)(JNIEnv *env, jobject self, jlong h) {
    NO_CONTEXT;
    uint64_t handle = (uint64_t)h;
    return dp_proto_destroy(&handle);
}
