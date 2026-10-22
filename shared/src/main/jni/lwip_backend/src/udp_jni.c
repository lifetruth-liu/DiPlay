#include "prototype_internal.h"
#include <jni.h>
#define JNI_NAME(name) Java_com_shilapi_xcertplay_network_backend_lwip_LwipNativeBindings_##name
static dp_result array_size(JNIEnv *env, jarray array, jsize *length) {
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    if (!array) return DP_INVALID_ARGUMENT;
    *length = (*env)->GetArrayLength(env, array);
    return (*env)->ExceptionCheck(env) ? DP_INTERNAL : DP_OK;
}
JNIEXPORT jint JNICALL JNI_NAME(udpBind)(JNIEnv *env, jobject self, jlong context, jint port, jlongArray result) {
    (void)self;
    jsize size;
    dp_result s = array_size(env, result, &size);
    if (s != DP_OK) return s;
    if (size != 2 || port < 0 || port > 65535) return DP_INVALID_ARGUMENT;
    uint64_t endpoint = 0;
    uint16_t actual = 0;
    s = dp_proto_udp_bind((uint64_t)context, (uint16_t)port, &endpoint, &actual);
    if (s != DP_OK) return s;
    jlong values[2] = {(jlong)endpoint, actual};
    (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    if ((*env)->ExceptionCheck(env)) {
        dp_proto_udp_close((uint64_t)context, endpoint);
        return DP_INTERNAL;
    }
    return DP_OK;
}
JNIEXPORT jint JNICALL JNI_NAME(udpSend)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                                        jbyteArray destination, jint port, jbyteArray payload, jint offset, jint length) {
    (void)self;
    uint8_t *scratch = NULL, address[16];
    dp_result s = dp_proto_udp_buffer((uint64_t)context, &scratch);
    if (s != DP_OK) return s;
    jsize size;
    s = array_size(env, destination, &size);
    if (s != DP_OK) return s;
    if (size != 16 || port < 1 || port > 65535 || offset < 0 || length < 0 || (unsigned)length > DP_UDP_MAX_PAYLOAD)
        return DP_INVALID_ARGUMENT;
    s = array_size(env, payload, &size);
    if (s != DP_OK) return s;
    if (length > size || offset > size - length) return DP_INVALID_ARGUMENT;
    (*env)->GetByteArrayRegion(env, destination, 0, 16, (jbyte *)address);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    if (length) (*env)->GetByteArrayRegion(env, payload, offset, length, (jbyte *)scratch);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    return dp_proto_udp_send((uint64_t)context, (uint64_t)endpoint, address, (uint16_t)port, scratch, (size_t)length);
}
JNIEXPORT jint JNICALL JNI_NAME(udpReceive)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                                           jbyteArray target, jbyteArray source, jintArray metadata) {
    (void)self;
    uint8_t *scratch = NULL;
    dp_result s = dp_proto_udp_buffer((uint64_t)context, &scratch);
    if (s != DP_OK) return s;
    jsize capacity, size;
    s = array_size(env, target, &capacity);
    if (s != DP_OK) return s;
    s = array_size(env, source, &size);
    if (s != DP_OK) return s;
    if (size != 16) return DP_INVALID_ARGUMENT;
    s = array_size(env, metadata, &size);
    if (s != DP_OK) return s;
    if (size != 3) return DP_INVALID_ARGUMENT;
    jboolean aliases = (*env)->IsSameObject(env, target, source);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    if (aliases) return DP_INVALID_ARGUMENT;
    dp_udp_datagram info;
    s = dp_proto_udp_receive((uint64_t)context, (uint64_t)endpoint, scratch,
                              (unsigned)capacity > DP_UDP_MAX_PAYLOAD ? DP_UDP_MAX_PAYLOAD : (size_t)capacity, &info);
    if (s != DP_OK) return s;
    if (info.copied_length) (*env)->SetByteArrayRegion(env, target, 0, info.copied_length, (jbyte *)scratch);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    (*env)->SetByteArrayRegion(env, source, 0, 16, (jbyte *)info.source);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    jint values[3] = {info.source_port, info.original_length, info.copied_length};
    (*env)->SetIntArrayRegion(env, metadata, 0, 3, values);
    return (*env)->ExceptionCheck(env) ? DP_INTERNAL : DP_OK;
}
JNIEXPORT jint JNICALL JNI_NAME(udpClose)(JNIEnv *env, jobject self, jlong context, jlong endpoint) {
    (void)self;
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    return dp_proto_udp_close((uint64_t)context, (uint64_t)endpoint);
}
