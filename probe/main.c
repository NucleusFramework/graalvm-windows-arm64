// Runs the probe's native image
#include <stdio.h>
#include "hello.h"

int main(void) {
    graal_isolate_t *isolate = NULL;
    graal_isolatethread_t *thread = NULL;
    if (graal_create_isolate(NULL, &isolate, &thread) != 0) {
        fprintf(stderr, "hello: no isolate\n");
        return 1;
    }
    return hello_run(thread);
}
