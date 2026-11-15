/* SPDX-License-Identifier: GPL-3.0-only
 * Read-only /system block image collector. No shell commands or compression.
 * All payload I/O uses a fixed 256 KiB buffer, even for multi-gigabyte images.
 */
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <inttypes.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <limits.h>
#include <mntent.h>
#include <time.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <linux/fs.h>
#include "sha256.h"

#ifdef DUMP_TEST
#define CHUNK (1024ULL * 1024)
#define RESERVE (1024ULL * 1024)
#else
#define CHUNK (64ULL * 1024 * 1024)
#define RESERVE (256ULL * 1024 * 1024)
#endif
static unsigned char buffer[256*1024];
static volatile sig_atomic_t stopping;
static uint64_t total, progress;
static int source=-1, directory=-1, wake_held;
static char device[PATH_MAX], product[256];
static double began, last_status, last_wake;
static const char *phase="STARTING";

static void notify(const char *message) {
#ifndef DUMP_TEST
    /* Best effort OEM toast. Never wait on Android's framework to finish I/O. */
    pid_t pid=fork();
    if(pid==0){
        alarm(15);
        execl("/system/bin/am","am","broadcast","-a","com.hkmc.intent.action.TOAST",
              "-e","com.hkmc.intent.extra.TOAST_MESSAGE",message,
              "--ez","com.hkmc.intent.extra.TOAST_POPUPSHOWING","true",(char *)NULL);
        _exit(127);
    }
#else
    (void)message;
#endif
}

static double seconds(void) { struct timespec t; if(clock_gettime(CLOCK_MONOTONIC,&t))return 0;return t.tv_sec+t.tv_nsec/1e9; }
static void caught(int signum) { (void)signum;stopping=1; }
static void die(const char *message) {
    fprintf(stderr,"%s: %s\n",message,strerror(errno));
    /* Early mount/source failures precede worker status updates. Persist their
     * diagnostic too; closing a file alone leaves USB writeback pending. */
    fflush(stderr);(void)fsync(STDERR_FILENO);exit(1);
}
static int exists(const char *path) { struct stat s;return lstat(path,&s)==0; }
static void sync_directory(void) {
    /* FAT implementations can reject directory fsync. File fsync is mandatory. */
    if(fsync(directory) && errno!=EINVAL && errno!=EOPNOTSUPP)die("sync USB directory");
}
static void write_all(int fd,const void *data,size_t count) {
    const unsigned char *p=data;
    while(count){ssize_t n=write(fd,p,count);if(n<0 && errno==EINTR)continue;if(n<=0)die("write USB (space or media error)");p+=n;count-=(size_t)n;}
}
static void atomic_text(const char *name,const char *value) {
    char temp[128];snprintf(temp,sizeof(temp),"%s.tmp",name);
    int fd=open(temp,O_WRONLY|O_CREAT|O_TRUNC|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)die("open USB status file");
    write_all(fd,value,strlen(value));if(fsync(fd))die("sync USB file");if(close(fd))die("close USB file");
    if(rename(temp,name))die("commit USB file");sync_directory();
}
static void wake(int acquire) {
#ifndef DUMP_TEST
    const char *path=acquire?"/sys/power/wake_lock":"/sys/power/wake_unlock";
    int fd=open(path,O_WRONLY|O_CLOEXEC);
    if(fd<0)return;
    /* Kernel timeout is 60 seconds, renewed every 20. No indefinite lock after a crash. */
    const char *value=acquire?"evergreen_system_dump 60000000000":"evergreen_system_dump";
    if(write(fd,value,strlen(value))==(ssize_t)strlen(value))wake_held=acquire;
    close(fd);
#else
    (void)acquire;
#endif
}
static void status(const char *state,const char *detail) {
    char text[1500];double now=seconds(),elapsed=now-began;
    snprintf(text,sizeof(text),"state=%s\nphase=%s\nverified_or_copied_bytes=%"PRIu64"\ntotal_bytes=%"PRIu64"\npercent=%.1f\nelapsed_seconds=%.0f\nwake_lock=%s\npid=%ld\ndetail=%s\n",
             state,phase,progress,total,total?100.0*(double)progress/(double)total:0.0,elapsed,wake_held?"held (60-second renewable lease)":"unavailable; keep the head unit awake",(long)getpid(),detail);
    atomic_text("STATUS.txt",text);last_status=now;
}
static void check_stop(void) {
    double now=seconds();
    if(now-last_wake>20){wake(1);last_wake=now;}
    if(stopping || exists("STOP")){
        status("PAUSED","Stopped safely; remove STOP, rerun the kit to resume.");
        notify("System dump paused. Keep the chunks to resume later.");wake(0);exit(2);
    }
    if(now-last_status>3)status("RUNNING","Do not remove USB or switch off the head unit.");
}
static void room(uint64_t need) {
    struct statvfs v;if(statvfs(".",&v))die("check USB free space");
    uint64_t available=(uint64_t)v.f_bavail*v.f_frsize;
#ifdef DUMP_TEST
    const char *override=getenv("DUMP_TEST_AVAILABLE");if(override)available=strtoull(override,NULL,10);
#endif
    if(available<need || available-need<RESERVE){
        status("NEEDS_SPACE","Free space for all remaining chunks plus the safety reserve, then rerun.");
        notify("System dump needs more USB space. See SYSTEM-DUMP/STATUS.txt.");
        errno=ENOSPC;die("insufficient USB free space");
    }
}
static void hash_region(int fd,uint64_t offset,uint64_t length,char out[65],int copyfd) {
    sha256 h;sha_init(&h);uint64_t done=0;
    while(done<length){
        check_stop();size_t ask=sizeof(buffer);if(length-done<ask)ask=(size_t)(length-done);
        ssize_t n=pread(fd,buffer,ask,(off_t)(offset+done));
        if(n<0 && errno==EINTR)continue;
        if(n<=0){if(!n)errno=EIO;die("read source or saved chunk");}
        sha_add(&h,buffer,(size_t)n);if(copyfd>=0)write_all(copyfd,buffer,(size_t)n);done+=(uint64_t)n;
        if(fd==source)progress=offset+done;
#ifdef DUMP_TEST
        const char *delay=getenv("DUMP_TEST_PAUSE_US");if(delay)usleep((useconds_t)strtoul(delay,NULL,10));
#endif
    }
    sha_end(&h,out);
}
static int read_text(const char *name,char *value,size_t cap) {
    int fd=open(name,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return -1;
    struct stat s;if(fstat(fd,&s)||!S_ISREG(s.st_mode)||s.st_size<0||(uint64_t)s.st_size>=cap){close(fd);errno=EINVAL;return -1;}
    ssize_t got=read(fd,value,cap-1);int saved=errno;close(fd);errno=saved;
    if(got!=s.st_size){errno=EIO;return -1;}value[got]=0;return 0;
}
static void healthy_source(void) {
#ifndef DUMP_TEST
    FILE *f=setmntent("/proc/mounts","r");if(!f)die("read mount table");
    struct mntent *m;int good=0;
    while((m=getmntent(f)))if(!strcmp(m->mnt_dir,"/system") && !strcmp(m->mnt_fsname,device) && hasmntopt(m,"ro"))good=1;
    endmntent(f);if(!good){errno=EBUSY;die("/system changed or is mounted writable; refusing inconsistent image");}
#endif
}
static void discover(void) {
#ifndef DUMP_TEST
    FILE *f=setmntent("/proc/mounts","r");if(!f)die("read mount table");struct mntent *m;
    while((m=getmntent(f)))if(!strcmp(m->mnt_dir,"/system")){
        if(strncmp(m->mnt_fsname,"/dev/block/",11) || strcmp(m->mnt_type,"ext4") || !hasmntopt(m,"ro")){
            errno=EINVAL;die("expected a read-only ext4 /system block mount; no remount is attempted");}
        if(strlen(m->mnt_fsname)>=sizeof(device)){errno=ENAMETOOLONG;die("system device path");}
        strcpy(device,m->mnt_fsname);break;
    }
    endmntent(f);if(!device[0]){errno=ENOENT;die("no supported /system mount");}
#endif
    source=open(device,O_RDONLY|O_CLOEXEC);if(source<0)die("open system read-only");
    struct stat s;if(fstat(source,&s))die("stat system source");
#ifdef DUMP_TEST
    if(!S_ISREG(s.st_mode)){errno=EINVAL;die("test source must be regular");}total=(uint64_t)s.st_size;
#else
    if(!S_ISBLK(s.st_mode)||ioctl(source,BLKGETSIZE64,&total)){errno=EINVAL;die("query system block size");}
#endif
    if(!total || total>128ULL*1024*1024*1024){errno=EINVAL;die("unexpected system partition size");}
}
static void collect(void) {
    char metadata[1800],saved[1800];
    snprintf(metadata,sizeof(metadata),"format=evergreen-system-dump-v1\nproduct=%s\nsource=%s\nsize=%"PRIu64"\nchunk_size=%"PRIu64"\nscope=system-partition-only\ncontents=as-installed-not-guaranteed-stock\n",product,device,total,(uint64_t)CHUNK);
    if(exists("image.info")){
        if(read_text("image.info",saved,sizeof(saved))||strcmp(saved,metadata)){errno=EINVAL;die("dump identity differs; use a fresh output folder/USB");}
    }else{
        /* An output folder without identity must be empty apart from our own status/log files. */
        if(exists("system.000000.bin")||exists("COMPLETE")){errno=EINVAL;die("unidentified existing dump");}
        atomic_text("image.info",metadata);
    }
    if(unlink("COMPLETE") && errno!=ENOENT)die("remove stale completion marker");
    wake(1);last_wake=seconds();status("RUNNING","Validating saved chunks before copying.");
    for(uint64_t offset=0,index=0;offset<total;offset+=CHUNK,index++){
        char name[80],receipt[100],partial[100],expected[100],input_hash[65],output_hash[65];
        uint64_t length=total-offset;if(length>CHUNK)length=CHUNK;
        snprintf(name,sizeof(name),"system.%06"PRIu64".bin",index);
        snprintf(receipt,sizeof(receipt),"%s.sha256",name);snprintf(partial,sizeof(partial),"%s.part",name);
        healthy_source();check_stop();
        /* A partial file is never resumed by offset: rewrite that one bounded chunk. */
        if(unlink(partial) && errno!=ENOENT)die("remove interrupted partial chunk");
        if(exists(name) && exists(receipt)){
            phase="VERIFYING_SAVED_CHUNK";
            if(read_text(receipt,expected,sizeof(expected))||strlen(expected)!=65||expected[64]!='\n'){errno=EINVAL;die("invalid saved chunk receipt");}expected[64]=0;
            int fd=open(name,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);struct stat s;
            if(fd<0)die("open saved chunk");
            if(fstat(fd,&s)||!S_ISREG(s.st_mode)||s.st_size!=(off_t)length){errno=EINVAL;die("saved chunk size mismatch");}
            hash_region(fd,0,length,output_hash,-1);close(fd);
            if(strcmp(expected,output_hash)){errno=EIO;die("saved chunk checksum mismatch; preserve dump and investigate USB");}
            hash_region(source,offset,length,input_hash,-1);
            if(strcmp(input_hash,expected)){errno=ESTALE;die("system differs from saved chunk; start a separate dump");}
            posix_fadvise(source,(off_t)offset,(off_t)length,POSIX_FADV_DONTNEED);
            progress=offset+length;fprintf(stderr,"Verified saved chunk %"PRIu64"\n",index);continue;
        }
        /* Commit may have been interrupted between data rename and receipt rename. */
        if(exists(name) || exists(receipt)){
            if(unlink(name)&&errno!=ENOENT)die("remove uncommitted chunk");
            if(unlink(receipt)&&errno!=ENOENT)die("remove uncommitted receipt");
        }
        phase="COPYING";room(total-offset);
        int out=open(partial,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);if(out<0)die("create chunk");
        hash_region(source,offset,length,input_hash,out);
        if(fsync(out))die("sync data chunk");if(close(out))die("close data chunk");
        phase="VERIFYING_NEW_CHUNK";
        out=open(partial,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(out<0)die("reopen chunk for verification");
        /* Best effort: drop our file cache before read-back; device caches still exist. */
        posix_fadvise(out,0,0,POSIX_FADV_DONTNEED);
        hash_region(out,0,length,output_hash,-1);close(out);
        if(strcmp(input_hash,output_hash)){errno=EIO;die("new chunk failed read-back checksum");}
        if(rename(partial,name))die("commit chunk");sync_directory();
        snprintf(expected,sizeof(expected),"%s\n",input_hash);atomic_text(receipt,expected);
        posix_fadvise(source,(off_t)offset,(off_t)length,POSIX_FADV_DONTNEED);
        progress=offset+length;status("RUNNING","Chunk synced and checksum verified.");
        fprintf(stderr,"Saved chunk %"PRIu64" (%"PRIu64" / %"PRIu64" bytes)\n",index,progress,total);fflush(stderr);
    }
    healthy_source();phase="FINISHED";
    atomic_text("COMPLETE","All expected chunks synced and SHA-256 verified. Run the host verifier before archiving.\n");
    status("COMPLETE","Collection complete. The worker is exiting; safely eject the USB before removal.");
    notify("System dump complete. Check SYSTEM-DUMP/STATUS.txt and safely eject USB.");
    wake(0);
}
static void failed_at_exit(void) {
    /* Do not overwrite a useful PAUSED/NEEDS_SPACE state. */
    if(strcmp(phase,"FINISHED") && !stopping && !exists("STOP")){
        char old[1800];if(read_text("STATUS.txt",old,sizeof(old))==0 && strstr(old,"state=RUNNING\n")){
            phase="ERROR";
            notify("System dump stopped with an error. See SYSTEM-DUMP/collector.log.");
            status("ERROR","See collector.log. Rerun to verify and resume; keep existing chunks.");
        }
    }
    wake(0);
}
int main(int argc,char **argv) {
    began=seconds();signal(SIGINT,caught);signal(SIGTERM,caught);signal(SIGHUP,SIG_IGN);signal(SIGPIPE,SIG_IGN);
    if(argc!=4 || (strcmp(argv[1],"--start") && strcmp(argv[1],"--foreground"))){fprintf(stderr,"usage: collector --start|--foreground USB_ROOT PRODUCT\n");return 1;}
    if(strlen(argv[3])>=sizeof(product) || strspn(argv[3],"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-")!=strlen(argv[3]) || !argv[3][0]){fprintf(stderr,"Invalid product identifier\n");return 1;}
    strcpy(product,argv[3]);
#ifdef DUMP_TEST
    const char *test=getenv("DUMP_TEST_SOURCE");if(!test || strlen(test)>=sizeof(device))return 1;strcpy(device,test);
#else
    if(geteuid()!=0){fprintf(stderr,"Run via NEMESIS as root\n");return 1;}
#endif
    char root[PATH_MAX];if(!realpath(argv[2],root))die("resolve USB root");
#ifndef DUMP_TEST
    int usb_ok=0;
    for(int i=0;i<3;i++){
        char candidate[64];snprintf(candidate,sizeof(candidate),"/storage/usb%d",i);if(!strcmp(root,candidate))usb_ok=1;
        snprintf(candidate,sizeof(candidate),"/mnt/media_rw/usb%d",i);if(!strcmp(root,candidate))usb_ok=1;
    }
    if(!usb_ok){errno=EINVAL;die("output must be the root of a NEMESIS USB mount");}
    struct stat usb_stat,stage_stat;
    if(stat(root,&usb_stat)||stat("/data/nemesis/system-dump",&stage_stat))die("verify USB storage");
    if(usb_stat.st_dev==stage_stat.st_dev){errno=EINVAL;die("USB output is on internal /data storage");}
#endif
    if(chdir(root))die("enter USB root");
    if(mkdir("SYSTEM-DUMP",0700) && errno!=EEXIST)die("create dump directory");
    directory=open("SYSTEM-DUMP",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(directory<0||fchdir(directory))die("open dump directory");
#ifdef DUMP_TEST
    const char *lockname=".active.lock";
#else
    const char *lockname="/data/nemesis/system-dump/active.lock";
#endif
    int lock=open(lockname,O_RDWR|O_CREAT|O_NOFOLLOW|O_CLOEXEC,0600);if(lock<0)die("open collector lock");
    if(flock(lock,LOCK_EX|LOCK_NB)){fprintf(stderr,"A collector is already running. Read SYSTEM-DUMP/STATUS.txt; do not start another.\n");return 1;}
    int log=open("collector.log",O_WRONLY|O_CREAT|O_APPEND|O_NOFOLLOW|O_CLOEXEC,0600);if(log<0)die("open log");
    if(dup2(log,STDERR_FILENO)<0)die("redirect startup log");
    setvbuf(stderr,NULL,_IONBF,0);
    discover();healthy_source();posix_fadvise(source,0,0,POSIX_FADV_SEQUENTIAL);
    if(!strcmp(argv[1],"--start")){
        /* Double fork: no terminal/Manager lifetime dependency. Readiness is a byte, not EOF. */
        int ready[2];if(pipe(ready))die("create readiness pipe");pid_t child=fork();if(child<0)die("fork worker");
        if(child){close(ready[1]);close(log);unsigned char ok=0;ssize_t n;do{n=read(ready[0],&ok,1);}while(n<0&&errno==EINTR);close(ready[0]);waitpid(child,NULL,0);
            if(n!=1 || ok!=1){fprintf(stderr,"Worker did not start; see SYSTEM-DUMP/collector.log\n");return 1;}
            puts("Background collection STARTED, not finished. Read SYSTEM-DUMP/STATUS.txt for progress; keep the car and USB powered.");return 0;}
        close(ready[0]);if(setsid()<0)_exit(1);child=fork();if(child<0)_exit(1);if(child)_exit(0);
        int null=open("/dev/null",O_RDWR);if(null<0)_exit(1);dup2(null,STDIN_FILENO);dup2(log,STDOUT_FILENO);dup2(log,STDERR_FILENO);if(null>2)close(null);if(log>2)close(log);
        setvbuf(stderr,NULL,_IONBF,0);atexit(failed_at_exit);
        phase="STARTING";status("RUNNING","Worker started. Initializing resumable image capture.");
        unsigned char ok=1;write_all(ready[1],&ok,1);close(ready[1]);
    }else{dup2(log,STDERR_FILENO);if(log>2)close(log);setvbuf(stderr,NULL,_IONBF,0);atexit(failed_at_exit);}
    /* Favor the running head unit. No real-time scheduling or unbounded allocation. */
    setpriority(PRIO_PROCESS,0,10);
    fprintf(stderr,"Collector started; source=%s size=%"PRIu64" product=%s\n",device,total,product);
    collect();close(source);close(directory);close(lock);return 0;
}
