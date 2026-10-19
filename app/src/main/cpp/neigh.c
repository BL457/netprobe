#include <jni.h>
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/neighbour.h>

/* Dumps the IPv4 neighbour table over an UNBOUND netlink route socket.
 * bind() is denied to all third-party apps; RTM_GETNEIGH is allowed only to
 * apps in the untrusted_app_30 domain (targetSdkVersion <= 31) on Android 13 and later (spec section 4). */

static const char *state_name(unsigned s) {
    if (s & NUD_REACHABLE) return "REACHABLE";
    if (s & NUD_STALE) return "STALE";
    if (s & NUD_DELAY) return "DELAY";
    if (s & NUD_PROBE) return "PROBE";
    if (s & NUD_PERMANENT) return "PERMANENT";
    if (s & NUD_FAILED) return "FAILED";
    if (s & NUD_INCOMPLETE) return "INCOMPLETE";
    if (s & NUD_NOARP) return "NOARP";
    return "NONE";
}

static jobjectArray err_array(JNIEnv *env, int e) {
    char buf[160];
    snprintf(buf, sizeof buf, "ERR|%d|%s", e, strerror(e));
    jobjectArray arr = (*env)->NewObjectArray(env, 1, (*env)->FindClass(env, "java/lang/String"), NULL);
    (*env)->SetObjectArrayElement(env, arr, 0, (*env)->NewStringUTF(env, buf));
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_northwalessolutions_netprobe_engine_neigh_NeighbourTable_dumpRaw(JNIEnv *env, jobject thiz) {
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (fd < 0) return err_array(env, errno);

    struct { struct nlmsghdr nlh; struct ndmsg ndm; } req;
    memset(&req, 0, sizeof req);
    req.nlh.nlmsg_len = NLMSG_LENGTH(sizeof(struct ndmsg));
    req.nlh.nlmsg_type = RTM_GETNEIGH;
    req.nlh.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    req.nlh.nlmsg_seq = 1;
    req.ndm.ndm_family = AF_INET;

    struct sockaddr_nl kernel = { .nl_family = AF_NETLINK };
    if (sendto(fd, &req, req.nlh.nlmsg_len, 0, (struct sockaddr *)&kernel, sizeof kernel) < 0) {
        int e = errno; close(fd); return err_array(env, e);
    }

    /* Collect lines first, then build the Java array. */
    enum { MAX = 2048, LINE = 64 };
    static char lines[MAX][LINE];
    int n = 0, done = 0;
    char buf[65536];
    while (!done) {
        ssize_t len = recv(fd, buf, sizeof buf, 0);
        if (len < 0) { if (errno == EINTR) continue; int e = errno; close(fd); return err_array(env, e); }
        for (struct nlmsghdr *h = (struct nlmsghdr *)buf; NLMSG_OK(h, (size_t)len); h = NLMSG_NEXT(h, len)) {
            if (h->nlmsg_type == NLMSG_DONE) { done = 1; break; }
            if (h->nlmsg_type == NLMSG_ERROR) {
                struct nlmsgerr *e = (struct nlmsgerr *)NLMSG_DATA(h);
                close(fd); return err_array(env, -e->error);
            }
            if (h->nlmsg_type != RTM_NEWNEIGH) continue;
            struct ndmsg *ndm = (struct ndmsg *)NLMSG_DATA(h);
            if (ndm->ndm_family != AF_INET) continue;
            char ip[INET_ADDRSTRLEN] = ""; char mac[18] = "";
            int alen = h->nlmsg_len - NLMSG_LENGTH(sizeof *ndm);
            for (struct rtattr *a = (struct rtattr *)((char *)ndm + NLMSG_ALIGN(sizeof *ndm)); RTA_OK(a, alen); a = RTA_NEXT(a, alen)) {
                if (a->rta_type == NDA_DST && RTA_PAYLOAD(a) == 4) inet_ntop(AF_INET, RTA_DATA(a), ip, sizeof ip);
                if (a->rta_type == NDA_LLADDR && RTA_PAYLOAD(a) == 6) {
                    unsigned char *m = RTA_DATA(a);
                    snprintf(mac, sizeof mac, "%02x:%02x:%02x:%02x:%02x:%02x", m[0], m[1], m[2], m[3], m[4], m[5]);
                }
            }
            if (ip[0] && n < MAX) { snprintf(lines[n], LINE, "%s|%s|%s", ip, mac, state_name(ndm->ndm_state)); n++; }
        }
    }
    close(fd);
    jobjectArray arr = (*env)->NewObjectArray(env, n, (*env)->FindClass(env, "java/lang/String"), NULL);
    for (int i = 0; i < n; i++) (*env)->SetObjectArrayElement(env, arr, i, (*env)->NewStringUTF(env, lines[i]));
    return arr;
}
