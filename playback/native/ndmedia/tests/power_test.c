/* SPDX-License-Identifier: Unlicense */
/* The power shim's create/release/idempotent contract (11 Power). Linked
 * against the built library because the state is native; Linux keeps the
 * portal path and does not build this test. */
#include "session.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    assert(nd_power_keep_awake(1) == 0);
    assert(nd_power_keep_awake(1) == 0);
    assert(nd_power_keep_awake(0) == 0);
    assert(nd_power_keep_awake(0) == 0);
    assert(nd_power_keep_awake(2) == ND_E_ARG);
    printf("power assertion create/release/idempotent ok\n");
    return 0;
}
