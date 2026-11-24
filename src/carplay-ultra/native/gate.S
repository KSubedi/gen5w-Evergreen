@ SPDX-License-Identifier: GPL-3.0-or-later
@ Ultimate Wireless Projection: the on/off gate in front of CarPlay Ultra-Lite's code segment.
@
@ Upstream (an HMG-API mod) patches libCarPlayCore.so in memory, and only when its Java side
@ starts: when its crash guard trips, or the mod is off, the library stays stock. Here the
@ library is patched on disk, so each of the three hook sites branches here first:
@   DiPO's files/cpc-on exists (org.uwp.carplayultra.Ultra writes it once CarPlay Ultra-Lite
@   has started in this DiPO process, and deletes it first at every start):
@       on to CarPlay Ultra-Lite's own stub, unchanged (its release bytes, tools/build-carplay-ultra.py)
@   no cpc-on: do what the replaced instruction(s) did and go back, as the stock library does.
@ Registers: what upstream's stubs may clobber at each site (they call C there), no more; the
@ stack is the same on the way out. Position independent: every address comes from gate_table,
@ filled by tools/build-carplay-ultra.py with (target - gate_table), like upstream's cpc_table.

    .syntax unified
    .thumb
    .section .text.gate, "ax", %progbits

@ gate_table word indexes
#define G_ORIG_INFO     0   /* AirPlayCopyServerInfo (Thumb) */
#define G_RET_INFO      1   /* 0x9c24 (Thumb): after its call */
#define G_LOGCAT        2   /* gLogCategory_AirPlayReceiverCore: what both two-instruction sites compute */
#define G_RET_SETUP     3   /* 0x12436 (Thumb) */
#define G_RET_STREAM    4   /* 0x1171c (Thumb): DiPO's own "Unsupported" path */
#define G_STUB_INFO     5   /* CarPlay Ultra-Lite's cpc_stub_info */
#define G_STUB_SETUP    6   /* cpc_stub_setup */
#define G_STUB_STREAM   7   /* cpc_stub_stream */

@ ultra_on: r0 = 1 if DiPO's files/cpc-on exists, else 0. Clobbers r0, r1, flags.
    .thumb_func
ultra_on:
    push    {r7, lr}
    adr     r0, cpc_on_path
    movs    r1, #0                  @ F_OK
    movs    r7, #33                 @ __NR_access (ARM EABI)
    svc     #0
    pop     {r7, lr}
    cmp     r0, #0
    ite     eq
    moveq   r0, #1
    movne   r0, #0
    bx      lr

@ b.w at 0x9c20, in place of "bl AirPlayCopyServerInfo"; r0-r2 are its arguments.
    .global gate_info
    .thumb_func
gate_info:
    push    {r0, r1, r2, lr}
    bl      ultra_on
    mov     r12, r0
    pop     {r0, r1, r2, lr}
    adr.w   r3, gate_table
    cmp     r12, #0
    beq     1f
    ldr     r12, [r3, #(G_STUB_INFO * 4)]
    add     r12, r3
    bx      r12                     @ on: CarPlay Ultra-Lite's stub (it calls AirPlayCopyServerInfo itself)
1:  ldr     r12, [r3, #(G_RET_INFO * 4)]
    add     r12, r3
    mov     lr, r12                 @ off: the bl's return address...
    ldr     r12, [r3, #(G_ORIG_INFO * 4)]
    add     r12, r3
    bx      r12                     @ ...and its call

@ b.w at 0x12430, in place of "ldr.w r0, [pc, #0x7f0]" + "add r0, pc".
    .global gate_setup
    .thumb_func
gate_setup:
    push    {r4, lr}
    bl      ultra_on
    pop     {r4, lr}
    adr.w   r3, gate_table
    cbz     r0, 1f
    ldr     r12, [r3, #(G_STUB_SETUP * 4)]
    add     r12, r3
    bx      r12                     @ on: CarPlay Ultra-Lite's stub
1:  ldr     r0, [r3, #(G_LOGCAT * 4)]
    add     r0, r3                  @ off: what the two replaced instructions computed
    ldr     r12, [r3, #(G_RET_SETUP * 4)]
    add     r12, r3
    bx      r12

@ b.w at 0x11718, in place of "ldr r0, [pc, #0x3ec]" + "add r0, pc".
    .global gate_stream
    .thumb_func
gate_stream:
    push    {r4, lr}
    bl      ultra_on
    pop     {r4, lr}
    adr.w   r3, gate_table
    cbz     r0, 1f
    ldr     r12, [r3, #(G_STUB_STREAM * 4)]
    add     r12, r3
    bx      r12                     @ on: CarPlay Ultra-Lite's stub
1:  ldr     r0, [r3, #(G_LOGCAT * 4)]
    add     r0, r3                  @ off: what the two replaced instructions computed
    ldr     r12, [r3, #(G_RET_STREAM * 4)]
    add     r12, r3
    bx      r12

    .balign 4
cpc_on_path:
    .asciz  "/data/data/com.daudio.app.dipo/files/cpc-on"

    .balign 4
    .global gate_table
gate_table:
    .rept 8
    .word   0                       @ filled by tools/build-carplay-ultra.py: target - gate_table
    .endr
