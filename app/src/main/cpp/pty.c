#include <jni.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <android/log.h>
#include <string.h>

int master_fd = -1;

JNIEXPORT jint JNICALL
Java_com_example_mylinuxterminal_PtyProcess_createPty(JNIEnv *env, jobject thiz, jstring cmd) {
    const char *cmd_str = (*env)->GetStringUTFChars(env, cmd, 0);
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

        // اجرای امن از طریق شل سیستم اندروید
        execl("/system/bin/sh", "sh", "-c", cmd_str, NULL);
        _exit(1);
    }
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);
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
