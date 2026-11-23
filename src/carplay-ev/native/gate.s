@ SPDX-License-Identifier: GPL-3.0-or-later
@ Identification serializer gate, patched into libiap2session.so (md5 fc7cb675...) by
@ tools/build-carplay-ev.py. Based on Open-HMG/hmgapi-wireless-cp-aa's
@ native/identity/recipe_gate.s; here the handler word is in the library's writable
@ .bss tail instead of in the gate, so nothing makes code writable at run time.
@ The library's iAP2SessionPacketMakePacketUsingParamListNew starts with a b.w here.
@ The literals marked (builder) are filled with offsets by the build tool.
.syntax unified
.thumb
.text
.global gate
.thumb_func
gate:                                   @ 0x00
    ldr.w ip, gate_mbox                 @ ip = handler word - (anchor + 4)
anchor:                                 @ 0x04
    add ip, pc                          @ ip = &handler word
    ldr.w ip, [ip]                      @ handler (0: not armed)
    cmp.w ip, #0
    beq.w fallback
    bx ip
.org 0x20
header:                                 @ 0x20, read by identity.c
    .word 0x45564731                    @ magic "1GVE"
    .word 2                             @ version 2
    .word fallback - gate               @ the original function, displaced
    .word 0                             @ 0x2c (builder): handler word - &this word
gate_mbox:
    .word 0                             @ 0x30 (builder): handler word - (anchor + 4)
.org 0x40
.thumb_func
fallback:                               @ 0x40
    push.w {r4, r5, r6, r7, r8, r9, r10, r11, lr}   @ the instruction the b.w replaced
    ldr.w ip, resume
anchor2:                                @ 0x48
    add ip, pc
    bx ip
.balign 4
resume:
    .word 0                             @ 0x4c (builder): (entry + 4) | 1 - (anchor2 + 4)
