// jdex native->Java INSTANCE-callback test fixture. Class com/jdex/crypto/InstNative.
// The native method is an INSTANCE method: it reads its key from an instance Java method
// `String key()` on `this` via CallObjectMethod, exercising receiver identity across the
// jdex<->native boundary. XOR with the returned key.
// Build: $NDK/aarch64-linux-android21-clang   -shared -fPIC -O2 -o libjniinst_arm64-v8a.so libjniinst.c
//        $NDK/x86_64-linux-android21-clang     -shared -fPIC -O2 -o libjniinst_x86_64.so   libjniinst.c
#include <jni.h>
#include <stdlib.h>
#include <string.h>

static jstring xor_with(JNIEnv *env, jbyte *b, jsize n, const char *key, jsize klen) {
    char *out = (char *) malloc((size_t) n + 1);
    for (jsize i = 0; i < n; i++) out[i] = (char) (b[i] ^ key[i % klen]);
    out[n] = 0;
    jstring r = (*env)->NewStringUTF(env, out);
    free(out);
    return r;
}

JNIEXPORT jstring JNICALL
getKeyedValue(JNIEnv *env, jobject thiz, jbyteArray data) {
    jclass cls = (*env)->GetObjectClass(env, thiz);
    jmethodID mid = (*env)->GetMethodID(env, cls, "key", "()Ljava/lang/String;");
    jstring jkey = (jstring) (*env)->CallObjectMethod(env, thiz, mid);
    const char *k = (*env)->GetStringUTFChars(env, jkey, NULL);
    jsize n = (*env)->GetArrayLength(env, data);
    jbyte *b = (*env)->GetByteArrayElements(env, data, NULL);
    jstring r = xor_with(env, b, n, k, (jsize) strlen(k));
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, jkey, k);
    return r;
}

static const JNINativeMethod kMethods[] = {
    {"getKeyedValue", "([B)Ljava/lang/String;", (void *) getKeyedValue},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;
    jclass c = (*env)->FindClass(env, "com/jdex/crypto/InstNative");
    (*env)->RegisterNatives(env, c, kMethods, 1);
    return JNI_VERSION_1_6;
}
