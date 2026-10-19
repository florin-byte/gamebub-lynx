-- Game Bub replacement for the MiSTer Lynx core's Altera dpram.vhd.
-- The original wraps altsyncram (BIDIR_DUAL_PORT, unregistered outputs,
-- read-during-write = NEW_DATA_NO_NBE_READ, i.e. write-first on the same
-- port). This is a behavioral equivalent that Vivado infers as true
-- dual-port block RAM (UG901 shared-variable TDP pattern). Entity interface
-- is identical to the original so no other core file changes. The original
-- file's second entity (dpram_dif) is unused by the Lynx core and dropped.

LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

ENTITY dpram IS
	generic (
		 addr_width : integer := 8;
		 data_width : integer := 8
	);
	PORT
	(
		clock_a		: IN STD_LOGIC;
		clken_a		: IN STD_LOGIC := '1';
		address_a	: IN STD_LOGIC_VECTOR (addr_width-1 DOWNTO 0);
		data_a		: IN STD_LOGIC_VECTOR (data_width-1 DOWNTO 0);
		wren_a		: IN STD_LOGIC := '0';
		q_a			: OUT STD_LOGIC_VECTOR (data_width-1 DOWNTO 0);

		clock_b		: IN STD_LOGIC;
		clken_b		: IN STD_LOGIC := '1';
		address_b	: IN STD_LOGIC_VECTOR (addr_width-1 DOWNTO 0);
		data_b		: IN STD_LOGIC_VECTOR (data_width-1 DOWNTO 0) := (others => '0');
		wren_b		: IN STD_LOGIC := '0';
		q_b			: OUT STD_LOGIC_VECTOR (data_width-1 DOWNTO 0)
	);
END dpram;

ARCHITECTURE SYN OF dpram IS

	type ram_t is array (0 to 2**addr_width - 1) of std_logic_vector(data_width-1 downto 0);
	shared variable ram : ram_t := (others => (others => '0'));

BEGIN

	process (clock_a)
	begin
		if rising_edge(clock_a) then
			if clken_a = '1' then
				if wren_a = '1' then
					ram(to_integer(unsigned(address_a))) := data_a;
					q_a <= data_a;
				else
					q_a <= ram(to_integer(unsigned(address_a)));
				end if;
			end if;
		end if;
	end process;

	process (clock_b)
	begin
		if rising_edge(clock_b) then
			if clken_b = '1' then
				if wren_b = '1' then
					ram(to_integer(unsigned(address_b))) := data_b;
					q_b <= data_b;
				else
					q_b <= ram(to_integer(unsigned(address_b)));
				end if;
			end if;
		end if;
	end process;

END SYN;
