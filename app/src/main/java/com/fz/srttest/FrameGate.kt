package com.fz.srttest

/**
 * 照 silu `UvcFpsPolicy.FrameGate`：令牌桶按目标帧率放行，摄像头给得比推流帧率多时在入口丢掉，
 * 后面的拷贝/编码/封装/发送全部省掉（最多攒 2 帧突发）。
 */
class FrameGate {
    private var targetFps = 0
    private var available = 0.0
    private var lastNs = Long.MIN_VALUE

    @Volatile var dropped = 0
        private set

    @Synchronized
    fun shouldAccept(nowNs: Long, fps: Int): Boolean {
        val f = fps.coerceIn(1, 120)
        if (lastNs != Long.MIN_VALUE && nowNs > lastNs && f == targetFps) {
            available = minOf(MAX_BURST, available + (nowNs - lastNs) * f / 1e9)
            lastNs = nowNs
            if (available + 1e-6 < 1.0) {
                dropped++
                return false
            }
            available = maxOf(0.0, available - 1.0)
            return true
        }
        targetFps = f
        lastNs = nowNs
        available = 0.0
        return true
    }

    @Synchronized
    fun reset() {
        lastNs = Long.MIN_VALUE
        available = 0.0
        targetFps = 0
    }

    private companion object {
        const val MAX_BURST = 2.0
    }
}
