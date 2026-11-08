// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.common

/** Per-request policy on clients that can attach publisher credentials. */
enum class PublisherAuthPolicy {
    DEFAULT,
    ANONYMOUS,
    ;

    companion object {
        /** Driver hint shared by Ktor and raw consumers; AuthInterceptor removes it before IO. */
        const val HEADER = "X-Neutrodyne-Publisher-Auth"
    }
}
