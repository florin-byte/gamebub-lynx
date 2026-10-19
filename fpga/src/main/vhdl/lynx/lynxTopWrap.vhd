-- Game Bub shim around the MiSTer LynxTop entity.
--
-- Purpose (see PORTS.md):
--  1. Vivado mixed-language boundaries only support std_logic(_vector)
--     ports (UG901). LynxTop has two integer ports (pixel_out_addr,
--     savestate_number); this wrap converts/ties them so the SystemVerilog
--     wrapper (lynx_handheld_core.sv) sees only std_logic types.
--  2. Tie off everything Game Bub does not use: savestates/rewind, cheats,
--     ComLynx serial, fast-forward/turbo, FPS overlay. Vivado strips the
--     dead logic.

library IEEE;
use IEEE.std_logic_1164.all;
use IEEE.numeric_std.all;

entity lynxTopWrap is
   port
   (
      clk            : in  std_logic; -- 64 MHz
      reset_in       : in  std_logic; -- active high
      pause_in       : in  std_logic; -- active high

      -- cart ROM fetch (pulse handshake, see PORTS.md)
      rom_addr       : out std_logic_vector(19 downto 0);
      rom_byte       : in  std_logic_vector( 7 downto 0);
      rom_req        : out std_logic;
      rom_ack        : in  std_logic;

      -- ROM metadata / header sniff stream
      romsize        : in  std_logic_vector(19 downto 0);
      romwrite_data  : in  std_logic_vector(15 downto 0);
      romwrite_addr  : in  std_logic_vector(19 downto 0);
      romwrite_wren  : in  std_logic;

      -- BIOS load (512 bytes, while in reset)
      bios_wraddr    : in  std_logic_vector(8 downto 0);
      bios_wrdata    : in  std_logic_vector(7 downto 0);
      bios_wr        : in  std_logic;

      -- video framebuffer write stream
      pixel_out_addr : out std_logic_vector(13 downto 0); -- 0..16319
      pixel_out_data : out std_logic_vector(11 downto 0); -- [11:8]=R [7:4]=G [3:0]=B
      pixel_out_we   : out std_logic;

      -- audio (16 bit signed)
      audio_l        : out std_logic_vector(15 downto 0);
      audio_r        : out std_logic_vector(15 downto 0);

      -- fast-forward (v2): held hotkey + speed select
      -- (00=400%, 01=133%, 10=160%, 11=200%)
      fastforward    : in  std_logic;
      speedselect    : in  std_logic_vector(1 downto 0);

      -- savestates (v2): trigger/status + 64-bit external memory bus
      -- (slot base = Softmap_SaveState_ADDR generic on LynxTop; slv slot
      -- number at the boundary per the UG901 mixed-language rule)
      save_state       : in  std_logic;
      load_state       : in  std_logic;
      savestate_number : in  std_logic_vector(1 downto 0);
      state_loaded     : out std_logic;
      ss_busy          : out std_logic;
      SAVE_out_Din     : out std_logic_vector(63 downto 0);
      SAVE_out_Dout    : in  std_logic_vector(63 downto 0);
      SAVE_out_Adr     : out std_logic_vector(25 downto 0);
      SAVE_out_rnw     : out std_logic;
      SAVE_out_ena     : out std_logic;
      SAVE_out_be      : out std_logic_vector(7 downto 0);
      SAVE_out_done    : in  std_logic;

      -- joypad (active high)
      JoyUP          : in  std_logic;
      JoyDown        : in  std_logic;
      JoyLeft        : in  std_logic;
      JoyRight       : in  std_logic;
      Option1        : in  std_logic;
      Option2        : in  std_logic;
      KeyB           : in  std_logic;
      KeyA           : in  std_logic;
      KeyPause       : in  std_logic
   );
end entity;

architecture arch of lynxTopWrap is

   signal pixel_addr_int : integer range 0 to 16319;

begin

   pixel_out_addr <= std_logic_vector(to_unsigned(pixel_addr_int, 14));

   ilynxTop : entity work.LynxTop
   generic map
   (
      -- byte 0x0020_0000 in SDRAM (unit: 32-bit words); slots 0-3 stride
      -- 0x8000 words = 128 KiB each -> bytes 0x200000..0x280000
      Softmap_SaveState_ADDR => 524288
   )
   port map
   (
      clk                   => clk,
      reset_in              => reset_in,
      pause_in              => pause_in,

      rom_addr              => rom_addr,
      rom_byte              => rom_byte,
      rom_req               => rom_req,
      rom_ack               => rom_ack,

      romsize               => romsize,
      romwrite_data         => romwrite_data,
      romwrite_addr         => romwrite_addr,
      romwrite_wren         => romwrite_wren,

      bios_wraddr           => bios_wraddr,
      bios_wrdata           => bios_wrdata,
      bios_wr               => bios_wr,

      pixel_out_addr        => pixel_addr_int,
      pixel_out_data        => pixel_out_data,
      pixel_out_we          => pixel_out_we,

      audio_l               => audio_l,
      audio_r               => audio_r,

      -- settings (v2: fast-forward exposed; turbo/overlay stay off)
      fastforward           => fastforward,
      turbo                 => '0',
      speedselect           => speedselect,
      fpsoverlay_on         => '0',

      JoyUP                 => JoyUP,
      JoyDown               => JoyDown,
      JoyLeft               => JoyLeft,
      JoyRight              => JoyRight,
      Option1               => Option1,
      Option2               => Option2,
      KeyB                  => KeyB,
      KeyA                  => KeyA,
      KeyPause              => KeyPause,

      -- ComLynx: idle
      comm_rx               => '1',
      comm_tx               => open,

      -- savestates (v2): exposed; rewind stays disabled. Header count
      -- always increments (full 0xFF byte-enable header writes).
      increaseSSHeaderCount => '1',
      save_state            => save_state,
      load_state            => load_state,
      savestate_number      => to_integer(unsigned(savestate_number)),
      state_loaded          => state_loaded,
      ss_busy               => ss_busy,
      SAVE_out_Din          => SAVE_out_Din,
      SAVE_out_Dout         => SAVE_out_Dout,
      SAVE_out_Adr          => SAVE_out_Adr,
      SAVE_out_rnw          => SAVE_out_rnw,
      SAVE_out_ena          => SAVE_out_ena,
      SAVE_out_be           => SAVE_out_be,
      SAVE_out_done         => SAVE_out_done,
      rewind_on             => '0',
      rewind_active         => '0',

      -- cheats: disabled
      cheat_clear           => '0',
      cheats_enabled        => '0',
      cheat_on              => '0',
      cheat_in              => (others => '0'),
      cheats_active         => open
   );

end architecture;
