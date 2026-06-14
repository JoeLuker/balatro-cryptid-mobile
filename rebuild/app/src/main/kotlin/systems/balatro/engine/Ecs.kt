package systems.balatro.engine

/**
 * The composition core. No inheritance, no method-overriding, no monkeypatching.
 *
 * An entity is an Int id. Behaviour is NOT a subclass — it is data (Components)
 * operated on by Systems registered into an ordered Pipeline. A feature adds a
 * component type and/or registers a system; it never wraps-and-re-calls another
 * feature's function. That is the entire point: the thing that made the old
 * LÖVE build fragile (5-deep Game:update wrappers, eval_card override chains,
 * tear-down-mid-chain crashes) cannot be expressed here.
 *
 * Data-oriented: each component type is a contiguous store (struct-of-arrays in
 * spirit), so a system iterates one cache-coherent array, not a graph of objects.
 */

typealias Entity = Int

/** Marker for component data. Components are plain data classes — no behaviour. */
interface Component

/**
 * Per-type component storage. Dense-packed for cache-friendly iteration; an
 * entity->index map keeps add/remove/lookup O(1). Iteration order is stable
 * (insertion order) so anything that depends on order is deterministic.
 */
class Store<T : Component> {
    private val dense = ArrayList<T>()
    private val owner = ArrayList<Entity>()
    private val indexOf = HashMap<Entity, Int>()

    fun set(e: Entity, c: T) {
        val i = indexOf[e]
        if (i != null) { dense[i] = c } else {
            indexOf[e] = dense.size; dense.add(c); owner.add(e)
        }
    }

    fun get(e: Entity): T? = indexOf[e]?.let { dense[it] }
    fun has(e: Entity): Boolean = indexOf.containsKey(e)

    fun remove(e: Entity) {
        val i = indexOf.remove(e) ?: return
        val last = dense.size - 1
        if (i != last) {                       // swap-remove keeps it dense
            dense[i] = dense[last]; owner[i] = owner[last]; indexOf[owner[i]] = i
        }
        dense.removeAt(last); owner.removeAt(last)
    }

    /** Iterate (entity, component) in stable order. Hot path — no allocation. */
    inline fun each(action: (Entity, T) -> Unit) {
        for (i in 0 until size) action(ownerAt(i), at(i))
    }

    val size: Int get() = dense.size
    fun at(i: Int): T = dense[i]
    fun ownerAt(i: Int): Entity = owner[i]
}

/** The world owns entities and the component stores. No globals, no `G`. */
class World {
    private var nextId: Entity = 1
    private val stores = HashMap<Class<*>, Store<*>>()

    fun create(): Entity = nextId++

    @Suppress("UNCHECKED_CAST")
    fun <T : Component> store(type: Class<T>): Store<T> =
        stores.getOrPut(type) { Store<T>() } as Store<T>

    inline fun <reified T : Component> store(): Store<T> = store(T::class.java)

    inline fun <reified T : Component> add(e: Entity, c: T) = store<T>().set(e, c)
    inline fun <reified T : Component> get(e: Entity): T? = store<T>().get(e)
    inline fun <reified T : Component> has(e: Entity): Boolean = store<T>().has(e)

    fun destroy(e: Entity) { stores.values.forEach { (it as Store<Component>).remove(e) } }
}

/**
 * A System is a pure function over the world for one phase. Systems are DATA in
 * an ordered list — `order` is explicit and queryable, removal is one delete,
 * and no system owns the next system's invocation. This is the anti-override.
 */
fun interface System { fun run(world: World, dt: Float) }

private data class Registered(val order: Int, val key: String, val system: System)

/** One phase's ordered system registry. */
class Phase {
    private val systems = ArrayList<Registered>()

    fun register(key: String, order: Int = 500, system: System) {
        systems.removeAll { it.key == key }        // re-register replaces (idempotent rebuild)
        systems.add(Registered(order, key, system))
        systems.sortBy { it.order }
    }

    fun unregister(key: String) { systems.removeAll { it.key == key } }
    fun run(world: World, dt: Float) { for (r in systems) r.system.run(world, dt) }
    val count: Int get() = systems.size
}

/** The whole engine: a world plus named phases run in a fixed order each tick. */
class Engine {
    val world = World()
    val simulate = Phase()   // input -> effect/scoring -> state
    val layout = Phase()     // positions / visibility (the felt)
    val render = Phase()     // emit draw commands (board); chrome is native Compose

    fun tick(dt: Float) {
        simulate.run(world, dt)
        layout.run(world, dt)
        render.run(world, dt)
    }
}
