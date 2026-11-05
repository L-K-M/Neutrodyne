/* SPDX-License-Identifier: Unlicense */
#ifndef NDMEDIA_JSON_H
#define NDMEDIA_JSON_H
#include <stddef.h>

/* Device names are OS input, not JSON literals. Return bytes excluding NUL. */
static int nd_json_escape(char *output, size_t capacity, const char *input) {
    static const char hex[] = "0123456789abcdef";
    size_t used = 0;
    if (!output || !input || !capacity)
        return -1;
    for (const unsigned char *p = (const unsigned char *)input; *p; ++p) {
        size_t width = *p < 32 ? 6 : (*p == '"' || *p == '\\' ? 2 : 1);
        if (width >= capacity - used)
            return -1;
        if (*p < 32) {
            output[used++] = '\\';
            output[used++] = 'u';
            output[used++] = '0';
            output[used++] = '0';
            output[used++] = hex[*p >> 4];
            output[used++] = hex[*p & 15];
        } else {
            if (width == 2)
                output[used++] = '\\';
            output[used++] = (char)*p;
        }
    }
    output[used] = 0;
    return (int)used;
}
#endif
