package lib.audio

import chisel3._

/**
 * MiSTer's DC blocker on a core's sound (Game Bub rev2 ports, 2026-09-24).
 *
 * MiSTer's `sys/audio_out.v` runs `sys/iir_filter.v`'s `DC_blocker` on each
 * channel of every core at 48 kHz: a one-pole high-pass with its zero at DC
 * and its pole at 1 - 2^-9 (-3 dB near 15 Hz). The Game Bub framework had
 * none, so a core whose sound is not centered on zero (an unsigned mixer, an
 * idle DAC level) played with an offset: less headroom, and a pop wherever the
 * output starts or stops. The consoles' own output capacitors take it out.
 *
 * The same response, computed as the input minus a running estimate of its
 * offset. The estimate steps at `update` (48 kHz from the system clock); the
 * sound itself passes at the core's own rate, so it is not sampled a second
 * time ahead of the framework's 48 kHz output:
 * {{{
 *   D  := D + in - (D >> 9)       at each update (D = the offset * 2^9)
 *   out = sat16(in - (D >> 9))    registered
 * }}}
 * that is (1 - z^-1) / (1 - (1 - 2^-9) z^-1) at the update rate, MiSTer's
 * filter without its 0.999 input scale. It always runs: while a core is
 * paused behind the Home menu (the DAC is powered down there) its held sample
 * fades to zero, and the sound goes on from where it stopped when the core
 * resumes, without a step. (Focus is no gate: the firmware also clears the
 * focus register while Home is held for the volume and brightness combos, with
 * the game running and audible.) The GB-MiSTer and NGPC ports have their own
 * blockers in the core (GbmDcBlocker, NgpcDcBlocker); this one is for the
 * ports without one.
 */
class AudioDcBlocker extends Module {
  val io = IO(new Bundle {
    val update = Input(Bool())
    val in = Input(SInt(16.W))
    val out = Output(SInt(16.W))
  })
  val shift = 9
  /**
   * The offset * 2^9. It moves toward 2^9 * in by less than the distance, so it stays within
   * [-2^24, 2^24) and the sum below fits its width.
   */
  val offsetScaled = RegInit(0.S((16 + shift + 1).W))
  val offset = offsetScaled >> shift
  when (io.update) {
    offsetScaled := (offsetScaled +& io.in -& offset)(offsetScaled.getWidth - 1, 0).asSInt
  }
  val diff = io.in -& offset
  val saturated = Mux(diff > 32767.S, 32767.S(16.W), Mux(diff < -32768.S, -32768.S(16.W), diff(15, 0).asSInt))
  io.out := RegNext(saturated, 0.S(16.W))
}
