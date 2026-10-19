@ GBA boot stub
@
@ Patched into official BIOS at 0x68 (reset vector). Runs the BIOS initialization,
@ memory clearing, I/O setup, etc, without doing the boot animation.
@
@ From mGBA:
@   r0: 00000000   r1: 00000000   r2: 00000000   r3: 00000000
@   r4: 00000000   r5: 00000000   r6: 00000000   r7: 00000000
@   r8: 00000000   r9: 00000000  r10: 00000000  r11: 00000000
@  r12: 00000000  r13: 03007F00  r14: 08000000  r15: 08000004
@ cpsr: 0000001F [-------]

.arm
.align 4

@ Disable interrupts (set REG_IME to 0)
ldr		r3, =0x04000208
mov		r2, #0
strb	r2, [r3, #0]

@ Setup stack pointers, by calling 'InitSystemStack', at 0xE0
@ This instruction will be at 0x74, so `bl #0x6C`
.word   0xeb000019

@ Call BIOS SWI 1: 'RegisterRamReset'
mov     r0, #0xFF
swi		#0x10000
@ Call BIOS SWI 19: 'SoundBias' - set SOUNDBIAS to 0x200
mov     r0, #1
swi		#0x190000
@ Call BIOS SWI 0: 'SoftReset'
swi		#0x00000
