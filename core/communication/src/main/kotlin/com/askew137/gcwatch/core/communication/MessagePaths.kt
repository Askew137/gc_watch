package com.askew137.gcwatch.core.communication

object MessagePaths {
    /** Phone → Watch: set active navigation target. Payload: protobuf Waypoint. */
    const val SET_TARGET = "/set_target"

    /**
     * Watch → Phone: sent automatically once the watch has both a target and a GPS fix, so the
     * route is ready before the user switches to it. Payload: protobuf RouteRequest.
     */
    const val ROUTE_REQUEST = "/route_request"

    /**
     * Phone → Watch: answer to ROUTE_REQUEST. Payload: protobuf ComputedRoute; an empty
     * `segments` list means "no route available" (missing data, no road nearby, ...).
     */
    const val ROUTE_RESULT = "/route_result"

    /** Bidirectional: config changed. Payload: protobuf AppConfig. */
    const val CONFIG_SYNC = "/config_sync"

    /** Phone → Watch: live location update when GpsSource = PHONE_FUSED. Payload: protobuf PhoneLocation. */
    const val PHONE_LOCATION = "/phone_location"

    /** Watch → Phone: watch-side GPS fix for phone's awareness. Payload: protobuf PhoneLocation. */
    const val WATCH_LOCATION = "/watch_location"

    /** Phone → Watch (ChannelClient path): the watch APK of a new release; the watch asks to install it. */
    const val CHANNEL_UPDATE_WEAR_APK = "/update/wear.apk"

    /** Watch → Phone: the update APK arrived and is stored (the phone may close the channel). */
    const val UPDATE_WEAR_RECEIVED = "/update/wear_received"
}

/** DataClient item path for persistent config sync. */
object DataPaths {
    const val CONFIG = "/data/config"
}
