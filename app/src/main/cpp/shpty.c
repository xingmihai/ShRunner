/*
 * shpty —— 给命令分配伪终端（PTY）的小工具。
 *
 * 为什么需要它：不少交互式 shell 脚本只在真终端下才阻塞等待输入，
 * 给它们管道的话 read 会立刻失败、拿到空值就直接按「非预期输入」终止
 * （典型表现：打印完提问立刻输出「操作已取消」）。
 * MT 管理器之类的终端 App 自带 PTY 所以能跑，普通 App 没有。
 * 部分 ROM 又不带 script / busybox，因此这里自带一份，不依赖系统环境。
 *
 * 用法：shpty <程序> [参数...]
 * 例：  shpty /system/bin/sh -c 'sh /data/local/tmp/a.sh'
 *
 * 行为：为子进程建立 PTY，把自身的 stdin/stdout 与 PTY 主端双向转发，
 *      子进程结束后返回它的退出码（被信号终止时返回 128+信号号）。
 */

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define BUF_SIZE 4096

/* bionic 的 ptsname_r 在较旧 API 上不可用，这里统一用 ptsname 并即时复制 */
static char *dup_slave_name(int master) {
    char *name = ptsname(master);
    if (name == NULL) return NULL;
    size_t len = strlen(name);
    char *copy = (char *)malloc(len + 1);
    if (copy == NULL) return NULL;
    memcpy(copy, name, len + 1);
    return copy;
}

/* 尽量把终端设置成脚本期待的样式：开启回显、规范模式，尺寸给个常规值 */
static void tune_terminal(int fd) {
    struct termios tio;
    if (tcgetattr(fd, &tio) != 0) return;
    tio.c_iflag |= ICRNL;               /* 回车转换行，脚本读取更稳 */
    tio.c_oflag |= ONLCR;               /* 换行输出为 \r\n，显示正常 */
    tio.c_lflag |= ICANON | ECHO | ECHOE | ECHOK;
    tio.c_cc[VEOF] = 4;
    tio.c_cc[VEOL] = 0;
    tcsetattr(fd, TCSANOW, &tio);

    struct winsize ws;
    ws.ws_row = 40;
    ws.ws_col = 120;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    ioctl(fd, TIOCSWINSZ, &ws);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: shpty <program> [args...]\n");
        return 2;
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        fprintf(stderr, "shpty: posix_openpt failed: %s\n", strerror(errno));
        return 3;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        fprintf(stderr, "shpty: grantpt/unlockpt failed: %s\n", strerror(errno));
        close(master);
        return 3;
    }

    char *slave_name = dup_slave_name(master);
    if (slave_name == NULL) {
        fprintf(stderr, "shpty: ptsname failed: %s\n", strerror(errno));
        close(master);
        return 3;
    }

    int slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        fprintf(stderr, "shpty: open %s failed: %s\n", slave_name, strerror(errno));
        free(slave_name);
        close(master);
        return 3;
    }
    free(slave_name);

    tune_terminal(slave);

    pid_t pid = fork();
    if (pid < 0) {
        fprintf(stderr, "shpty: fork failed: %s\n", strerror(errno));
        close(master);
        close(slave);
        return 3;
    }

    if (pid == 0) {
        /* 子进程：成为会话首进程并让 PTY 成为控制终端，这样脚本的 isatty 为真 */
        close(master);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        execvp(argv[1], &argv[1]);
        fprintf(stderr, "shpty: exec %s failed: %s\n", argv[1], strerror(errno));
        _exit(127);
    }

    /* 父进程：在 PTY 主端与自身标准流之间双向转发 */
    close(slave);

    struct pollfd fds[2];
    char buf[BUF_SIZE];

    int stdin_open = 1;
    int master_open = 1;

    while (master_open) {
        int nfds = 0;

        if (stdin_open) {
            fds[nfds].fd = STDIN_FILENO;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            nfds++;
        }
        fds[nfds].fd = master;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        int master_idx = nfds;
        nfds++;

        int ready = poll(fds, (unsigned int)nfds, -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (stdin_open && (fds[0].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n > 0) {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t w = write(master, buf + off, (size_t)(n - off));
                    if (w <= 0) break;
                    off += w;
                }
            } else {
                /* 输入结束：给子进程发 EOF（^D），而不是直接断开 */
                char eof = 4;
                ssize_t ignore = write(master, &eof, 1);
                (void)ignore;
                stdin_open = 0;
            }
        }

        if (fds[master_idx].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = read(master, buf, sizeof(buf));
            if (n > 0) {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t w = write(STDOUT_FILENO, buf + off, (size_t)(n - off));
                    if (w <= 0) break;
                    off += w;
                }
            } else if (n == 0 || (n < 0 && errno != EINTR)) {
                master_open = 0;
            }
        }
    }

    int status = 0;
    for (;;) {
        pid_t r = waitpid(pid, &status, 0);
        if (r == pid) break;
        if (r < 0 && errno == EINTR) continue;
        status = 0;
        break;
    }

    close(master);

    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}
