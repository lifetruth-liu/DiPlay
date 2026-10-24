#include "wire_faults.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x)                                                                                   \
    do {                                                                                           \
        if (!(x)) {                                                                                \
            fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #x);                                \
            exit(1);                                                                               \
        }                                                                                          \
    } while (0)
static const unsigned char bytes[14] = {1};
int main(int argc, char **argv) {
    CHECK(argc == 2);
    wf_queue *q = calloc(1, sizeof(*q));
    CHECK(q);
    const unsigned char *out;
    size_t length;
    if (!strcmp(argv[1], "capacity")) {
        for (int i = 0; i < WF_CAPACITY; i++)
            CHECK(wf_push(q, bytes, 14, 0) == 1);
        CHECK(wf_push(q, bytes, 14, 0) == 0);
        CHECK(sizeof(*q) < 512 * 1024);
        for (int i = 0; i < WF_CAPACITY; i++) {
            CHECK(wf_peek(q, 0, &out, &length));
            wf_sent(q);
        }
        CHECK(!q->count && !wf_peek(q, 0, &out, &length));
        for (unsigned i = 0; i < WF_CAPACITY; i++) {
            unsigned char frame[14] = {0};
            frame[0] = (unsigned char)i;
            CHECK(wf_push(q, frame, 14, 0) == 1);
        }
        for (unsigned i = 0; i < 100; i++) {
            CHECK(wf_peek(q, 0, &out, &length) && out[0] == i);
            wf_sent(q);
        }
        for (unsigned i = 0; i < 100; i++) {
            unsigned char frame[14] = {0};
            frame[0] = (unsigned char)i;
            CHECK(wf_push(q, frame, 14, 0) == 1);
        }
        for (unsigned i = 100; i < WF_CAPACITY + 100; i++) {
            CHECK(wf_peek(q, 0, &out, &length) && out[0] == (unsigned char)i);
            wf_sent(q);
        }
        CHECK(!q->count);
    } else if (!strcmp(argv[1], "delay")) {
        q->config.delay_ms = 5;
        unsigned char owned[14] = {7};
        CHECK(wf_push(q, owned, 14, 100) == 1);
        owned[0] = 8;
        CHECK(!wf_peek(q, 104, &out, &length));
        CHECK(wf_wait(q, 104) == 1);
        CHECK(wf_peek(q, 105, &out, &length));
        CHECK(length == 14 && out[0] == 7);
        wf_sent(q);
        CHECK(wf_wait(q, 105) == UINT32_MAX);
    } else if (!strcmp(argv[1], "drop")) {
        q->config.drop_every = 4;
        for (int i = 0; i < 12; i++)
            CHECK(wf_push(q, bytes, 14, 0) == 1);
        CHECK(q->count == 9 && q->dropped == 3 && q->admitted == 12);
    } else if (!strcmp(argv[1], "reorder")) {
        q->config.reorder_every = 3;
        for (unsigned char i = 1; i <= 3; i++) {
            unsigned char frame[14] = {0};
            frame[0] = i;
            CHECK(wf_push(q, frame, 14, 0) == 1);
        }
        unsigned char expected[] = {1, 3, 2};
        for (int i = 0; i < 3; i++) {
            CHECK(wf_peek(q, 0, &out, &length));
            CHECK(out[0] == expected[i]);
            wf_sent(q);
        }
        CHECK(q->reordered == 1);
    } else if (!strcmp(argv[1], "duplicate")) {
        q->config.duplicate_every = 4;
        for (unsigned char i = 1; i <= 4; i++) {
            unsigned char frame[14] = {0};
            frame[0] = i;
            CHECK(wf_push(q, frame, 14, 0) == 1);
        }
        unsigned char expected[] = {1, 2, 2, 3, 4};
        for (int i = 0; i < 5; i++) {
            CHECK(wf_peek(q, 0, &out, &length));
            CHECK(out[0] == expected[i]);
            wf_sent(q);
        }
        CHECK(!q->count && q->duplicates_sent == 1);
    } else if (!strcmp(argv[1], "validation")) {
        CHECK(wf_push(q, NULL, 14, 0) == -1);
        CHECK(wf_push(q, bytes, 13, 0) == -1);
        CHECK(wf_push(q, bytes, 1515, 0) == -1);
        CHECK(!q->count && !q->admitted);
        q->config.delay_ms = 5;
        CHECK(wf_push(q, bytes, 14, UINT64_MAX - 1) == -1);
        CHECK(!q->count && !q->admitted);
    } else
        return 2;
    free(q);
    puts("PASS wire fault queue");
    return 0;
}
