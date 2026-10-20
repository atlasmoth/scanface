package com.scanface.platform

import kotlin.math.abs

internal data class CameraZoomRequest(val token: Long, val ratio: Float)

/**
 * Serializes asynchronous camera zoom changes without losing the final gesture
 * target. Call from one thread. Only [confirmed] may be used as capture metadata;
 * capture must remain blocked while [unsettled] is true.
 */
internal class CameraZoomQueue {
    var minZoom: Float = 1f
        private set
    var maxZoom: Float = 1f
        private set
    var confirmed: Float = 1f
        private set
    var desired: Float = 1f
        private set
    var pending: CameraZoomRequest? = null
        private set

    val unsettled: Boolean
        get() = pending != null || abs(desired - confirmed) > TOLERANCE

    private var nextToken = 0L

    /** Bounds have already been restricted to the active lens and product cap. */
    fun reset(min: Float, max: Float, confirmed: Float) {
        require(min.isFinite() && max.isFinite() && min > 0f && max >= min)
        require(confirmed.isFinite())
        nextToken++
        minZoom = min
        maxZoom = max
        this.confirmed = confirmed.coerceIn(min, max)
        desired = this.confirmed
        pending = null
    }

    /** Returns work to dispatch, or queues the latest target behind pending work. */
    fun request(value: Float, force: Boolean = false): CameraZoomRequest? {
        if (!value.isFinite()) return null
        desired = value.coerceIn(minZoom, maxZoom)
        if (pending != null) return null
        if (!force && abs(desired - confirmed) <= TOLERANCE) {
            desired = confirmed
            return null
        }
        return dispatch()
    }

    /** An acknowledged target may immediately release the next coalesced request. */
    fun acknowledge(request: CameraZoomRequest): CameraZoomRequest? {
        val active = pending ?: return null
        if (active.token != request.token) return null
        confirmed = active.ratio
        pending = null
        if (abs(desired - confirmed) > TOLERANCE) return dispatch()
        desired = confirmed
        return null
    }

    /** Invalidates callbacks; the owner must also cancel/reset ambiguous hardware. */
    fun cancel() {
        nextToken++
        pending = null
        desired = confirmed
    }

    private fun dispatch(): CameraZoomRequest =
        CameraZoomRequest(++nextToken, desired).also { pending = it }

    private companion object {
        const val TOLERANCE = .001f
    }
}
