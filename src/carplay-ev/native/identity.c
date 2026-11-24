/* SPDX-License-Identifier: GPL-3.0-or-later
 * Generic owner of the verified libiap2session identification serializer gate.
 * From Open-HMG/hmgapi-wireless-cp-aa (native/identity/identity.c, f657ca4e).
 * Ultimate Wireless Projection changes: the gate is patched into the library file
 * (gate.s, tools/build-carplay-ev.py), its handler word lives in the library's
 * writable .bss tail, so it is set with a plain store (no mprotect of code pages);
 * the header is version 2; JNI names are org.egmp.carplay.ev.app.NativeIdentity's.
 * CarPlayStartSession (start_* below) is egmp-carplay-ev-routing's (ioniq6.15, src/native/identity.c,
 * GPL-3.0-or-later, as LIVI's livi-runtime bringup.rs does it), for the CarPlay Ultra-Lite test port:
 * the car answers the phone's CarPlayAvailability (0x4300) with the 0x4301 message Java encodes.
 */
#include <jni.h>
typedef unsigned int u32;
typedef unsigned short u16;
typedef void *(*Lookup)(u16,u16,u16 *,void *,int *);
typedef u32 (*Builder)(u32,u32,u32,u32,u32,void *,u32,const char *,Lookup);
typedef int (*Mutex)(void *);
typedef struct {u16 used,id,type,pad;u32 init,length,done,children;} Param;
typedef const Param *(*Children)(u16,u16,u16 *,void *,int *);
extern void *dlopen(const char *,int);
extern void *dlsym(void *,const char *);

static const Param *root_table,*info_table,*status_table;
static Lookup root_lookup,info_lookup,status_lookup;
static Builder raw_builder;
static u32 *hook_mailbox;
static Mutex lock_mutex,unlock_mutex;
static u32 identity_mutex;
static int configured,has_vehicle,has_theme;
static char display_name[321];
static char theme[97];
static u32 dc_w,ac_w,engine=2,connector,dc_connector,ac_connector;
static Param info_copy[9],status_copy[15];
#define WIRELESS_GROUP 24
#define WIRELESS_MAX 8
static Children stock_children;
static Lookup wireless_lookup;
static Param wireless_copy[WIRELESS_MAX+2];
static int (*protect_page)(void *,u32,int);

/* CarPlayStartSession (egmp-carplay-ev-routing ioniq6.15; LIVI livi-runtime bringup.rs): the
 * identification also lists 0x4301 sent and 0x4300 (CarPlayAvailability) received, and when the
 * phone sends 0x4300 the car answers with the 0x4301 message Java encoded (Wi-Fi, AirPlay identity,
 * theme asset). The stock stack knows neither message: the 0x4300 handler takes over the dead
 * duplicate 0x5202 entry of iAP2CtlHandleMsgFuncList (the dispatcher stops at the first 0x5202), and
 * the reply goes out below SendCmd, whose supported-message check would refuse 0x4301. */
#define START_MAX 512
#define AVAILABILITY 0x4300
typedef struct {u32 name,id,label,arg,notify,reply;} Handler;
typedef int (*Blob)(unsigned char *,int,u32,u32,u32);
static unsigned char start_msg[START_MAX];
static u32 start_len,start_offers,start_sent,start_offer_len;
static int start_last=1;
static unsigned char start_offer[64];
static Blob stock_blob;
static void *(*packet_create)(u32,u32);
static void (*packet_destroy)(void *);
static void *(*session_malloc)(u32,const char *,u32);
static int (*send_packet)(void *);
static Handler *handlers;
static u32 start_notify(u32 link,u32 session,const unsigned char *params,u32 n){
    u32 keep=n<sizeof start_offer?n:sizeof start_offer;
    for(u32 i=0;params && i<keep;i++)start_offer[i]=params[i];
    start_offer_len=n;__atomic_add_fetch(&start_offers,1,__ATOMIC_RELEASE);
    u32 len=__atomic_load_n(&start_len,__ATOMIC_ACQUIRE);
    if(!len){start_last=-1;return 0;}
    void *packet=packet_create(link,session);
    if(!packet){start_last=-2;return 0;}
    unsigned char *buf=session_malloc(len,"carplayev",1);
    if(!buf){packet_destroy(packet);start_last=-3;return 0;}
    for(u32 i=0;i<len;i++)buf[i]=start_msg[i];
    ((u32*)packet)[1]=(u32)buf;((u32*)packet)[2]=len;
    start_last=send_packet(packet);
    __atomic_add_fetch(&start_sent,1,__ATOMIC_RELEASE);
    return 0;
}
/* messages sent (6) / received (7) by the accessory: the stock list, then 0x4301 / 0x4300 */
static int start_blob(unsigned char *buf,int cap,u32 id,u32 x,u32 y){
    int n=stock_blob(buf,cap,id,x,y);
    if(n<0 || (id!=6 && id!=7))return n;
    if(!buf)return n+2;
    if(cap-n<2)return n;
    buf[n]=0x43;buf[n+1]=id==6?0x01:0x00;return n+2;
}
static int start_bind(void *lib){
    packet_create=dlsym(lib,"iAP2SessionPacketCreate");
    packet_destroy=dlsym(lib,"iAP2SessionPacketDestroy");
    session_malloc=dlsym(lib,"iAP2SessionMalloc");
    send_packet=dlsym(lib,"iAP2ControlSessionSendPacket");
    handlers=dlsym(lib,"iAP2CtlHandleMsgFuncList");
    return packet_create && packet_destroy && session_malloc && send_packet && handlers?0:-18;
}

static void copy(char *d,const char *s,u32 n){while(n--)*d++=*s++;}
static int string_ok(const char *s,u32 n){if(!s || !n)return 0;for(u32 i=0;i<n;i++)if(!s[i])return 0;return 1;}
static int set_handler(u32 value){
    if(!hook_mailbox)return -18;
    __atomic_store_n(hook_mailbox,value,__ATOMIC_RELEASE);
    return 0;
}
static void connector_dc(u16 a,u16 b,u32 c,void *d){(void)a;(void)b;(void)c;(void)d;connector=dc_connector;}
static void connector_ac(u16 a,u16 b,u32 c,void *d){(void)a;(void)b;(void)c;(void)d;connector=ac_connector;}
static void *info_value(u16 id,u16 type,u16 *n,void *key,int *more){
    if(more)*more=0;
    if(id==2)return &engine;
    if(id==6 || id==8)return display_name;
    if(id==11)return &connector;
    if(id==12)return &dc_w;
    if(id==14)return &ac_w;
    return info_lookup(id,type,n,key,more);
}
static void *status_value(u16 id,u16 type,u16 *n,void *key,int *more){return status_lookup(id,type,n,key,more);}
static void *wireless_value(u16 id,u16 type,u16 *n,void *key,int *more){
    if(id==5 || id==6){if(more)*more=0;return theme;}
    return wireless_lookup(id,type,n,key,more);
}
static Param parameter(u16 id,u16 type);
static const Param *identity_children(u16 id,u16 type,u16 *count,void *key,int *more){
    (void)type;(void)key;if(more)*more=0;
    if(id==20){*count=9;return info_copy;}
    if(id==21){*count=15;return status_copy;}
    if(id==WIRELESS_GROUP && stock_children){
        u32 n=0;const Param *stock=stock_children(id,type,(u16*)&n,key,more);
        if(!stock || n>WIRELESS_MAX){*count=(u16)n;return stock;}
        for(u32 i=0;i<n;i++)wireless_copy[i]=stock[i];
        wireless_copy[n]=parameter(5,12);wireless_copy[n+1]=parameter(6,10);
        *count=(u16)(n+2);if(more)*more=0;return wireless_copy;
    }
    return 0;
}
static void *identity_value(u16 id,u16 type,u16 *n,void *key,int *more){
    if(id==20){if(more)*more=0;return (void*)info_value;}
    if(id==21){if(more)*more=0;return (void*)status_value;}
    if(id==WIRELESS_GROUP && has_theme && wireless_lookup){if(more)*more=0;return (void*)wireless_value;}
    return root_lookup(id,type,n,key,more);
}
static Param parameter(u16 id,u16 type){Param p={1,id,type,0,0,0,0,0};return p;}
static u32 identity_builder(u32 handle,u32 session,u32 a,u32 b,u32 command,void *table,u32 count,const char *name,Lookup lookup){
    if(!__atomic_load_n(&configured,__ATOMIC_ACQUIRE) || command!=0x1d01 || table!=root_table || count!=24)
        return raw_builder(handle,session,a,b,command,table,count,name,lookup);
    if(lock_mutex(&identity_mutex))return 0;
    Param root[24];for(u32 i=0;i<24;i++){
        root[i]=root_table[i];
        if((root[i].id==6 || root[i].id==7) && root[i].length && __atomic_load_n(&start_len,__ATOMIC_ACQUIRE)){
            stock_blob=(Blob)root[i].length;root[i].length=(u32)start_blob;
        }
        else if(has_vehicle && (root[i].id==20 || root[i].id==21)){root[i].used=1;root[i].children=(u32)identity_children;}
        else if(has_theme && root[i].id==WIRELESS_GROUP && wireless_lookup && root[i].children){
            stock_children=(Children)root[i].children;root[i].children=(u32)identity_children;
        }
    }
    u32 result=raw_builder(handle,session,a,b,command,root,24,name,identity_value);
    unlock_mutex(&identity_mutex);return result;
}
static Builder recipe_original(Builder entry){
    if(!entry)return 0;u16 *p=(u16*)((u32)entry&~1U);
    if((p[0]&0xf800)!=0xf000 || (p[1]&0xd000)!=0x9000)return 0;
    u32 s=(p[0]>>10)&1,j1=(p[1]>>13)&1,j2=(p[1]>>11)&1;
    u32 bits=(s<<24)|((!(j1^s))<<23)|((!(j2^s))<<22)|((p[0]&1023)<<12)|((p[1]&2047)<<1);
    int delta=(int)(bits<<7)>>7;char *gate=(char*)p+4+delta;u32 *header=(u32*)(gate+32);
    /* version 2: header[3] is the handler word's offset from &header[3] (a writable page) */
    if(header[0]!=0x45564731 || header[1]!=2 || header[2]<48 || header[2]>128 || !header[3] || (header[3]&3))return 0;
    hook_mailbox=(u32*)((char*)&header[3]+(int)header[3]);return (Builder)((u32)(gate+header[2])|1);
}
static int bind(void){
    void *lib=dlopen("/system/lib/libiap2session.so",2),*c=dlopen("/system/lib/libc.so",2);
    if(!lib || !c)return -10;
    lock_mutex=dlsym(c,"pthread_mutex_lock");unlock_mutex=dlsym(c,"pthread_mutex_unlock");
    protect_page=dlsym(c,"mprotect");   /* only for CarPlayStartSession's handler entry */
    start_bind(lib);                    /* optional: no CarPlayStartSession without it */
    raw_builder=recipe_original(dlsym(lib,"iAP2SessionPacketMakePacketUsingParamListNew"));
    root_table=dlsym(lib,"iAP2IdenInfoMsgList");root_lookup=dlsym(lib,"_Z26iAP2ControlIden_GetPointerttPtPvPi");
    info_lookup=dlsym(lib,"_Z41iAP2ControlIden_VehicleInfoIdenGetPointerttPtPvPi");
    status_lookup=dlsym(lib,"iAP2ControlIden_VehicleStatusGetPointer");
    wireless_lookup=dlsym(lib,"iAP2ControlIden_WirelessGetPointer");
    const Param *(*get_info)(u32*)=dlsym(lib,"iAP2ControlIden_GetVehicleInfoParamList");
    const Param *(*get_status)(u32*)=dlsym(lib,"iAP2ControlIden_GetVehicleStatusParamList");
    if(!lock_mutex || !unlock_mutex)return -20;
    if(!raw_builder)return -21;
    if(!root_table || !root_lookup)return -22;
    if(!info_lookup || !get_info)return -23;
    if(!status_lookup || !get_status)return -24;
    u32 n=0;info_table=get_info(&n);if(n!=5 || !info_table)return -25;
    status_table=get_status(&n);if(n!=13 || !status_table)return -26;
    return 0;
}
static int configure(const char *name,u32 len,int engine_type,int dc_type,u32 dc,int ac_type,u32 ac,const char *theme_id,u32 theme_len){
    if(!hook_mailbox){int rc=bind();if(rc)return rc;}
    int vehicle=len!=0,themed=theme_len!=0;
    if(!vehicle && !themed)return -12;
    if(vehicle && (!string_ok(name,len) || len>320 || engine_type<0 || engine_type>3 || dc_type<0 || dc_type>1 || ac_type!=2
            || !dc || dc>1000000 || !ac || ac>1000000))return -12;
    if(themed){
        if(!wireless_lookup || !string_ok(theme_id,theme_len) || theme_len>96)return wireless_lookup?-12:-18;
        for(u32 i=0;i<theme_len;i++)if(theme_id[i]<0x21 || theme_id[i]>0x7e)return -12;
    }
    if(lock_mutex(&identity_mutex))return -15;
    has_vehicle=vehicle;has_theme=themed;
    copy(theme,theme_id,theme_len);theme[theme_len]=0;
    if(vehicle){
        copy(display_name,name,len);display_name[len]=0;engine=(u32)engine_type;dc_connector=(u32)dc_type;ac_connector=(u32)ac_type;dc_w=dc;ac_w=ac;
        for(u32 i=0;i<5;i++){
            info_copy[i]=info_table[i];
            if(info_copy[i].id==2 || info_copy[i].id==6 || info_copy[i].id==8){info_copy[i].used=1;info_copy[i].init=info_copy[i].length=info_copy[i].done=0;}
        }
        info_copy[5]=parameter(11,8);info_copy[5].init=(u32)connector_dc;
        info_copy[6]=parameter(11,8);info_copy[6].init=(u32)connector_ac;
        info_copy[7]=parameter(12,6);info_copy[8]=parameter(14,6);
        for(u32 i=0;i<13;i++){status_copy[i]=status_table[i];u16 id=status_copy[i].id;if(id==3 || id==6 || id==11 || id==15)status_copy[i].used=1;}
        status_copy[13]=parameter(21,12);status_copy[14]=parameter(30,12);
    }
    __atomic_store_n(&configured,1,__ATOMIC_RELEASE);int result=set_handler((u32)identity_builder);
    if(result)__atomic_store_n(&configured,0,__ATOMIC_RELEASE);
    unlock_mutex(&identity_mutex);return result;
}

/* Before configure (any time: re-armed when the hotspot's addresses change): the encoded 0x4301
 * message (40 40 len 43 01 ...), or len 0 for off */
static int start_session(const unsigned char *msg,u32 len){
    if(!hook_mailbox){int rc=bind();if(rc)return rc;}
    if(len>START_MAX || (len && (len<6 || msg[0]!=0x40 || msg[1]!=0x40 || ((u32)msg[2]<<8|msg[3])!=len || msg[4]!=0x43 || msg[5]!=0x01)))return -12;
    if(!len){__atomic_store_n(&start_len,0,__ATOMIC_RELEASE);return 0;}
    if(!handlers || !packet_create || !protect_page)return -18;
    Handler *h=&handlers[33];
    if(handlers[32].id!=0x5202 || (h->id!=0x5202 && h->id!=AVAILABILITY))return -20;
    if(lock_mutex(&identity_mutex))return -15;
    for(u32 i=0;i<len;i++)start_msg[i]=msg[i];
    __atomic_store_n(&start_len,len,__ATOMIC_RELEASE);
    if(h->id!=AVAILABILITY){
        void *page=(void*)((u32)h&~4095U);u32 span=((u32)(h+1)-(u32)page+4095U)&~4095U;
        if(protect_page(page,span,3)){__atomic_store_n(&start_len,0,__ATOMIC_RELEASE);unlock_mutex(&identity_mutex);return -19;}
        h->notify=(u32)start_notify;h->reply=0;h->arg=0;
        __atomic_store_n(&h->id,AVAILABILITY,__ATOMIC_RELEASE);
    }
    unlock_mutex(&identity_mutex);return 0;
}
/* 0 offers seen, 1 answers sent, 2 last send result, 3 last offer's parameter length, 16+i its bytes */
static int start_status(int which){
    if(which==0)return (int)__atomic_load_n(&start_offers,__ATOMIC_ACQUIRE);
    if(which==1)return (int)__atomic_load_n(&start_sent,__ATOMIC_ACQUIRE);
    if(which==2)return start_last;
    if(which==3)return (int)start_offer_len;
    if(which>=16 && which<16+(int)sizeof start_offer)return start_offer[which-16];
    return -1;
}
JNIEXPORT jint JNICALL Java_org_egmp_carplay_ev_app_NativeIdentity_startSession(JNIEnv *env,jclass cls,jbyteArray msg){
    (void)cls;jsize n=msg?(*env)->GetArrayLength(env,msg):0;if(n<0 || n>START_MAX)return -12;
    unsigned char bytes[START_MAX];if(n)(*env)->GetByteArrayRegion(env,msg,0,n,(jbyte*)bytes);
    if((*env)->ExceptionCheck(env))return -12;
    return start_session(bytes,(u32)n);
}
JNIEXPORT jint JNICALL Java_org_egmp_carplay_ev_app_NativeIdentity_startSessionStatus(JNIEnv *env,jclass cls,jint which){
    (void)env;(void)cls;return start_status(which);
}
JNIEXPORT jint JNICALL Java_org_egmp_carplay_ev_app_NativeIdentity_configure(JNIEnv *env,jclass cls,jbyteArray name,jint engine_type,jint dc_type,jint dc,jint ac_type,jint ac,jbyteArray theme_id){
    (void)cls;if(!name || !theme_id)return -12;jsize n=(*env)->GetArrayLength(env,name),t=(*env)->GetArrayLength(env,theme_id);
    if(n>320 || t>96)return -12;char bytes[321],theme_bytes[97];
    if(n)(*env)->GetByteArrayRegion(env,name,0,n,(jbyte*)bytes);
    if(t)(*env)->GetByteArrayRegion(env,theme_id,0,t,(jbyte*)theme_bytes);
    if((*env)->ExceptionCheck(env))return -12;
    return configure(bytes,(u32)n,engine_type,dc_type,(u32)dc,ac_type,(u32)ac,theme_bytes,(u32)t);
}
JNIEXPORT void JNICALL Java_org_egmp_carplay_ev_app_NativeIdentity_shutdown(JNIEnv *env,jclass cls){
    (void)env;(void)cls;__atomic_store_n(&configured,0,__ATOMIC_RELEASE);if(hook_mailbox)set_handler(0);
}
/* __aeabi_memclr: defined once, in bridge_jni.c */
_Static_assert(sizeof(void*)==4,"HU ABI is ARM32");
_Static_assert(sizeof(Param)==24,"native table ABI");
