package org.example.miniwsa.event;

/** Optional geo info — informational only, never a rejection reason (spec §3). */
public record GeoLocation(String country, String city) {
}
