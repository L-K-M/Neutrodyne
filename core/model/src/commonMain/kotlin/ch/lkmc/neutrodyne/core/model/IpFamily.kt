// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * IPv4 vs IPv6 address family (04 IP-family matching): playability scoring prefers formats whose
 * `ip=` address family matches the client's [IpFamily] learned at boot. Compared by enum only —
 * never by string.
 */
enum class IpFamily { V4, V6 }
