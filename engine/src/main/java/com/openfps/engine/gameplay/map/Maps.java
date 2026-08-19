/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.gameplay.Pickup;
import com.openfps.engine.gameplay.Weapon;

import java.util.List;

/**
 * The factory class for every shipped {@link MapSpec}.
 *
 * <p>One static method per map; the method name is the id, the return value
 * is the spec. {@link MapLibrary#registerDefaults()} is the one place that
 * knows which methods to call, and the rest of the codebase asks the
 * library for a map by id rather than reaching into this class directly.
 * The split is the load-bearing one: a future pass that wants to add a
 * second map touches this class and {@code registerDefaults} — nothing
 * else.</p>
 *
 * <p>The data is in Java source rather than a text file because
 * {@code AGENTS.md} says "match the project's conventions" and the project
 * does not ship a parser for any data format. Every map is fully
 * self-documenting from its constructor calls; the ASCII art, the lane
 * descriptions and the rationale for each placement sit in
 * {@code docs/maps/&lt;setting&gt;/&lt;id&gt;.md}.</p>
 */
public final class Maps
{
    private Maps()
    {
        // utility class
    }

    // ----- Cornerstone (Urban Warzone × TDM) --------------------------------

    /**
     * The first shipped map. Urban Warzone, TDM, MW2-Rust / BO6 large-map
     * sizing (3200 x 3200 world units, MAP_SCALE = 16 → 200 m square),
     * three-lane COD layout. The level .ofm is the original 320 x 320
     * kit composition, left untouched and treated as a centerpiece in
     * the middle of the larger spec. The full design spec — the ASCII
     * map, the callouts, the lane routes, the spawn rationale — is in
     * {@code docs/maps/urban-warzone/01-cornerstone.md}.
     *
     * @return the Cornerstone map spec
     */
    public static MapSpec cornerstone()
    {
        return new MapSpec(
            // ---- identity
            "cornerstone",
            "Cornerstone",
            MapSetting.URBAN_WARZONE,
            MatchMode.TDM,
            // ---- playable area: 3200 x 3200, 128 high (one
            // MAX_OPEN_HEIGHT floor). All x/z in the spec are
            // scaled by 10 from the original 320 x 320 spec; the
            // level .ofm remains a 320 x 320 centerpiece.
            new MapDimensions(3200.0f, 3200.0f, 128.0f),
            // ---- three lanes A/B/C, each with five chokepoints in
            // travel order. The original three chokepoints per
            // lane are scaled by 10; two new chokepoints per lane
            // are added at intermediate z rows so a 5-chokepoint
            // lane (15 total) names the full north-to-south run.
            // Chokepoint labels (Cafe, Plaza, Library, Bridge,
            // Market, Atrium, Storefront, Alley) are preserved on
            // the originals; the additions use new labels.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Cafe", -960.0f, -1360.0f),
                    new Chokepoint("cp_a2", "Plaza", -320.0f, -800.0f),
                    new Chokepoint("cp_a3", "Library", 320.0f, -1360.0f),
                    new Chokepoint("cp_a4", "Cafe Mid", -640.0f, 0.0f),
                    new Chokepoint("cp_a5", "Plaza S", -640.0f, 800.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Bridge", -960.0f, 0.0f),
                    new Chokepoint("cp_b2", "Market", 0.0f, 0.0f),
                    new Chokepoint("cp_b3", "Atrium", 960.0f, 0.0f),
                    new Chokepoint("cp_b4", "Bridge N", 0.0f, -800.0f),
                    new Chokepoint("cp_b5", "Bridge S", 320.0f, 800.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Storefront", -960.0f, 1360.0f),
                    new Chokepoint("cp_c2", "Alley", 0.0f, 800.0f),
                    new Chokepoint("cp_c3", "Plaza", 960.0f, 1360.0f),
                    new Chokepoint("cp_c4", "Storefront Mid", 640.0f, 0.0f),
                    new Chokepoint("cp_c5", "Plaza Mid", -320.0f, 800.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west and
            // east edges of each team's half. X offsets are scaled
            // by 10 to -1280 / +1280; the spawns sit well clear of
            // the level .ofm's 320 x 320 kit walls (which are at
            // +-153.6 in the original 320 system, 1536 in the
            // scaled 3200 system). Facings point inward, slightly
            // off-axis, so a spawner faces the lane without looking
            // directly down it. Y stays at 0.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -1280.0f, 0.0f, -960.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -1280.0f, 0.0f, -320.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, -1280.0f, 0.0f, 320.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 1280.0f, 0.0f, -320.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 1280.0f, 0.0f, 320.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 1280.0f, 0.0f, 960.0f,
                    toRadians(280.0f))
            ),
            // ---- bot waypoints: 28 total (12 originals scaled by
            // 10 + 16 new), a closed loop visited in id order with
            // wrap. The 12 originals are the original 320-system
            // positions multiplied by 10 and sit on the 5 inner
            // z rows (z = -1360, -800, 0, 800, 1360) inside the
            // original kit column ranges. The 16 new waypoints
            // are on 4 outer z rows (z = -1200, -400, 400, 1200)
            // with x = -1280, -640, 640, 1280 — past the kit
            // column x range ([-112, -48] / [48, 112] in the
            // 320 system) and well clear of the level .ofm's
            // walls. All waypoints at y = 0 (kit floor); the
            // level .ofm is a 320 x 320 flat plate, so the
            // higher y values from a multi-floor kit do not
            // apply.
            List.of(
                new Waypoint("wp_0", -800.0f, 0.0f, -1360.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, -1360.0f),
                new Waypoint("wp_2", 320.0f, 0.0f, -800.0f),
                new Waypoint("wp_3", 0.0f, 0.0f, 0.0f),
                new Waypoint("wp_4", 960.0f, 0.0f, 0.0f),
                new Waypoint("wp_5", 320.0f, 0.0f, 800.0f),
                new Waypoint("wp_6", 0.0f, 0.0f, 1360.0f),
                new Waypoint("wp_7", -800.0f, 0.0f, 1360.0f),
                new Waypoint("wp_8", 960.0f, 0.0f, -800.0f),
                new Waypoint("wp_9", 960.0f, 0.0f, 800.0f),
                new Waypoint("wp_10", -960.0f, 0.0f, 800.0f),
                new Waypoint("wp_11", -960.0f, 0.0f, -800.0f),
                new Waypoint("wp_12", -1280.0f, 0.0f, -1200.0f),
                new Waypoint("wp_13", -640.0f, 0.0f, -1200.0f),
                new Waypoint("wp_14", 640.0f, 0.0f, -1200.0f),
                new Waypoint("wp_15", 1280.0f, 0.0f, -1200.0f),
                new Waypoint("wp_16", -1280.0f, 0.0f, -400.0f),
                new Waypoint("wp_17", -640.0f, 0.0f, -400.0f),
                new Waypoint("wp_18", 640.0f, 0.0f, -400.0f),
                new Waypoint("wp_19", 1280.0f, 0.0f, -400.0f),
                new Waypoint("wp_20", -1280.0f, 0.0f, 400.0f),
                new Waypoint("wp_21", -640.0f, 0.0f, 400.0f),
                new Waypoint("wp_22", 640.0f, 0.0f, 400.0f),
                new Waypoint("wp_23", 1280.0f, 0.0f, 400.0f),
                new Waypoint("wp_24", -1280.0f, 0.0f, 1200.0f),
                new Waypoint("wp_25", -640.0f, 0.0f, 1200.0f),
                new Waypoint("wp_26", 640.0f, 0.0f, 1200.0f),
                new Waypoint("wp_27", 1280.0f, 0.0f, 1200.0f)
            ),
            // ---- TDM markers: the empty singleton
            MapMarkers.TeamDeathmatch.INSTANCE,
            // ---- asset paths (level .ofm unchanged; the 320 x 320
            // mesh is the centerpiece in the middle of the 3200
            // x 3200 spec).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/cornerstone/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Overpass (Urban Warzone × Hardpoint) ----------------------------

    /**
     * The fifth shipped map. Urban Warzone, Hardpoint, MW2-Rust /
     * BO6 large-map sizing (4000 x 4000 world units, MAP_SCALE = 16
     * → 250 m square). A highway interchange at street level: two
     * parallel elevated overpasses running east-west with a service
     * road between them, and a control building anchoring the south.
     * The full design spec — the ASCII map, the callouts, the lane
     * structure, the three hardpoint zones, and the spawn rationale
     * — is in {@code docs/maps/urban-warzone/02-hp-overpass.md}.
     *
     * <p>Distinct from {@link #cornerstone} in feel: this is not a
     * three-lane map in the COD sense. The two overpasses are the
     * high ground (long sightlines, exposed from below), the service
     * road is the contested low ground, and the control building is
     * the chokepoint that decides the third rotation. Three
     * hardpoint zones rotate: Overpass S, Overpass N, then the
     * control building.</p>
     *
     * <p>The level .ofm is the original 320 x 320 composition with
     * the overpasses at y = 64 — left untouched and treated as a
     * centerpiece. The 12.5x spec surrounds it on all sides; the
     * y = 64 waypoints on the overpasses stay raised so a bot that
     * walks onto an overpass still walks on the overpass.</p>
     *
     * @return the Overpass map spec
     */
    public static MapSpec overpass()
    {
        return new MapSpec(
            // ---- identity
            "overpass",
            "Overpass",
            MapSetting.URBAN_WARZONE,
            MatchMode.HARDPOINT,
            // ---- playable area: 4000 x 4000, 128 high (the
            // overpass decks sit at y=64 with the control building
            // at y=80; the y values are preserved on the originals
            // and replicated on the new waypoints that sit on the
            // overpasses). All x/z in the spec are scaled by 12.5
            // from the original 320 x 320 spec; the level .ofm
            // remains a 320 x 320 centerpiece.
            new MapDimensions(4000.0f, 4000.0f, 128.0f),
            // ---- three lanes A/B/C, even though the map is not a
            // three-lane COD layout — the LaneAxis is EAST_WEST
            // because the overpasses run that way. The original
            // three chokepoints per lane are scaled by 12.5; two
            // new chokepoints per lane are added at the W and E
            // quarters (x = +-900) so a 5-chokepoint lane (15
            // total) names the full west-to-east run on each of
            // the two overpasses and the service road. Chokepoint
            // labels (Overpass N/S, Service Road) are preserved on
            // the originals; the additions use the "W Mid" / "E
            // Mid" suffix.
            List.of(
                new Lane("lane_a", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_a1", "Overpass N West", -1800.0f, -1500.0f),
                    new Chokepoint("cp_a2", "Overpass N Centre", 0.0f, -1500.0f),
                    new Chokepoint("cp_a3", "Overpass N East", 1800.0f, -1500.0f),
                    new Chokepoint("cp_a4", "Overpass N W Mid", -900.0f, -1500.0f),
                    new Chokepoint("cp_a5", "Overpass N E Mid", 900.0f, -1500.0f)
                )),
                new Lane("lane_b", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_b1", "Service Road West", -1800.0f, 0.0f),
                    new Chokepoint("cp_b2", "Service Road Centre", 0.0f, 0.0f),
                    new Chokepoint("cp_b3", "Service Road East", 1800.0f, 0.0f),
                    new Chokepoint("cp_b4", "Service Road W Mid", -900.0f, 0.0f),
                    new Chokepoint("cp_b5", "Service Road E Mid", 900.0f, 0.0f)
                )),
                new Lane("lane_c", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_c1", "Overpass S West", -1800.0f, 1000.0f),
                    new Chokepoint("cp_c2", "Overpass S Centre", 0.0f, 1000.0f),
                    new Chokepoint("cp_c3", "Overpass S East", 1800.0f, 1000.0f),
                    new Chokepoint("cp_c4", "Overpass S W Mid", -900.0f, 1000.0f),
                    new Chokepoint("cp_c5", "Overpass S E Mid", 900.0f, 1000.0f)
                ))
            ),
            // ---- six spawn points: three per team on the west and
            // east edges, facings aimed at the ramps. Spawn x is
            // +-1600 (scaled 12.5 from 128), well clear of the
            // level .ofm's 320 x 320 walls (at +-153.6 in the
            // 320 system, 1920 in the 12.5x spec). Y stays at 0.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -1600.0f, 0.0f, -1200.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -1600.0f, 0.0f, -800.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, -1600.0f, 0.0f, -400.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 1600.0f, 0.0f, 400.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 1600.0f, 0.0f, 800.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 1600.0f, 0.0f, 1200.0f,
                    toRadians(280.0f))
            ),
            // ---- 28 bot waypoints (12 originals scaled by 12.5
            // + 16 new), a closed loop visited in id order with
            // wrap. The y=64 originals (overpass gantries) stay
            // at y=64; the y=0 originals (ground/service road)
            // stay at y=0. Of the 16 new waypoints, 8 sit on the
            // overpasses at y=64 (4 on each overpass, at the
            // extreme west/east ends and at the inner quarter
            // x positions), and 8 sit on the ground at y=0 (4
            // along the service road north and south, and 4
            // around the control building). The new y=64
            // waypoints are at z=-1500 (north overpass) and
            // z=1000 (south overpass), matching the originals'
            // overpass z rows. The new y=0 waypoints are at
            // z=-500, 500, 1500, and 1800 (4 rows past the
            // original service-road and control-building z
            // positions), keeping the waypoints outside the
            // level .ofm and inside the 4000 x 4000 spec.
            List.of(
                new Waypoint("wp_0", 0.0f, 64.0f, 1000.0f),
                new Waypoint("wp_1", 400.0f, 64.0f, 750.0f),
                new Waypoint("wp_2", 1200.0f, 64.0f, 1000.0f),
                new Waypoint("wp_3", 1600.0f, 0.0f, 0.0f),
                new Waypoint("wp_4", 1200.0f, 0.0f, -750.0f),
                new Waypoint("wp_5", 0.0f, 64.0f, -1500.0f),
                new Waypoint("wp_6", -1200.0f, 64.0f, -1500.0f),
                new Waypoint("wp_7", -1600.0f, 0.0f, 0.0f),
                new Waypoint("wp_8", -1200.0f, 0.0f, 750.0f),
                new Waypoint("wp_9", -400.0f, 0.0f, 750.0f),
                new Waypoint("wp_10", 1200.0f, 0.0f, 1625.0f),
                new Waypoint("wp_11", -1200.0f, 0.0f, 1625.0f),
                new Waypoint("wp_12", -1800.0f, 64.0f, -1500.0f),
                new Waypoint("wp_13", -600.0f, 64.0f, -1500.0f),
                new Waypoint("wp_14", 600.0f, 64.0f, -1500.0f),
                new Waypoint("wp_15", 1800.0f, 64.0f, -1500.0f),
                new Waypoint("wp_16", -1800.0f, 64.0f, 1000.0f),
                new Waypoint("wp_17", -600.0f, 64.0f, 1000.0f),
                new Waypoint("wp_18", 600.0f, 64.0f, 1000.0f),
                new Waypoint("wp_19", 1800.0f, 64.0f, 1000.0f),
                new Waypoint("wp_20", -1800.0f, 0.0f, -500.0f),
                new Waypoint("wp_21", 1800.0f, 0.0f, -500.0f),
                new Waypoint("wp_22", -1800.0f, 0.0f, 500.0f),
                new Waypoint("wp_23", 1800.0f, 0.0f, 500.0f),
                new Waypoint("wp_24", -1800.0f, 0.0f, 1500.0f),
                new Waypoint("wp_25", 0.0f, 0.0f, 1500.0f),
                new Waypoint("wp_26", 1800.0f, 0.0f, 1500.0f),
                new Waypoint("wp_27", 0.0f, 0.0f, 1800.0f)
            ),
            // ---- Hardpoint markers: three zones, 1800-tic (30s)
            // rotation, 1 point per tic. Activation order B, A, C.
            // The x/z coords are scaled by 12.5 from the original
            // 320-system positions; the radius (48) is the
            // Hardpoint capture radius in world units and is left
            // unchanged per the "scale X/Z only" rule. The zone
            // callouts (Overpass N, Overpass S, Control Building)
            // and ids (hp_a, hp_b, hp_c) are preserved.
            new MapMarkers.Hardpoint(List.of(
                new MapMarkers.HardpointZone("hp_a", "Overpass N", 0.0f, -1500.0f,
                    48.0f),
                new MapMarkers.HardpointZone("hp_b", "Overpass S", 0.0f, 1000.0f,
                    48.0f),
                new MapMarkers.HardpointZone("hp_c", "Control Building", 0.0f,
                    1700.0f, 48.0f)
            ), 1800, 1),
            // ---- asset paths (level .ofm unchanged; the 320 x 320
            // mesh is the centerpiece in the middle of the 4000
            // x 4000 spec).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/overpass/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Tripoint (Urban Warzone × Domination) ----------------------------

    /**
     * The sixth shipped map. Urban Warzone, Domination, MW2-Rust /
     * BO6 large-map sizing (4800 x 4800 world units, MAP_SCALE = 16
     * → 300 m square). A three-way intersection at street level: a
     * roundabout in the centre and three approach streets (north,
     * south-east, south-west) leading to the three flags. The full
     * design spec is in {@code docs/maps/urban-warzone/03-dom-tripoint.md}.
     *
     * <p>Distinct from {@link #cornerstone} in feel: the play is
     * "centre → flag", not "flag → flag". The roundabout is the
     * contested ground; the three flags are the rewards. A team that
     * captures two flags at once earns double the score; capturing
     * all three is the lockout.</p>
     *
     * <p>The level .ofm is the original 320 x 320 composition — left
     * untouched and treated as a centerpiece. The 15x spec surrounds
     * it on all sides; the 16 new waypoints fill the 4 outer z rows
     * that the 320 x 320 level .ofm does not reach.</p>
     *
     * @return the Tripoint map spec
     */
    public static MapSpec tripoint()
    {
        return new MapSpec(
            // ---- identity
            "tripoint",
            "Tripoint",
            MapSetting.URBAN_WARZONE,
            MatchMode.DOMINATION,
            // ---- playable area: 4800 x 4800, 96 high. All x/z
            // in the spec are scaled by 15 from the original 320
            // x 320 spec; the level .ofm remains a 320 x 320
            // centerpiece.
            new MapDimensions(4800.0f, 4800.0f, 96.0f),
            // ---- three approach streets, encoded as lanes A/B/C.
            // The original three chokepoints per lane are scaled
            // by 15; two new chokepoints per lane are added
            // (one mid-arm midpoint, one at the far end) so a
            // 5-chokepoint lane (15 total) names the full
            // approach from the roundabout to the flag. Chokepoint
            // labels (Roundabout, Approach, FLAG A, FLAG C SE,
            // FLAG C SW) are preserved on the originals; the
            // additions use the "Mid" / "Far" suffix.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Roundabout", 0.0f, 0.0f),
                    new Chokepoint("cp_a2", "Approach N Centre", 0.0f, -900.0f),
                    new Chokepoint("cp_a3", "FLAG A", 0.0f, -1680.0f),
                    new Chokepoint("cp_a4", "Approach N W", -600.0f, -1200.0f),
                    new Chokepoint("cp_a5", "Approach N E", 600.0f, -1800.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Roundabout East", 600.0f, 0.0f),
                    new Chokepoint("cp_b2", "Approach SE Centre", 900.0f, 600.0f),
                    new Chokepoint("cp_b3", "FLAG C SE", 1200.0f, 1200.0f),
                    new Chokepoint("cp_b4", "Approach SE Mid", 400.0f, 400.0f),
                    new Chokepoint("cp_b5", "Approach SE Far", 1500.0f, 1500.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Roundabout West", -600.0f, 0.0f),
                    new Chokepoint("cp_c2", "Approach SW Centre", -900.0f, 600.0f),
                    new Chokepoint("cp_c3", "FLAG C SW", -1200.0f, 1200.0f),
                    new Chokepoint("cp_c4", "Approach SW Mid", -400.0f, 400.0f),
                    new Chokepoint("cp_c5", "Approach SW Far", -1500.0f, 1500.0f)
                ))
            ),
            // ---- six spawn points: three per team on the west and
            // east edges, facings aimed at the south-east and
            // south-west approach streets respectively. Spawn x
            // is +-2160 (scaled 15 from 144), well clear of the
            // level .ofm's 320 x 320 walls (at +-153.6 in the 320
            // system, 2304 in the 15x spec). Y stays at 0.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -2160.0f, 0.0f, -960.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_bravo", Team.RED, -2160.0f, 0.0f, -480.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_charlie", Team.RED, -2160.0f, 0.0f, 0.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 2160.0f, 0.0f, 0.0f,
                    toRadians(280.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 2160.0f, 0.0f, 480.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 2160.0f, 0.0f, 960.0f,
                    toRadians(260.0f))
            ),
            // ---- 28 bot waypoints (12 originals scaled by 15
            // + 16 new), a closed loop visited in id order with
            // wrap. The 12 originals are the original 320-system
            // positions multiplied by 15 and form the inner
            // triangle of N approach, SE approach, SW approach,
            // and roundabout. The 16 new waypoints are on 4 outer
            // z rows (z = -1800, -400, 400, 1800) with x =
            // -1800, -600, 600, 1800 — past the level .ofm's kit
            // column x range and well clear of the level .ofm
            // walls. All waypoints at y = 0 (kit floor).
            List.of(
                new Waypoint("wp_0", 0.0f, 0.0f, -1680.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, -1200.0f),
                new Waypoint("wp_2", 0.0f, 0.0f, -600.0f),
                new Waypoint("wp_3", 0.0f, 0.0f, 0.0f),
                new Waypoint("wp_4", 480.0f, 0.0f, 0.0f),
                new Waypoint("wp_5", 960.0f, 0.0f, 240.0f),
                new Waypoint("wp_6", 960.0f, 0.0f, 480.0f),
                new Waypoint("wp_7", 240.0f, 0.0f, 960.0f),
                new Waypoint("wp_8", 0.0f, 0.0f, 1200.0f),
                new Waypoint("wp_9", -480.0f, 0.0f, 960.0f),
                new Waypoint("wp_10", -960.0f, 0.0f, 480.0f),
                new Waypoint("wp_11", -960.0f, 0.0f, 240.0f),
                new Waypoint("wp_12", -1800.0f, 0.0f, -1800.0f),
                new Waypoint("wp_13", -600.0f, 0.0f, -1800.0f),
                new Waypoint("wp_14", 600.0f, 0.0f, -1800.0f),
                new Waypoint("wp_15", 1800.0f, 0.0f, -1800.0f),
                new Waypoint("wp_16", -1200.0f, 0.0f, -400.0f),
                new Waypoint("wp_17", -400.0f, 0.0f, -400.0f),
                new Waypoint("wp_18", 400.0f, 0.0f, -400.0f),
                new Waypoint("wp_19", 1200.0f, 0.0f, -400.0f),
                new Waypoint("wp_20", -1200.0f, 0.0f, 400.0f),
                new Waypoint("wp_21", -400.0f, 0.0f, 400.0f),
                new Waypoint("wp_22", 400.0f, 0.0f, 400.0f),
                new Waypoint("wp_23", 1200.0f, 0.0f, 400.0f),
                new Waypoint("wp_24", -1800.0f, 0.0f, 1800.0f),
                new Waypoint("wp_25", -600.0f, 0.0f, 1800.0f),
                new Waypoint("wp_26", 600.0f, 0.0f, 1800.0f),
                new Waypoint("wp_27", 1800.0f, 0.0f, 1800.0f)
            ),
            // ---- Domination markers: three flags, A, B, C. B
            // is the larger radius — the roundabout is the
            // contested ground and the capture zone is wider
            // than the per-flag stands. The flag x/z coords
            // are scaled by 15; the radii (32 / 48) are the
            // capture radii in world units and are left
            // unchanged per the "scale X/Z only" rule.
            new MapMarkers.Domination(List.of(
                new MapMarkers.Flag("flag_a", "FLAG A", 0.0f, -1680.0f, 32.0f),
                new MapMarkers.Flag("flag_b", "FLAG B", 0.0f, 0.0f, 48.0f),
                new MapMarkers.Flag("flag_c", "FLAG C", -1200.0f, 1200.0f, 32.0f)
            )),
            // ---- asset paths (level .ofm unchanged; the 320 x 320
            // mesh is the centerpiece in the middle of the 4800
            // x 4800 spec).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/tripoint/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Extraction (Urban Warzone × CTF) --------------------------------

    /**
     * The seventh shipped map. Urban Warzone, CTF, MW2-Rust / BO6
     * large-map sizing (5600 x 5600 world units, MAP_SCALE = 16 →
     * 350 m square). A mid-sized urban block split by a long
     * boulevard. Each team's base sits at one end of the boulevard,
     * with the flag in a small structure inside the base. The full
     * design spec is in {@code docs/maps/urban-warzone/04-ctf-extraction.md}.
     *
     * <p>Distinct from {@link #cornerstone} in feel: the play is
     * "carry the flag down the boulevard" with the flanking lanes as
     * the defender's cover. The carrier is visible from the moment
     * they leave their own base until they reach the enemy capture
     * point — a long, open sightline, with the cover walls in lanes
     * A and C the only off-axis cover a defender can use.</p>
     *
     * <p>The level .ofm is the original 320 x 320 composition — left
     * untouched and treated as a centerpiece. The 17.5x spec
     * surrounds it on all sides; the red base sits in the SW corner
     * of the spec and the blue base in the NE corner, with the long
     * boulevard between them.</p>
     *
     * @return the Extraction map spec
     */
    public static MapSpec extraction()
    {
        return new MapSpec(
            // ---- identity
            "extraction",
            "Extraction",
            MapSetting.URBAN_WARZONE,
            MatchMode.CTF,
            // ---- playable area: 5600 x 5600, 96 high (the bases
            // are 4-tall platforms with a 24-tall flagpole; the
            // cover walls are 48-tall). All x/z in the spec are
            // scaled by 17.5 from the original 320 x 320 spec; the
            // level .ofm remains a 320 x 320 centerpiece.
            new MapDimensions(5600.0f, 5600.0f, 96.0f),
            // ---- three lanes A/B/C, mirroring Cornerstone but
            // with the role of each lane shifted: B is now the
            // boulevard (the long sightline), A and C are the
            // flanking cover-wall lanes. The original three
            // chokepoints per lane are scaled by 17.5; two new
            // chokepoints per lane are added (one at the inner
            // midpoint, one at the far end) so a 5-chokepoint
            // lane (15 total) names the full NW-to-NE or W-to-E
            // or SW-to-SE run. Chokepoint labels (Red Base,
            // Cover Wall, Boulevard, Blue Base) are preserved on
            // the originals; the additions use the "Mid" /
            // "Center" suffix.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Red Base", -2240.0f, -2240.0f),
                    new Chokepoint("cp_a2", "Cover Wall NW", -1680.0f, -1680.0f),
                    new Chokepoint("cp_a3", "Cover Wall NE", 1680.0f, -1680.0f),
                    new Chokepoint("cp_a4", "Cover Wall N Mid", 0.0f, -1680.0f),
                    new Chokepoint("cp_a5", "Cover Wall N Center", 0.0f, -800.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Boulevard West", -2240.0f, 0.0f),
                    new Chokepoint("cp_b2", "Boulevard Centre", 0.0f, 0.0f),
                    new Chokepoint("cp_b3", "Boulevard East", 2240.0f, 0.0f),
                    new Chokepoint("cp_b4", "Boulevard W Mid", -1120.0f, 0.0f),
                    new Chokepoint("cp_b5", "Boulevard E Mid", 1120.0f, 0.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Cover Wall SW", -1680.0f, 1680.0f),
                    new Chokepoint("cp_c2", "Cover Wall SE", 1680.0f, 1680.0f),
                    new Chokepoint("cp_c3", "Blue Base", 2240.0f, 2240.0f),
                    new Chokepoint("cp_c4", "Cover Wall S Mid", 0.0f, 1680.0f),
                    new Chokepoint("cp_c5", "Cover Wall S Center", 0.0f, 800.0f)
                ))
            ),
            // ---- six spawn points: three per team on the west
            // and east edges of the playable area. Spawn x is
            // +-2240 (scaled 17.5 from 128), well clear of the
            // level .ofm's 320 x 320 walls (at +-153.6 in the
            // 320 system, 2688 in the 17.5x spec). Y stays at 0.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -2240.0f, 0.0f, -2240.0f,
                    toRadians(45.0f)),
                new SpawnPoint("red_bravo", Team.RED, -2240.0f, 0.0f, -1680.0f,
                    toRadians(60.0f)),
                new SpawnPoint("red_charlie", Team.RED, -2240.0f, 0.0f, -1120.0f,
                    toRadians(80.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 2240.0f, 0.0f, 1120.0f,
                    toRadians(225.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 2240.0f, 0.0f, 1680.0f,
                    toRadians(240.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 2240.0f, 0.0f, 2240.0f,
                    toRadians(260.0f))
            ),
            // ---- 28 bot waypoints (12 originals scaled by 17.5
            // + 16 new), a closed loop visited in id order with
            // wrap. The 12 originals are the original 320-system
            // positions multiplied by 17.5 and form a 3 x 4 grid
            // on the 3 boulevard z rows (z = -2240, 0, 2240).
            // The 16 new waypoints are on 4 inner z rows
            // (z = -1600, -800, 800, 1600) with x = -1600, -800,
            // 800, 1600 — past the level .ofm's kit column x
            // range and well clear of the level .ofm walls. All
            // waypoints at y = 0 (kit floor).
            List.of(
                new Waypoint("wp_0", -2240.0f, 0.0f, -2240.0f),
                new Waypoint("wp_1", -700.0f, 0.0f, -2240.0f),
                new Waypoint("wp_2", 700.0f, 0.0f, -2240.0f),
                new Waypoint("wp_3", 2240.0f, 0.0f, -2240.0f),
                new Waypoint("wp_4", -2240.0f, 0.0f, 0.0f),
                new Waypoint("wp_5", -700.0f, 0.0f, 0.0f),
                new Waypoint("wp_6", 700.0f, 0.0f, 0.0f),
                new Waypoint("wp_7", 2240.0f, 0.0f, 0.0f),
                new Waypoint("wp_8", -2240.0f, 0.0f, 2240.0f),
                new Waypoint("wp_9", -700.0f, 0.0f, 2240.0f),
                new Waypoint("wp_10", 700.0f, 0.0f, 2240.0f),
                new Waypoint("wp_11", 2240.0f, 0.0f, 2240.0f),
                new Waypoint("wp_12", -1600.0f, 0.0f, -1600.0f),
                new Waypoint("wp_13", -800.0f, 0.0f, -1600.0f),
                new Waypoint("wp_14", 800.0f, 0.0f, -1600.0f),
                new Waypoint("wp_15", 1600.0f, 0.0f, -1600.0f),
                new Waypoint("wp_16", -1600.0f, 0.0f, -800.0f),
                new Waypoint("wp_17", -800.0f, 0.0f, -800.0f),
                new Waypoint("wp_18", 800.0f, 0.0f, -800.0f),
                new Waypoint("wp_19", 1600.0f, 0.0f, -800.0f),
                new Waypoint("wp_20", -1600.0f, 0.0f, 800.0f),
                new Waypoint("wp_21", -800.0f, 0.0f, 800.0f),
                new Waypoint("wp_22", 800.0f, 0.0f, 800.0f),
                new Waypoint("wp_23", 1600.0f, 0.0f, 800.0f),
                new Waypoint("wp_24", -1600.0f, 0.0f, 1600.0f),
                new Waypoint("wp_25", -800.0f, 0.0f, 1600.0f),
                new Waypoint("wp_26", 800.0f, 0.0f, 1600.0f),
                new Waypoint("wp_27", 1600.0f, 0.0f, 1600.0f)
            ),
            // ---- CTF markers: red's base and blue's base. The
            // flag and the capture point are at the same spot in
            // each base. Both bases sit at the corners of the
            // playable area in the 17.5x spec; the radius (32)
            // is the capture radius in world units and is left
            // unchanged per the "scale X/Z only" rule.
            new MapMarkers.CaptureTheFlag(
                new MapMarkers.Base(Team.RED, -2240.0f, -2240.0f, -2240.0f,
                    -2240.0f, 32.0f),
                new MapMarkers.Base(Team.BLUE, 2240.0f, 2240.0f, 2240.0f,
                    2240.0f, 32.0f)
            ),
            // ---- asset paths (level .ofm unchanged; the 320 x 320
            // mesh is the centerpiece in the middle of the 5600
            // x 5600 spec).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/extraction/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Refinery (Industrial Complex × TDM) -----------------------------

    /**
     * The second shipped map. Industrial Complex, TDM, MW2-Rust /
     * BO6 large-map sizing (4000 x 4000 world units, MAP_SCALE = 16
     * → 250 m square), three-lane COD layout with multi-level
     * geometry: floor, mid-level catwalks, and tall tank tops. The
     * full design spec is in
     * {@code docs/maps/industrial-complex/01-refinery.md}.
     *
     * <p>Distinct from {@link #cornerstone} in feel: a refinery, not
     * a city block. The cover is tanks and catwalks, not buildings
     * and alleyways. Three tall distillation columns anchor the
     * north end; a large process hall with internal pipework runs
     * across the middle; three wide boiler structures anchor the
     * south. The level .ofm is the original 320 x 320 composition
     * — left untouched and treated as a centerpiece in the middle
     * of the larger spec. All x/z in the spec are scaled by 12.5
     * from the original 320 x 320 spec; the level .ofm remains a
     * 320 x 320 centerpiece.</p>
     *
     * @return the Refinery map spec
     */
    public static MapSpec refinery()
    {
        return new MapSpec(
            // ---- identity
            "refinery",
            "Refinery",
            MapSetting.INDUSTRIAL_COMPLEX,
            MatchMode.TDM,
            // ---- playable area: 4000 x 4000, 128 high (one
            // MAX_OPEN_HEIGHT floor). All x/z in the spec are
            // scaled by 12.5 from the original 320 x 320 spec;
            // the level .ofm remains a 320 x 320 centerpiece.
            new MapDimensions(4000.0f, 4000.0f, 128.0f),
            // ---- three lanes A/B/C, each with five chokepoints
            // in travel order. The original three chokepoints
            // per lane are scaled by 12.5; two new "Far"
            // chokepoints are added at the W and E extremes
            // (x = +-1500) so a 5-chokepoint lane (15 total)
            // names the full west-to-east run on each row.
            // Chokepoint labels (Distillation Tower, Tank Row,
            // Process Hall, Control Room, Boiler) are preserved
            // on the originals; the additions use the
            // "Far W" / "Far E" suffix. Lane centres are at
            // z=-1250, 0, +1250 (three rows spanning the
            // 4000-unit depth).
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Distillation Tower Far W",
                        -1500.0f, -1250.0f),
                    new Chokepoint("cp_a2", "Distillation Tower",
                        -375.0f, -1250.0f),
                    new Chokepoint("cp_a3", "Tank Row", 0.0f, -1250.0f),
                    new Chokepoint("cp_a4", "Tank Row East", 375.0f, -1250.0f),
                    new Chokepoint("cp_a5", "Distillation Tower Far E",
                        1500.0f, -1250.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Process Hall Far W",
                        -1500.0f, 0.0f),
                    new Chokepoint("cp_b2", "Process Hall West",
                        -375.0f, 0.0f),
                    new Chokepoint("cp_b3", "Control Room", 0.0f, 0.0f),
                    new Chokepoint("cp_b4", "Process Hall East", 375.0f, 0.0f),
                    new Chokepoint("cp_b5", "Process Hall Far E",
                        1500.0f, 0.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Boiler Far W",
                        -1500.0f, 1250.0f),
                    new Chokepoint("cp_c2", "Boiler West",
                        -375.0f, 1250.0f),
                    new Chokepoint("cp_c3", "Boiler Centre", 0.0f, 1250.0f),
                    new Chokepoint("cp_c4", "Boiler East", 375.0f, 1250.0f),
                    new Chokepoint("cp_c5", "Boiler Far E",
                        1500.0f, 1250.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west
            // and east edges of the playable area (x = +-1500,
            // 12.5x from the 6v6 spec's x=+-120). y stays 0;
            // facings aim inward toward the lane centre.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -1500.0f, 0.0f, -1250.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -1500.0f, 0.0f, 0.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_charlie", Team.RED, -1500.0f, 0.0f, 1250.0f,
                    toRadians(90.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 1500.0f, 0.0f, -1250.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 1500.0f, 0.0f, 0.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 1500.0f, 0.0f, 1250.0f,
                    toRadians(270.0f))
            ),
            // ---- 27 bot waypoints: a closed snake through a
            // 3-col x 9-row grid covering the 4000 x 4000
            // playable area. Cols at x = -1800, 0, 1800 (1800
            // unit spacing, well inside the inner wall at
            // +-1920). Rows at z = -1800, -1350, -900, -450, 0,
            // 450, 900, 1350, 1800 (450 unit spacing, evenly
            // distributed). The snake alternates direction per
            // row so a bot at waypoint i moves toward waypoint
            // i+1 without doubling back on itself. y stays at
            // 0 (kit floor).
            List.of(
                new Waypoint("wp_0", -1800.0f, 0.0f, -1800.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, -1800.0f),
                new Waypoint("wp_2", 1800.0f, 0.0f, -1800.0f),
                new Waypoint("wp_3", 1800.0f, 0.0f, -1350.0f),
                new Waypoint("wp_4", 0.0f, 0.0f, -1350.0f),
                new Waypoint("wp_5", -1800.0f, 0.0f, -1350.0f),
                new Waypoint("wp_6", -1800.0f, 0.0f, -900.0f),
                new Waypoint("wp_7", 0.0f, 0.0f, -900.0f),
                new Waypoint("wp_8", 1800.0f, 0.0f, -900.0f),
                new Waypoint("wp_9", 1800.0f, 0.0f, -450.0f),
                new Waypoint("wp_10", 0.0f, 0.0f, -450.0f),
                new Waypoint("wp_11", -1800.0f, 0.0f, -450.0f),
                new Waypoint("wp_12", -1800.0f, 0.0f, 0.0f),
                new Waypoint("wp_13", 0.0f, 0.0f, 0.0f),
                new Waypoint("wp_14", 1800.0f, 0.0f, 0.0f),
                new Waypoint("wp_15", 1800.0f, 0.0f, 450.0f),
                new Waypoint("wp_16", 0.0f, 0.0f, 450.0f),
                new Waypoint("wp_17", -1800.0f, 0.0f, 450.0f),
                new Waypoint("wp_18", -1800.0f, 0.0f, 900.0f),
                new Waypoint("wp_19", 0.0f, 0.0f, 900.0f),
                new Waypoint("wp_20", 1800.0f, 0.0f, 900.0f),
                new Waypoint("wp_21", 1800.0f, 0.0f, 1350.0f),
                new Waypoint("wp_22", 0.0f, 0.0f, 1350.0f),
                new Waypoint("wp_23", -1800.0f, 0.0f, 1350.0f),
                new Waypoint("wp_24", -1800.0f, 0.0f, 1800.0f),
                new Waypoint("wp_25", 0.0f, 0.0f, 1800.0f),
                new Waypoint("wp_26", 1800.0f, 0.0f, 1800.0f)
            ),
            // ---- TDM markers: the empty singleton
            MapMarkers.TeamDeathmatch.INSTANCE,
            // ---- asset paths (level .ofm unchanged; the 320 x
            // 320 mesh is the centerpiece in the middle of the
            // 4000 x 4000 spec).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/refinery/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Crossroads (Desert Ravine × TDM) --------------------------------

    /**
     * The third shipped map. Desert Ravine, TDM, 6v6 sizing, three-lane
     * COD layout. The full design spec is in
     * {@code docs/maps/desert-ravine/01-crossroads.md}.
     *
     * <p>Distinct from the previous two in feel: a desert town at a
     * four-way crossroads, not a city block and not a refinery. Open
     * sightlines, low sandstone buildings, a central plaza with four
     * corner chokepoints, and sparse cover. The play favours sniping
     * and rotation, not corner-clearing.</p>
     *
     * @return the Crossroads map spec
     */
    public static MapSpec crossroads()
    {
        return new MapSpec(
            // ---- identity
            "crossroads",
            "Crossroads",
            MapSetting.DESERT_RAVINE,
            MatchMode.TDM,
            // ---- playable area: 3200 x 3200, 128 high (desert town
            // scaled to 200m; 16 world units per metre). The level
            // .ofm and the kit (columns at +/-80, walls at +/-160)
            // are centerpieces in the middle of the playable area;
            // the data (spawns, waypoints, chokepoints) is spread
            // across the full 3200 x 3200 playable extent.
            new MapDimensions(3200.0f, 3200.0f, 128.0f),
            // ---- three lanes A/B/C, each with chokepoints in travel
            // order. Chokepoints sit on three rows that fit inside the
            // kit's playable area: the previous z=160 row put the
            // plaza chokepoints at the south wall centre (a 16-unit
            // half-extent chokepoint there overlaps the wall AABB at
            // z=[153.6, 166.4]) and the z=296 row sat 130 units past
            // the south wall outer face, unreachable.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Shack Row Far West", -1500.0f, -1000.0f),
                    new Chokepoint("cp_a2", "Shack Row West", -750.0f, -1000.0f),
                    new Chokepoint("cp_a3", "Shack Row Centre", 0.0f, -1000.0f),
                    new Chokepoint("cp_a4", "Shack Row East", 750.0f, -1000.0f),
                    new Chokepoint("cp_a5", "Shack Row Far East", 1500.0f, -1000.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "West Town Gate", -1500.0f, 0.0f),
                    new Chokepoint("cp_b2", "Cafe Corner", -750.0f, 0.0f),
                    new Chokepoint("cp_b3", "Plaza Centre", 0.0f, 0.0f),
                    new Chokepoint("cp_b4", "Sheriff's Office", 750.0f, 0.0f),
                    new Chokepoint("cp_b5", "East Town Gate", 1500.0f, 0.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Wells Fargo Far West", -1500.0f, 1000.0f),
                    new Chokepoint("cp_c2", "Wells Fargo", -750.0f, 1000.0f),
                    new Chokepoint("cp_c3", "Warehouse Row Centre", 0.0f, 1000.0f),
                    new Chokepoint("cp_c4", "Trading Post", 750.0f, 1000.0f),
                    new Chokepoint("cp_c5", "Trading Post Far East", 1500.0f, 1000.0f)
                ))
            ),
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -1280.0f, 0.0f, -800.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -1280.0f, 0.0f, 0.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, -1280.0f, 0.0f, 800.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 1280.0f, 0.0f, -800.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 1280.0f, 0.0f, 0.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 1280.0f, 0.0f, 800.0f,
                    toRadians(280.0f))
            ),
            List.of(
                new Waypoint("wp_0", -1200.0f, 0.0f, -1500.0f),
                new Waypoint("wp_1", -800.0f, 0.0f, -1500.0f),
                new Waypoint("wp_2", -400.0f, 0.0f, -1500.0f),
                new Waypoint("wp_3", 0.0f, 0.0f, -1500.0f),
                new Waypoint("wp_4", 400.0f, 0.0f, -1500.0f),
                new Waypoint("wp_5", 800.0f, 0.0f, -1500.0f),
                new Waypoint("wp_6", 1200.0f, 0.0f, -1500.0f),
                new Waypoint("wp_7", 1200.0f, 0.0f, -500.0f),
                new Waypoint("wp_8", 800.0f, 0.0f, -500.0f),
                new Waypoint("wp_9", 400.0f, 0.0f, -500.0f),
                new Waypoint("wp_10", 0.0f, 0.0f, -500.0f),
                new Waypoint("wp_11", -400.0f, 0.0f, -500.0f),
                new Waypoint("wp_12", -800.0f, 0.0f, -500.0f),
                new Waypoint("wp_13", -1200.0f, 0.0f, -500.0f),
                new Waypoint("wp_14", -1200.0f, 0.0f, 500.0f),
                new Waypoint("wp_15", -800.0f, 0.0f, 500.0f),
                new Waypoint("wp_16", -400.0f, 0.0f, 500.0f),
                new Waypoint("wp_17", 0.0f, 0.0f, 500.0f),
                new Waypoint("wp_18", 400.0f, 0.0f, 500.0f),
                new Waypoint("wp_19", 800.0f, 0.0f, 500.0f),
                new Waypoint("wp_20", 1200.0f, 0.0f, 500.0f),
                new Waypoint("wp_21", 1200.0f, 0.0f, 1500.0f),
                new Waypoint("wp_22", 800.0f, 0.0f, 1500.0f),
                new Waypoint("wp_23", 400.0f, 0.0f, 1500.0f),
                new Waypoint("wp_24", 0.0f, 0.0f, 1500.0f),
                new Waypoint("wp_25", -400.0f, 0.0f, 1500.0f),
                new Waypoint("wp_26", -800.0f, 0.0f, 1500.0f),
                new Waypoint("wp_27", -1200.0f, 0.0f, 1500.0f)
            ),
            // ---- TDM markers: the empty singleton
            MapMarkers.TeamDeathmatch.INSTANCE,
            // ---- asset paths
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/crossroads/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    /**
     * The fourth shipped map. Arctic Station, TDM, 6v6 sizing, the
     * cleanest sightline map of the four. Two long east–west frozen
     * bridges over a frozen ravine, with a service building anchoring
     * the south. The full design spec is in
     * {@code docs/maps/arctic-station/01-icebridge.md}.
     *
     * <p>Distinct from the previous three in feel: a polar highway
     * rest stop, not a city block and not a refinery and not a desert
     * town. Sheet-metal bridges with frosted-glass railings, snowdrift
     * cover on the ravine floor, fuel-depot and service-building
     * anchors. The play favours long sightline duels and bridge
     * rotations, not corner-clearing.</p>
     *
     * @return the Icebridge map spec
     */
    public static MapSpec arcticStation()
    {
        return new MapSpec(
            // ---- identity
            "arctic-station",
            "Icebridge",
            MapSetting.ARCTIC_STATION,
            MatchMode.TDM,
            // ---- playable area: 4000 x 4000, 128 high (the bridges
            // are 32-tall with a service building on top). All x/z
            // in the spec are scaled by 12.5 from the original 320
            // x 320 spec; the level .ofm remains a 320 x 320
            // centerpiece. The y=32 bridge decks are part of the
            // .ofm, not the spec, and stay raised.
            new MapDimensions(4000.0f, 4000.0f, 128.0f),
            // ---- three lanes A/B/C, each with five chokepoints in
            // travel order. The original three chokepoints per
            // lane are scaled by 12.5; two new "Far" chokepoints
            // are added at the W and E extremes (x = +-1500) so a
            // 5-chokepoint lane (15 total) names the full
            // west-to-east run on each row. Chokepoint labels
            // (Fuel Depot, North Bridge, Ravine, Snowdrift,
            // Service Building) are preserved on the originals;
            // the additions use the "Far W" / "Far E" suffix.
            // Lane centres are at z=-1250, 0, +1250 (three rows
            // spanning the 4000-unit depth).
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Fuel Depot Far W",
                        -1500.0f, -1250.0f),
                    new Chokepoint("cp_a2", "Fuel Depot West",
                        -375.0f, -1250.0f),
                    new Chokepoint("cp_a3", "North Bridge Centre",
                        0.0f, -1250.0f),
                    new Chokepoint("cp_a4", "Fuel Depot East",
                        375.0f, -1250.0f),
                    new Chokepoint("cp_a5", "Fuel Depot Far E",
                        1500.0f, -1250.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Ravine Far W",
                        -1500.0f, 0.0f),
                    new Chokepoint("cp_b2", "Ravine West",
                        -375.0f, 0.0f),
                    new Chokepoint("cp_b3", "Snowdrift Mid",
                        0.0f, 0.0f),
                    new Chokepoint("cp_b4", "Ravine East",
                        375.0f, 0.0f),
                    new Chokepoint("cp_b5", "Ravine Far E",
                        1500.0f, 0.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Service Building Far W",
                        -1500.0f, 1250.0f),
                    new Chokepoint("cp_c2", "Service Building West",
                        -375.0f, 1250.0f),
                    new Chokepoint("cp_c3", "Service Building Centre",
                        0.0f, 1250.0f),
                    new Chokepoint("cp_c4", "Service Building East",
                        375.0f, 1250.0f),
                    new Chokepoint("cp_c5", "Service Building Far E",
                        1500.0f, 1250.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west
            // and east edges of the playable area (x = +-1500,
            // 12.5x from the 6v6 spec's x=+-128). y stays 0;
            // facings aim inward toward the lane centre. The
            // bridge waypoints sit at y=32 on the level .ofm, but
            // the spawns are on the ground so y=0.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -1500.0f, 0.0f, -1250.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -1500.0f, 0.0f, -625.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, -1500.0f, 0.0f, 0.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 1500.0f, 0.0f, 1250.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 1500.0f, 0.0f, 625.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 1500.0f, 0.0f, 0.0f,
                    toRadians(280.0f))
            ),
            // ---- 21 bot waypoints: a closed snake through a
            // 7-col x 3-row grid covering the 4000 x 4000 playable
            // area. Cols at x = -1800, -1200, -600, 0, 600, 1200,
            // 1800 (600 unit spacing, well inside the inner wall
            // at +-1920). Rows at z = -1250 (north bridge, y=32),
            // 0 (ravine floor, y=0), +1250 (south bridge, y=32);
            // the Y values match the bridge deck heights in the
            // level .ofm, so a bot at a bridge waypoint stands
            // on the deck and a bot at a ravine waypoint stands
            // on the floor. The snake alternates direction per
            // row so a bot at waypoint i moves toward waypoint
            // i+1 without doubling back on itself.
            List.of(
                new Waypoint("wp_0", -1800.0f, 32.0f, -1250.0f),
                new Waypoint("wp_1", -1200.0f, 32.0f, -1250.0f),
                new Waypoint("wp_2", -600.0f, 32.0f, -1250.0f),
                new Waypoint("wp_3", 0.0f, 32.0f, -1250.0f),
                new Waypoint("wp_4", 600.0f, 32.0f, -1250.0f),
                new Waypoint("wp_5", 1200.0f, 32.0f, -1250.0f),
                new Waypoint("wp_6", 1800.0f, 32.0f, -1250.0f),
                new Waypoint("wp_7", 1800.0f, 0.0f, 0.0f),
                new Waypoint("wp_8", 1200.0f, 0.0f, 0.0f),
                new Waypoint("wp_9", 600.0f, 0.0f, 0.0f),
                new Waypoint("wp_10", 0.0f, 0.0f, 0.0f),
                new Waypoint("wp_11", -600.0f, 0.0f, 0.0f),
                new Waypoint("wp_12", -1200.0f, 0.0f, 0.0f),
                new Waypoint("wp_13", -1800.0f, 0.0f, 0.0f),
                new Waypoint("wp_14", -1800.0f, 32.0f, 1250.0f),
                new Waypoint("wp_15", -1200.0f, 32.0f, 1250.0f),
                new Waypoint("wp_16", -600.0f, 32.0f, 1250.0f),
                new Waypoint("wp_17", 0.0f, 32.0f, 1250.0f),
                new Waypoint("wp_18", 600.0f, 32.0f, 1250.0f),
                new Waypoint("wp_19", 1200.0f, 32.0f, 1250.0f),
                new Waypoint("wp_20", 1800.0f, 32.0f, 1250.0f)
            ),
            // ---- TDM markers: the empty singleton
            MapMarkers.TeamDeathmatch.INSTANCE,
            // ---- asset paths (level .ofm unchanged; the 320 x
            // 320 mesh is the centerpiece in the middle of the
            // 4000 x 4000 spec, with the bridges at y = 32
            // preserved).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/arctic-station/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Foundry (Industrial Complex × Hardpoint) -----------------------

    /**
     * The eighth shipped map. Industrial Complex, Hardpoint,
     * MW2-Rust / BO6 large-map sizing (4800 x 4800 world units,
     * MAP_SCALE = 16 → 300 m square). A heavy-machinery foundry:
     * three large machine halls (the cast-metal shop, the assembly
     * floor, and the cooling room) are the three Hardpoint zones,
     * each a high-walled, low-ceiling space. Two horizontal mid-level
     * gantries (the casting-gantry at y=64, z=80 and the foundry
     * spine at z=160) plus a vertical gantry at x=0 connect the
     * three halls at mid-height. The full design spec — the ASCII
     * map, the callouts, the lane structure, the three hardpoint
     * zones, and the spawn rationale — is in
     * {@code docs/maps/industrial-complex/02-hp-foundry.md}.
     *
     * <p>Distinct from {@link #overpass} in feel: this is a
     * three-zone rotation across three enclosed halls (cast-metal →
     * assembly → cooling), where Overpass is two open-air
     * overpasses and a building. Both are 30-second Hardpoint
     * rotations, but the Foundry's rotation moves the contested
     * ground south to north over the course of a round; Overpass's
     * rotation moves it east to west. The level .ofm is the
     * original 320 x 320 composition with the gantries at y = 64 —
     * left untouched and treated as a centerpiece. All x/z in the
     * spec are scaled by 15 from the original 320 x 320 spec; the
     * level .ofm remains a 320 x 320 centerpiece. The y=64
     * gantries are part of the .ofm, not the spec, and stay
     * raised.</p>
     *
     * @return the Foundry map spec
     */
    public static MapSpec foundry()
    {
        return new MapSpec(
            // ---- identity
            "foundry",
            "Foundry",
            MapSetting.INDUSTRIAL_COMPLEX,
            MatchMode.HARDPOINT,
            // ---- playable area: 4800 x 4800, 128 high (the
            // halls are 64-tall and the gantries sit at y=64 in
            // the .ofm). All x/z in the spec are scaled by 15
            // from the original 320 x 320 spec; the level .ofm
            // remains a 320 x 320 centerpiece.
            new MapDimensions(4800.0f, 4800.0f, 128.0f),
            // ---- three lanes A/B/C encoded as the gantry
            // routes: A is the casting-gantry, B is the foundry
            // spine (the contested middle), C is the
            // cooling-gantry. The axis is east-west because the
            // two horizontal gantries run that way; the
            // cooling-gantry is the vertical link. The original
            // three chokepoints per lane are scaled by 15; two
            // new "Far" chokepoints are added at the lane
            // extremes (x = +-1500 for lane_a/b, z = +-1500 for
            // lane_c) so a 5-chokepoint lane (15 total) names the
            // full run on each gantry. Chokepoint labels
            // (Casting Gantry, Foundry Spine, Cooling Gantry)
            // are preserved on the originals; the additions use
            // the "Far W" / "Far E" / "Far S" / "Far N" suffix.
            List.of(
                new Lane("lane_a", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_a1", "Casting Gantry Far W",
                        -1500.0f, -1500.0f),
                    new Chokepoint("cp_a2", "Casting Gantry West",
                        -450.0f, -1500.0f),
                    new Chokepoint("cp_a3", "Casting Gantry Centre",
                        0.0f, -1500.0f),
                    new Chokepoint("cp_a4", "Casting Gantry East",
                        450.0f, -1500.0f),
                    new Chokepoint("cp_a5", "Casting Gantry Far E",
                        1500.0f, -1500.0f)
                )),
                new Lane("lane_b", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_b1", "Foundry Spine Far W",
                        -1500.0f, 0.0f),
                    new Chokepoint("cp_b2", "Foundry Spine West",
                        -450.0f, 0.0f),
                    new Chokepoint("cp_b3", "Foundry Spine Centre",
                        0.0f, 0.0f),
                    new Chokepoint("cp_b4", "Foundry Spine East",
                        450.0f, 0.0f),
                    new Chokepoint("cp_b5", "Foundry Spine Far E",
                        1500.0f, 0.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Cooling Gantry Far S",
                        0.0f, -1500.0f),
                    new Chokepoint("cp_c2", "Cooling Room South",
                        0.0f, -450.0f),
                    new Chokepoint("cp_c3", "Cooling Gantry Mid",
                        0.0f, 0.0f),
                    new Chokepoint("cp_c4", "Cooling Gantry South",
                        0.0f, 450.0f),
                    new Chokepoint("cp_c5", "Cooling Gantry Far N",
                        0.0f, 1500.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west
            // and east edges of the floor, facings aimed at the
            // foundry spine (the contested middle). z is one of
            // -600 / 600 / 1500 — three open-floor rows (15x
            // from the 6v6 spec's z=-40 / 40 / 100). x is
            // +-1800 (15x from x=+-120). y stays 0.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -1800.0f, 0.0f, -600.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -1800.0f, 0.0f, 600.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_charlie", Team.RED, -1800.0f, 0.0f, 1500.0f,
                    toRadians(90.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 1800.0f, 0.0f, -600.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 1800.0f, 0.0f, 600.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 1800.0f, 0.0f, 1500.0f,
                    toRadians(270.0f))
            ),
            // ---- 27 bot waypoints: a closed snake through a
            // 3-col x 9-row grid covering the 4800 x 4800
            // playable area. Cols at x = -2160, 0, 2160 (2160
            // unit spacing, inside the inner wall at +-2320).
            // Rows at z = -2160, -1620, -1080, -540, 0, 540,
            // 1080, 1620, 2160 (540 unit spacing). The snake
            // alternates direction per row. y stays at 0 (kit
            // floor); the .ofm's gantries at y=64 are visual
            // and do not change the spec y values.
            List.of(
                new Waypoint("wp_0", -2160.0f, 0.0f, -2160.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, -2160.0f),
                new Waypoint("wp_2", 2160.0f, 0.0f, -2160.0f),
                new Waypoint("wp_3", 2160.0f, 0.0f, -1620.0f),
                new Waypoint("wp_4", 0.0f, 0.0f, -1620.0f),
                new Waypoint("wp_5", -2160.0f, 0.0f, -1620.0f),
                new Waypoint("wp_6", -2160.0f, 0.0f, -1080.0f),
                new Waypoint("wp_7", 0.0f, 0.0f, -1080.0f),
                new Waypoint("wp_8", 2160.0f, 0.0f, -1080.0f),
                new Waypoint("wp_9", 2160.0f, 0.0f, -540.0f),
                new Waypoint("wp_10", 0.0f, 0.0f, -540.0f),
                new Waypoint("wp_11", -2160.0f, 0.0f, -540.0f),
                new Waypoint("wp_12", -2160.0f, 0.0f, 0.0f),
                new Waypoint("wp_13", 0.0f, 0.0f, 0.0f),
                new Waypoint("wp_14", 2160.0f, 0.0f, 0.0f),
                new Waypoint("wp_15", 2160.0f, 0.0f, 540.0f),
                new Waypoint("wp_16", 0.0f, 0.0f, 540.0f),
                new Waypoint("wp_17", -2160.0f, 0.0f, 540.0f),
                new Waypoint("wp_18", -2160.0f, 0.0f, 1080.0f),
                new Waypoint("wp_19", 0.0f, 0.0f, 1080.0f),
                new Waypoint("wp_20", 2160.0f, 0.0f, 1080.0f),
                new Waypoint("wp_21", 2160.0f, 0.0f, 1620.0f),
                new Waypoint("wp_22", 0.0f, 0.0f, 1620.0f),
                new Waypoint("wp_23", -2160.0f, 0.0f, 1620.0f),
                new Waypoint("wp_24", -2160.0f, 0.0f, 2160.0f),
                new Waypoint("wp_25", 0.0f, 0.0f, 2160.0f),
                new Waypoint("wp_26", 2160.0f, 0.0f, 2160.0f)
            ),
            // ---- Hardpoint markers: three zones, 1800-tic (30s)
            // rotation, 1 point per tic. Activation order A, B,
            // C (cast-metal → assembly → cooling). Zone x/z
            // are scaled 15x from the 6v6 spec; zone radius is
            // scaled 15x as well (48 -> 720) so the capture
            // footprint keeps the same proportion of the
            // playable area.
            new MapMarkers.Hardpoint(List.of(
                new MapMarkers.HardpointZone("hp_a", "Cast-Metal Shop",
                    -900.0f, 1920.0f, 720.0f),
                new MapMarkers.HardpointZone("hp_b", "Assembly Floor",
                    -900.0f, 120.0f, 720.0f),
                new MapMarkers.HardpointZone("hp_c", "Cooling Room",
                    900.0f, 0.0f, 720.0f)
            ), 1800, 1),
            // ---- asset paths (level .ofm unchanged; the 320 x
            // 320 mesh is the centerpiece in the middle of the
            // 4800 x 4800 spec).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/foundry/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Mesa (Desert Ravine × Hardpoint) --------------------------------

    /**
     * The ninth shipped map. Desert Ravine, Hardpoint, 6v6 sizing. A
     * flat-topped sandstone mesa with a single easy ramp on the south
     * face and a harder switchback stair on the north face. The mesa
     * top is the contested ground; the two HP zones on the mesa top
     * (C and B) are the high ground. The third zone (A) is a
     * canyon-floor cave to the south, which the round opens on before
     * the rotation pushes the fight onto the mesa. The full design
     * spec is in {@code docs/maps/desert-ravine/02-hp-mesa.md}.
     *
     * <p>Distinct from {@link #overpass} in feel: the two mesa-top
     * zones are the high ground (long sightlines, exposed from
     * below), the desert floor is the contested low ground, and the
     * cave is the chokepoint that decides the first rotation. A
     * sniper duel from the mesa rim is the round's signature
     * exchange.</p>
     *
     * @return the Mesa map spec
     */
    public static MapSpec mesa()
    {
        return new MapSpec(
            // ---- identity
            "mesa",
            "Mesa",
            MapSetting.DESERT_RAVINE,
            MatchMode.HARDPOINT,
            // ---- playable area: 4800 x 4800, 128 high (the mesa
            // top sits at y=30, the cave floor at y=0; 16 world
            // units per metre gives a 300m map). The level .ofm
            // and the kit (columns at +/-80, walls at +/-160)
            // are centerpieces anchored at the origin; the data
            // spreads across the full 4800 x 4800 playable extent.
            new MapDimensions(4800.0f, 4800.0f, 128.0f),
            // ---- three lanes A/B/C. Lane A is the desert floor on
            // the south side (the cave approach), lane B is the mesa
            // top (the contested middle), lane C is the desert floor
            // on the north side (the switchback approach). All
            // chokepoints are inside the playable area; the original
            // z=270 (cave) and z=160 (mesa rim) were either outside
            // the kit's south wall or sitting on it, so the HUD
            // labels rendered off-screen.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Cave S Far West", -1500.0f, 1500.0f),
                    new Chokepoint("cp_a2", "Cave S West", -750.0f, 1500.0f),
                    new Chokepoint("cp_a3", "Cave S Centre", 0.0f, 1500.0f),
                    new Chokepoint("cp_a4", "Cave S East", 750.0f, 1500.0f),
                    new Chokepoint("cp_a5", "Cave S Far East", 1500.0f, 1500.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Mesa Top S Far West", -1500.0f, 450.0f),
                    new Chokepoint("cp_b2", "Mesa Top S West", -750.0f, 450.0f),
                    new Chokepoint("cp_b3", "Mesa Top S Centre", 0.0f, 450.0f),
                    new Chokepoint("cp_b4", "Mesa Top S East", 750.0f, 450.0f),
                    new Chokepoint("cp_b5", "Mesa Top S Far East", 1500.0f, 450.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Mesa Top N Far West", -1500.0f, -750.0f),
                    new Chokepoint("cp_c2", "Mesa Top N West", -750.0f, -750.0f),
                    new Chokepoint("cp_c3", "Mesa Top N Centre", 0.0f, -750.0f),
                    new Chokepoint("cp_c4", "Mesa Top N East", 750.0f, -750.0f),
                    new Chokepoint("cp_c5", "Mesa Top N Far East", 1500.0f, -750.0f)
                ))
            ),
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -1800.0f, 0.0f, 1500.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_bravo", Team.RED, -1800.0f, 0.0f, 1800.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_charlie", Team.RED, -1800.0f, 0.0f, 2100.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 1800.0f, 0.0f, -1500.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 1800.0f, 0.0f, -1800.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 1800.0f, 0.0f, -2100.0f,
                    toRadians(280.0f))
            ),
            List.of(
                new Waypoint("wp_0", 300.0f, 30.0f, 600.0f),
                new Waypoint("wp_1", 1200.0f, 30.0f, 600.0f),
                new Waypoint("wp_2", 2100.0f, 30.0f, 600.0f),
                new Waypoint("wp_3", 2100.0f, 30.0f, 1500.0f),
                new Waypoint("wp_4", 1200.0f, 30.0f, 1500.0f),
                new Waypoint("wp_5", 300.0f, 30.0f, 1500.0f),
                new Waypoint("wp_6", -1800.0f, 0.0f, -2100.0f),
                new Waypoint("wp_7", -1080.0f, 0.0f, -2100.0f),
                new Waypoint("wp_8", -360.0f, 0.0f, -2100.0f),
                new Waypoint("wp_9", 360.0f, 0.0f, -2100.0f),
                new Waypoint("wp_10", 1080.0f, 0.0f, -2100.0f),
                new Waypoint("wp_11", 1800.0f, 0.0f, -2100.0f),
                new Waypoint("wp_12", 1800.0f, 0.0f, -700.0f),
                new Waypoint("wp_13", 1080.0f, 0.0f, -700.0f),
                new Waypoint("wp_14", 360.0f, 0.0f, -700.0f),
                new Waypoint("wp_15", -360.0f, 0.0f, -700.0f),
                new Waypoint("wp_16", -1080.0f, 0.0f, -700.0f),
                new Waypoint("wp_17", -1800.0f, 0.0f, -700.0f),
                new Waypoint("wp_18", -1800.0f, 0.0f, 700.0f),
                new Waypoint("wp_19", -1080.0f, 0.0f, 700.0f),
                new Waypoint("wp_20", -360.0f, 0.0f, 700.0f),
                new Waypoint("wp_21", 360.0f, 0.0f, 700.0f),
                new Waypoint("wp_22", 1080.0f, 0.0f, 700.0f),
                new Waypoint("wp_23", 1800.0f, 0.0f, 700.0f),
                new Waypoint("wp_24", 1800.0f, 0.0f, 2100.0f),
                new Waypoint("wp_25", 1080.0f, 0.0f, 2100.0f),
                new Waypoint("wp_26", 360.0f, 0.0f, 2100.0f),
                new Waypoint("wp_27", -360.0f, 0.0f, 2100.0f),
                new Waypoint("wp_28", -1080.0f, 0.0f, 2100.0f),
                new Waypoint("wp_29", -1800.0f, 0.0f, 2100.0f)
            ),
            new MapMarkers.Hardpoint(List.of(
                new MapMarkers.HardpointZone("hp_a", "Cave S", 900.0f, 1500.0f, 720.0f),
                new MapMarkers.HardpointZone("hp_b", "Mesa Top S", 900.0f, 450.0f, 720.0f),
                new MapMarkers.HardpointZone("hp_c", "Mesa Top N", 900.0f, -750.0f, 720.0f)
            ), 1800, 1),
            // ---- asset paths
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/mesa/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Subzero (Arctic Station × Hardpoint) ----------------------------

    /**
     * The tenth shipped map. Arctic Station, Hardpoint, MW2-Rust /
     * BO6 large-map sizing (4800 x 4800 world units, MAP_SCALE = 16
     * → 300 m square). A radar-research outpost on a polar ice shelf.
     * Three low sheet-metal buildings (the Generator Shed, the
     * Operations Trailer, and the Fuel Depot) are the three
     * Hardpoint zones, each a small enclosed space with one wide
     * doorway. The buildings are connected by a system of
     * snow-walled trenches at floor level, so a player who has
     * dropped into a trench can rotate between buildings without
     * being shot from above. The full design spec is in
     * {@code docs/maps/arctic-station/02-hp-arctic.md}.
     *
     * <p>Distinct from {@link #foundry} in feel: this is three
     * buildings at the corners of a much wider triangle, connected
     * by long trenches in a Y pattern. The rotation moves the
     * contested ground east to west over the course of a round
     * (Generator Shed → Operations Trailer → Fuel Depot). All x/z
     * in the spec are scaled by 15 from the original 320 x 320
     * spec; the level .ofm remains a 320 x 320 centerpiece.</p>
     *
     * @return the Subzero map spec
     */
    public static MapSpec arcticHp()
    {
        return new MapSpec(
            // ---- identity
            "arctic-hp",
            "Subzero",
            MapSetting.ARCTIC_STATION,
            MatchMode.HARDPOINT,
            // ---- playable area: 4800 x 4800, 96 high (the
            // buildings are short — 32-tall sheet metal — and the
            // trenches are 8-tall snow walls). All x/z in the spec
            // are scaled by 15 from the original 320 x 320 spec;
            // the level .ofm remains a 320 x 320 centerpiece.
            new MapDimensions(4800.0f, 4800.0f, 96.0f),
            // ---- three lanes A/B/C encoded as the three trench
            // routes plus the open ground. The original three
            // chokepoints per lane are scaled by 15; two new "Far"
            // chokepoints are added at the W and E extremes (x =
            // +-1800) so a 5-chokepoint lane (15 total) names the
            // full west-to-east run on each row. Chokepoint labels
            // (Generator Shed, W Trench, Operations, Open Ground,
            // E Trench, Fuel Depot) are preserved on the originals;
            // the additions use the "Far W" / "Far E" suffix.
            // Lane centres are at z=-1500, 0, +1500 (three rows
            // spanning the 4800-unit depth).
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Generator Shed Far W",
                        -1800.0f, -1500.0f),
                    new Chokepoint("cp_a2", "Generator Shed Doorway",
                        450.0f, -450.0f),
                    new Chokepoint("cp_a3", "W Trench Mid",
                        0.0f, 0.0f),
                    new Chokepoint("cp_a4", "Operations Doorway",
                        450.0f, 450.0f),
                    new Chokepoint("cp_a5", "Operations Far E",
                        1800.0f, 1500.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "W Snow Wall Far W",
                        -1800.0f, -1500.0f),
                    new Chokepoint("cp_b2", "W Snow Wall",
                        0.0f, 450.0f),
                    new Chokepoint("cp_b3", "Open Ground",
                        0.0f, 0.0f),
                    new Chokepoint("cp_b4", "E Snow Wall",
                        0.0f, -450.0f),
                    new Chokepoint("cp_b5", "E Snow Wall Far E",
                        1800.0f, 1500.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Operations Doorway E Far W",
                        -1800.0f, 1500.0f),
                    new Chokepoint("cp_c2", "Operations Doorway E",
                        450.0f, 450.0f),
                    new Chokepoint("cp_c3", "E Trench Mid",
                        0.0f, 0.0f),
                    new Chokepoint("cp_c4", "Fuel Depot Doorway",
                        450.0f, -450.0f),
                    new Chokepoint("cp_c5", "Fuel Depot Far E",
                        1800.0f, -1500.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west
            // and east edges of the playable area (x = +-1800,
            // 15x from the 6v6 spec's x=+-120). y stays 0;
            // facings aim inward toward the trench entrances.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -1800.0f, 0.0f, -1500.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -1800.0f, 0.0f, 0.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_charlie", Team.RED, -1800.0f, 0.0f, 1500.0f,
                    toRadians(90.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 1800.0f, 0.0f, -1500.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 1800.0f, 0.0f, 0.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 1800.0f, 0.0f, 1500.0f,
                    toRadians(270.0f))
            ),
            // ---- 27 bot waypoints: a closed snake through a
            // 3-col x 9-row grid covering the 4800 x 4800 playable
            // area. Cols at x = -1800, 0, 1800 (1800 unit spacing,
            // well inside the inner wall at +-2304). Rows at z =
            // -2000, -1500, -1000, -500, 0, 500, 1000, 1500, 2000
            // (500 unit spacing, evenly distributed). The snake
            // alternates direction per row so a bot at waypoint i
            // moves toward waypoint i+1 without doubling back on
            // itself. y stays at 0 (kit floor). Note: the level
            // .ofm's buildings and trenches are 320 x 320 in the
            // middle of the 4800 x 4800 spec, so the waypoints
            // patrol the open ground around the centerpiece
            // rather than visiting the named features - fixing
            // that requires regenerating level.ofm.
            List.of(
                new Waypoint("wp_0", -1800.0f, 0.0f, -2000.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, -2000.0f),
                new Waypoint("wp_2", 1800.0f, 0.0f, -2000.0f),
                new Waypoint("wp_3", 1800.0f, 0.0f, -1500.0f),
                new Waypoint("wp_4", 0.0f, 0.0f, -1500.0f),
                new Waypoint("wp_5", -1800.0f, 0.0f, -1500.0f),
                new Waypoint("wp_6", -1800.0f, 0.0f, -1000.0f),
                new Waypoint("wp_7", 0.0f, 0.0f, -1000.0f),
                new Waypoint("wp_8", 1800.0f, 0.0f, -1000.0f),
                new Waypoint("wp_9", 1800.0f, 0.0f, -500.0f),
                new Waypoint("wp_10", 0.0f, 0.0f, -500.0f),
                new Waypoint("wp_11", -1800.0f, 0.0f, -500.0f),
                new Waypoint("wp_12", -1800.0f, 0.0f, 0.0f),
                new Waypoint("wp_13", 0.0f, 0.0f, 0.0f),
                new Waypoint("wp_14", 1800.0f, 0.0f, 0.0f),
                new Waypoint("wp_15", 1800.0f, 0.0f, 500.0f),
                new Waypoint("wp_16", 0.0f, 0.0f, 500.0f),
                new Waypoint("wp_17", -1800.0f, 0.0f, 500.0f),
                new Waypoint("wp_18", -1800.0f, 0.0f, 1000.0f),
                new Waypoint("wp_19", 0.0f, 0.0f, 1000.0f),
                new Waypoint("wp_20", 1800.0f, 0.0f, 1000.0f),
                new Waypoint("wp_21", 1800.0f, 0.0f, 1500.0f),
                new Waypoint("wp_22", 0.0f, 0.0f, 1500.0f),
                new Waypoint("wp_23", -1800.0f, 0.0f, 1500.0f),
                new Waypoint("wp_24", -1800.0f, 0.0f, 2000.0f),
                new Waypoint("wp_25", 0.0f, 0.0f, 2000.0f),
                new Waypoint("wp_26", 1800.0f, 0.0f, 2000.0f)
            ),
            // ---- Hardpoint markers: three zones, 1800-tic (30s)
            // rotation, 1 point per tic. Activation order A, B, C
            // (Generator Shed → Operations Trailer → Fuel Depot, the
            // round opens in the north-west building and ends in the
            // south-west building). All x/z scaled by 15; zone
            // radius scaled to 480 (15x) so the capture zone is
            // proportional to the larger map.
            new MapMarkers.Hardpoint(List.of(
                new MapMarkers.HardpointZone("hp_a", "Generator Shed", 900.0f, -900.0f,
                    480.0f),
                new MapMarkers.HardpointZone("hp_b", "Operations Trailer", 0.0f, 0.0f,
                    480.0f),
                new MapMarkers.HardpointZone("hp_c", "Fuel Depot", 900.0f, 1500.0f,
                    480.0f)
            ), 1800, 1),
            // ---- asset paths
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/arctic-hp/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    /**
     * Converts degrees to radians, used by the spawn facings.
     *
     * @param degrees the angle in degrees
     * @return the angle in radians
     */
    private static float toRadians(final float degrees)
    {
        // StrictMath on principle: simulation state paths use StrictMath
        // (PlayerController, Bot). This is map-data construction, not the
        // tic path, but matching the convention costs nothing.
        return (float) (degrees * StrictMath.PI / 180.0);
    }

    // ----- Pipeline (Industrial Complex × Domination) -----------------------

    /**
     * The eleventh shipped map. Industrial Complex, Domination,
     * MW2-Rust / BO6 large-map sizing (5600 x 5600 world units,
     * MAP_SCALE = 16 → 350 m square). A long east-west pipeline
     * pumping station: three parallel pipelines running across the
     * full 5600-unit width, with a control valve at the centre of
     * each pipeline. Three catwalks at y=64 run north-south
     * alongside each pipeline. Two east-west underpasses at x=-100
     * and x=+100 cut 64-unit-wide gaps through the pipelines and
     * the catwalks, so a player can cross the map without being
     * shot by a defender on the catwalk. The full design spec is in
     * {@code docs/maps/industrial-complex/03-dom-pipeline.md}.
     *
     * <p>Distinct from {@link #tripoint} in feel: this is a long
     * east-west corridor with the contested ground running through
     * the centre, not a three-way intersection. The three flags are
     * the three control valves; the round opens with both teams
     * pushing toward the centre, contesting FLAG_B (Pipeline
     * Centre) first, and the winning team rolls out to FLAG_A
     * (Pipeline South) and FLAG_C (Pipeline North). The level .ofm
     * is the original 320 x 320 composition — left untouched and
     * treated as a centerpiece. All x/z in the spec are scaled by
     * 17.5 from the original 320 x 320 spec; the level .ofm remains
     * a 320 x 320 centerpiece. Height grows from 96 to 128 to
     * match the other 12v12 industrial specs.</p>
     *
     * @return the Pipeline map spec
     */
    public static MapSpec pipeline()
    {
        return new MapSpec(
            // ---- identity
            "pipeline",
            "Pipeline",
            MapSetting.INDUSTRIAL_COMPLEX,
            MatchMode.DOMINATION,
            // ---- playable area: 5600 x 5600, 128 high (the
            // pipelines are 16 tall with a 16-tall control valve
            // on top; the catwalks sit at y=64 in the .ofm).
            // All x/z in the spec are scaled by 17.5 from the
            // original 320 x 320 spec; the level .ofm remains a
            // 320 x 320 centerpiece. Height is normalised to
            // the 12v12 standard of 128.
            new MapDimensions(5600.0f, 5600.0f, 128.0f),
            // ---- three lanes A/B/C encoded as the three
            // parallel pipelines. Lane A is the south pipeline,
            // lane B is the centre pipeline (the contested
            // middle), lane C is the north pipeline. The
            // original three chokepoints per lane are scaled by
            // 17.5; two new "Far" chokepoints are added at the
            // W and E extremes (x = +-2240) so a 5-chokepoint
            // lane (15 total) names the full west-to-east run
            // on each pipeline. Chokepoint labels (Pipeline
            // South / Centre / North West / Centre / East) are
            // preserved on the originals; the additions use the
            // "Far W" / "Far E" suffix. Lane centres are at
            // z=1680, 0, -1680.
            List.of(
                new Lane("lane_a", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_a1", "Pipeline South Far W",
                        -2240.0f, 1680.0f),
                    new Chokepoint("cp_a2", "Pipeline South West",
                        -560.0f, 1680.0f),
                    new Chokepoint("cp_a3", "Pipeline South Centre",
                        0.0f, 1680.0f),
                    new Chokepoint("cp_a4", "Pipeline South East",
                        560.0f, 1680.0f),
                    new Chokepoint("cp_a5", "Pipeline South Far E",
                        2240.0f, 1680.0f)
                )),
                new Lane("lane_b", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_b1", "Pipeline Centre Far W",
                        -2240.0f, 0.0f),
                    new Chokepoint("cp_b2", "Pipeline Centre West",
                        -560.0f, 0.0f),
                    new Chokepoint("cp_b3", "Pipeline Centre",
                        0.0f, 0.0f),
                    new Chokepoint("cp_b4", "Pipeline Centre East",
                        560.0f, 0.0f),
                    new Chokepoint("cp_b5", "Pipeline Centre Far E",
                        2240.0f, 0.0f)
                )),
                new Lane("lane_c", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_c1", "Pipeline North Far W",
                        -2240.0f, -1680.0f),
                    new Chokepoint("cp_c2", "Pipeline North West",
                        -560.0f, -1680.0f),
                    new Chokepoint("cp_c3", "Pipeline North Centre",
                        0.0f, -1680.0f),
                    new Chokepoint("cp_c4", "Pipeline North East",
                        560.0f, -1680.0f),
                    new Chokepoint("cp_c5", "Pipeline North Far E",
                        2240.0f, -1680.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west
            // and east edges of the playable area (x = +-2240,
            // 17.5x from the 6v6 spec's x=+-128), facings aimed
            // at Pipeline Centre. y stays 0.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -2240.0f, 0.0f, -1120.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -2240.0f, 0.0f, 0.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, -2240.0f, 0.0f, 1120.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 2240.0f, 0.0f, -1120.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 2240.0f, 0.0f, 0.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 2240.0f, 0.0f, 1120.0f,
                    toRadians(280.0f))
            ),
            // ---- 27 bot waypoints: a closed snake through a
            // 3-col x 9-row grid covering the 5600 x 5600
            // playable area. Cols at x = -2520, 0, 2520 (2520
            // unit spacing, inside the inner wall at +-2720).
            // Rows at z = -2520, -1890, -1260, -630, 0, 630,
            // 1260, 1890, 2520 (630 unit spacing). The snake
            // alternates direction per row. y stays at 0 (kit
            // floor).
            List.of(
                new Waypoint("wp_0", -2520.0f, 0.0f, -2520.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, -2520.0f),
                new Waypoint("wp_2", 2520.0f, 0.0f, -2520.0f),
                new Waypoint("wp_3", 2520.0f, 0.0f, -1890.0f),
                new Waypoint("wp_4", 0.0f, 0.0f, -1890.0f),
                new Waypoint("wp_5", -2520.0f, 0.0f, -1890.0f),
                new Waypoint("wp_6", -2520.0f, 0.0f, -1260.0f),
                new Waypoint("wp_7", 0.0f, 0.0f, -1260.0f),
                new Waypoint("wp_8", 2520.0f, 0.0f, -1260.0f),
                new Waypoint("wp_9", 2520.0f, 0.0f, -630.0f),
                new Waypoint("wp_10", 0.0f, 0.0f, -630.0f),
                new Waypoint("wp_11", -2520.0f, 0.0f, -630.0f),
                new Waypoint("wp_12", -2520.0f, 0.0f, 0.0f),
                new Waypoint("wp_13", 0.0f, 0.0f, 0.0f),
                new Waypoint("wp_14", 2520.0f, 0.0f, 0.0f),
                new Waypoint("wp_15", 2520.0f, 0.0f, 630.0f),
                new Waypoint("wp_16", 0.0f, 0.0f, 630.0f),
                new Waypoint("wp_17", -2520.0f, 0.0f, 630.0f),
                new Waypoint("wp_18", -2520.0f, 0.0f, 1260.0f),
                new Waypoint("wp_19", 0.0f, 0.0f, 1260.0f),
                new Waypoint("wp_20", 2520.0f, 0.0f, 1260.0f),
                new Waypoint("wp_21", 2520.0f, 0.0f, 1890.0f),
                new Waypoint("wp_22", 0.0f, 0.0f, 1890.0f),
                new Waypoint("wp_23", -2520.0f, 0.0f, 1890.0f),
                new Waypoint("wp_24", -2520.0f, 0.0f, 2520.0f),
                new Waypoint("wp_25", 0.0f, 0.0f, 2520.0f),
                new Waypoint("wp_26", 2520.0f, 0.0f, 2520.0f)
            ),
            // ---- Domination markers: three flags, A, B, C.
            // Flag x/z are scaled 17.5x from the 6v6 spec;
            // radius is scaled 17.5x as well (32 -> 560) so the
            // capture footprint keeps the same proportion of
            // the playable area. All start neutral; the round
            // opens with both teams pushing toward the centre,
            // contesting FLAG_B (Pipeline Centre) first.
            new MapMarkers.Domination(List.of(
                new MapMarkers.Flag("flag_a", "Pipeline South",
                    0.0f, 1680.0f, 560.0f),
                new MapMarkers.Flag("flag_b", "Pipeline Centre",
                    0.0f, 0.0f, 560.0f),
                new MapMarkers.Flag("flag_c", "Pipeline North",
                    0.0f, -1680.0f, 560.0f)
            )),
            // ---- asset paths (level .ofm unchanged; the 320 x
            // 320 mesh is the centerpiece in the middle of the
            // 5600 x 5600 spec).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/pipeline/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Sandbar (Desert Ravine × Domination) -----------------------------

    /**
     * The twelfth shipped map. Desert Ravine, Domination, 6v6 sizing.
     * A wide, shallow canyon with three flat-topped sandstone buttes
     * (z=64, z=160, z=256) rising from the canyon floor. Each butte
     * is 32 tall with a single 8-tread ramp on the east side. A dry
     * riverbed runs through the centre (y=-8) as the lowest ground in
     * the map. The full design spec is in
     * {@code docs/maps/desert-ravine/03-dom-sandbar.md}.
     *
     * <p>Distinct from {@link #tripoint} in feel: the contested
     * ground is the canyon floor (the long east-west sightline
     * between the buttes), not a roundabout. The buttes are the
     * rewards — a player who controls a butte has the high ground
     * and can see across the canyon. A sniper duel from the butte
     * top is the round's signature exchange.</p>
     *
     * @return the Sandbar map spec
     */
    public static MapSpec sandbar()
    {
        return new MapSpec(
            // ---- identity
            "sandbar",
            "Sandbar",
            MapSetting.DESERT_RAVINE,
            MatchMode.DOMINATION,
            // ---- playable area: 4000 x 4000, 128 high (a
            // 250m canyon with three flat-topped buttes; 16
            // world units per metre). The level .ofm and the
            // kit (columns at +/-80, walls at +/-160) are
            // centerpieces anchored at the origin; the data
            // spreads across the full 4000 x 4000 playable
            // extent.
            new MapDimensions(4000.0f, 4000.0f, 128.0f),
            // ---- three lanes A/B/C encoded as the three butte
            // approaches. Lane A is the south approach to FLAG_A,
            // lane B is the central approach to FLAG_B (Butte
            // Centre, the contested middle), lane C is the north
            // approach to FLAG_C. Chokepoints sit on three rows
            // at z = +/-96, 0, inside the playable area
            // (x in [-149.6, +149.6] — the previous x=240 east
            // chokepoints sat 86.4 units past the inner wall
            // face).
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Butte S Approach Far West", -1800.0f, 1200.0f),
                    new Chokepoint("cp_a2", "Butte S Approach West", -900.0f, 1200.0f),
                    new Chokepoint("cp_a3", "Butte S Centre", 0.0f, 1200.0f),
                    new Chokepoint("cp_a4", "Butte S Approach East", 900.0f, 1200.0f),
                    new Chokepoint("cp_a5", "Butte S Approach Far East", 1800.0f, 1200.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Butte Centre Far West", -1800.0f, 0.0f),
                    new Chokepoint("cp_b2", "Butte Centre West", -900.0f, 0.0f),
                    new Chokepoint("cp_b3", "Riverbed Centre", 0.0f, 0.0f),
                    new Chokepoint("cp_b4", "Butte Centre East", 900.0f, 0.0f),
                    new Chokepoint("cp_b5", "Butte Centre Far East", 1800.0f, 0.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Butte N Approach Far West", -1800.0f, -1200.0f),
                    new Chokepoint("cp_c2", "Butte N Approach West", -900.0f, -1200.0f),
                    new Chokepoint("cp_c3", "Butte N Centre", 0.0f, -1200.0f),
                    new Chokepoint("cp_c4", "Butte N Approach East", 900.0f, -1200.0f),
                    new Chokepoint("cp_c5", "Butte N Approach Far East", 1800.0f, -1200.0f)
                ))
            ),
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -400.0f, 0.0f, -400.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_bravo", Team.RED, -400.0f, 0.0f, 0.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_charlie", Team.RED, -400.0f, 0.0f, 400.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 400.0f, 0.0f, -400.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 400.0f, 0.0f, 0.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 400.0f, 0.0f, 400.0f,
                    toRadians(280.0f))
            ),
            List.of(
                new Waypoint("wp_0", -1500.0f, 0.0f, -1500.0f),
                new Waypoint("wp_1", -1000.0f, 0.0f, -1500.0f),
                new Waypoint("wp_2", -500.0f, 0.0f, -1500.0f),
                new Waypoint("wp_3", 0.0f, 0.0f, -1500.0f),
                new Waypoint("wp_4", 500.0f, 0.0f, -1500.0f),
                new Waypoint("wp_5", 1000.0f, 0.0f, -1500.0f),
                new Waypoint("wp_6", 1500.0f, 0.0f, -1500.0f),
                new Waypoint("wp_7", 1500.0f, 0.0f, -500.0f),
                new Waypoint("wp_8", 1000.0f, 0.0f, -500.0f),
                new Waypoint("wp_9", 500.0f, 0.0f, -500.0f),
                new Waypoint("wp_10", 0.0f, 0.0f, -500.0f),
                new Waypoint("wp_11", -500.0f, 0.0f, -500.0f),
                new Waypoint("wp_12", -1000.0f, 0.0f, -500.0f),
                new Waypoint("wp_13", -1500.0f, 0.0f, -500.0f),
                new Waypoint("wp_14", -1500.0f, 0.0f, 500.0f),
                new Waypoint("wp_15", -1000.0f, 0.0f, 500.0f),
                new Waypoint("wp_16", -500.0f, 0.0f, 500.0f),
                new Waypoint("wp_17", 0.0f, 0.0f, 500.0f),
                new Waypoint("wp_18", 500.0f, 0.0f, 500.0f),
                new Waypoint("wp_19", 1000.0f, 0.0f, 500.0f),
                new Waypoint("wp_20", 1500.0f, 0.0f, 500.0f),
                new Waypoint("wp_21", 1500.0f, 0.0f, 1500.0f),
                new Waypoint("wp_22", 1000.0f, 0.0f, 1500.0f),
                new Waypoint("wp_23", 500.0f, 0.0f, 1500.0f),
                new Waypoint("wp_24", 0.0f, 0.0f, 1500.0f),
                new Waypoint("wp_25", -500.0f, 0.0f, 1500.0f),
                new Waypoint("wp_26", -1000.0f, 0.0f, 1500.0f),
                new Waypoint("wp_27", -1500.0f, 0.0f, 1500.0f)
            ),
            new MapMarkers.Domination(List.of(
                new MapMarkers.Flag("flag_a", "Butte South", 0.0f, 1200.0f, 400.0f),
                new MapMarkers.Flag("flag_b", "Butte Centre", 0.0f, 0.0f, 400.0f),
                new MapMarkers.Flag("flag_c", "Butte North", 0.0f, -1200.0f, 400.0f)
            )),
            // ---- asset paths
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/sandbar/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Arctic-Dom / Frostline (Arctic Station × Domination) ------------

    /**
     * The thirteenth shipped map. Arctic Station, Domination,
     * MW2-Rust sizing (4000 x 4000 world units, MAP_SCALE = 16
     * → 250 m square). A long east-west polar ice road with three
     * flag platforms spaced 1200 units apart along the road
     * (z=-1200, 0, +1200). Each platform is a 16x16 raised ice
     * block with a radar mast in the centre. The full design
     * spec is in {@code docs/maps/arctic-station/03-dom-arctic.md}.
     *
     * <p>Distinct from {@link #tripoint} in feel: the contested
     * ground is the ice road (the long east-west feature), not a
     * roundabout. The three flags are the three platforms; the
     * round opens with both teams pushing toward the centre,
     * contesting FLAG_B (Centre Platform) first, and the winning
     * team rolls out to FLAG_A (South Platform) and FLAG_C (North
     * Platform). The map id is {@code "arctic-dom"}; the display
     * name is "Frostline" (per the design spec). All x/z in the
     * spec are scaled by 12.5 from the original 320 x 320 spec;
     * the y=4 floor offset is part of the level .ofm and stays
     * put. The level .ofm remains a 320 x 320 centerpiece.</p>
     *
     * @return the Arctic-Dom (Frostline) map spec
     */
    public static MapSpec arcticDom()
    {
        return new MapSpec(
            // ---- identity
            "arctic-dom",
            "Frostline",
            MapSetting.ARCTIC_STATION,
            MatchMode.DOMINATION,
            // ---- playable area: 4000 x 4000, 96 high (the
            // platforms are 16 tall; the radar masts rise 32
            // units above the platform tops; the snow walls are
            // 16 tall). All x/z in the spec are scaled by 12.5
            // from the original 320 x 320 spec; the level .ofm
            // remains a 320 x 320 centerpiece.
            new MapDimensions(4000.0f, 4000.0f, 96.0f),
            // ---- three lanes A/B/C encoded as the three
            // platform approaches along the ice road. The
            // original three chokepoints per lane are scaled by
            // 12.5; two new "Far" chokepoints are added at the W
            // and E extremes (x = +-1500) so a 5-chokepoint lane
            // (15 total) names the full east-to-west run on each
            // row. Chokepoint labels (South Platform, Centre
            // Platform, North Platform) are preserved on the
            // originals; the additions use the "Far W" / "Far E"
            // suffix. Lane centres are at z=-1200, 0, +1200
            // (three platforms spanning the 4000-unit depth).
            List.of(
                new Lane("lane_a", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_a1", "South Platform Far W",
                        -1500.0f, 1200.0f),
                    new Chokepoint("cp_a2", "South Platform West",
                        -1200.0f, 1200.0f),
                    new Chokepoint("cp_a3", "South Platform Centre",
                        0.0f, 1200.0f),
                    new Chokepoint("cp_a4", "South Platform East",
                        1200.0f, 1200.0f),
                    new Chokepoint("cp_a5", "South Platform Far E",
                        1500.0f, 1200.0f)
                )),
                new Lane("lane_b", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_b1", "Centre Platform Far W",
                        -1500.0f, 0.0f),
                    new Chokepoint("cp_b2", "Centre Platform West",
                        -1200.0f, 0.0f),
                    new Chokepoint("cp_b3", "Centre Platform",
                        0.0f, 0.0f),
                    new Chokepoint("cp_b4", "Centre Platform East",
                        1200.0f, 0.0f),
                    new Chokepoint("cp_b5", "Centre Platform Far E",
                        1500.0f, 0.0f)
                )),
                new Lane("lane_c", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_c1", "North Platform Far W",
                        -1500.0f, -1200.0f),
                    new Chokepoint("cp_c2", "North Platform West",
                        -1200.0f, -1200.0f),
                    new Chokepoint("cp_c3", "North Platform Centre",
                        0.0f, -1200.0f),
                    new Chokepoint("cp_c4", "North Platform East",
                        1200.0f, -1200.0f),
                    new Chokepoint("cp_c5", "North Platform Far E",
                        1500.0f, -1200.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west
            // and east edges of the playable area (x = +-1600,
            // 12.5x from the 6v6 spec's x=+-128), facings aimed
            // at the centre of the road. RED's spawns are spread
            // along the road so RED can pivot between FLAG_C and
            // FLAG_A.
            //
            // Y is 4 (not 0): the level .ofm's floor is 8 units
            // thick and centred on y=0, so its top sits at y=4. A
            // body at y=0 stands 4 units inside the floor — the
            // floor's AABB is X=[-160, 176], Z=[-160, 240] and every
            // body in that rectangle is inside it. Standing on the
            // floor top means feet at y=4.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -1600.0f, 4.0f, -1000.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -1600.0f, 4.0f, 0.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, -1600.0f, 4.0f, 1000.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 1600.0f, 4.0f, -1000.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 1600.0f, 4.0f, 0.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 1600.0f, 4.0f, 1000.0f,
                    toRadians(280.0f))
            ),
            // ---- 27 bot waypoints: a closed snake through a
            // 3-col x 9-row grid covering the 4000 x 4000 playable
            // area. Cols at x = -1800, 0, 1800 (1800 unit spacing,
            // well inside the inner wall at +-1920). Rows at z =
            // -1800, -1350, -900, -450, 0, 450, 900, 1350, 1800
            // (450 unit spacing, evenly distributed). The snake
            // alternates direction per row so a bot at waypoint i
            // moves toward waypoint i+1 without doubling back on
            // itself. y stays at 4 (the level .ofm's floor top).
            List.of(
                new Waypoint("wp_0", -1800.0f, 4.0f, -1800.0f),
                new Waypoint("wp_1", 0.0f, 4.0f, -1800.0f),
                new Waypoint("wp_2", 1800.0f, 4.0f, -1800.0f),
                new Waypoint("wp_3", 1800.0f, 4.0f, -1350.0f),
                new Waypoint("wp_4", 0.0f, 4.0f, -1350.0f),
                new Waypoint("wp_5", -1800.0f, 4.0f, -1350.0f),
                new Waypoint("wp_6", -1800.0f, 4.0f, -900.0f),
                new Waypoint("wp_7", 0.0f, 4.0f, -900.0f),
                new Waypoint("wp_8", 1800.0f, 4.0f, -900.0f),
                new Waypoint("wp_9", 1800.0f, 4.0f, -450.0f),
                new Waypoint("wp_10", 0.0f, 4.0f, -450.0f),
                new Waypoint("wp_11", -1800.0f, 4.0f, -450.0f),
                new Waypoint("wp_12", -1800.0f, 4.0f, 0.0f),
                new Waypoint("wp_13", 0.0f, 4.0f, 0.0f),
                new Waypoint("wp_14", 1800.0f, 4.0f, 0.0f),
                new Waypoint("wp_15", 1800.0f, 4.0f, 450.0f),
                new Waypoint("wp_16", 0.0f, 4.0f, 450.0f),
                new Waypoint("wp_17", -1800.0f, 4.0f, 450.0f),
                new Waypoint("wp_18", -1800.0f, 4.0f, 900.0f),
                new Waypoint("wp_19", 0.0f, 4.0f, 900.0f),
                new Waypoint("wp_20", 1800.0f, 4.0f, 900.0f),
                new Waypoint("wp_21", 1800.0f, 4.0f, 1350.0f),
                new Waypoint("wp_22", 0.0f, 4.0f, 1350.0f),
                new Waypoint("wp_23", -1800.0f, 4.0f, 1350.0f),
                new Waypoint("wp_24", -1800.0f, 4.0f, 1800.0f),
                new Waypoint("wp_25", 0.0f, 4.0f, 1800.0f),
                new Waypoint("wp_26", 1800.0f, 4.0f, 1800.0f)
            ),
            // ---- Domination markers: three flags, A, B, C. The
            // flags sit on the road between the platforms (the
            // platforms themselves are visual only — the level
            // .ofm's platforms are at x=160 which sits on the kit
            // east wall, so a flag at x=160 is unreachable from the
            // playable area). The round opens with both teams
            // pushing toward the centre, contesting FLAG_B
            // (Centre Platform) first. All x/z scaled by 12.5;
            // flag radius scaled to 400 (12.5x) so the capture
            // zone is proportional to the larger map.
            new MapMarkers.Domination(List.of(
                new MapMarkers.Flag("flag_a", "South Platform", 0.0f, 1200.0f, 400.0f),
                new MapMarkers.Flag("flag_b", "Centre Platform", 0.0f, 0.0f, 400.0f),
                new MapMarkers.Flag("flag_c", "North Platform", 0.0f, -1200.0f, 400.0f)
            )),
            // ---- asset paths (level .ofm unchanged; the 320 x
            // 320 mesh is the centerpiece in the middle of the
            // 4000 x 4000 spec, with the floor at y = 4).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/arctic-dom/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Storage (Industrial Complex × CTF) -----------------------------

    /**
     * The tenth shipped map. Industrial Complex, CTF, MW2-Rust /
     * BO6 large-map sizing (4800 x 4800 world units, MAP_SCALE = 16
     * → 300 m square). A chemical storage facility: two warehouse
     * buildings at opposite ends of the map, each with the team's
     * flag inside, and a maze of eight storage tanks in the centre.
     * The full design spec is in
     * {@code docs/maps/industrial-complex/04-ctf-storage.md}.
     *
     * <p>Distinct from {@link #extraction} in feel: the play is
     * "carry the flag through the tank maze" with the central row
     * of tanks as the contested centre. The carrier's run is
     * ~280 units long (6v6) or ~4200 units long (12v12), broken
     * into three segments: leaving the home warehouse, crossing
     * the maze, and entering the enemy warehouse. The level .ofm
     * is the original 320 x 320 composition — left untouched and
     * treated as a centerpiece. All x/z in the spec are scaled by
     * 15 from the original 320 x 320 spec; the level .ofm remains
     * a 320 x 320 centerpiece.</p>
     *
     * @return the Storage map spec
     */
    public static MapSpec storage()
    {
        return new MapSpec(
            // ---- identity
            "storage",
            "Storage",
            MapSetting.INDUSTRIAL_COMPLEX,
            MatchMode.CTF,
            // ---- playable area: 4800 x 4800, 128 high (the
            // warehouses are 32-tall, the central catwalk is at
            // y=64 in the .ofm). All x/z in the spec are scaled
            // by 15 from the original 320 x 320 spec; the level
            // .ofm remains a 320 x 320 centerpiece.
            new MapDimensions(4800.0f, 4800.0f, 128.0f),
            // ---- three lanes A/B/C, mirroring Refinery but
            // with the warehouse anchors on opposite ends. The
            // original three chokepoints per lane are scaled by
            // 15; two new "Far" chokepoints are added at the
            // lane extremes to extend each lane to the
            // playable-area edge. Lane_a runs the west side
            // (x = -1800), lane_b the central tank row (x =
            // +-750), lane_c the east side (x = 1800).
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Red Warehouse Far",
                        -1800.0f, -2160.0f),
                    new Chokepoint("cp_a2", "Red Warehouse",
                        -1800.0f, -1920.0f),
                    new Chokepoint("cp_a3", "Red Approach",
                        -1800.0f, -1200.0f),
                    new Chokepoint("cp_a4", "Red Approach East",
                        -1800.0f, -600.0f),
                    new Chokepoint("cp_a5", "Red Approach Far E",
                        -1800.0f, 0.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Tank Row N Far",
                        0.0f, -1500.0f),
                    new Chokepoint("cp_b2", "Tank Row N West",
                        -750.0f, -300.0f),
                    new Chokepoint("cp_b3", "Tank Row N East",
                        750.0f, -300.0f),
                    new Chokepoint("cp_b4", "Tank Row S West",
                        -750.0f, 900.0f),
                    new Chokepoint("cp_b5", "Tank Row S Far",
                        0.0f, 1500.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Blue Approach Far",
                        1800.0f, 0.0f),
                    new Chokepoint("cp_c2", "Tank Row S East",
                        750.0f, 900.0f),
                    new Chokepoint("cp_c3", "Blue Approach",
                        1800.0f, 1200.0f),
                    new Chokepoint("cp_c4", "Blue Warehouse",
                        1800.0f, 1920.0f),
                    new Chokepoint("cp_c5", "Blue Warehouse Far",
                        1800.0f, 2160.0f)
                ))
            ),
            // ---- six spawn points: three per team in the
            // central corridor (x = +-480, 15x from the 6v6
            // spec's x=+-32), spread along z. y stays 0;
            // facings aim outward toward the lane the team
            // will attack.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -480.0f, 0.0f, -480.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -480.0f, 0.0f, 0.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, -480.0f, 0.0f, 480.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 480.0f, 0.0f, -480.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 480.0f, 0.0f, 0.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 480.0f, 0.0f, 480.0f,
                    toRadians(280.0f))
            ),
            // ---- 27 bot waypoints: a closed snake through a
            // 3-col x 9-row grid covering the 4800 x 4800
            // playable area. Cols at x = -2160, 0, 2160 (2160
            // unit spacing, inside the inner wall at +-2320).
            // Rows at z = -2160, -1620, -1080, -540, 0, 540,
            // 1080, 1620, 2160 (540 unit spacing). The snake
            // alternates direction per row. y stays at 0 (kit
            // floor).
            List.of(
                new Waypoint("wp_0", -2160.0f, 0.0f, -2160.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, -2160.0f),
                new Waypoint("wp_2", 2160.0f, 0.0f, -2160.0f),
                new Waypoint("wp_3", 2160.0f, 0.0f, -1620.0f),
                new Waypoint("wp_4", 0.0f, 0.0f, -1620.0f),
                new Waypoint("wp_5", -2160.0f, 0.0f, -1620.0f),
                new Waypoint("wp_6", -2160.0f, 0.0f, -1080.0f),
                new Waypoint("wp_7", 0.0f, 0.0f, -1080.0f),
                new Waypoint("wp_8", 2160.0f, 0.0f, -1080.0f),
                new Waypoint("wp_9", 2160.0f, 0.0f, -540.0f),
                new Waypoint("wp_10", 0.0f, 0.0f, -540.0f),
                new Waypoint("wp_11", -2160.0f, 0.0f, -540.0f),
                new Waypoint("wp_12", -2160.0f, 0.0f, 0.0f),
                new Waypoint("wp_13", 0.0f, 0.0f, 0.0f),
                new Waypoint("wp_14", 2160.0f, 0.0f, 0.0f),
                new Waypoint("wp_15", 2160.0f, 0.0f, 540.0f),
                new Waypoint("wp_16", 0.0f, 0.0f, 540.0f),
                new Waypoint("wp_17", -2160.0f, 0.0f, 540.0f),
                new Waypoint("wp_18", -2160.0f, 0.0f, 1080.0f),
                new Waypoint("wp_19", 0.0f, 0.0f, 1080.0f),
                new Waypoint("wp_20", 2160.0f, 0.0f, 1080.0f),
                new Waypoint("wp_21", 2160.0f, 0.0f, 1620.0f),
                new Waypoint("wp_22", 0.0f, 0.0f, 1620.0f),
                new Waypoint("wp_23", -2160.0f, 0.0f, 1620.0f),
                new Waypoint("wp_24", -2160.0f, 0.0f, 2160.0f),
                new Waypoint("wp_25", 0.0f, 0.0f, 2160.0f),
                new Waypoint("wp_26", 2160.0f, 0.0f, 2160.0f)
            ),
            // ---- CTF markers: red's base (west) and blue's
            // base (east). Base flag/capture x/z are scaled
            // 15x from the 6v6 spec; radius is scaled 15x as
            // well (32 -> 480) so the capture footprint
            // keeps the same proportion of the playable area.
            new MapMarkers.CaptureTheFlag(
                new MapMarkers.Base(Team.RED, -1920.0f, -1920.0f,
                    -1920.0f, -1920.0f, 480.0f),
                new MapMarkers.Base(Team.BLUE, 1920.0f, 1920.0f,
                    1920.0f, 1920.0f, 480.0f)
            ),
            // ---- asset paths (level .ofm unchanged; the 320 x
            // 320 mesh is the centerpiece in the middle of the
            // 4800 x 4800 spec).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/storage/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Stronghold (Desert Ravine × CTF) --------------------------------

    /**
     * The eleventh shipped map. Desert Ravine, CTF, 6v6 sizing. A
     * sandstone fortress with two gate towers, four corner towers, and
     * a central courtyard. The full design spec is in
     * {@code docs/maps/desert-ravine/04-ctf-stronghold.md}.
     *
     * <p>Distinct from {@link #extraction} in feel: the play is
     * "carry the flag through the courtyard" with the gate towers as
     * the natural chokepoints. The courtyard is a 96×96 open space with
     * a fountain at the centre; flanking cliff walls at z=64 and z=256
     * give defenders a high-ground option.</p>
     *
     * @return the Stronghold map spec
     */
    public static MapSpec stronghold()
    {
        return new MapSpec(
            // ---- identity
            "stronghold",
            "Stronghold",
            MapSetting.DESERT_RAVINE,
            MatchMode.CTF,
            // ---- playable area: 4000 x 4000, 128 high (a 250m
            // sandstone fortress with two gate towers, four corner
            // towers, and a central courtyard; 16 world units per
            // metre). The level .ofm and the kit (columns at
            // +/-80, walls at +/-160) are centerpieces anchored at
            // the origin; the data spreads across the full
            // 4000 x 4000 playable extent.
            new MapDimensions(4000.0f, 4000.0f, 128.0f),
            // ---- three lanes A/B/C, with the courtyard in the
            // middle and the cliff walls in lanes A and C.
            // Chokepoints sit on three rows at z = +/-96, 0
            // inside the playable area; the previous z=64 /
            // z=160 / z=256 placements treated z as a 0..320 range
            // and put the south cliff past the kit south wall.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Cliff N Far West", -1800.0f, -1200.0f),
                    new Chokepoint("cp_a2", "Cliff N West", -900.0f, -1200.0f),
                    new Chokepoint("cp_a3", "Cliff N Centre", 0.0f, -1200.0f),
                    new Chokepoint("cp_a4", "Cliff N East", 900.0f, -1200.0f),
                    new Chokepoint("cp_a5", "Cliff N Far East", 1800.0f, -1200.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "West Gate Far", -1800.0f, 0.0f),
                    new Chokepoint("cp_b2", "West Gate", -900.0f, 0.0f),
                    new Chokepoint("cp_b3", "Courtyard Fountain", 0.0f, 0.0f),
                    new Chokepoint("cp_b4", "East Gate", 900.0f, 0.0f),
                    new Chokepoint("cp_b5", "East Gate Far", 1800.0f, 0.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Cliff S Far West", -1800.0f, 1200.0f),
                    new Chokepoint("cp_c2", "Cliff S West", -900.0f, 1200.0f),
                    new Chokepoint("cp_c3", "Cliff S Centre", 0.0f, 1200.0f),
                    new Chokepoint("cp_c4", "Cliff S East", 900.0f, 1200.0f),
                    new Chokepoint("cp_c5", "Cliff S Far East", 1800.0f, 1200.0f)
                ))
            ),
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -400.0f, 0.0f, -400.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -400.0f, 0.0f, 0.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, -400.0f, 0.0f, 400.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 400.0f, 0.0f, -400.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 400.0f, 0.0f, 0.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 400.0f, 0.0f, 400.0f,
                    toRadians(280.0f))
            ),
            List.of(
                new Waypoint("wp_0", -1500.0f, 0.0f, -1500.0f),
                new Waypoint("wp_1", -1000.0f, 0.0f, -1500.0f),
                new Waypoint("wp_2", -500.0f, 0.0f, -1500.0f),
                new Waypoint("wp_3", 0.0f, 0.0f, -1500.0f),
                new Waypoint("wp_4", 500.0f, 0.0f, -1500.0f),
                new Waypoint("wp_5", 1000.0f, 0.0f, -1500.0f),
                new Waypoint("wp_6", 1500.0f, 0.0f, -1500.0f),
                new Waypoint("wp_7", 1500.0f, 0.0f, -500.0f),
                new Waypoint("wp_8", 1000.0f, 0.0f, -500.0f),
                new Waypoint("wp_9", 500.0f, 0.0f, -500.0f),
                new Waypoint("wp_10", 0.0f, 0.0f, -500.0f),
                new Waypoint("wp_11", -500.0f, 0.0f, -500.0f),
                new Waypoint("wp_12", -1000.0f, 0.0f, -500.0f),
                new Waypoint("wp_13", -1500.0f, 0.0f, -500.0f),
                new Waypoint("wp_14", -1500.0f, 0.0f, 500.0f),
                new Waypoint("wp_15", -1000.0f, 0.0f, 500.0f),
                new Waypoint("wp_16", -500.0f, 0.0f, 500.0f),
                new Waypoint("wp_17", 0.0f, 0.0f, 500.0f),
                new Waypoint("wp_18", 500.0f, 0.0f, 500.0f),
                new Waypoint("wp_19", 1000.0f, 0.0f, 500.0f),
                new Waypoint("wp_20", 1500.0f, 0.0f, 500.0f),
                new Waypoint("wp_21", 1500.0f, 0.0f, 1500.0f),
                new Waypoint("wp_22", 1000.0f, 0.0f, 1500.0f),
                new Waypoint("wp_23", 500.0f, 0.0f, 1500.0f),
                new Waypoint("wp_24", 0.0f, 0.0f, 1500.0f),
                new Waypoint("wp_25", -500.0f, 0.0f, 1500.0f),
                new Waypoint("wp_26", -1000.0f, 0.0f, 1500.0f),
                new Waypoint("wp_27", -1500.0f, 0.0f, 1500.0f)
            ),
            new MapMarkers.CaptureTheFlag(
                new MapMarkers.Base(Team.RED, -1600.0f, -1600.0f, -1600.0f, -1600.0f, 400.0f),
                new MapMarkers.Base(Team.BLUE, 1600.0f, 1600.0f, 1600.0f, 1600.0f, 400.0f)
            ),
            // ---- asset paths
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/stronghold/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Coldfront (Arctic Station × CTF) --------------------------------

    /**
     * The twelfth and final shipped map. Arctic Station, CTF,
     * MW2-Rust / BO6 large-map sizing (4800 x 4800 world units,
     * MAP_SCALE = 16 → 300 m square). A polar-research base split
     * across two sides of a frozen river. The full design spec is
     * in {@code docs/maps/arctic-station/04-ctf-arctic.md}.
     *
     * <p>Distinct from {@link #extraction} in feel: the play is
     * "carry the flag across the frozen river" with the watchtowers
     * as the natural chokepoints. The river is 1440 units wide and
     * unbroken; the carrier's run is ~3840 units long, visible
     * from both watchtowers the entire way. All x/z in the spec
     * are scaled by 15 from the original 320 x 320 spec; the
     * level .ofm remains a 320 x 320 centerpiece.</p>
     *
     * @return the Coldfront map spec
     */
    public static MapSpec coldfront()
    {
        return new MapSpec(
            // ---- identity
            "coldfront",
            "Coldfront",
            MapSetting.ARCTIC_STATION,
            MatchMode.CTF,
            // ---- playable area: 4800 x 4800, 128 high. The kit
            // composer centres the playable area on the origin, so
            // x and z both span -2400 to +2400; the inner wall face
            // sits at +/-2304.
            new MapDimensions(4800.0f, 4800.0f, 128.0f),
            // ---- three lanes A/B/C, with the frozen river in the
            // middle. Chokepoints sit inside the playable area.
            // All x/z scaled by 15 from the original 320 x 320
            // spec; original labels (Red Service Shed, Red Main
            // Hut, Red Watchtower, River N/S, Blue Watchtower,
            // Blue Main Hut, Blue Service Shed) are preserved.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Red Service Shed", -960.0f, 1800.0f),
                    new Chokepoint("cp_a2", "Red Main Hut", -1440.0f, 2400.0f),
                    new Chokepoint("cp_a3", "Red Watchtower", -480.0f, 2400.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "River N West", -480.0f, 1500.0f),
                    new Chokepoint("cp_b2", "River Centre", 0.0f, 2400.0f),
                    new Chokepoint("cp_b3", "River S East", 480.0f, 3300.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Blue Watchtower", 480.0f, 2400.0f),
                    new Chokepoint("cp_c2", "Blue Main Hut", 1440.0f, 2400.0f),
                    new Chokepoint("cp_c3", "Blue Service Shed", 960.0f, 1800.0f)
                ))
            ),
            // ---- six spawn points in the central corridor
            // (x = +/-400, 15x the 6v6 spec's x=+/-32). y stays 0.
            // The wider corridor gives the larger playable area
            // some room to push off the spawn without bumping the
            // walls.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -400.0f, 0.0f, -400.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -400.0f, 0.0f, 0.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, -400.0f, 0.0f, 400.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 400.0f, 0.0f, -400.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 400.0f, 0.0f, 0.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 400.0f, 0.0f, 400.0f,
                    toRadians(280.0f))
            ),
            // ---- 27 bot waypoints: a closed snake through a
            // 3-col x 9-row grid covering the 4800 x 4800 playable
            // area. Cols at x = -1800, 0, 1800 (1800 unit spacing,
            // well inside the inner wall at +-2304). Rows at z =
            // -2000, -1500, -1000, -500, 0, 500, 1000, 1500, 2000
            // (500 unit spacing, evenly distributed). The snake
            // alternates direction per row so a bot at waypoint i
            // moves toward waypoint i+1 without doubling back on
            // itself. y stays at 0 (kit floor).
            List.of(
                new Waypoint("wp_0", -1800.0f, 0.0f, -2000.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, -2000.0f),
                new Waypoint("wp_2", 1800.0f, 0.0f, -2000.0f),
                new Waypoint("wp_3", 1800.0f, 0.0f, -1500.0f),
                new Waypoint("wp_4", 0.0f, 0.0f, -1500.0f),
                new Waypoint("wp_5", -1800.0f, 0.0f, -1500.0f),
                new Waypoint("wp_6", -1800.0f, 0.0f, -1000.0f),
                new Waypoint("wp_7", 0.0f, 0.0f, -1000.0f),
                new Waypoint("wp_8", 1800.0f, 0.0f, -1000.0f),
                new Waypoint("wp_9", 1800.0f, 0.0f, -500.0f),
                new Waypoint("wp_10", 0.0f, 0.0f, -500.0f),
                new Waypoint("wp_11", -1800.0f, 0.0f, -500.0f),
                new Waypoint("wp_12", -1800.0f, 0.0f, 0.0f),
                new Waypoint("wp_13", 0.0f, 0.0f, 0.0f),
                new Waypoint("wp_14", 1800.0f, 0.0f, 0.0f),
                new Waypoint("wp_15", 1800.0f, 0.0f, 500.0f),
                new Waypoint("wp_16", 0.0f, 0.0f, 500.0f),
                new Waypoint("wp_17", -1800.0f, 0.0f, 500.0f),
                new Waypoint("wp_18", -1800.0f, 0.0f, 1000.0f),
                new Waypoint("wp_19", 0.0f, 0.0f, 1000.0f),
                new Waypoint("wp_20", 1800.0f, 0.0f, 1000.0f),
                new Waypoint("wp_21", 1800.0f, 0.0f, 1500.0f),
                new Waypoint("wp_22", 0.0f, 0.0f, 1500.0f),
                new Waypoint("wp_23", -1800.0f, 0.0f, 1500.0f),
                new Waypoint("wp_24", -1800.0f, 0.0f, 2000.0f),
                new Waypoint("wp_25", 0.0f, 0.0f, 2000.0f),
                new Waypoint("wp_26", 1800.0f, 0.0f, 2000.0f)
            ),
            // ---- CTF markers: red's base (west) and blue's
            // base (east). The bases are inside the playable
            // area, inside the compounds. All x/z scaled by 15.
            new MapMarkers.CaptureTheFlag(
                new MapMarkers.Base(Team.RED, -1920.0f, 1920.0f, -1920.0f, 1920.0f,
                    480.0f),
                new MapMarkers.Base(Team.BLUE, 1920.0f, 1920.0f, 1920.0f, 1920.0f,
                    480.0f)
            ),
            // ---- asset paths (level .ofm unchanged; the 320 x
            // 320 mesh is the centerpiece in the middle of the
            // 4800 x 4800 spec).
            List.of(),
            new MapAssets(
                "engine/src/main/resources/maps/coldfront/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Area Rules (AREA_RULES) -------------------------------------

    /**
     * The seventeenth shipped map. Area Rules, the 2026-08
     * pickup-sandbox mode. A 1600 x 1600 playable area (smaller
     * than the other settings because the mode is about the
     * pickups, not the geometry), with eight bot waypoints
     * arranged in a ring, and three weapon pickups: one rocket
     * launcher hidden under the level .ofm's centerpiece (the
     * "use it or lose it" weapon, one per map), and two shotguns
     * in the diagonals so the player has to move to find them.
     *
     * <p>The level .ofm is the same cornerstone as the TDM
     * cornerstone, treated as a centerpiece in the middle of
     * the 1600 x 1600 spec. The kit composer's perimeter wall,
     * ceiling, columns, crates, and the new structural scenery
     * (L-walls, T-walls, stepped covers, low half-walls) fill
     * the rest of the playable area. The pickups are placed in
     * spots the player cannot see from the spawn - one in the
     * centre under the level geometry (the rocket) and two in
     * the diagonals where a player who has cleared the centre
     * will eventually walk.</p>
     *
     * <p>Distinct from the other modes in feel: there is no
     * scoring, no win condition, no team. The match is "kill
     * the bots" and the only failure is running out of ammo
     * (the bot DPS outpaces the blaster's damage at range).
     * The pickups are the affordance, and the rocket launcher
     * is the kill switch.</p>
     *
     * @return the Area Rules map spec
     */
    public static MapSpec areaRules()
    {
        return new MapSpec(
            // ---- identity
            "area-rules",
            "Area Rules",
            // ---- visual setting: the URBAN_WARZONE kit
            // doubles as the area-rules kit because the
            // mode is more about the pickups than the
            // setting. The kit composer's geometry fills
            // the playable area.
            MapSetting.URBAN_WARZONE,
            // ---- mode
            MatchMode.AREA_RULES,
            // ---- dimensions: 1600 x 1600. Smaller than
            // the other maps because the mode is about
            // pickups, not geometry.
            new MapDimensions(1600.0f, 1600.0f, 192.0f),
            // ---- lanes
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a_n", "North", 0.0f, -640.0f),
                    new Chokepoint("cp_a_c", "Centre", 0.0f, 0.0f),
                    new Chokepoint("cp_a_s", "South", 0.0f, 640.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b_n", "North", 480.0f, -640.0f),
                    new Chokepoint("cp_b_c", "Centre", 480.0f, 0.0f),
                    new Chokepoint("cp_b_s", "South", 480.0f, 640.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c_n", "North", -480.0f, -640.0f),
                    new Chokepoint("cp_c_c", "Centre", -480.0f, 0.0f),
                    new Chokepoint("cp_c_s", "South", -480.0f, 640.0f)
                ))
            ),
            // ---- spawn points: 3 per team, on each
            // team's half of the playable area.
            List.of(
                new SpawnPoint("red_a", Team.RED, -640.0f, 0.0f, -640.0f, 0.0f),
                new SpawnPoint("red_b", Team.RED, -640.0f, 0.0f, 0.0f, (float) (Math.PI * 0.5)),
                new SpawnPoint("red_c", Team.RED, -640.0f, 0.0f, 640.0f, 0.0f),
                new SpawnPoint("blue_a", Team.BLUE, 640.0f, 0.0f, -640.0f, (float) Math.PI),
                new SpawnPoint("blue_b", Team.BLUE, 640.0f, 0.0f, 0.0f, (float) (-Math.PI * 0.5)),
                new SpawnPoint("blue_c", Team.BLUE, 640.0f, 0.0f, 640.0f, (float) Math.PI)
            ),
            // ---- bot waypoints: 8 bots in a ring, the
            // same PACE_X / PACE_Z / ORBIT cycle the other
            // maps use.
            List.of(
                new Waypoint("wp_0", 0.0f, 0.0f, -640.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, 0.0f),
                new Waypoint("wp_2", 0.0f, 0.0f, 640.0f),
                new Waypoint("wp_3", 480.0f, 0.0f, -480.0f),
                new Waypoint("wp_4", 480.0f, 0.0f, 480.0f),
                new Waypoint("wp_5", -480.0f, 0.0f, -480.0f),
                new Waypoint("wp_6", -480.0f, 0.0f, 480.0f),
                new Waypoint("wp_7", 0.0f, 0.0f, 320.0f)
            ),
            // ---- markers: area-rules has no extra
            // structure beyond the pickups it carries.
            MapMarkers.AreaRules.INSTANCE,
            // ---- pickups: two shotguns in the diagonals
            // and one rocket launcher in the centre. The
            // rocket is "hidden" under the level .ofm
            // centerpiece (the player walks into the
            // centre to find it); the shotguns are in
            // the corners so the player has to move.
            //   shotgun_sw at (-720, 0, -720)
            //   shotgun_se at (720, 0, 720)
            //   rocket_centre at (0, 0, 0) - the
            //     "use it or lose it" weapon, one per map.
            List.of(
                new Pickup(Weapon.SHOTGUN, -720.0f, 0.0f, -720.0f),
                new Pickup(Weapon.SHOTGUN, 720.0f, 0.0f, 720.0f),
                new Pickup(Weapon.ROCKET_LAUNCHER, 0.0f, 0.0f, 0.0f)
            ),
            // ---- asset paths: the cornerstone level .ofm
            // is the centerpiece, and the demo's blaster
            // is the held weapon.
            new MapAssets(
                "engine/src/main/resources/maps/cornerstone/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }
}
