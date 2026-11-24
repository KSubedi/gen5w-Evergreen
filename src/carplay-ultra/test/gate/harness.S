@ SPDX-License-Identifier: GPL-3.0-or-later
@ qemu-arm test harness for src/carplay-ultra/native/gate.S (tools/test-carplay-ultra-gate.py).
@ Plays the library: jumps into each gate the way the b.w at a hook site does, with known
@ registers and stack, and stands in for every place the gate can go. Exit status 0 = every
@ check passed, else the number of the failed check. EXPECT_ON (r11, set from argc) says which
@ path every gate must take: argc 1 = off (no cpc-on), argc 2 = on.

    .syntax unified
    .thumb
    .section .text.harness, "ax", %progbits

    .global _start
    .thumb_func
_start:
    ldr     r11, [sp]               @ argc
    sub     r11, r11, #1            @ 0 off, 1 on
    mov     r10, sp                 @ the stack as the "library" has it at each site

@ 1: gate_info, the bl AirPlayCopyServerInfo site
    movs    r0, #11
    movs    r1, #12
    movs    r2, #13
    ldr     lr, =0x1ee7             @ the function's own lr: must survive into the on path
    b.w     gate_info
    .thumb_func
    .global h_ret_info
h_ret_info:                         @ off path returns here (as 0x9c24), r0 from fake_info
    cmp     r11, #0
    bne     fail1
    cmp     r0, #0x55
    bne     fail1
    cmp     sp, r10
    bne     fail1
    b       t2

    .thumb_func
    .global h_orig_info
h_orig_info:                        @ AirPlayCopyServerInfo: arguments untouched, lr = h_ret_info
    cmp     r0, #11
    bne     fail1
    cmp     r1, #12
    bne     fail1
    cmp     r2, #13
    bne     fail1
    adr     r3, h_ret_info
    orr     r3, r3, #1
    cmp     lr, r3
    bne     fail1
    movs    r0, #0x55
    bx      lr

    .thumb_func
    .global h_stub_info
h_stub_info:                        @ on path: CarPlay Ultra-Lite's stub, everything as at the site
    cmp     r11, #1
    bne     fail1
    cmp     r0, #11
    bne     fail1
    cmp     r1, #12
    bne     fail1
    cmp     r2, #13
    bne     fail1
    ldr     r3, =0x1ee7
    cmp     lr, r3
    bne     fail1
    cmp     sp, r10
    bne     fail1
    b       t2

@ 2: gate_setup, the ldr.w r0 / add r0, pc site
t2: movs    r0, #0
    ldr     lr, =0x2ee7
    b.w     gate_setup
    .thumb_func
    .global h_ret_setup
h_ret_setup:                        @ off: r0 = the log category, lr kept
    cmp     r11, #0
    bne     fail2
    ldr     r3, =h_logcat
    cmp     r0, r3
    bne     fail2
    ldr     r3, =0x2ee7
    cmp     lr, r3
    bne     fail2
    cmp     sp, r10
    bne     fail2
    b       t3
    .thumb_func
    .global h_stub_setup
h_stub_setup:
    cmp     r11, #1
    bne     fail2
    ldr     r3, =0x2ee7
    cmp     lr, r3
    bne     fail2
    cmp     sp, r10
    bne     fail2
    b       t3

@ 3: gate_stream, the ldr r0 / add r0, pc site
t3: movs    r0, #0
    ldr     lr, =0x3ee7
    b.w     gate_stream
    .thumb_func
    .global h_ret_stream
h_ret_stream:
    cmp     r11, #0
    bne     fail3
    ldr     r3, =h_logcat
    cmp     r0, r3
    bne     fail3
    ldr     r3, =0x3ee7
    cmp     lr, r3
    bne     fail3
    cmp     sp, r10
    bne     fail3
    b       pass
    .thumb_func
    .global h_stub_stream
h_stub_stream:
    cmp     r11, #1
    bne     fail3
    ldr     r3, =0x3ee7
    cmp     lr, r3
    bne     fail3
    cmp     sp, r10
    bne     fail3
    b       pass

pass:   movs r0, #0
        b    out
fail1:  movs r0, #1
        b    out
fail2:  movs r0, #2
        b    out
fail3:  movs r0, #3
out:    movs r7, #1                 @ __NR_exit
        svc  #0
    .ltorg

    .balign 4
    .global h_logcat
h_logcat:
    .word   0
