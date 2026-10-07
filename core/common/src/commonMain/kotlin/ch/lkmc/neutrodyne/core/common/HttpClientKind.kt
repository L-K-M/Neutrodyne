// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * One value per OkHttpClient island client (01 Networking baseline): separate timeouts and
 * interceptors per purpose. Modules inject `NeutrodyneHttpClients`/`NetworkClients` — never
 * construct clients directly.
 */
enum class HttpClientKind { FEED, API, IMAGE, MEDIA, DOWNLOAD, YOUTUBE, SYNC }
