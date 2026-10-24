/* Test-only deterministic wire scheduler; never linked by Android.mk. */
#ifndef DP_WIRE_FAULTS_H
#define DP_WIRE_FAULTS_H
#include <stddef.h>
#include <stdint.h>
#define WF_CAPACITY 256
typedef struct {
    uint32_t drop_every, delay_ms, reorder_every, duplicate_every;
} wf_config;
typedef struct {
    size_t length;
    uint64_t due;
    unsigned copies;
    int duplicate;
    unsigned char bytes[1514];
} wf_frame;
typedef struct {
    wf_config config;
    size_t head, count;
    uint64_t admitted, dropped, reordered, delayed, duplicates_sent;
    wf_frame frames[WF_CAPACITY];
} wf_queue;
/* 1 copied/admitted (including deliberate wire loss), 0 full, -1 invalid. */
int wf_push(wf_queue *, const unsigned char *, size_t, uint64_t now);
int wf_peek(const wf_queue *, uint64_t now, const unsigned char **, size_t *);
void wf_sent(wf_queue *);
uint32_t wf_wait(const wf_queue *, uint64_t now);
#endif
