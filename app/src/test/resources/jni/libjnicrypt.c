// jdex native string-decryptor test fixture. Class com/jdex/crypto/Native; XOR with cycling key "KEY".
// Build: $NDK/aarch64-linux-android21-clang  -shared -fPIC -O2 -o libjnicrypt_arm64-v8a.so libjnicrypt.c
//        $NDK/armv7a-linux-androideabi21-clang -shared -fPIC -O2 -o libjnicrypt_armeabi-v7a.so libjnicrypt.c
#include <jni.h>
#include <stdlib.h>
#include <string.h>

static const char KEY[3] = {'K', 'E', 'Y'};

static jstring xor_with(JNIEnv *env, jbyte *b, jsize n, const char *key, jsize klen) {
    char *out = (char *) malloc((size_t) n + 1);
    for (jsize i = 0; i < n; i++) out[i] = (char) (b[i] ^ key[i % klen]);
    out[n] = 0;
    jstring r = (*env)->NewStringUTF(env, out);
    free(out);
    return r;
}

JNIEXPORT jstring JNICALL
decryptBytes(JNIEnv *env, jclass clazz, jbyteArray data) {
    jsize n = (*env)->GetArrayLength(env, data);
    jbyte *b = (*env)->GetByteArrayElements(env, data, NULL);
    jstring r = xor_with(env, b, n, KEY, 3);
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
    return r;
}

JNIEXPORT jstring JNICALL
decryptString(JNIEnv *env, jclass clazz, jstring in) {
    const char *s = (*env)->GetStringUTFChars(env, in, NULL);
    jstring r = xor_with(env, (jbyte *) s, (jsize) strlen(s), KEY, 3);
    (*env)->ReleaseStringUTFChars(env, in, s);
    return r;
}

// Fetches its key from a Java method `static String key()` — needs the dex<->native bridge.
JNIEXPORT jstring JNICALL
decryptWithJavaKey(JNIEnv *env, jclass clazz, jbyteArray data) {
    jmethodID mid = (*env)->GetStaticMethodID(env, clazz, "key", "()Ljava/lang/String;");
    jstring jkey = (jstring) (*env)->CallStaticObjectMethod(env, clazz, mid);
    const char *k = (*env)->GetStringUTFChars(env, jkey, NULL);
    jsize n = (*env)->GetArrayLength(env, data);
    jbyte *b = (*env)->GetByteArrayElements(env, data, NULL);
    jstring r = xor_with(env, b, n, k, (jsize) strlen(k));
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, jkey, k);
    return r;
}

// Plain (non-JNI) export for raw-harness emulation tests: sums n bytes, no JNIEnv.
JNIEXPORT int JNICALL jdex_sum(const unsigned char *buf, int n) {
    int s = 0;
    for (int i = 0; i < n; i++) s += buf[i];
    return s;
}

static const JNINativeMethod kMethods[] = {
    {"decryptBytes", "([B)Ljava/lang/String;", (void *) decryptBytes},
    {"decryptString", "(Ljava/lang/String;)Ljava/lang/String;", (void *) decryptString},
    {"decryptWithJavaKey", "([B)Ljava/lang/String;", (void *) decryptWithJavaKey},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;
    jclass c = (*env)->FindClass(env, "com/jdex/crypto/Native");
    (*env)->RegisterNatives(env, c, kMethods, 3);
    return JNI_VERSION_1_6;
}
