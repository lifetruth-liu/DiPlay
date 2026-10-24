#ifndef DIPLAY_TEST_FAKE_JNI_H
#define DIPLAY_TEST_FAKE_JNI_H

#include <stdint.h>

typedef uint8_t jboolean;
typedef int8_t jbyte;
typedef uint16_t jchar;
typedef int16_t jshort;
typedef float jfloat;
typedef double jdouble;
typedef int32_t jint;
typedef int64_t jlong;
typedef jint jsize;

typedef struct _jobject *jobject;
typedef jobject jclass;
typedef jobject jthrowable;
typedef jobject jstring;
typedef jobject jarray;
typedef jarray jbooleanArray;
typedef jarray jbyteArray;
typedef jarray jcharArray;
typedef jarray jshortArray;
typedef jarray jintArray;
typedef jarray jlongArray;
typedef jarray jfloatArray;
typedef jarray jdoubleArray;
typedef jarray jobjectArray;

typedef struct JNINativeInterface_ JNINativeInterface_;
typedef const JNINativeInterface_ *JNIEnv;

struct JNINativeInterface_ {
    jsize (*GetArrayLength)(JNIEnv *env, jarray array);
    void (*GetByteArrayRegion)(JNIEnv *env, jbyteArray array, jsize start, jsize length, jbyte *buffer);
    void (*SetByteArrayRegion)(JNIEnv *env, jbyteArray array, jsize start, jsize length, const jbyte *buffer);
    void (*SetIntArrayRegion)(JNIEnv *env, jintArray array, jsize start, jsize length, const jint *buffer);
    void (*SetLongArrayRegion)(JNIEnv *env, jlongArray array, jsize start, jsize length, const jlong *buffer);
    jlongArray (*NewLongArray)(JNIEnv *env, jsize length);
    jboolean (*IsSameObject)(JNIEnv *env, jobject left, jobject right);
    void *(*GetDirectBufferAddress)(JNIEnv *env, jobject buffer);
    jlong (*GetDirectBufferCapacity)(JNIEnv *env, jobject buffer);
    jboolean (*ExceptionCheck)(JNIEnv *env);
};

#define JNIEXPORT
#define JNICALL
#define JNI_TRUE ((jboolean)1)
#define JNI_FALSE ((jboolean)0)

#endif
