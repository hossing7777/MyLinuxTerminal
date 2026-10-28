#include <jni.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <android/log.h>
#include <string.h>
#include <sys/wait.h>

int master_fd = -1;

JNIEXPORT jint JNICALL
Java_com_example_mylinuxterminal_PtyProcess_createPty(
    JNIEnv *env, jobject thiz,
    jstring scriptPath, jstring prootPath, jstring alpinePath,
    jstring tmpDir, jstring loaderPath, jstring libDir, jstring filesDir) {

    const char *c_script = (*env)->GetStringUTFChars(env, scriptPath, 0);
    const char *c_tmp = (*env)->GetStringUTFChars(env, tmpDir, 0);
    const char *c_loader = (*env)->GetStringUTFChars(env, loaderPath, 0);
    const char *c_lib = (*env)->GetStringUTFChars(env, libDir, 0);

    master_fd = posix_openpt(O_RDWR | O_NOCTTY);
    if (master_fd == -1) return -1;
    if (grantpt(master_fd) == -1) return -1;
    if (unlockpt(master_fd) == -1) return -1;

    char *slave_name = ptsname(master_fd);
    if (slave_name == NULL) return -1;

    pid_t child_pid = fork();
    if (child_pid == 0) {
        close(master_fd);
        int slave_fd = open(slave_name, O_RDWR | O_NOCTTY);
        if (slave_fd == -1) _exit(1);

        setsid();
        ioctl(slave_fd, TIOCSCTTY, 0);

        dup2(slave_fd, 0);
        dup2(slave_fd, 1);
        dup2(slave_fd, 2);
        if (slave_fd > 2) close(slave_fd);

        // 👇 تنظیم متغیرهای محیطی (کلید موفقیت)
        setenv("PROOT_TMP_DIR", c_tmp, 1);
        setenv("PROOT_NO_SECCOMP", "1", 1);
        setenv("PROOT_FORCE_FOREIGN_BINARY", "1", 1); // متغیر جادویی سامسونگ
        setenv("PROOT_LOADER", c_loader, 1);
        setenv("LD_LIBRARY_PATH", c_lib, 1);
        setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
        setenv("HOME", "/root", 1);
        setenv("TERM", "xterm-256color", 1);

        // اجرای اسکریپت
        execl("/system/bin/sh", "sh", c_script, NULL);
        _exit(1);
    }

    (*env)->ReleaseStringUTFChars(env, scriptPath, c_script);
    (*env)->ReleaseStringUTFChars(env, tmpDir, c_tmp);
    (*env)->ReleaseStringUTFChars(env, loaderPath, c_loader);
    (*env)->ReleaseStringUTFChars(env, libDir, c_lib);
    return master_fd;
}

JNIEXPORT jint JNICALL
Java_com_example_mylinuxterminal_PtyProcess_readData(JNIEnv *env, jobject thiz, jbyteArray buffer) {
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    int len = read(master_fd, buf, (*env)->GetArrayLength(env, buffer));
    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    return len;
}

JNIEXPORT void JNICALL
Java_com_example_mylinuxterminal_PtyProcess_writeData(JNIEnv *env, jobject thiz, jstring data) {
    const char *str = (*env)->GetStringUTFChars(env, data, 0);
    write(master_fd, str, strlen(str));
    (*env)->ReleaseStringUTFChars(env, data, str);
}
