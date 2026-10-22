#include "prototype_internal.h"
#include <jni.h>
#define JNI_NAME(name) Java_com_shilapi_xcertplay_network_backend_lwip_LwipNativeBindings_##name
static dp_result shape(JNIEnv *env, jarray array, jsize expected) {
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    if (!array) return DP_INVALID_ARGUMENT;
    jsize size = (*env)->GetArrayLength(env, array);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    return size == expected ? DP_OK : DP_INVALID_ARGUMENT;
}
static dp_result info_shape(JNIEnv *env, jbyteArray local, jbyteArray remote, jintArray ports) {
    dp_result s = shape(env, local, 16);
    if (s == DP_OK) s = shape(env, remote, 16);
    if (s == DP_OK) s = shape(env, ports, 2);
    if (s != DP_OK) return s;
    jboolean aliases = (*env)->IsSameObject(env, local, remote);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    return aliases ? DP_INVALID_ARGUMENT : DP_OK;
}
static dp_result publish_info(JNIEnv *env, const dp_tcp_info *info, jbyteArray local, jbyteArray remote, jintArray ports) {
    (*env)->SetByteArrayRegion(env, local, 0, 16, (const jbyte *)info->local);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    (*env)->SetByteArrayRegion(env, remote, 0, 16, (const jbyte *)info->remote);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    jint values[2] = {info->local_port, info->remote_port};
    (*env)->SetIntArrayRegion(env, ports, 0, 2, values);
    return (*env)->ExceptionCheck(env) ? DP_INTERNAL : DP_OK;
}
JNIEXPORT jint JNICALL JNI_NAME(tcpListen)(JNIEnv *env, jobject self, jlong context, jint port, jint backlog, jlongArray result) {
    (void)self;
    dp_result s = shape(env, result, 2);
    if (s != DP_OK) return s;
    if (port < 0 || port > 65535 || backlog < 1 || (unsigned)backlog > DP_TCP_MAX_BACKLOG) return DP_INVALID_ARGUMENT;
    uint64_t endpoint = 0; uint16_t actual = 0;
    s = dp_proto_tcp_listen((uint64_t)context, (uint16_t)port, (unsigned)backlog, &endpoint, &actual);
    if (s != DP_OK) return s;
    jlong values[2] = {(jlong)endpoint, actual};
    (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    if ((*env)->ExceptionCheck(env)) { dp_proto_tcp_listener_close((uint64_t)context, endpoint); return DP_INTERNAL; }
    return DP_OK;
}
JNIEXPORT jint JNICALL JNI_NAME(tcpAccept)(JNIEnv *env, jobject self, jlong context, jlong listener,
                                         jlongArray result, jbyteArray local, jbyteArray remote, jintArray ports) {
    (void)self;
    dp_result s = shape(env, result, 1);
    if (s == DP_OK) s = info_shape(env, local, remote, ports);
    if (s != DP_OK) return s;
    uint64_t endpoint = 0; dp_tcp_info info;
    s = dp_proto_tcp_accept((uint64_t)context, (uint64_t)listener, &endpoint, &info);
    if (s != DP_OK) return s;
    s = publish_info(env, &info, local, remote, ports);
    if (s == DP_OK) {
        jlong value = (jlong)endpoint;
        (*env)->SetLongArrayRegion(env, result, 0, 1, &value);
        if ((*env)->ExceptionCheck(env)) s = DP_INTERNAL;
    }
    if (s != DP_OK) dp_proto_tcp_close((uint64_t)context, endpoint);
    return s;
}
JNIEXPORT jint JNICALL JNI_NAME(tcpConnect)(JNIEnv *env, jobject self, jlong context,
                                          jbyteArray destination, jint port, jlongArray result) {
    (void)self;
    uint8_t *scratch = NULL;
    dp_result s = dp_proto_copy_buffer((uint64_t)context, &scratch);
    if (s != DP_OK) return s;
    s = shape(env, destination, 16);
    if (s == DP_OK) s = shape(env, result, 1);
    if (s != DP_OK) return s;
    if (port < 1 || port > 65535) return DP_INVALID_ARGUMENT;
    uint8_t address[16];
    (*env)->GetByteArrayRegion(env, destination, 0, 16, (jbyte *)address);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    uint64_t endpoint = 0;
    s = dp_proto_tcp_connect((uint64_t)context, address, (uint16_t)port, &endpoint);
    if (s != DP_OK) return s;
    jlong value = (jlong)endpoint;
    (*env)->SetLongArrayRegion(env, result, 0, 1, &value);
    if ((*env)->ExceptionCheck(env)) { dp_proto_tcp_close((uint64_t)context, endpoint); return DP_INTERNAL; }
    return DP_OK;
}
JNIEXPORT jint JNICALL JNI_NAME(tcpConnectStatus)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                                                jbyteArray local, jbyteArray remote, jintArray ports) {
    (void)self;
    dp_result s = info_shape(env, local, remote, ports);
    if (s != DP_OK) return s;
    dp_tcp_info info;
    s = dp_proto_tcp_connect_status((uint64_t)context, (uint64_t)endpoint, &info);
    return s == DP_OK ? publish_info(env, &info, local, remote, ports) : s;
}
static dp_result range(JNIEnv *env, jbyteArray bytes, jint offset, jint length, jintArray count) {
    dp_result s = shape(env, count, 1);
    if (s != DP_OK) return s;
    if (!bytes || offset < 0 || length < 0) return DP_INVALID_ARGUMENT;
    jsize size = (*env)->GetArrayLength(env, bytes);
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    return length > size || offset > size - length ? DP_INVALID_ARGUMENT : DP_OK;
}
static jint transfer(JNIEnv *env, jlong context, jlong endpoint, jbyteArray bytes,
                     jint offset, jint length, jintArray count, int writing) {
    uint8_t *scratch = NULL;
    dp_result s = dp_proto_copy_buffer((uint64_t)context, &scratch);
    if (s != DP_OK) return s;
    s = range(env, bytes, offset, length, count);
    if (s != DP_OK) return s;
    jsize capacity = (unsigned)length > DP_PROTO_COPY_BYTES ? (jsize)DP_PROTO_COPY_BYTES : length;
    if (writing && capacity) {
        (*env)->GetByteArrayRegion(env, bytes, offset, capacity, (jbyte *)scratch);
        if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    }
    size_t copied = 0;
    s = writing ? dp_proto_tcp_write((uint64_t)context, (uint64_t)endpoint, scratch, (size_t)capacity, &copied) :
                  dp_proto_tcp_read((uint64_t)context, (uint64_t)endpoint, scratch, (size_t)capacity, &copied);
    if (copied > (size_t)capacity) return DP_INTERNAL;
    if (!writing && copied) {
        (*env)->SetByteArrayRegion(env, bytes, offset, (jsize)copied, (const jbyte *)scratch);
        if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    }
    jint value = (jint)copied;
    (*env)->SetIntArrayRegion(env, count, 0, 1, &value);
    return (*env)->ExceptionCheck(env) ? DP_INTERNAL : s;
}
JNIEXPORT jint JNICALL JNI_NAME(tcpRead)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                                       jbyteArray bytes, jint offset, jint length, jintArray count) {
    (void)self; return transfer(env, context, endpoint, bytes, offset, length, count, 0);
}
JNIEXPORT jint JNICALL JNI_NAME(tcpWrite)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                                        jbyteArray bytes, jint offset, jint length, jintArray count) {
    (void)self; return transfer(env, context, endpoint, bytes, offset, length, count, 1);
}
JNIEXPORT jint JNICALL JNI_NAME(tcpShutdownOutput)(JNIEnv *env, jobject self, jlong context, jlong endpoint) {
    (void)self;
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    return dp_proto_tcp_shutdown_output((uint64_t)context, (uint64_t)endpoint);
}
JNIEXPORT jint JNICALL JNI_NAME(tcpClose)(JNIEnv *env, jobject self, jlong context, jlong endpoint) {
    (void)self;
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    return dp_proto_tcp_close((uint64_t)context, (uint64_t)endpoint);
}
JNIEXPORT jint JNICALL JNI_NAME(tcpCloseGraceful)(JNIEnv *env, jobject self, jlong context, jlong endpoint) {
    (void)self;
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    return dp_proto_tcp_close_graceful((uint64_t)context, (uint64_t)endpoint);
}
JNIEXPORT jint JNICALL JNI_NAME(tcpListenerClose)(JNIEnv *env, jobject self, jlong context, jlong endpoint) {
    (void)self;
    if ((*env)->ExceptionCheck(env)) return DP_INTERNAL;
    return dp_proto_tcp_listener_close((uint64_t)context, (uint64_t)endpoint);
}
