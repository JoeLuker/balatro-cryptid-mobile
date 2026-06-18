package systems.balatro.engine

/** A can/is interactivity toggle (node.lua states). `is` is a Kotlin keyword, so the active flag is `isOn`. */
class Toggle(var can: Boolean = true, var isOn: Boolean = false)

/** node.lua `self.states` — visibility + the interaction flags (collision/hover/click/drag are P3). */
class NodeStates {
    var visible = true
    val collide = Toggle(can = false)
    val focus = Toggle(can = false)
    val hover = Toggle(can = true)
    val click = Toggle(can = true)
    val drag = Toggle(can = true)
    val releaseOn = Toggle(can = true)
}

/**
 * Replaces Balatro's global instance tables (`G.MOVEABLES`, `G.I.NODE/MOVEABLE`, `G.STAGE_OBJECTS`)
 * with an injected registry (P0-T4) — the main loop iterates [moveables] to call `move` each frame.
 * Removal is DEFERRED: nodes mark themselves removed and queue here, and [flushRemovals] compacts the
 * lists at a safe point, so a node can remove itself (or its subtree) mid-iteration without a
 * concurrent-modification hazard.
 */
class SceneRegistry {
    /** G.ROOM — the default container + the room transform calculate_parrallax reads. */
    var room: Node? = null
    private var idCounter = 1L
    val nodes = ArrayList<Node>()
    val moveables = ArrayList<Moveable>()
    private val pending = ArrayList<Node>()

    fun nextId(): Long = idCounter++
    fun registerNode(n: Node) { nodes.add(n) }
    fun registerMoveable(m: Moveable) { moveables.add(m) }
    fun queueRemoval(n: Node) { pending.add(n) }

    fun flushRemovals() {
        if (pending.isEmpty()) return
        val set = pending.toHashSet()
        nodes.removeAll(set)
        moveables.removeAll(set.filterIsInstance<Moveable>().toHashSet())
        pending.clear()
    }
}

/**
 * Port of engine/node.lua (P0-T4 lifecycle + P0-T5 scene graph). Everything visible — and some
 * invisible anchors like G.ROOM — is a Node: it carries a transform [T] (collision alias [ct]), a
 * config bag, interaction [states], a per-frame epoch ([frameDraw]/[frameMove]) to avoid double
 * work, a [container] reference frame, and a keyed [children] tree.
 *
 * Input/collision/popup methods (collides_with_point, drag, hover, set_offset) are the Controller's
 * concern (P3) and are intentionally left as prototypes here; drawing is the Compose render layer
 * (P2) — [draw]/[drawSelf] keep the structural recursion so the order is owned by the tree.
 */
open class Node(
    val scene: SceneRegistry,
    val T: Transform = Transform(),
    container: Node? = null,
    createdWhilePaused: Boolean = false,
) {
    /** collision transform — aliases T unless a subclass overrides (node.lua:36). */
    var ct: Transform = T
    val config: MutableMap<String, Any?> = HashMap()
    val id: Long = scene.nextId()

    var frameDraw = -1L
    var frameMove = -1L

    val states = NodeStates()
    var container: Node? = container ?: scene.room
    /** keyed tree: named children (h_popup/d_popup) + positional keys (node.lua:75). */
    val children = LinkedHashMap<String, Node>()
    val createdOnPause = createdWhilePaused
    var removed = false
        private set

    init { scene.registerNode(this) }

    /** node.lua:129 — draw self, then recurse visible children (z-order owned by the tree). */
    open fun draw() {
        if (!states.visible) return
        drawSelf()
        for (v in children.values) v.draw()
    }

    /** Render hook — the Compose layer (P2) supplies the actual draw; base is structural-only. */
    open fun drawSelf() {}

    /** node.lua:304 (set_container) — re-parent self and the whole subtree onto a new container frame. */
    fun reparent(c: Node) {
        for (v in children.values) v.reparent(c)
        container = c
    }

    /** node.lua:326 — recursively remove the subtree and deregister (deferred). */
    open fun remove() {
        for (v in children.values.toList()) v.remove()
        scene.queueRemoval(this)
        removed = true
    }

    // Prototypes (node.lua:387-396) — overridden by subclasses / wired to the loop later.
    open fun update(dt: Double) {}
    open fun animate() {}
    open fun click() {}
    open fun release(dragged: Node?) {}
}
