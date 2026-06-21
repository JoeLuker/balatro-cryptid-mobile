package systems.balatro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import systems.balatro.bridge.Telemetry

/**
 * Joker art from the REUSED atlases (base Jokers.png + Cryptid atlasone/two/three/exotic).
 * Every atlas uses the same 142x190 cell; the (atlas, col, row) for each ported joker is
 * taken straight from the original definitions. Needed atlases are each decoded ONCE and
 * the cells cropped off the main thread, same cache discipline as the card/board art.
 */
object JokerArt {
    // key -> (atlas asset file, col, row), from each joker's original `atlas` + `pos`.
    // Extracted from the source defs: vanilla from game.lua (set="Joker" -> Jokers.png), Cryptid
    // from mods/Cryptid/items/*.lua (atlas + pos). 132/160 CATALOG jokers; the unmapped ones either
    // carry no `atlas` field in source (fspinner, jimball, wee_fib, wheelhope — Cryptid default) or
    // are later-batch additions awaiting a verified cell — both left to the name placeholder for now.
    private val MAP: Map<String, Triple<String, Int, Int>> = mapOf(
        "j_abstract" to Triple("Jokers.png", 3, 3),   // Abstract Joker (game.lua pos x=3,y=3)
        "j_arrowhead" to Triple("Jokers.png", 1, 8),
        "j_clever" to Triple("Jokers.png", 2, 14),     // Clever Joker — Two Pair chips (game.lua pos x=2,y=14)
        "j_crafty" to Triple("Jokers.png", 4, 14),     // Crafty Joker — Flush chips (game.lua pos x=4,y=14)
        "j_crazy" to Triple("Jokers.png", 5, 0),       // Crazy Joker — Straight mult (game.lua pos x=5,y=0)
        "j_cry_annihalation" to Triple("atlasthree.png", 8, 7),
        "j_cry_antennastoheaven" to Triple("atlasone.png", 3, 1),
        "j_cry_big_cube" to Triple("atlasone.png", 4, 4),
        "j_cry_bonkers" to Triple("atlasthree.png", 8, 5),
        "j_cry_brokenhome" to Triple("atlasthree.png", 1, 7),
        "j_cry_clash" to Triple("atlasthree.png", 8, 1),
        "j_cry_clicked_cookie" to Triple("atlastwo.png", 2, 6),
        "j_cry_cube" to Triple("atlasone.png", 5, 4),
        "j_cry_cursor" to Triple("atlasone.png", 4, 1),
        "j_cry_delirious" to Triple("atlasthree.png", 4, 5),
        "j_cry_discreet" to Triple("atlasthree.png", 6, 6),
        "j_cry_dubious" to Triple("atlasthree.png", 0, 6),
        "j_cry_duos" to Triple("atlasthree.png", 0, 0),
        "j_cry_duplicare" to Triple("atlasexotic.png", 0, 6),
        "j_cry_eternalflame" to Triple("atlasone.png", 0, 4),
        "j_cry_exoplanet" to Triple("atlastwo.png", 1, 2),
        "j_cry_exponentia" to Triple("atlasexotic.png", 0, 0),
        "j_cry_exposed" to Triple("atlastwo.png", 0, 5),
        "j_cry_filler" to Triple("atlasthree.png", 0, 1),
        "j_cry_foolhardy" to Triple("atlasthree.png", 8, 2),
        "j_cry_formidiulosus" to Triple("atlasexotic.png", 6, 4),
        "j_cry_foxy" to Triple("atlasthree.png", 3, 6),
        "j_cry_fuckedup" to Triple("atlasthree.png", 7, 2),
        "j_cry_giggly" to Triple("atlasthree.png", 0, 5),
        "j_cry_happyhouse" to Triple("atlastwo.png", 2, 4),
        "j_cry_home" to Triple("atlasthree.png", 2, 0),
        "j_cry_iterum" to Triple("atlasexotic.png", 0, 1),
        "j_cry_kittyprinter" to Triple("atlasone.png", 3, 5),
        "j_cry_kooky" to Triple("atlasthree.png", 6, 5),
        "j_cry_krustytheclown" to Triple("atlasone.png", 3, 4),
        "j_cry_lightupthenight" to Triple("atlasone.png", 1, 1),
        "j_cry_manic" to Triple("atlasthree.png", 2, 5),
        "j_cry_mask" to Triple("atlastwo.png", 1, 5),
        "j_cry_maximized" to Triple("atlastwo.png", 5, 2),
        "j_cry_meteor" to Triple("atlastwo.png", 0, 2),
        "j_cry_monkey_dagger" to Triple("atlastwo.png", 4, 3),
        "j_cry_nice" to Triple("atlasone.png", 2, 3),
        "j_cry_night" to Triple("atlasthree.png", 3, 1),
        "j_cry_nosound" to Triple("atlasone.png", 2, 1),
        "j_cry_nuts" to Triple("atlasthree.png", 1, 0),
        "j_cry_nutty" to Triple("atlasthree.png", 1, 5),
        "j_cry_oldblueprint" to Triple("atlasthree.png", 2, 1),
        "j_cry_pirate_dagger" to Triple("atlastwo.png", 3, 3),
        "j_cry_pizza_slice" to Triple("atlastwo.png", 6, 4),
        "j_cry_primus" to Triple("atlasexotic.png", 0, 4),
        "j_cry_quintet" to Triple("atlasthree.png", 3, 0),
        "j_cry_savvy" to Triple("atlasthree.png", 4, 6),
        "j_cry_shrewd" to Triple("atlasthree.png", 1, 6),
        "j_cry_silly" to Triple("atlasthree.png", 3, 5),
        "j_cry_stardust" to Triple("atlastwo.png", 2, 2),
        "j_cry_stronghold" to Triple("atlasthree.png", 8, 4),
        "j_cry_subtle" to Triple("atlasthree.png", 5, 6),
        "j_cry_swarm" to Triple("atlasthree.png", 5, 0),
        "j_cry_the" to Triple("atlasthree.png", 5, 7),
        "j_cry_tricksy" to Triple("atlasthree.png", 2, 6),
        "j_cry_triplet_rhythm" to Triple("atlastwo.png", 0, 4),
        "j_cry_unity" to Triple("atlasthree.png", 4, 0),
        "j_cry_unjust_dagger" to Triple("atlasone.png", 3, 0),
        "j_cry_verisimile" to Triple("atlasexotic.png", 6, 5),
        "j_cry_wacky" to Triple("atlasthree.png", 5, 5),
        "j_cry_waluigi" to Triple("atlastwo.png", 0, 3),
        "j_cry_weegaming" to Triple("atlastwo.png", 3, 4),
        "j_cry_whip" to Triple("atlasone.png", 5, 3),
        "j_cry_words_cant_even" to Triple("atlasthree.png", 6, 7),
        "j_cry_wtf" to Triple("atlasthree.png", 7, 1),
        "j_cry_zooble" to Triple("atlasone.png", 1, 5),
        "j_banner" to Triple("Jokers.png", 1, 2),        // Banner — +30 Chips/remaining discard (game.lua pos x=1,y=2)
        "j_baron" to Triple("Jokers.png", 6, 12),        // Baron — King held: X1.5 Mult (game.lua pos x=6,y=12)
        "j_baseball" to Triple("Jokers.png", 6, 14),     // Baseball Card — X1.5/Uncommon joker (game.lua pos x=6,y=14)
        "j_blue_joker" to Triple("Jokers.png", 7, 10),   // Blue Joker — +2 Chips/deck card (game.lua pos x=7,y=10)
        "j_campfire" to Triple("Jokers.png", 5, 15),     // Campfire — Xmult +0.25/joker sold (game.lua pos x=5,y=15)
        "j_castle" to Triple("Jokers.png", 9, 15),       // Castle — +3 Chips/suited card discarded (game.lua pos x=9,y=15)
        "j_devious" to Triple("Jokers.png", 3, 14),    // Devious Joker — Straight chips (game.lua pos x=3,y=14)
        "j_dusk" to Triple("Jokers.png", 4, 7),         // Dusk — retrigger all played on last hand (game.lua pos x=4,y=7)
        "j_drivers_license" to Triple("Jokers.png", 0, 7),  // Driver's License — x3 if >=16 enhanced (game.lua pos x=0,y=7)
        "j_droll" to Triple("Jokers.png", 6, 0),       // Droll Joker — Flush mult (game.lua pos x=6,y=0)
        "j_even_steven" to Triple("Jokers.png", 8, 3),
        "j_fibonacci" to Triple("Jokers.png", 1, 5),
        "j_flower_pot" to Triple("Jokers.png", 0, 6),
        "j_four_fingers" to Triple("Jokers.png", 6, 6),  // Four Fingers — flush/straight need 4 (game.lua pos x=6,y=6)
        "j_gluttenous_joker" to Triple("Jokers.png", 9, 1),
        "j_green_joker" to Triple("Jokers.png", 2, 11),  // Green Joker — +1 Mult/hand, -1/discard (game.lua pos x=2,y=11)
        "j_greedy_joker" to Triple("Jokers.png", 6, 1),
        "j_hack" to Triple("Jokers.png", 5, 2),          // Hack — retrigger 2/3/4/5 (game.lua pos x=5,y=2)
        "j_half" to Triple("Jokers.png", 7, 0),
        "j_hanging_chad" to Triple("Jokers.png", 9, 6),  // Hanging Chad — retrigger first scored card (game.lua pos x=9,y=6)
        "j_hologram" to Triple("Jokers.png", 4, 12),     // Hologram — Xmult +0.25/added card (game.lua pos x=4,y=12)
        "j_joker" to Triple("Jokers.png", 0, 0),
        "j_jolly" to Triple("Jokers.png", 2, 0),       // Jolly Joker — Pair mult (game.lua pos x=2,y=0)
        "j_lusty_joker" to Triple("Jokers.png", 7, 1),
        "j_loyalty_card" to Triple("Jokers.png", 4, 2),  // Loyalty Card — X4 every 5th hand (game.lua pos x=4,y=2)
        "j_mad" to Triple("Jokers.png", 4, 0),         // Mad Joker — Two Pair mult (game.lua pos x=4,y=0)
        "j_mime" to Triple("Jokers.png", 4, 1),         // Mime — retrigger held cards with effects (game.lua pos x=4,y=1)
        "j_odd_todd" to Triple("Jokers.png", 9, 3),
        "j_obelisk" to Triple("Jokers.png", 9, 12),      // Obelisk — Xmult +0.2 per non-this-type hand (game.lua pos x=9,y=12)
        "j_onyx_agate" to Triple("Jokers.png", 2, 8),
        "j_pareidolia" to Triple("Jokers.png", 6, 3),  // Pareidolia — every card is a face (game.lua pos x=6,y=3)
        "j_photograph" to Triple("Jokers.png", 2, 13),
        "j_raised_fist" to Triple("Jokers.png", 8, 2),  // Raised Fist — +2x lowest held card (game.lua pos x=8,y=2)
        "j_ramen" to Triple("Jokers.png", 2, 15),        // Ramen — Xmult starts x2, -0.01/discard (game.lua pos x=2,y=15)
        "j_red_card" to Triple("Jokers.png", 7, 11),     // Red Card — +3 Mult/skip (game.lua pos x=7,y=11)
        "j_runner" to Triple("Jokers.png", 3, 10),       // Runner — +15 Chips/Straight (game.lua pos x=3,y=10)
        "j_scary_face" to Triple("Jokers.png", 2, 3),
        "j_scholar" to Triple("Jokers.png", 0, 4),
        "j_seeing_double" to Triple("Jokers.png", 4, 4),
        "j_shoot_the_moon" to Triple("Jokers.png", 2, 6),  // Shoot the Moon — Queen held: +13 Mult (game.lua pos x=2,y=6)
        "j_shortcut" to Triple("Jokers.png", 3, 12),   // Shortcut — straights skip one rank (game.lua pos x=3,y=12)
        "j_sly" to Triple("Jokers.png", 0, 14),        // Sly Joker — Pair chips (game.lua pos x=0,y=14)
        "j_smeared" to Triple("Jokers.png", 4, 6),     // Smeared Joker — red/black suits collide (game.lua pos x=4,y=6)
        "j_smiley" to Triple("Jokers.png", 6, 15),
        "j_sock_and_buskin" to Triple("Jokers.png", 3, 1),  // Sock and Buskin — retrigger faces (game.lua pos x=3,y=1)
        "j_spare_trousers" to Triple("Jokers.png", 4, 15), // Spare Trousers — +2 Mult/Two Pair (game.lua j_trousers pos x=4,y=15)
        "j_square" to Triple("Jokers.png", 9, 11),     // Square Joker — +4 Chips/5-card hand (game.lua pos x=9,y=11)
        "j_steel_joker" to Triple("Jokers.png", 7, 2), // Steel Joker — X(1+0.2*steel) (game.lua pos x=7,y=2)
        "j_stone" to Triple("Jokers.png", 9, 0),       // Stone Joker — +25 Chips/stone card (game.lua pos x=9,y=0)
        "j_supernova" to Triple("Jokers.png", 2, 4),    // Supernova — +1 Mult/times this hand played (game.lua pos x=2,y=4)
        "j_swashbuckler" to Triple("Jokers.png", 9, 5), // Swashbuckler — +Mult per joker sell value (game.lua pos x=9,y=5)
        "j_throwback" to Triple("Jokers.png", 5, 7),    // Throwback — Xmult +0.25/skipped blind (game.lua pos x=5,y=7)
        "j_splash" to Triple("Jokers.png", 6, 10),     // Splash — every played card scores (game.lua pos x=6,y=10)
        "j_stuntman" to Triple("Jokers.png", 8, 6),
        "j_triboulet" to Triple("Jokers.png", 4, 8),
        "j_walkie_talkie" to Triple("Jokers.png", 8, 15),
        "j_wee" to Triple("Jokers.png", 0, 0),          // Wee Joker — +8 Chips/5-card hand (game.lua pos x=0,y=0; same cell as j_joker)
        "j_wily" to Triple("Jokers.png", 1, 14),       // Wily Joker — Three of a Kind chips (game.lua pos x=1,y=14)
        "j_wrathful_joker" to Triple("Jokers.png", 8, 1),
        "j_zany" to Triple("Jokers.png", 3, 0),        // Zany Joker — Three of a Kind mult (game.lua pos x=3,y=0)
    )

    /** Crop the cells for `keys`, decoding each distinct atlas only once. */
    fun cache(ctx: Context, keys: List<String>): Map<String, ImageBitmap> {
        val out = HashMap<String, ImageBitmap>()
        val byAtlas = keys.distinct().mapNotNull { k -> MAP[k]?.let { k to it } }.groupBy { it.second.first }
        for ((file, entries) in byAtlas) {
            val atlas = try {
                ctx.assets.open("textures/$file").use { BitmapFactory.decodeStream(it) }
            } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString(), "file" to file); continue }
            for ((k, t) in entries) {
                val (_, col, row) = t
                out[k] = Bitmap.createBitmap(atlas, col * 142, row * 190, 142, 190).asImageBitmap()
            }
        }
        return out
    }
}
