#include "gamebub_cwl.h"
#include "esp_private/panic_internal.h"

extern bool g_panic_abort;
extern char *g_panic_abort_details;

const char *gamebub_crash_reason(const void *raw)
{
    const panic_info_t *info = raw;
    // The default stack-overflow hook includes the offending task name here.
    if (g_panic_abort && g_panic_abort_details) {
        return g_panic_abort_details;
    }
    return info && info->reason ? info->reason : "Unknown ESP-IDF panic";
}
