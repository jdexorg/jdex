// jdex native NewObject test fixture. buildTag() constructs an app object (EmuFix, from the DEX
// source) via NewObject, then calls its instance method tag() via CallObjectMethod. Exercises the
// transparent native-NewObject -> jdex-constructed-object path.
// Build: $NDK/aarch64-linux-android21-clang -shared -fPIC -O2 -o libemunew_arm64-v8a.so libemunew.c
#include <jni.h>

JNIEXPORT jstring JNICALL
buildTag(JNIEnv *env, jclass clazz) {
    jclass c = (*env)->FindClass(env, "EmuFix");
    jmethodID ctor = (*env)->GetMethodID(env, c, "<init>", "(Ljava/lang/String;I)V");
    jstring n = (*env)->NewStringUTF(env, "hi");
    jobject o = (*env)->NewObject(env, c, ctor, n, 5);
    jmethodID m = (*env)->GetMethodID(env, c, "tag", "()Ljava/lang/String;");
    return (jstring) (*env)->CallObjectMethod(env, o, m);
}

static const JNINativeMethod kM[] = {
    {"buildTag", "()Ljava/lang/String;", (void *) buildTag},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *r) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;
    jclass c = (*env)->FindClass(env, "com/jdex/emunew/Trigger");
    (*env)->RegisterNatives(env, c, kM, 1);
    return JNI_VERSION_1_6;
}
