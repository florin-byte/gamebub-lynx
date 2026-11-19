CC ?= cc
CFLAGS = -std=c11 -O2 -Wall -Wextra -Wno-missing-field-initializers -I..
BUILD ?= /tmp/gamebub-gb031-host
.PHONY: test
test: $(BUILD)/transport_bench_control_test $(BUILD)/transport_bench_test $(BUILD)/gba_test $(BUILD)/proto_test $(BUILD)/lx_test $(BUILD)/adaptor_test $(BUILD)/bridge_test $(BUILD)/peers_test $(BUILD)/sync_test $(BUILD)/sync_rewrite_test $(BUILD)/sync_gba_test
	$(BUILD)/transport_bench_control_test
	$(BUILD)/transport_bench_test
	$(BUILD)/gba_test
	$(BUILD)/proto_test
	$(BUILD)/lx_test
	$(BUILD)/adaptor_test
	$(BUILD)/bridge_test
	$(BUILD)/peers_test
	$(BUILD)/sync_test
	$(BUILD)/sync_rewrite_test
	$(BUILD)/sync_gba_test
$(BUILD):
	mkdir -p $@
$(BUILD)/proto_test: proto_test.c ../cwl_proto.c ../cwl_proto.h | $(BUILD)
	$(CC) $(CFLAGS) proto_test.c ../cwl_proto.c -o $@
$(BUILD)/lx_test: lx_test.c ../cwl_lx.c ../cwl_lx.h | $(BUILD)
	$(CC) $(CFLAGS) lx_test.c ../cwl_lx.c -o $@
$(BUILD)/adaptor_test: adaptor_test.c ../gamebub_cwl_gba.c ../gamebub_cwl_bridge.c ../gamebub_cwl_logic.c ../gamebub_cwl_logic.h ../gamebub_cwl_gba.h ../cwl_proto.c ../cwl_lx.c | $(BUILD)
	$(CC) $(CFLAGS) adaptor_test.c ../gamebub_cwl_gba.c ../gamebub_cwl_bridge.c ../gamebub_cwl_logic.c ../cwl_proto.c ../cwl_lx.c -o $@
$(BUILD)/bridge_test: bridge_test.c ../gamebub_cwl_gba.c ../gamebub_cwl_bridge.c ../gamebub_cwl_logic.c ../gamebub_cwl_logic.h ../gamebub_cwl_gba.h ../cwl_proto.c ../cwl_proto.h ../cwl_lx.c | $(BUILD)
	$(CC) $(CFLAGS) bridge_test.c ../gamebub_cwl_gba.c ../gamebub_cwl_bridge.c ../gamebub_cwl_logic.c ../cwl_proto.c ../cwl_lx.c -o $@
$(BUILD)/peers_test: peers_test.c ../gamebub_cwl_peers.c ../gamebub_cwl_peers.h ../gamebub_cwl_gba.c ../gamebub_cwl_bridge.c ../gamebub_cwl_logic.c ../gamebub_cwl_logic.h ../gamebub_cwl_gba.h ../cwl_proto.c ../cwl_lx.c | $(BUILD)
	$(CC) $(CFLAGS) peers_test.c ../gamebub_cwl_peers.c ../gamebub_cwl_gba.c ../gamebub_cwl_bridge.c ../gamebub_cwl_logic.c ../cwl_proto.c ../cwl_lx.c -o $@

$(BUILD)/gba_test: gba_test.c ../gamebub_cwl_gba.c ../gamebub_cwl_gba.h ../gamebub_cwl_bridge.c ../gamebub_cwl_logic.c ../gamebub_cwl_logic.h ../gamebub_cwl_gba.h ../cwl_proto.c ../cwl_lx.c | $(BUILD)
	$(CC) $(CFLAGS) gba_test.c ../gamebub_cwl_gba.c ../gamebub_cwl_bridge.c ../gamebub_cwl_logic.c ../cwl_proto.c ../cwl_lx.c -o $@

$(BUILD)/transport_bench_test: transport_bench_test.c ../gamebub_cwl.h | $(BUILD)
	$(CC) $(CFLAGS) -Werror transport_bench_test.c -o $@

$(BUILD)/transport_bench_runtime.inc: ../gamebub_cwl.c | $(BUILD)
	sed -n '/^\/\/ Radio-only benchmark/,/^static void run/p' $< | sed '$$d' > $@
$(BUILD)/transport_bench_control_test: transport_bench_control_test.c ../gamebub_cwl.h $(BUILD)/transport_bench_runtime.inc
	$(CC) $(CFLAGS) -Werror -I$(BUILD) transport_bench_control_test.c -o $@

$(BUILD)/sync_test: sync_test.c ../gamebub_cwl_sync.c ../gamebub_cwl_sync.h | $(BUILD)
	$(CC) $(CFLAGS) sync_test.c ../gamebub_cwl_sync.c -o $@
$(BUILD)/sync_rewrite_test: sync_rewrite_test.c ../gamebub_cwl_sync.c ../gamebub_cwl_sync.h | $(BUILD)
	$(CC) $(CFLAGS) sync_rewrite_test.c ../gamebub_cwl_sync.c -o $@
$(BUILD)/sync_gba_test: sync_gba_test.c ../gamebub_cwl_sync.c ../gamebub_cwl_sync.h | $(BUILD)
	$(CC) $(CFLAGS) sync_gba_test.c ../gamebub_cwl_sync.c -o $@
