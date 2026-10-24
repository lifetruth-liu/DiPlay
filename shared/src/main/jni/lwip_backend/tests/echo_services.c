#include "echo_services.h"
int dp_prototype_echo_start(void *unused);
int echo_services_start(void *unused) { return dp_prototype_echo_start(unused); }
