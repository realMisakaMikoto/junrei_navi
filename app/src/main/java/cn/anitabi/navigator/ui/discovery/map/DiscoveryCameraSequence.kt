package cn.anitabi.navigator.ui.discovery.map

/** One camera request at a time; each dependent update waits for a matching native finish. */
internal class DiscoveryCameraSequence {
    class Step(val isComplete: () -> Boolean, val apply: () -> Unit, val canSkip: Boolean = true)
    private class Request(val steps: List<Step>, val onComplete: () -> Unit) {
        var index = 0
    }
    private var current: Request? = null
    val isPending: Boolean get() = current != null

    fun start(steps: List<Step>, onComplete: () -> Unit) {
        val request = Request(steps, onComplete)
        current = request
        advance(request)
    }

    fun onCameraFinish() {
        val request = current ?: return
        if (!request.steps[request.index].isComplete()) return
        request.index++
        advance(request)
    }

    fun cancel() { current = null }

    private fun advance(request: Request) {
        try {
            while (current === request && request.index < request.steps.size) {
                val step = request.steps[request.index]
                if (step.canSkip && step.isComplete()) request.index++
                else {
                    step.apply()
                    return
                }
            }
            if (current === request) {
                current = null
                request.onComplete()
            }
        } catch (error: RuntimeException) {
            if (current === request) current = null
            throw error
        }
    }
}
