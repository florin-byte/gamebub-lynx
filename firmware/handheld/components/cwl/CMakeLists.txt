idf_component_register(SRCS "gamebub_crash.c" "cwl_proto.c" "cwl_lx.c" "gamebub_cwl_logic.c" "gamebub_cwl_bridge.c" "gamebub_cwl_gba.c" "gamebub_cwl.c" "gamebub_cwl_peers.c" "gamebub_cwl_sync.c"
                       INCLUDE_DIRS "."
                       REQUIRES esp_wifi esp_event esp_netif esp_timer esp_hw_support heap freertos)
