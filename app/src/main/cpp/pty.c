#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <android/log.h>
#include <string.h>
#include <errno.h>

#define LOG_TAG "PtyProcess"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int g_master_fd = -1;
static pid_t g_child_pid = -1;

JNIEXPORT jint JNICALL
Java_com_example_mylinuxterminal_PtyProcess_createPty(
        JNIEnv *env, jobject thiz,
        jstring scriptPath, jstring prootPath, jstring alpinePath,
        jstring tmpDir, jstring loaderPath, jstring libDir, jstring filesDir) {

    const char *c_script = (*env)->GetStringUTFChars(env, scriptPath, NULL);
    const char *c_tmp    = (*env)->GetStringUTFChars(env, tmpDir,     NULL);
    const char *c_loader = (*env)->GetStringUTFChars(env, loaderPath, NULL);
    const char *c_libdir = (*env)->GetStringUTFChars(env, libDir,     NULL);

    int fd = posix_openpt(O_RDWR | O_NOCTTY);
    if (fd < 0) { LOGE("posix_openpt: %s", strerror(errno)); goto cleanup; }
    if (grantpt(fd) != 0 || unlockpt(fd) != 0) {
        LOGE("grantpt/unlockpt: %s", strerror(errno));
        close(fd); fd = -1; goto cleanup;
    }
    const char *slave_name = ptsname(fd);
    if (!slave_name) {
        LOGE("ptsname: %s", strerror(errno));
        close(fd); fd = -1; goto cleanup;
    }

    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        close(fd); fd = -1; goto cleanup;
    }

    if (pid == 0) {
        close(fd);
        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(1);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0); dup2(slave, 1); dup2(slave, 2);
        if (slave > 2) close(slave);

        struct termios tio;
        if (tcgetattr(0, &tio) == 0) {
            cfmakeraw(&tio);
            tcsetattr(0, TCSANOW, &tio);
        }

        setenv("PROOT_TMP_DIR",              c_tmp,    1);
        setenv("PROOT_NO_SECCOMP",           "1",      1);
        setenv("PROOT_FORCE_FOREIGN_BINARY", "1",      1);
        setenv("PROOT_LOADER",               c_loader, 1);
        setenv("LD_LIBRARY_PATH",            c_libdir, 1);
        setenv("TERM",    "xterm-256color", 1);
        setenv("HOME",    "/root",          1);
        setenv("LANG",    "C.UTF-8",        1);
        setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);

        execl("/system/bin/sh", "sh", c_script, (char *)NULL);
        _exit(1);
    }

    g_master_fd = fd;
    g_child_pid = pid;
    LOGI("PTY started: fd=%d pid=%d", fd, (int)pid);

cleanup:
    (*env)->ReleaseStringUTFChars(env, scriptPath, c_script);
    (*env)->ReleaseStringUTFChars(env, tmpDir,     c_tmp);
    (*env)->ReleaseStringUTFChars(env, loaderPath, c_loader);
    (*env)->ReleaseStringUTFChars(env, libDir,     c_libdir);
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_example_mylinuxterminal_PtyProcess_readData(
        JNIEnv *env, jobject thiz, jbyteArray buffer) {

    if (g_master_fd < 0) return -1;
    jsize len = (*env)->GetArrayLength(env, buffer);
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);

    ssize_t n = read(g_master_fd, bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);

    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        if (errno == EINTR) return 0;
        LOGE("read: %s", strerror(errno));
        return -1;
    }
    if (n == 0) return -1;
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_com_example_mylinuxterminal_PtyProcess_writeData(
        JNIEnv *env, jobject thiz, jstring data) {

    if (g_master_fd < 0 || !data) return;
    const char *c = (*env)->GetStringUTFChars(env, data, NULL);
    size_t len = strlen(c), off = 0;

    while (off < len) {
        ssize_t n = write(g_master_fd, c + off, len - off);
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) { usleep(2000); continue; }
            LOGE("write: %s", strerror(errno));
            break;
        }
        off += (size_t)n;
    }
    (*env)->ReleaseStringUTFChars(env, data, c);
}
