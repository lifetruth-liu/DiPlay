#include "wire_faults.h"
#include <string.h>
int wf_push(wf_queue *q, const unsigned char *bytes, size_t length, uint64_t now) {
    if (!bytes || length < 14 || length > 1514 || now > UINT64_MAX - q->config.delay_ms)
        return -1;
    if (q->count == WF_CAPACITY)
        return 0;
    uint64_t sequence = ++q->admitted;
    if (q->config.drop_every && sequence % q->config.drop_every == 0) {
        q->dropped++;
        return 1;
    }
    size_t tail = (q->head + q->count) % WF_CAPACITY;
    wf_frame *frame = &q->frames[tail];
    frame->length = length;
    frame->due = now + q->config.delay_ms;
    frame->duplicate = q->config.duplicate_every &&
                       sequence % q->config.duplicate_every == q->config.duplicate_every / 2;
    frame->copies = frame->duplicate ? 2 : 1;
    memcpy(frame->bytes, bytes, length);
    if (q->config.delay_ms)
        q->delayed++;
    if (q->count && q->config.reorder_every && sequence % q->config.reorder_every == 0) {
        size_t previous = (tail + WF_CAPACITY - 1) % WF_CAPACITY;
        wf_frame temporary = q->frames[previous];
        q->frames[previous] = *frame;
        *frame = temporary;
        q->reordered++;
    }
    q->count++;
    return 1;
}
int wf_peek(const wf_queue *q, uint64_t now, const unsigned char **bytes, size_t *length) {
    if (!q->count || q->frames[q->head].due > now)
        return 0;
    *bytes = q->frames[q->head].bytes;
    *length = q->frames[q->head].length;
    return 1;
}
void wf_sent(wf_queue *q) {
    if (!q->count)
        return;
    wf_frame *frame = &q->frames[q->head];
    if (--frame->copies)
        return;
    if (frame->duplicate)
        q->duplicates_sent++;
    q->head = (q->head + 1) % WF_CAPACITY;
    q->count--;
}
uint32_t wf_wait(const wf_queue *q, uint64_t now) {
    if (!q->count)
        return UINT32_MAX;
    uint64_t due = q->frames[q->head].due;
    if (due <= now)
        return 0;
    return due - now > UINT32_MAX ? UINT32_MAX : (uint32_t)(due - now);
}
