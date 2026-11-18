package cc.catsco.android.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private data class FlowNode(val u: Float, val v: Float, val phase: Float, val radius: Float, val alpha: Float)
private data class FlowParticle(val progress: Float, val v: Float, val phase: Float, val radius: Float, val alpha: Float, val duration: Float)
private data class FlowEdge(val from: Int, val to: Int)
private data class FlowScene(val nodes: List<FlowNode>, val edges: List<FlowEdge>, val particles: List<FlowParticle>)
private const val Tau = (Math.PI * 2).toFloat()

private fun flowScene(): FlowScene {
    val random = Random(0xca75c0)
    val nodes = List(40) {
        FlowNode(random.nextFloat() * 2.24f - 1.12f, random.nextFloat() * 2.16f - 1.08f,
            random.nextFloat() * Tau, .72f + random.nextFloat() * .98f, .2f + random.nextFloat() * .25f)
    }
    val edges = mutableSetOf<FlowEdge>()
    nodes.forEachIndexed { index, node ->
        nodes.indices.filter { it != index }.sortedBy { other ->
            val dx = node.u - nodes[other].u
            val dy = (node.v - nodes[other].v) * .72f
            dx * dx + dy * dy
        }.take(3).forEach { other -> edges += FlowEdge(minOf(index, other), maxOf(index, other)) }
    }
    val particles = List(100) { index ->
        FlowParticle(random.nextFloat(), -1.02f + ((index + random.nextFloat() * .4f) / 100) * 2.04f,
            random.nextFloat() * Tau, .45f + random.nextFloat() * 1.05f,
            .16f + random.nextFloat() * .36f, 9f + random.nextFloat() * 6f)
    }
    return FlowScene(nodes, edges.toList(), particles)
}

private fun flowPoint(u: Float, v: Float, width: Float, height: Float, seconds: Float): Offset {
    val fold = sin(u * 3.4f - seconds * .72f) * (.62f + cos(v * Math.PI.toFloat()) * .2f) +
        cos(u * 1.55f + v * 2.8f + seconds * .43f) * .42f
    val twist = sin(u * 2.1f - seconds * .36f) * v
    return Offset(width * .5f + u * width * .51f + twist * width * .045f,
        height * .46f + u * height * .2f + v * height * .37f + fold * height * .08f)
}

/** Read the clock in the draw phase so animation never recomposes the login form. */
@Composable
internal fun AuthFlowBackground(active: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    val scene = remember { flowScene() }
    var seconds by remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(active, lifecycle) {
        if (!active || !ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastFrame = 0L
            while (isActive) withInfiniteAnimationFrameNanos { now ->
                if (lastFrame == 0L) lastFrame = now
                if (now - lastFrame >= 33_000_000) {
                    seconds += ((now - lastFrame) / 1_000_000_000f).coerceAtMost(.08f)
                    lastFrame = now
                }
            }
        }
    }
    val color = if (dark) Color(0xFF7CDCC2) else Color(0xFF0E8968)
    Canvas(modifier) { drawFlow(scene, seconds, color) }
}

private fun DrawScope.drawFlow(scene: FlowScene, seconds: Float, color: Color) {
    // Scene coordinates use logical pixels, matching the preview at any screen density.
    val width = size.width / density
    val height = size.height / density
    fun point(u: Float, v: Float): Offset = flowPoint(u, v, width, height, seconds) * density
    val positions = scene.nodes.map { node -> point(node.u, node.v + sin(seconds * .45f + node.phase) * .025f) }
    val face = Path()
    scene.edges.forEachIndexed { index, edge ->
        val start = positions[edge.from]
        val end = positions[edge.to]
        drawLine(color.copy(alpha = .085f), start, end, .55f * density)
        if (index % 3 == 0) {
            val third = positions[(edge.to + 1) % positions.size]
            face.reset()
            face.moveTo(start.x, start.y)
            face.lineTo(end.x, end.y)
            face.lineTo(third.x, third.y)
            face.close()
            drawPath(face, color.copy(alpha = .009f))
        }
    }
    scene.nodes.forEachIndexed { index, node ->
        val anchor = index % 11 == 0
        drawCircle(color.copy(alpha = (node.alpha + if (anchor) .18f else 0f).coerceAtMost(.75f)),
            node.radius * (if (anchor) 2.1f else 1f) * density, positions[index])
    }
    scene.particles.forEachIndexed { index, particle ->
        val raw = (particle.progress + seconds / particle.duration) % 1f
        val progress = if (raw < .65f) raw / .65f * .44f else .44f + (raw - .65f) / .35f * .56f
        val u = -1.16f + progress * 2.32f
        val v = particle.v + sin(seconds * .75f + particle.phase + u * 2.2f) * .055f
        val position = point(u, v)
        val edgeFade = min(1f, min(progress / .055f, (1 - progress) / .055f)).coerceAtLeast(0f)
        val alpha = particle.alpha * edgeFade
        if (index % 7 == 0) drawLine(color.copy(alpha = alpha * .34f),
            point(u - .016f, v), position, .65f * density)
        drawCircle(color.copy(alpha = alpha), particle.radius * (if (index % 17 == 0) 1.65f else 1f) * density, position)
    }
}
