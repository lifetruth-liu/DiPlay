#ifndef DIPLAY_ENDPOINT_SERVICES_H
#define DIPLAY_ENDPOINT_SERVICES_H
#include "diplay_lwip.h"
typedef struct endpoint_services endpoint_services;
endpoint_services *endpoint_services_create(dp_core *);
int endpoint_services_pump(dp_core *, endpoint_services *);
int endpoint_services_verified(const endpoint_services *);
int endpoint_services_destroy(dp_core *, endpoint_services *);
#endif
