LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := diplay_lwip_stack
LOCAL_SRC_FILES := $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LOCAL_PATH)/third_party/lwip/src/core/*.c) $(wildcard $(LOCAL_PATH)/third_party/lwip/src/core/ipv6/*.c)) third_party/lwip/src/netif/ethernet.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/config $(LOCAL_PATH)/port $(LOCAL_PATH)/third_party/lwip/src/include
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_C_INCLUDES)
LOCAL_CFLAGS := -std=c11 -O2 -Wall -Wextra -fvisibility=hidden
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := diplay_lwip
LOCAL_SRC_FILES := src/core.c src/netif.c src/prototype.c src/prototype_echo.c src/prototype_jni.c src/endpoint_registry.c src/udp_endpoint.c src/udp_jni.c src/tcp_endpoint.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include $(LOCAL_PATH)/src
LOCAL_CFLAGS := -std=c11 -O2 -Wall -Wextra -Werror -fvisibility=hidden
LOCAL_STATIC_LIBRARIES := diplay_lwip_stack
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
