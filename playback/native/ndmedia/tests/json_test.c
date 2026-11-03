/* SPDX-License-Identifier: Unlicense */
#include "json.h"
#include <assert.h>
#include <string.h>

int main(void) {
    char result[256];
    const char *expected = "Headset \\\"A\\\" \\\\ line\\u000a\\u0001";
    assert(nd_json_escape(result, sizeof(result), "Headset \"A\" \\ line\n\001") == (int)strlen(expected));
    assert(strcmp(result, expected) == 0);
    assert(nd_json_escape(result, sizeof(result), "\xc3\xa4") == 2);
    assert(strcmp(result, "\xc3\xa4") == 0);
    assert(nd_json_escape(result, 1, "") == 0 && result[0] == 0);
    assert(nd_json_escape(result, 2, "x") == 1);
    assert(nd_json_escape(result, 1, "x") == -1);
    assert(nd_json_escape(result, 6, "\n") == -1);
    assert(nd_json_escape(result, 0, "") == -1);
    assert(nd_json_escape(result, sizeof(result), NULL) == -1);
    return 0;
}
