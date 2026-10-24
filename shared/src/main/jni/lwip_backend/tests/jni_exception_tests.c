#include "diplay_prototype.h"
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(condition)                                                                          \
    do {                                                                                          \
        if (!(condition)) {                                                                       \
            fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition);                      \
            exit(1);                                                                              \
        }                                                                                         \
    } while (0)

#define JNI_NAME(name) Java_com_shilapi_xcertplay_network_backend_lwip_LwipNativeBindings_##name

typedef struct _jobject {
    jsize length;
    int direct;
    jbyte bytes[32];
    jlong longs[16];
    jint ints[4];
} fake_array;

typedef struct {
    int exception_pending;
    int fail_next_long_write;
    int array_length_calls;
    int fail_array_length_at;
    int byte_read_calls;
    int fail_byte_read_at;
    int byte_write_calls;
    int fail_byte_write_at;
    int int_write_calls;
    int fail_int_write_at;
    int new_long_array_calls;
    int fail_next_new_long_array;
    int direct_address_calls;
    int direct_capacity_calls;
    int jni_calls;
    int jni_calls_while_exception_pending;
} fake_jni_state;

static fake_jni_state *active_state;
static fake_array stats_array_storage;

static void note_jni_access(void) {
    active_state->jni_calls++;
    if (active_state->exception_pending) active_state->jni_calls_while_exception_pending++;
}

static jsize fake_get_array_length(JNIEnv *env, jarray array) {
    (void)env;
    note_jni_access();
    active_state->array_length_calls++;
    if (active_state->exception_pending) return 0;
    if (active_state->array_length_calls == active_state->fail_array_length_at) {
        active_state->exception_pending = 1;
        return 0;
    }
    return ((fake_array *)array)->length;
}

static void fake_get_byte_array_region(JNIEnv *env, jbyteArray array, jsize start, jsize length,
                                       jbyte *target) {
    (void)env;
    note_jni_access();
    if (active_state->exception_pending) return;
    active_state->byte_read_calls++;
    if (active_state->byte_read_calls == active_state->fail_byte_read_at) {
        active_state->exception_pending = 1;
        return;
    }
    memcpy(target, ((fake_array *)array)->bytes + start, (size_t)length);
}

static void fake_set_byte_array_region(JNIEnv *env, jbyteArray array, jsize start, jsize length,
                                       const jbyte *values) {
    (void)env;
    note_jni_access();
    active_state->byte_write_calls++;
    if (active_state->exception_pending) return;
    if (active_state->byte_write_calls == active_state->fail_byte_write_at) {
        active_state->exception_pending = 1;
        return;
    }
    memcpy(((fake_array *)array)->bytes + start, values, (size_t)length);
}

static void fake_set_int_array_region(JNIEnv *env, jintArray array, jsize start, jsize length,
                                      const jint *values) {
    (void)env;
    note_jni_access();
    active_state->int_write_calls++;
    if (active_state->exception_pending) return;
    if (active_state->int_write_calls == active_state->fail_int_write_at) {
        active_state->exception_pending = 1;
        return;
    }
    memcpy(((fake_array *)array)->ints + start, values, (size_t)length * sizeof(*values));
}

static void fake_set_long_array_region(JNIEnv *env, jlongArray array, jsize start, jsize length,
                                       const jlong *values) {
    (void)env;
    note_jni_access();
    if (active_state->exception_pending) return;
    if (active_state->fail_next_long_write) {
        active_state->fail_next_long_write = 0;
        active_state->exception_pending = 1;
        return;
    }
    memcpy(((fake_array *)array)->longs + start, values, (size_t)length * sizeof(*values));
}

static jlongArray fake_new_long_array(JNIEnv *env, jsize length) {
    (void)env;
    note_jni_access();
    active_state->new_long_array_calls++;
    if (active_state->exception_pending) return NULL;
    if (active_state->fail_next_new_long_array) {
        active_state->fail_next_new_long_array = 0;
        active_state->exception_pending = 1;
        return NULL;
    }
    memset(&stats_array_storage, 0, sizeof(stats_array_storage));
    stats_array_storage.length = length;
    return (jlongArray)&stats_array_storage;
}

static jboolean fake_exception_check(JNIEnv *env) {
    (void)env;
    return active_state->exception_pending ? JNI_TRUE : JNI_FALSE;
}

static jboolean fake_is_same_object(JNIEnv *env, jobject first, jobject second) {
    (void)env;
    note_jni_access();
    return first == second ? JNI_TRUE : JNI_FALSE;
}

static void *fake_get_direct_buffer_address(JNIEnv *env, jobject buffer) {
    (void)env;
    note_jni_access();
    active_state->direct_address_calls++;
    if (active_state->exception_pending || !buffer || !((fake_array *)buffer)->direct) return NULL;
    return ((fake_array *)buffer)->bytes;
}

static jlong fake_get_direct_buffer_capacity(JNIEnv *env, jobject buffer) {
    (void)env;
    note_jni_access();
    active_state->direct_capacity_calls++;
    if (active_state->exception_pending || !buffer || !((fake_array *)buffer)->direct) return -1;
    return ((fake_array *)buffer)->length;
}

static const JNINativeInterface_ fake_functions = {
    .GetArrayLength = fake_get_array_length,
    .GetByteArrayRegion = fake_get_byte_array_region,
    .SetByteArrayRegion = fake_set_byte_array_region,
    .SetLongArrayRegion = fake_set_long_array_region,
    .NewLongArray = fake_new_long_array,
    .SetIntArrayRegion = fake_set_int_array_region,
    .GetDirectBufferAddress = fake_get_direct_buffer_address,
    .GetDirectBufferCapacity = fake_get_direct_buffer_capacity,
    .ExceptionCheck = fake_exception_check,
    .IsSameObject = fake_is_same_object,
};

static JNIEnv fake_environment = &fake_functions;

extern jint JNI_NAME(udpBind)(JNIEnv *env, jobject self, jlong context, jint port, jlongArray result);
extern jint JNI_NAME(udpSend)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                              jbyteArray destination, jint port, jbyteArray payload,
                              jint offset, jint length);
extern jint JNI_NAME(udpSendDirect)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                                    jbyteArray destination, jint port, jobject payload, jint length);
extern jint JNI_NAME(udpReceive)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                                jbyteArray target, jbyteArray source, jintArray metadata);
extern jint JNI_NAME(udpReceiveDirect)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                                       jobject target, jint length, jbyteArray source, jintArray metadata);
extern jint JNI_NAME(udpClose)(JNIEnv *env, jobject self, jlong context, jlong endpoint);
extern jint JNI_NAME(tcpListen)(JNIEnv *env, jobject self, jlong context, jint port, jint backlog,
                               jlongArray result);
extern jint JNI_NAME(tcpListenWithOptions)(JNIEnv *env, jobject self, jlong context, jint port,
                                          jint backlog, jboolean reuse, jlongArray result);
extern jint JNI_NAME(tcpListenerClose)(JNIEnv *env, jobject self, jlong context, jlong endpoint);
extern jint JNI_NAME(tcpConnect)(JNIEnv *env, jobject self, jlong context, jbyteArray destination,
                                jint port, jlongArray result);
extern jint JNI_NAME(tcpClose)(JNIEnv *env, jobject self, jlong context, jlong endpoint);
extern jint JNI_NAME(tcpWrite)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                               jbyteArray bytes, jint offset, jint length, jintArray count);
extern jint JNI_NAME(tcpWriteDirect)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                                     jobject bytes, jint length, jintArray count);
extern jint JNI_NAME(tcpRead)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                              jbyteArray bytes, jint offset, jint length, jintArray count);
extern jint JNI_NAME(tcpReadDirect)(JNIEnv *env, jobject self, jlong context, jlong endpoint,
                                    jobject bytes, jint length, jintArray count);
extern jint JNI_NAME(tcpAccept)(JNIEnv *env, jobject self, jlong context, jlong listener,
                                jlongArray result, jbyteArray local, jbyteArray remote, jintArray ports);
extern jlong JNI_NAME(create)(JNIEnv *env, jobject self, jbyteArray mac, jbyteArray ip, jint mtu,
                              jlong now, jbyteArray entropy);
extern jint JNI_NAME(supply)(JNIEnv *env, jobject self, jlong context, jlong now, jbyteArray entropy);
extern jint JNI_NAME(input)(JNIEnv *env, jobject self, jlong context, jbyteArray frame);
extern jint JNI_NAME(takeOutput)(JNIEnv *env, jobject self, jlong context, jbyteArray target);
extern jlongArray JNI_NAME(stats)(JNIEnv *env, jobject self, jlong context);

static const uint8_t local_mac[6] = {0x02, 0x44, 0x69, 0x50, 0x6c, 0x61};
static const uint8_t peer_mac[6] = {0x02, 0x44, 0x69, 0x50, 0x6c, 0x62};
static const uint8_t local_ip[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
static const uint8_t peer_ip[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2};

static uint16_t read16(const uint8_t *p) { return (uint16_t)((p[0] << 8) | p[1]); }
static uint32_t read32(const uint8_t *p) { return ((uint32_t)read16(p) << 16) | read16(p + 2); }
static void write16(uint8_t *p, uint16_t value) { p[0] = (uint8_t)(value >> 8); p[1] = (uint8_t)value; }
static void write32(uint8_t *p, uint32_t value) {
    write16(p, (uint16_t)(value >> 16));
    write16(p + 2, (uint16_t)value);
}

static uint32_t sum_words(const uint8_t *bytes, size_t length) {
    uint32_t sum = 0;
    while (length >= 2) {
        sum += read16(bytes);
        bytes += 2;
        length -= 2;
    }
    if (length) sum += (uint32_t)bytes[0] << 8;
    return sum;
}

static uint16_t transport_checksum(const uint8_t source[16], const uint8_t destination[16],
                                  const uint8_t *segment, size_t length, uint8_t protocol) {
    uint32_t sum = sum_words(source, 16) + sum_words(destination, 16) +
                   (uint32_t)(length >> 16) + (uint32_t)(length & 0xffff) + protocol +
                   sum_words(segment, length);
    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t)~sum;
}

static void input_ipv6(uint64_t context, const uint8_t ethernet_destination[6],
                       const uint8_t source[16], const uint8_t destination[16],
                       uint8_t protocol, const uint8_t *payload, size_t payload_length) {
    uint8_t frame[1514] = {0};
    CHECK(payload_length <= sizeof(frame) - 54);
    memcpy(frame, ethernet_destination, 6);
    memcpy(frame + 6, peer_mac, 6);
    write16(frame + 12, 0x86dd);
    frame[14] = 0x60;
    write16(frame + 18, (uint16_t)payload_length);
    frame[20] = protocol;
    frame[21] = protocol == 58 ? 255 : 64;
    memcpy(frame + 22, source, 16);
    memcpy(frame + 38, destination, 16);
    memcpy(frame + 54, payload, payload_length);
    CHECK(dp_proto_input(context, frame, 54 + payload_length) == DP_OK);
}

static void advertise_peer(uint64_t context) {
    uint8_t packet[32] = {136, 0};
    packet[4] = 0x60;
    memcpy(packet + 8, peer_ip, 16);
    packet[24] = 2;
    packet[25] = 1;
    memcpy(packet + 26, peer_mac, 6);
    write16(packet + 2, transport_checksum(peer_ip, local_ip, packet, sizeof(packet), 58));
    input_ipv6(context, local_mac, peer_ip, local_ip, 58, packet, sizeof(packet));
}

static void send_tcp(uint64_t context, uint16_t source_port, uint16_t destination_port,
                     uint8_t flags, uint32_t sequence, uint32_t acknowledgement) {
    uint8_t packet[20] = {0};
    write16(packet, source_port);
    write16(packet + 2, destination_port);
    write32(packet + 4, sequence);
    write32(packet + 8, acknowledgement);
    packet[12] = 0x50;
    packet[13] = flags;
    write16(packet + 14, 32768);
    write16(packet + 16, transport_checksum(peer_ip, local_ip, packet, sizeof(packet), 6));
    input_ipv6(context, local_mac, peer_ip, local_ip, 6, packet, sizeof(packet));
}

static void send_udp_payload(uint64_t context, uint16_t source_port, uint16_t destination_port,
                             const uint8_t *payload, size_t payload_length) {
    uint8_t packet[8 + 64] = {0};
    CHECK(payload_length <= sizeof(packet) - 8);
    write16(packet, source_port);
    write16(packet + 2, destination_port);
    write16(packet + 4, (uint16_t)(8 + payload_length));
    if (payload_length) memcpy(packet + 8, payload, payload_length);
    uint16_t checksum = transport_checksum(peer_ip, local_ip, packet, 8 + payload_length, 17);
    write16(packet + 6, checksum ? checksum : 0xffff);
    input_ipv6(context, local_mac, peer_ip, local_ip, 17, packet, 8 + payload_length);
}

static void check_udp_output(uint64_t context, uint16_t destination_port,
                             const uint8_t *expected_payload, size_t expected_length) {
    uint8_t frame[1514];
    size_t length = 0;
    CHECK(dp_proto_take_output(context, frame, sizeof(frame), &length) == DP_OK);
    CHECK(length == 14 + 40 + 8 + expected_length);
    CHECK(read16(frame + 12) == 0x86dd && frame[20] == 17);
    CHECK(read16(frame + 18) == 8 + expected_length);
    CHECK(read16(frame + 56) == destination_port);
    CHECK(read16(frame + 58) == 8 + expected_length);
    if (expected_length) CHECK(memcmp(frame + 62, expected_payload, expected_length) == 0);
    CHECK(dp_proto_take_output(context, frame, sizeof(frame), &length) == DP_OK && length == 0);
}

static void send_tcp_payload(uint64_t context, uint16_t source_port, uint16_t destination_port,
                             uint32_t sequence, uint32_t acknowledgement,
                             const uint8_t *payload, size_t payload_length) {
    uint8_t packet[20 + 64] = {0};
    CHECK(payload_length <= sizeof(packet) - 20);
    write16(packet, source_port);
    write16(packet + 2, destination_port);
    write32(packet + 4, sequence);
    write32(packet + 8, acknowledgement);
    packet[12] = 0x50;
    packet[13] = 0x18;
    write16(packet + 14, 32768);
    if (payload_length) memcpy(packet + 20, payload, payload_length);
    write16(packet + 16, transport_checksum(peer_ip, local_ip, packet, 20 + payload_length, 6));
    input_ipv6(context, local_mac, peer_ip, local_ip, 6, packet, 20 + payload_length);
}

static uint32_t send_syn_and_read_server_sequence(uint64_t context, uint16_t source_port,
                                                 uint32_t client_sequence) {
    send_tcp(context, source_port, 7000, 2, client_sequence, 0);
    uint8_t frame[1514];
    size_t length = 0;
    uint32_t server_sequence = 0;
    int found_syn_ack = 0;
    int needs_neighbor_advertisement = 0;
    do {
        CHECK(dp_proto_take_output(context, frame, sizeof(frame), &length) == DP_OK);
        if (length >= 74 && frame[20] == 6 && (frame[67] & 0x12) == 0x12) {
            server_sequence = read32(frame + 58);
            found_syn_ack = 1;
        }
        if (length >= 86 && frame[20] == 58 && frame[54] == 135 &&
            memcmp(frame + 62, peer_ip, 16) == 0)
            needs_neighbor_advertisement = 1;
    } while (length != 0);
    if (needs_neighbor_advertisement && !found_syn_ack) {
        advertise_peer(context);
        do {
            CHECK(dp_proto_take_output(context, frame, sizeof(frame), &length) == DP_OK);
            if (length >= 74 && frame[20] == 6 && (frame[67] & 0x12) == 0x12) {
                server_sequence = read32(frame + 58);
                found_syn_ack = 1;
            }
        } while (length != 0);
    }
    if (!found_syn_ack) fprintf(stderr, "missing SYN-ACK for peer port %u\n", source_port);
    CHECK(found_syn_ack);
    send_tcp(context, source_port, 7000, 0x10, client_sequence + 1, server_sequence + 1);
    return server_sequence;
}

static void clear_exception(void) { active_state->exception_pending = 0; }

static void check_preexisting_exception(uint64_t context) {
    fake_array mac = {.length = 6};
    fake_array ip = {.length = 16};
    fake_array entropy = {.length = 16};
    fake_array frame = {.length = 64};
    fake_array target = {.length = 1514};

    active_state->exception_pending = 1;
    active_state->jni_calls = 0;
    active_state->jni_calls_while_exception_pending = 0;
    CHECK(JNI_NAME(create)(&fake_environment, NULL, (jbyteArray)&mac, (jbyteArray)&ip, 1500,
                           1, (jbyteArray)&entropy) == DP_INTERNAL);
    CHECK(active_state->jni_calls == 0);
    CHECK(active_state->jni_calls_while_exception_pending == 0);

    active_state->jni_calls = 0;
    CHECK(JNI_NAME(supply)(&fake_environment, NULL, (jlong)context, 1,
                           (jbyteArray)&entropy) == DP_INTERNAL);
    CHECK(active_state->jni_calls == 0);
    CHECK(active_state->jni_calls_while_exception_pending == 0);

    active_state->jni_calls = 0;
    CHECK(JNI_NAME(input)(&fake_environment, NULL, (jlong)context,
                          (jbyteArray)&frame) == DP_INTERNAL);
    CHECK(active_state->jni_calls == 0);
    CHECK(active_state->jni_calls_while_exception_pending == 0);

    active_state->jni_calls = 0;
    CHECK(JNI_NAME(takeOutput)(&fake_environment, NULL, (jlong)context,
                               (jbyteArray)&target) == DP_INTERNAL);
    CHECK(active_state->jni_calls == 0);
    CHECK(active_state->jni_calls_while_exception_pending == 0);

    active_state->jni_calls = 0;
    CHECK(JNI_NAME(stats)(&fake_environment, NULL, (jlong)context) == NULL);
    CHECK(active_state->jni_calls == 0);
    CHECK(active_state->jni_calls_while_exception_pending == 0);

    clear_exception();

    active_state->fail_array_length_at = 1;
    active_state->array_length_calls = 0;
    active_state->jni_calls_while_exception_pending = 0;
    CHECK(JNI_NAME(create)(&fake_environment, NULL, (jbyteArray)&mac, (jbyteArray)&ip, 1500,
                           1, (jbyteArray)&entropy) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    CHECK(active_state->jni_calls_while_exception_pending == 0);

    clear_exception();
    active_state->array_length_calls = 0;
    active_state->jni_calls_while_exception_pending = 0;
    CHECK(JNI_NAME(supply)(&fake_environment, NULL, (jlong)context, 1,
                           (jbyteArray)&entropy) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    CHECK(active_state->jni_calls_while_exception_pending == 0);

    clear_exception();
    active_state->array_length_calls = 0;
    active_state->jni_calls_while_exception_pending = 0;
    CHECK(JNI_NAME(input)(&fake_environment, NULL, (jlong)context,
                          (jbyteArray)&frame) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    CHECK(active_state->jni_calls_while_exception_pending == 0);

    clear_exception();
    active_state->array_length_calls = 0;
    active_state->jni_calls_while_exception_pending = 0;
    CHECK(JNI_NAME(takeOutput)(&fake_environment, NULL, (jlong)context,
                               (jbyteArray)&target) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    CHECK(active_state->jni_calls_while_exception_pending == 0);
    clear_exception();
    active_state->fail_array_length_at = 0;
}

static void check_stats_array_allocation_exception(uint64_t context) {
    active_state->new_long_array_calls = 0;
    active_state->fail_next_new_long_array = 1;
    active_state->jni_calls = 0;
    active_state->jni_calls_while_exception_pending = 0;

    CHECK(JNI_NAME(stats)(&fake_environment, NULL, (jlong)context) == NULL);
    CHECK(active_state->exception_pending);
    CHECK(active_state->new_long_array_calls == 1);
    CHECK(active_state->jni_calls == 1);
    CHECK(active_state->jni_calls_while_exception_pending == 0);

    clear_exception();
    active_state->jni_calls = 0;
    active_state->jni_calls_while_exception_pending = 0;
    jlongArray result = JNI_NAME(stats)(&fake_environment, NULL, (jlong)context);
    CHECK(result == (jlongArray)&stats_array_storage);
    CHECK(!active_state->exception_pending);
    CHECK(active_state->new_long_array_calls == 2);
    CHECK(active_state->jni_calls == 2); /* allocation followed by array publication */
    CHECK(active_state->jni_calls_while_exception_pending == 0);
    CHECK(stats_array_storage.length == 11 && stats_array_storage.longs[0] == DP_OK);
}

static void drain_output(uint64_t context) {
    uint8_t frame[1514];
    size_t length = 0;
    do {
        CHECK(dp_proto_take_output(context, frame, sizeof(frame), &length) == DP_OK);
    } while (length != 0);
}

static void assert_endpoint_counts(uint64_t context, uint32_t tcp_listeners, uint32_t udp_pcbs) {
    dp_proto_stats stats;
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.tcp_listeners == tcp_listeners);
    CHECK(stats.core.udp_pcbs == udp_pcbs);
}

static void check_udp_bind_output_failure(uint64_t context) {
    fake_array result_storage = {.length = 2};
    jlongArray result = (jlongArray)&result_storage;
    active_state->fail_next_long_write = 1;
    CHECK(JNI_NAME(udpBind)(&fake_environment, NULL, (jlong)context, 0, result) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    assert_endpoint_counts(context, 0, 0);

    clear_exception();
    CHECK(JNI_NAME(udpBind)(&fake_environment, NULL, (jlong)context, 0, result) == DP_OK);
    CHECK(result_storage.longs[0] != 0 && result_storage.longs[1] > 0);
    CHECK(JNI_NAME(udpClose)(&fake_environment, NULL, (jlong)context, result_storage.longs[0]) == DP_OK);
    assert_endpoint_counts(context, 0, 0);
}

static void check_tcp_listen_output_failure(uint64_t context, int with_options) {
    fake_array result_storage = {.length = 2};
    jlongArray result = (jlongArray)&result_storage;
    active_state->fail_next_long_write = 1;
    jint status = with_options
                      ? JNI_NAME(tcpListenWithOptions)(&fake_environment, NULL, (jlong)context, 0,
                                                      1, JNI_FALSE, result)
                      : JNI_NAME(tcpListen)(&fake_environment, NULL, (jlong)context, 0, 1, result);
    CHECK(status == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    assert_endpoint_counts(context, 0, 0);

    clear_exception();
    status = with_options
                 ? JNI_NAME(tcpListenWithOptions)(&fake_environment, NULL, (jlong)context, 0,
                                                 1, JNI_FALSE, result)
                 : JNI_NAME(tcpListen)(&fake_environment, NULL, (jlong)context, 0, 1, result);
    CHECK(status == DP_OK);
    CHECK(result_storage.longs[0] != 0 && result_storage.longs[1] > 0);
    CHECK(JNI_NAME(tcpListenerClose)(&fake_environment, NULL, (jlong)context, result_storage.longs[0]) == DP_OK);
    assert_endpoint_counts(context, 0, 0);
}

static void check_tcp_connect_output_failure(uint64_t context) {
    fake_array address_storage = {.length = 16};
    address_storage.bytes[0] = (jbyte)0xfe;
    address_storage.bytes[1] = (jbyte)0x80;
    address_storage.bytes[15] = 2;
    fake_array result_storage = {.length = 1};
    jbyteArray address = (jbyteArray)&address_storage;
    jlongArray result = (jlongArray)&result_storage;

    active_state->fail_next_long_write = 1;
    CHECK(JNI_NAME(tcpConnect)(&fake_environment, NULL, (jlong)context, address, 7000, result) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    dp_proto_stats stats;
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.tcp_active == 0);

    clear_exception();
    CHECK(JNI_NAME(tcpConnect)(&fake_environment, NULL, (jlong)context, address, 7000, result) == DP_OK);
    CHECK(result_storage.longs[0] != 0);
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.tcp_active == 1);
    CHECK(JNI_NAME(tcpClose)(&fake_environment, NULL, (jlong)context, result_storage.longs[0]) == DP_OK);
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.tcp_active == 0);
}

static void check_tcp_connect_input_exception(uint64_t context) {
    fake_array address_storage = {.length = 16};
    memcpy(address_storage.bytes, peer_ip, sizeof(peer_ip));
    fake_array result_storage = {.length = 1};
    jbyteArray address = (jbyteArray)&address_storage;
    jlongArray result = (jlongArray)&result_storage;

    active_state->byte_read_calls = 0;
    active_state->fail_byte_read_at = 1;
    CHECK(JNI_NAME(tcpConnect)(&fake_environment, NULL, (jlong)context, address, 7000, result) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    dp_proto_stats stats;
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.tcp_active == 0);

    clear_exception();
    active_state->fail_byte_read_at = 0;
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.tcp_active == 0 && stats.failure == DP_OK);
}

static void check_udp_send_input_exceptions(uint64_t context) {
    fake_array endpoint_storage = {.length = 2};
    fake_array address_storage = {.length = 16};
    fake_array payload_storage = {.length = 4};
    memcpy(address_storage.bytes, peer_ip, sizeof(peer_ip));
    memcpy(payload_storage.bytes, "data", 4);
    jlongArray endpoint_result = (jlongArray)&endpoint_storage;
    jbyteArray address = (jbyteArray)&address_storage;
    jbyteArray payload = (jbyteArray)&payload_storage;
    CHECK(JNI_NAME(udpBind)(&fake_environment, NULL, (jlong)context, 0, endpoint_result) == DP_OK);
    jlong endpoint = endpoint_storage.longs[0];
    CHECK(endpoint != 0);

    drain_output(context);
    active_state->byte_read_calls = 0;
    active_state->fail_byte_read_at = 1;
    CHECK(JNI_NAME(udpSend)(&fake_environment, NULL, (jlong)context, endpoint,
                            address, 50000, payload, 0, 4) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    dp_proto_stats stats;
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.udp_pcbs == 1 && stats.tx_frames == 0);

    clear_exception();
    active_state->byte_read_calls = 0;
    active_state->fail_byte_read_at = 2;
    CHECK(JNI_NAME(udpSend)(&fake_environment, NULL, (jlong)context, endpoint,
                            address, 50000, payload, 0, 4) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.udp_pcbs == 1 && stats.tx_frames == 0);

    clear_exception();
    active_state->fail_byte_read_at = 0;
    CHECK(JNI_NAME(udpClose)(&fake_environment, NULL, (jlong)context, endpoint) == DP_OK);
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.udp_pcbs == 0 && stats.failure == DP_OK);
}

static void expect_udp_receive_output_exception(uint64_t context, jlong endpoint,
                                                uint16_t local_port, int fail_byte_at,
                                                int fail_int_at, int expected_byte_writes,
                                                int expected_int_writes) {
    static const uint8_t payload[] = {'d', 'a', 't', 'a'};
    send_udp_payload(context, 50000, local_port, payload, sizeof(payload));

    fake_array target_storage = {.length = 16};
    fake_array source_storage = {.length = 16};
    fake_array metadata_storage = {.length = 3};
    active_state->byte_write_calls = 0;
    active_state->fail_byte_write_at = fail_byte_at;
    active_state->int_write_calls = 0;
    active_state->fail_int_write_at = fail_int_at;
    active_state->jni_calls_while_exception_pending = 0;

    CHECK(JNI_NAME(udpReceive)(&fake_environment, NULL, (jlong)context, endpoint,
                               (jbyteArray)&target_storage, (jbyteArray)&source_storage,
                               (jintArray)&metadata_storage) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    CHECK(active_state->byte_write_calls == expected_byte_writes);
    CHECK(active_state->int_write_calls == expected_int_writes);
    CHECK(active_state->jni_calls_while_exception_pending == 0);

    clear_exception();
    active_state->fail_byte_write_at = 0;
    active_state->fail_int_write_at = 0;
    active_state->byte_write_calls = 0;
    active_state->int_write_calls = 0;
    CHECK(JNI_NAME(udpReceive)(&fake_environment, NULL, (jlong)context, endpoint,
                               (jbyteArray)&target_storage, (jbyteArray)&source_storage,
                               (jintArray)&metadata_storage) == DP_WOULD_BLOCK);
    CHECK(active_state->byte_write_calls == 0 && active_state->int_write_calls == 0);
}

static void check_udp_receive_output_exceptions(uint64_t context) {
    fake_array endpoint_storage = {.length = 2};
    CHECK(JNI_NAME(udpBind)(&fake_environment, NULL, (jlong)context, 0,
                            (jlongArray)&endpoint_storage) == DP_OK);
    jlong endpoint = endpoint_storage.longs[0];
    uint16_t port = (uint16_t)endpoint_storage.longs[1];
    CHECK(endpoint != 0 && port != 0);

    expect_udp_receive_output_exception(context, endpoint, port, 1, 0, 1, 0);
    expect_udp_receive_output_exception(context, endpoint, port, 2, 0, 2, 0);
    expect_udp_receive_output_exception(context, endpoint, port, 0, 1, 2, 1);

    static const uint8_t payload[] = {'d', 'a', 't', 'a'};
    send_udp_payload(context, 50000, port, payload, sizeof(payload));
    fake_array target_storage = {.length = 16};
    fake_array source_storage = {.length = 16};
    fake_array metadata_storage = {.length = 3};
    CHECK(JNI_NAME(udpReceive)(&fake_environment, NULL, (jlong)context, endpoint,
                               (jbyteArray)&target_storage, (jbyteArray)&source_storage,
                               (jintArray)&metadata_storage) == DP_OK);
    CHECK(memcmp(target_storage.bytes, payload, sizeof(payload)) == 0);
    CHECK(memcmp(source_storage.bytes, peer_ip, sizeof(peer_ip)) == 0);
    CHECK(metadata_storage.ints[0] == 50000 && metadata_storage.ints[1] == 4 &&
          metadata_storage.ints[2] == 4);
    CHECK(JNI_NAME(udpClose)(&fake_environment, NULL, (jlong)context, endpoint) == DP_OK);
}

static void check_tcp_accept_output_failure(uint64_t context) {
    fake_array listener_storage = {.length = 2};
    jlongArray listener_result = (jlongArray)&listener_storage;
    CHECK(JNI_NAME(tcpListen)(&fake_environment, NULL, (jlong)context, 7000, 2,
                              listener_result) == DP_OK);
    jlong listener = listener_storage.longs[0];
    CHECK(listener != 0);
    send_syn_and_read_server_sequence(context, 50000, 1000);
    fake_array accepted_storage = {.length = 1};
    fake_array local_storage = {.length = 16};
    fake_array remote_storage = {.length = 16};
    fake_array ports_storage = {.length = 2};
    jlongArray accepted_result = (jlongArray)&accepted_storage;
    active_state->fail_next_long_write = 1;
    CHECK(JNI_NAME(tcpAccept)(&fake_environment, NULL, (jlong)context, listener,
                              accepted_result, (jbyteArray)&local_storage,
                              (jbyteArray)&remote_storage, (jintArray)&ports_storage) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    assert_endpoint_counts(context, 1, 0);
    dp_proto_stats stats;
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.tcp_active == 0);

    clear_exception();
    uint32_t server_sequence = send_syn_and_read_server_sequence(context, 50001, 2000);
    CHECK(JNI_NAME(tcpAccept)(&fake_environment, NULL, (jlong)context, listener,
                              accepted_result, (jbyteArray)&local_storage,
                              (jbyteArray)&remote_storage, (jintArray)&ports_storage) == DP_OK);
    CHECK(accepted_storage.longs[0] != 0);
    CHECK(ports_storage.ints[0] == 7000 && ports_storage.ints[1] == 50001);
    static const uint8_t inbound_payload[] = {'r', 'e', 'a', 'd'};
    send_tcp_payload(context, 50001, 7000, 2001, server_sequence + 1,
                     inbound_payload, sizeof(inbound_payload));
    drain_output(context);
    fake_array read_storage = {.length = 16};
    fake_array read_count_storage = {.length = 1, .ints = {77}};
    active_state->byte_write_calls = 0;
    active_state->fail_byte_write_at = 1;
    active_state->int_write_calls = 0;
    active_state->jni_calls_while_exception_pending = 0;
    CHECK(JNI_NAME(tcpRead)(&fake_environment, NULL, (jlong)context,
                            accepted_storage.longs[0], (jbyteArray)&read_storage,
                            0, sizeof(inbound_payload),
                            (jintArray)&read_count_storage) == DP_INTERNAL);
    CHECK(active_state->exception_pending && read_count_storage.ints[0] == 77);
    CHECK(active_state->byte_write_calls == 1 && active_state->int_write_calls == 0);
    CHECK(active_state->jni_calls_while_exception_pending == 0);
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK && stats.core.tcp_active == 1);
    clear_exception();
    active_state->fail_byte_write_at = 0;
    active_state->byte_write_calls = 0;
    active_state->int_write_calls = 0;
    CHECK(JNI_NAME(tcpRead)(&fake_environment, NULL, (jlong)context,
                            accepted_storage.longs[0], (jbyteArray)&read_storage,
                            0, sizeof(inbound_payload),
                            (jintArray)&read_count_storage) == DP_WOULD_BLOCK);
    CHECK(read_count_storage.ints[0] == 0 && active_state->byte_write_calls == 0);
    CHECK(active_state->int_write_calls == 1);

    fake_array payload_storage = {.length = 4};
    fake_array count_storage = {.length = 1};
    memcpy(payload_storage.bytes, "data", 4);
    jbyteArray payload = (jbyteArray)&payload_storage;
    jintArray count = (jintArray)&count_storage;
    drain_output(context);
    active_state->byte_read_calls = 0;
    active_state->fail_byte_read_at = 1;
    CHECK(JNI_NAME(tcpWrite)(&fake_environment, NULL, (jlong)context,
                             accepted_storage.longs[0], payload, 0, 4, count) == DP_INTERNAL);
    CHECK(active_state->exception_pending);
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.tcp_active == 1 && stats.tx_frames == 0);

    clear_exception();
    active_state->fail_byte_read_at = 0;
    CHECK(JNI_NAME(tcpWrite)(&fake_environment, NULL, (jlong)context,
                             accepted_storage.longs[0], payload, 0, 4, count) == DP_OK);
    CHECK(count_storage.ints[0] == 4);
    CHECK(dp_proto_get_stats(context, &stats) == DP_OK);
    CHECK(stats.core.tcp_active == 1 && stats.tx_frames > 0);
    drain_output(context);
    CHECK(JNI_NAME(tcpClose)(&fake_environment, NULL, (jlong)context,
                             accepted_storage.longs[0]) == DP_OK);
    CHECK(JNI_NAME(tcpListenerClose)(&fake_environment, NULL, (jlong)context, listener) == DP_OK);
    assert_endpoint_counts(context, 0, 0);
}

static void check_udp_direct_buffers(uint64_t context) {
    fake_array endpoint_storage = {.length = 2};
    CHECK(JNI_NAME(udpBind)(&fake_environment, NULL, (jlong)context, 0,
                            (jlongArray)&endpoint_storage) == DP_OK);
    jlong endpoint = endpoint_storage.longs[0];
    uint16_t port = (uint16_t)endpoint_storage.longs[1];
    CHECK(endpoint != 0 && port != 0);

    fake_array address = {.length = 16};
    memcpy(address.bytes, peer_ip, sizeof(peer_ip));
    fake_array payload = {.length = 4, .direct = 1};
    memcpy(payload.bytes, "send", 4);
    CHECK(JNI_NAME(udpSendDirect)(&fake_environment, NULL, (jlong)context, endpoint,
                                  (jbyteArray)&address, 50000, (jobject)&payload, 4) == DP_OK);
    uint8_t frame[1514];
    size_t length = 0;
    CHECK(dp_proto_take_output(context, frame, sizeof(frame), &length) == DP_OK);
    CHECK(length == 86 && frame[20] == 58 && frame[54] == 135);
    CHECK(memcmp(frame + 62, peer_ip, sizeof(peer_ip)) == 0);
    advertise_peer(context);
    check_udp_output(context, 50000, (const uint8_t *)"send", 4);
    fake_array empty_payload = {.length = 1, .direct = 1};
    CHECK(JNI_NAME(udpSendDirect)(&fake_environment, NULL, (jlong)context, endpoint,
                                  (jbyteArray)&address, 50000, (jobject)&empty_payload, 0) == DP_OK);
    check_udp_output(context, 50000, NULL, 0);

    static const uint8_t inbound[] = {'d', 'i', 'r', 'e', 'c', 't'};
    send_udp_payload(context, 51000, port, inbound, sizeof(inbound));
    fake_array target = {.length = 16, .direct = 1};
    fake_array source = {.length = 16};
    fake_array metadata = {.length = 3};
    CHECK(JNI_NAME(udpReceiveDirect)(&fake_environment, NULL, (jlong)context, endpoint,
                                     (jobject)&target, 17, (jbyteArray)&source,
                                     (jintArray)&metadata) == DP_INVALID_ARGUMENT);
    CHECK(JNI_NAME(udpReceiveDirect)(&fake_environment, NULL, (jlong)context, endpoint,
                                     (jobject)&target, 16, (jbyteArray)&source,
                                     (jintArray)&metadata) == DP_OK);
    CHECK(memcmp(target.bytes, inbound, sizeof(inbound)) == 0);
    CHECK(memcmp(source.bytes, peer_ip, sizeof(peer_ip)) == 0);
    CHECK(metadata.ints[0] == 51000 && metadata.ints[1] == 6 && metadata.ints[2] == 6);

    CHECK(JNI_NAME(udpClose)(&fake_environment, NULL, (jlong)context, endpoint) == DP_OK);
}

static void check_udp_direct_receive_exception(uint64_t context) {
    fake_array endpoint_storage = {.length = 2};
    CHECK(JNI_NAME(udpBind)(&fake_environment, NULL, (jlong)context, 0,
                            (jlongArray)&endpoint_storage) == DP_OK);
    jlong endpoint = endpoint_storage.longs[0];
    uint16_t port = (uint16_t)endpoint_storage.longs[1];
    static const uint8_t payload[] = {'n', 'o', 'p', 'e'};
    send_udp_payload(context, 51001, port, payload, sizeof(payload));

    fake_array target = {.length = 16, .direct = 1};
    memset(target.bytes, 0x5a, sizeof(target.bytes));
    fake_array source = {.length = 16};
    fake_array metadata = {.length = 3, .ints = {71, 72, 73}};
    active_state->fail_int_write_at = active_state->int_write_calls + 1;
    CHECK(JNI_NAME(udpReceiveDirect)(&fake_environment, NULL, (jlong)context, endpoint,
                                     (jobject)&target, 16, (jbyteArray)&source,
                                     (jintArray)&metadata) == DP_INTERNAL);
    CHECK(active_state->exception_pending && metadata.ints[0] == 71 &&
          metadata.ints[1] == 72 && metadata.ints[2] == 73);
    for (size_t i = 0; i < sizeof(target.bytes); i++) CHECK(target.bytes[i] == 0x5a);
    clear_exception();
    CHECK(JNI_NAME(udpReceiveDirect)(&fake_environment, NULL, (jlong)context, endpoint,
                                     (jobject)&target, 16, (jbyteArray)&source,
                                     (jintArray)&metadata) == DP_WOULD_BLOCK);
    CHECK(JNI_NAME(udpClose)(&fake_environment, NULL, (jlong)context, endpoint) == DP_OK);
}

static void check_stale_endpoint_handles(uint64_t context) {
    fake_array udp_endpoint = {.length = 2};
    CHECK(JNI_NAME(udpBind)(&fake_environment, NULL, (jlong)context, 0,
                            (jlongArray)&udp_endpoint) == DP_OK);
    jlong stale_udp = udp_endpoint.longs[0];
    CHECK(JNI_NAME(udpClose)(&fake_environment, NULL, (jlong)context, stale_udp) == DP_OK);
    CHECK(JNI_NAME(udpClose)(&fake_environment, NULL, (jlong)context, stale_udp) == DP_INVALID_ARGUMENT);

    fake_array direct_target = {.length = 16, .direct = 1};
    memset(direct_target.bytes, 0x5a, sizeof(direct_target.bytes));
    fake_array source = {.length = 16};
    fake_array metadata = {.length = 3, .ints = {71, 72, 73}};
    CHECK(JNI_NAME(udpReceiveDirect)(&fake_environment, NULL, (jlong)context, stale_udp,
                                     (jobject)&direct_target, 16, (jbyteArray)&source,
                                     (jintArray)&metadata) == DP_INVALID_ARGUMENT);
    CHECK(metadata.ints[0] == 71 && metadata.ints[1] == 72 && metadata.ints[2] == 73);
    for (size_t i = 0; i < sizeof(direct_target.bytes); i++) CHECK(direct_target.bytes[i] == 0x5a);

    fake_array tcp_listener = {.length = 2};
    CHECK(JNI_NAME(tcpListen)(&fake_environment, NULL, (jlong)context, 0, 1,
                              (jlongArray)&tcp_listener) == DP_OK);
    jlong stale_listener = tcp_listener.longs[0];
    CHECK(JNI_NAME(tcpListenerClose)(&fake_environment, NULL, (jlong)context,
                                     stale_listener) == DP_OK);
    CHECK(JNI_NAME(tcpListenerClose)(&fake_environment, NULL, (jlong)context,
                                     stale_listener) == DP_INVALID_ARGUMENT);
    CHECK(JNI_NAME(tcpClose)(&fake_environment, NULL, (jlong)context,
                             stale_listener) == DP_INVALID_ARGUMENT);

    fake_array accepted = {.length = 1, .longs = {123}};
    fake_array local = {.length = 16};
    fake_array remote = {.length = 16};
    fake_array ports = {.length = 2, .ints = {81, 82}};
    memset(local.bytes, 0x5a, sizeof(local.bytes));
    memset(remote.bytes, 0x5a, sizeof(remote.bytes));
    CHECK(JNI_NAME(tcpAccept)(&fake_environment, NULL, (jlong)context, stale_listener,
                              (jlongArray)&accepted, (jbyteArray)&local,
                              (jbyteArray)&remote, (jintArray)&ports) == DP_INVALID_ARGUMENT);
    CHECK(accepted.longs[0] == 123 && ports.ints[0] == 81 && ports.ints[1] == 82);
    for (size_t i = 0; i < sizeof(local.bytes); i++) {
        CHECK(local.bytes[i] == 0x5a);
        CHECK(remote.bytes[i] == 0x5a);
    }
}

static void check_tcp_direct_buffers(uint64_t context) {
    fake_array listener_storage = {.length = 2};
    CHECK(JNI_NAME(tcpListen)(&fake_environment, NULL, (jlong)context, 7000, 2,
                              (jlongArray)&listener_storage) == DP_OK);
    jlong listener = listener_storage.longs[0];
    uint32_t server_sequence = send_syn_and_read_server_sequence(context, 50002, 3000);
    fake_array accepted_storage = {.length = 1};
    fake_array local = {.length = 16};
    fake_array remote = {.length = 16};
    fake_array ports = {.length = 2};
    jint accept_status = JNI_NAME(tcpAccept)(&fake_environment, NULL, (jlong)context, listener,
                                              (jlongArray)&accepted_storage, (jbyteArray)&local,
                                              (jbyteArray)&remote, (jintArray)&ports);
    CHECK(accept_status == DP_OK);
    jlong stream = accepted_storage.longs[0];
    static const uint8_t inbound[] = {'r', 'e', 'a', 'd'};
    send_tcp_payload(context, 50002, 7000, 3001, server_sequence + 1, inbound, sizeof(inbound));
    CHECK(dp_proto_poll(context) == DP_OK);
    drain_output(context);

    fake_array non_direct = {.length = 16};
    fake_array count = {.length = 1, .ints = {77}};
    CHECK(JNI_NAME(tcpReadDirect)(&fake_environment, NULL, (jlong)context, stream,
                                  (jobject)&non_direct, 16, (jintArray)&count) == DP_INVALID_ARGUMENT);
    CHECK(count.ints[0] == 77);
    fake_array read_buffer = {.length = 16, .direct = 1};
    CHECK(JNI_NAME(tcpReadDirect)(&fake_environment, NULL, (jlong)context, stream,
                                  (jobject)&read_buffer, 16, (jintArray)&count) == DP_OK);
    CHECK(count.ints[0] == 4 && memcmp(read_buffer.bytes, inbound, sizeof(inbound)) == 0);

    static const uint8_t exception_payload[] = {'l', 'o', 's', 't'};
    send_tcp_payload(context, 50002, 7000, 3005, server_sequence + 5,
                     exception_payload, sizeof(exception_payload));
    CHECK(dp_proto_poll(context) == DP_OK);
    drain_output(context);
    fake_array failed_read_buffer = {.length = 16, .direct = 1};
    memset(failed_read_buffer.bytes, 0x5a, sizeof(failed_read_buffer.bytes));
    count.ints[0] = 77;
    active_state->fail_int_write_at = active_state->int_write_calls + 1;
    CHECK(JNI_NAME(tcpReadDirect)(&fake_environment, NULL, (jlong)context, stream,
                                  (jobject)&failed_read_buffer, 16, (jintArray)&count) == DP_INTERNAL);
    CHECK(active_state->exception_pending && count.ints[0] == 77);
    for (size_t i = 0; i < sizeof(failed_read_buffer.bytes); i++)
        CHECK(failed_read_buffer.bytes[i] == 0x5a);
    clear_exception();
    CHECK(JNI_NAME(tcpReadDirect)(&fake_environment, NULL, (jlong)context, stream,
                                  (jobject)&failed_read_buffer, 16, (jintArray)&count) == DP_WOULD_BLOCK);
    CHECK(count.ints[0] == 0);

    fake_array write_buffer = {.length = 16, .direct = 1};
    memcpy(write_buffer.bytes, "send", 4);
    CHECK(JNI_NAME(tcpWriteDirect)(&fake_environment, NULL, (jlong)context, stream,
                                   (jobject)&write_buffer, 4, (jintArray)&count) == DP_OK);
    CHECK(count.ints[0] == 4);
    uint8_t frame[1514];
    size_t frame_length = 0;
    int found_payload = 0;
    do {
        CHECK(dp_proto_take_output(context, frame, sizeof(frame), &frame_length) == DP_OK);
        if (frame_length >= 74 && frame[20] == 6) {
            size_t tcp_header_length = (size_t)(frame[66] >> 4) * 4;
            size_t ipv6_payload_length = read16(frame + 18);
            if (tcp_header_length <= ipv6_payload_length &&
                ipv6_payload_length - tcp_header_length == 4 &&
                memcmp(frame + 54 + tcp_header_length, "send", 4) == 0) found_payload = 1;
        }
    } while (frame_length != 0);
    CHECK(found_payload);
    CHECK(JNI_NAME(tcpClose)(&fake_environment, NULL, (jlong)context, stream) == DP_OK);
    CHECK(JNI_NAME(tcpListenerClose)(&fake_environment, NULL, (jlong)context, listener) == DP_OK);
}

int main(void) {
    fake_jni_state state = {0};
    active_state = &state;
    uint8_t mac[6] = {0x02, 0x44, 0x69, 0x50, 0x6c, 0x61};
    uint8_t ip[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    uint8_t entropy[16] = {0x1, 0x3, 0x5, 0x7, 0x9, 0xb, 0xd, 0xf,
                           0x2, 0x4, 0x6, 0x8, 0xa, 0xc, 0xe, 0x10};
    uint64_t context = 0;
    CHECK(dp_proto_create(mac, ip, 1500, 1, entropy, sizeof(entropy), &context) == DP_OK);

    check_preexisting_exception(context);
    check_stats_array_allocation_exception(context);
    check_udp_bind_output_failure(context);
    check_tcp_listen_output_failure(context, 0);
    check_tcp_listen_output_failure(context, 1);
    CHECK(dp_proto_set_link_ready(context, 1) == DP_OK);
    for (uint64_t tick = 0; tick < 16; ++tick) {
        CHECK(dp_proto_supply(context, 350 + tick * 250, NULL, 0) == DP_OK);
        CHECK(dp_proto_poll(context) == DP_OK);
    }
    drain_output(context);
    check_tcp_connect_input_exception(context);
    drain_output(context);
    check_udp_send_input_exceptions(context);
    check_udp_receive_output_exceptions(context);
    check_udp_direct_buffers(context);
    check_tcp_accept_output_failure(context);
    check_tcp_connect_output_failure(context);
    check_tcp_direct_buffers(context);
    check_udp_direct_receive_exception(context);
    check_stale_endpoint_handles(context);

    CHECK(dp_proto_destroy(&context) == DP_OK);
    CHECK(context == 0);
    return 0;
}
