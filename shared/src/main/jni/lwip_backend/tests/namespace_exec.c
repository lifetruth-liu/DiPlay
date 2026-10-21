/* Test tool: isolate before allowing any interface configuration. No lwIP dependency. */
#define _GNU_SOURCE
#include <errno.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s command [args...]\n", argv[0]);
        return 2;
    }
    char before[128], after[128];
    ssize_t n = readlink("/proc/self/ns/net", before, sizeof(before) - 1);
    if (n <= 0 || (size_t)n >= sizeof(before) - 1) {
        perror("read parent namespace");
        return 1;
    }
    before[n] = 0;
    if (unshare(CLONE_NEWNET)) {
        perror("unshare network namespace");
        return 1;
    }
    n = readlink("/proc/self/ns/net", after, sizeof(after) - 1);
    if (n <= 0 || (size_t)n >= sizeof(after) - 1) {
        perror("read child namespace");
        return 1;
    }
    after[n] = 0;
    if (!strcmp(before, after)) {
        fprintf(stderr, "Refusing to execute in the parent network namespace\n");
        return 1;
    }
    fprintf(stderr, "isolated network namespace: %s -> %s\n", before, after);
    execvp(argv[1], argv + 1);
    perror("exec isolated command");
    return errno == ENOENT ? 127 : 1;
}
