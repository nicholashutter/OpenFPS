/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import com.openfps.engine.gameplay.MatchMode;

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
     * The first shipped map. Urban Warzone, TDM, 6v6 sizing, three-lane
     * COD layout. The full design spec — the ASCII map, the callouts, the
     * lane routes, the spawn rationale — is in
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
            // ---- playable area: 320 x 320, 128 high (one MAX_OPEN_HEIGHT floor)
            new MapDimensions(320.0f, 320.0f, 128.0f),
            // ---- three lanes A/B/C, each with chokepoints in travel order
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Cafe", 64.0f, 24.0f),
                    new Chokepoint("cp_a2", "Plaza", 128.0f, 80.0f),
                    new Chokepoint("cp_a3", "Library", 192.0f, 24.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Bridge", 64.0f, 160.0f),
                    new Chokepoint("cp_b2", "Market", 160.0f, 160.0f),
                    new Chokepoint("cp_b3", "Atrium", 256.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Storefront", 64.0f, 296.0f),
                    new Chokepoint("cp_c2", "Alley", 160.0f, 240.0f),
                    new Chokepoint("cp_c3", "Plaza", 256.0f, 296.0f)
                ))
            ),
            // ---- six spawn points: three per team, two on the west and
            // east edges of each team's half. Facings point inward, slightly
            // off-axis, so a spawner faces the lane without looking directly
            // down it.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 64.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 128.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 192.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 128.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 192.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 256.0f,
                    toRadians(280.0f))
            ),
            // ---- bot waypoints: a closed loop covering lanes A, B and C,
            // touched in order. A bot at waypoint i moves toward waypoint
            // i+1, wrapping at the end.
            List.of(
                new Waypoint("wp_0", 80.0f, 0.0f, 24.0f),
                new Waypoint("wp_1", 160.0f, 0.0f, 24.0f),
                new Waypoint("wp_2", 192.0f, 0.0f, 80.0f),
                new Waypoint("wp_3", 160.0f, 0.0f, 160.0f),
                new Waypoint("wp_4", 256.0f, 0.0f, 160.0f),
                new Waypoint("wp_5", 192.0f, 0.0f, 240.0f),
                new Waypoint("wp_6", 160.0f, 0.0f, 296.0f),
                new Waypoint("wp_7", 80.0f, 0.0f, 296.0f)
            ),
            // ---- TDM markers: the empty singleton
            MapMarkers.TeamDeathmatch.INSTANCE,
            // ---- asset paths
            new MapAssets(
                "engine/src/main/resources/maps/cornerstone/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Overpass (Urban Warzone × Hardpoint) ----------------------------

    /**
     * The fifth shipped map. Urban Warzone, Hardpoint, 6v6 sizing. A
     * highway interchange at street level: two parallel elevated
     * overpasses running east-west with a service road between them,
     * and a control building anchoring the south. The full design
     * spec — the ASCII map, the callouts, the lane structure, the
     * three hardpoint zones, and the spawn rationale — is in
     * {@code docs/maps/urban-warzone/02-hp-overpass.md}.
     *
     * <p>Distinct from {@link #cornerstone} in feel: this is not a
     * three-lane map in the COD sense. The two overpasses are the
     * high ground (long sightlines, exposed from below), the service
     * road is the contested low ground, and the control building is
     * the chokepoint that decides the third rotation. Three
     * hardpoint zones rotate: Overpass S, Overpass N, then the
     * control building.</p>
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
            // ---- playable area: 320 x 320, 128 high (the overpass
            // decks sit at y=64 with the control building at y=80)
            new MapDimensions(320.0f, 320.0f, 128.0f),
            // ---- three lanes A/B/C, even though the map is not a
            // three-lane COD layout — the LaneAxis is EAST_WEST
            // because the overpasses run that way.
            List.of(
                new Lane("lane_a", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_a1", "Overpass N West", 16.0f, 40.0f),
                    new Chokepoint("cp_a2", "Overpass N Centre", 160.0f, 40.0f),
                    new Chokepoint("cp_a3", "Overpass N East", 304.0f, 40.0f)
                )),
                new Lane("lane_b", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_b1", "Service Road West", 16.0f, 160.0f),
                    new Chokepoint("cp_b2", "Service Road Centre", 160.0f, 160.0f),
                    new Chokepoint("cp_b3", "Service Road East", 304.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_c1", "Overpass S West", 16.0f, 240.0f),
                    new Chokepoint("cp_c2", "Overpass S Centre", 160.0f, 240.0f),
                    new Chokepoint("cp_c3", "Overpass S East", 304.0f, 240.0f)
                ))
            ),
            // ---- six spawn points: three per team on the west and
            // east edges, facings aimed at the ramps.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 64.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 96.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 128.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 192.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 224.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 256.0f,
                    toRadians(280.0f))
            ),
            // ---- six bot waypoints: a closed loop visiting both
            // overpasses, the service road, and the ramps in turn.
            List.of(
                new Waypoint("wp_0", 160.0f, 64.0f, 240.0f),
                new Waypoint("wp_1", 192.0f, 64.0f, 220.0f),
                new Waypoint("wp_2", 304.0f, 0.0f, 160.0f),
                new Waypoint("wp_3", 160.0f, 64.0f, 40.0f),
                new Waypoint("wp_4", 16.0f, 0.0f, 160.0f),
                new Waypoint("wp_5", 128.0f, 0.0f, 220.0f)
            ),
            // ---- Hardpoint markers: three zones, 1800-tic (30s)
            // rotation, 1 point per tic. Activation order B, A, C.
            new MapMarkers.Hardpoint(List.of(
                new MapMarkers.HardpointZone("hp_a", "Overpass N", 160.0f, 40.0f, 48.0f),
                new MapMarkers.HardpointZone("hp_b", "Overpass S", 160.0f, 240.0f, 48.0f),
                new MapMarkers.HardpointZone("hp_c", "Control Building", 160.0f, 296.0f,
                    48.0f)
            ), 1800, 1),
            // ---- asset paths
            new MapAssets(
                "engine/src/main/resources/maps/overpass/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Tripoint (Urban Warzone × Domination) ----------------------------

    /**
     * The sixth shipped map. Urban Warzone, Domination, 6v6 sizing. A
     * three-way intersection at street level: a roundabout in the
     * centre and three approach streets (north, south-east,
     * south-west) leading to the three flags. The full design spec is
     * in {@code docs/maps/urban-warzone/03-dom-tripoint.md}.
     *
     * <p>Distinct from {@link #cornerstone} in feel: the play is
     * "centre → flag", not "flag → flag". The roundabout is the
     * contested ground; the three flags are the rewards. A team that
     * captures two flags at once earns double the score; capturing
     * all three is the lockout.</p>
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
            // ---- playable area: 320 x 320, 96 high (the flags sit
            // on a 4-unit kerb with 16-unit stands; the perimeter
            // walls are 32-tall)
            new MapDimensions(320.0f, 320.0f, 96.0f),
            // ---- three approach streets, encoded as lanes A/B/C.
            // The axis is whatever the player reads; the chokepoints
            // sit at the centre and at each flag.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Roundabout", 160.0f, 160.0f),
                    new Chokepoint("cp_a2", "Approach N Centre", 160.0f, 100.0f),
                    new Chokepoint("cp_a3", "FLAG A", 160.0f, 48.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Roundabout East", 200.0f, 160.0f),
                    new Chokepoint("cp_b2", "Approach SE Centre", 220.0f, 200.0f),
                    new Chokepoint("cp_b3", "FLAG C SE", 240.0f, 240.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Roundabout West", 120.0f, 160.0f),
                    new Chokepoint("cp_c2", "Approach SW Centre", 100.0f, 200.0f),
                    new Chokepoint("cp_c3", "FLAG C SW", 80.0f, 240.0f)
                ))
            ),
            // ---- six spawn points: three per team on the west and
            // east edges, facings aimed at the south-east and
            // south-west approach streets respectively.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 96.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 128.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 160.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 160.0f,
                    toRadians(280.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 192.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 224.0f,
                    toRadians(260.0f))
            ),
            // ---- six bot waypoints: a closed loop visiting the
            // three flags and the roundabout in order.
            List.of(
                new Waypoint("wp_0", 160.0f, 0.0f, 48.0f),
                new Waypoint("wp_1", 160.0f, 0.0f, 100.0f),
                new Waypoint("wp_2", 160.0f, 0.0f, 160.0f),
                new Waypoint("wp_3", 240.0f, 0.0f, 200.0f),
                new Waypoint("wp_4", 160.0f, 0.0f, 240.0f),
                new Waypoint("wp_5", 80.0f, 0.0f, 200.0f)
            ),
            // ---- Domination markers: three flags, A, B, C. B is
            // the larger radius — the roundabout is the contested
            // ground and the capture zone is wider than the
            // per-flag stands.
            new MapMarkers.Domination(List.of(
                new MapMarkers.Flag("flag_a", "FLAG A", 160.0f, 48.0f, 32.0f),
                new MapMarkers.Flag("flag_b", "FLAG B", 160.0f, 160.0f, 48.0f),
                new MapMarkers.Flag("flag_c", "FLAG C", 80.0f, 240.0f, 32.0f)
            )),
            // ---- asset paths
            new MapAssets(
                "engine/src/main/resources/maps/tripoint/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Extraction (Urban Warzone × CTF) --------------------------------

    /**
     * The seventh shipped map. Urban Warzone, CTF, 6v6 sizing. A
     * mid-sized urban block split by a long boulevard. Each team's
     * base sits at one end of the boulevard, with the flag in a small
     * structure inside the base. The full design spec is in
     * {@code docs/maps/urban-warzone/04-ctf-extraction.md}.
     *
     * <p>Distinct from {@link #cornerstone} in feel: the play is
     * "carry the flag down the boulevard" with the flanking lanes as
     * the defender's cover. The carrier is visible from the moment
     * they leave their own base until they reach the enemy capture
     * point — a long, open sightline, with the cover walls in lanes
     * A and C the only off-axis cover a defender can use.</p>
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
            // ---- playable area: 320 x 320, 96 high (the bases are
            // 4-tall platforms with a 24-tall flagpole; the cover
            // walls are 48-tall)
            new MapDimensions(320.0f, 320.0f, 96.0f),
            // ---- three lanes A/B/C, mirroring Cornerstone but with
            // the role of each lane shifted: B is now the boulevard
            // (the long sightline), A and C are the flanking
            // cover-wall lanes.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Red Base", 32.0f, 32.0f),
                    new Chokepoint("cp_a2", "Cover Wall NW", 64.0f, 64.0f),
                    new Chokepoint("cp_a3", "Cover Wall NE", 256.0f, 64.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Boulevard West", 32.0f, 160.0f),
                    new Chokepoint("cp_b2", "Boulevard Centre", 160.0f, 160.0f),
                    new Chokepoint("cp_b3", "Boulevard East", 288.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Cover Wall SW", 64.0f, 256.0f),
                    new Chokepoint("cp_c2", "Cover Wall SE", 256.0f, 256.0f),
                    new Chokepoint("cp_c3", "Blue Base", 288.0f, 288.0f)
                ))
            ),
            // ---- six spawn points: three per team inside the
            // team's own base, facings aimed at the boulevard.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 32.0f,
                    toRadians(45.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 64.0f,
                    toRadians(60.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 96.0f,
                    toRadians(80.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 224.0f,
                    toRadians(225.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 256.0f,
                    toRadians(240.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 288.0f,
                    toRadians(260.0f))
            ),
            // ---- six bot waypoints: a closed loop along the
            // boulevard and the south flank.
            List.of(
                new Waypoint("wp_0", 16.0f, 0.0f, 32.0f),
                new Waypoint("wp_1", 96.0f, 0.0f, 96.0f),
                new Waypoint("wp_2", 160.0f, 0.0f, 160.0f),
                new Waypoint("wp_3", 224.0f, 0.0f, 224.0f),
                new Waypoint("wp_4", 304.0f, 0.0f, 288.0f),
                new Waypoint("wp_5", 32.0f, 0.0f, 256.0f)
            ),
            // ---- CTF markers: red's base and blue's base. The
            // flag and the capture point are at the same spot in
            // each base (the spec's "red's flag is also red's
            // capture point" rule).
            new MapMarkers.CaptureTheFlag(
                new MapMarkers.Base(Team.RED, 32.0f, 32.0f, 32.0f, 32.0f, 32.0f),
                new MapMarkers.Base(Team.BLUE, 288.0f, 288.0f, 288.0f, 288.0f, 32.0f)
            ),
            // ---- asset paths
            new MapAssets(
                "engine/src/main/resources/maps/extraction/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Refinery (Industrial Complex × TDM) -----------------------------

    /**
     * The second shipped map. Industrial Complex, TDM, 6v6 sizing,
     * three-lane COD layout with multi-level geometry: floor, mid-level
     * catwalks, and tall tank tops. The full design spec is in
     * {@code docs/maps/industrial-complex/01-refinery.md}.
     *
     * <p>Distinct from {@link #cornerstone} in feel: a refinery, not a
     * city block. The cover is tanks and catwalks, not buildings and
     * alleyways. Three tall distillation columns anchor the north end;
     * a large process hall with internal pipework runs across the
     * middle; three wide boiler structures anchor the south.</p>
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
            // ---- playable area: 320 x 320, 128 high (one MAX_OPEN_HEIGHT floor)
            new MapDimensions(320.0f, 320.0f, 128.0f),
            // ---- three lanes A/B/C, each with chokepoints in travel order
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Distillation Tower", -100.0f, 40.0f),
                    new Chokepoint("cp_a2", "Tank Row", 0.0f, 40.0f),
                    new Chokepoint("cp_a3", "Tank Row East", 100.0f, 40.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Process Hall West", -100.0f, 160.0f),
                    new Chokepoint("cp_b2", "Control Room", 0.0f, 160.0f),
                    new Chokepoint("cp_b3", "Process Hall East", 100.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Boiler West", -100.0f, 270.0f),
                    new Chokepoint("cp_c2", "Boiler Centre", 0.0f, 270.0f),
                    new Chokepoint("cp_c3", "Boiler East", 100.0f, 270.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west and east
            // edges, facings aimed at the process hall's cut-throughs.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 60.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 160.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 260.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 60.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 160.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 260.0f,
                    toRadians(280.0f))
            ),
            // ---- bot waypoints: a closed loop visiting the tank row,
            // the process hall, and the boiler row in order.
            List.of(
                new Waypoint("wp_0", -100.0f, 0.0f, 40.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, 40.0f),
                new Waypoint("wp_2", 100.0f, 0.0f, 40.0f),
                new Waypoint("wp_3", 100.0f, 0.0f, 160.0f),
                new Waypoint("wp_4", 0.0f, 0.0f, 160.0f),
                new Waypoint("wp_5", -100.0f, 0.0f, 160.0f),
                new Waypoint("wp_6", -100.0f, 0.0f, 270.0f),
                new Waypoint("wp_7", 0.0f, 0.0f, 270.0f),
                new Waypoint("wp_8", 100.0f, 0.0f, 270.0f)
            ),
            // ---- TDM markers: the empty singleton
            MapMarkers.TeamDeathmatch.INSTANCE,
            // ---- asset paths
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
            // ---- playable area: 320 x 320, 96 high (desert buildings are lower)
            new MapDimensions(320.0f, 320.0f, 96.0f),
            // ---- three lanes A/B/C, each with chokepoints in travel order
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Shack Row West", -112.0f, 24.0f),
                    new Chokepoint("cp_a2", "Shack Row Centre", 0.0f, 24.0f),
                    new Chokepoint("cp_a3", "Shack Row East", 112.0f, 24.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Cafe Corner", -64.0f, 160.0f),
                    new Chokepoint("cp_b2", "Plaza Centre", 0.0f, 160.0f),
                    new Chokepoint("cp_b3", "Sheriff's Office", 64.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Wells Fargo", -112.0f, 296.0f),
                    new Chokepoint("cp_c2", "Warehouse Row Centre", 0.0f, 296.0f),
                    new Chokepoint("cp_c3", "Trading Post", 112.0f, 296.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west and east
            // edges, facings aimed at the plaza.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 80.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 160.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 240.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 80.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 160.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 240.0f,
                    toRadians(280.0f))
            ),
            // ---- bot waypoints: a closed loop visiting the three rows.
            List.of(
                new Waypoint("wp_0", -112.0f, 0.0f, 24.0f),
                new Waypoint("wp_1", 0.0f, 0.0f, 24.0f),
                new Waypoint("wp_2", 112.0f, 0.0f, 24.0f),
                new Waypoint("wp_3", 64.0f, 0.0f, 160.0f),
                new Waypoint("wp_4", 0.0f, 0.0f, 160.0f),
                new Waypoint("wp_5", -64.0f, 0.0f, 160.0f),
                new Waypoint("wp_6", -112.0f, 0.0f, 296.0f),
                new Waypoint("wp_7", 0.0f, 0.0f, 296.0f),
                new Waypoint("wp_8", 112.0f, 0.0f, 296.0f)
            ),
            // ---- TDM markers: the empty singleton
            MapMarkers.TeamDeathmatch.INSTANCE,
            // ---- asset paths
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
            // ---- playable area: 320 x 320, 128 high (the bridges are
            // 32-tall with a service building on top)
            new MapDimensions(320.0f, 320.0f, 128.0f),
            // ---- three lanes A/B/C, each with chokepoints in travel
            // order. The lanes follow the bridges: A is the North
            // Bridge, B is the ravine floor, C is the South Bridge.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Fuel Depot West", -128.0f, 40.0f),
                    new Chokepoint("cp_a2", "North Bridge Centre", 0.0f, 40.0f),
                    new Chokepoint("cp_a3", "Fuel Depot East", 128.0f, 40.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Ravine West", -128.0f, 144.0f),
                    new Chokepoint("cp_b2", "Snowdrift Mid", 0.0f, 144.0f),
                    new Chokepoint("cp_b3", "Ravine East", 128.0f, 144.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Service Building West", -64.0f, 264.0f),
                    new Chokepoint("cp_c2", "Service Building Centre", 0.0f, 264.0f),
                    new Chokepoint("cp_c3", "Service Building East", 64.0f, 264.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west and
            // east edges, facings aimed at the service building.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 200.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 240.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 280.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 200.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 240.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 280.0f,
                    toRadians(280.0f))
            ),
            // ---- bot waypoints: a closed loop visiting the North
            // Bridge, the ravine floor, and the South Bridge in order.
            List.of(
                new Waypoint("wp_0", -128.0f, 32.0f, 40.0f),
                new Waypoint("wp_1", 0.0f, 32.0f, 40.0f),
                new Waypoint("wp_2", 128.0f, 32.0f, 40.0f),
                new Waypoint("wp_3", 128.0f, 0.0f, 144.0f),
                new Waypoint("wp_4", 0.0f, 0.0f, 144.0f),
                new Waypoint("wp_5", -128.0f, 0.0f, 144.0f),
                new Waypoint("wp_6", -128.0f, 32.0f, 216.0f),
                new Waypoint("wp_7", 0.0f, 32.0f, 216.0f),
                new Waypoint("wp_8", 128.0f, 32.0f, 216.0f)
            ),
            // ---- TDM markers: the empty singleton
            MapMarkers.TeamDeathmatch.INSTANCE,
            // ---- asset paths
            new MapAssets(
                "engine/src/main/resources/maps/arctic-station/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Foundry (Industrial Complex × Hardpoint) -----------------------

    /**
     * The eighth shipped map. Industrial Complex, Hardpoint, 6v6
     * sizing. A heavy-machinery foundry: three large machine halls
     * (the cast-metal shop, the assembly floor, and the cooling room)
     * are the three Hardpoint zones, each a high-walled, low-ceiling
     * space. Two horizontal mid-level gantries (the casting-gantry at
     * y=64, z=80 and the foundry spine at z=160) plus a vertical
     * gantry at x=0 connect the three halls at mid-height. The full
     * design spec — the ASCII map, the callouts, the lane structure,
     * the three hardpoint zones, and the spawn rationale — is in
     * {@code docs/maps/industrial-complex/02-hp-foundry.md}.
     *
     * <p>Distinct from {@link #overpass} in feel: this is a three-zone
     * rotation across three enclosed halls (cast-metal → assembly →
     * cooling), where Overpass is two open-air overpasses and a
     * building. Both are 30-second Hardpoint rotations, but the
     * Foundry's rotation moves the contested ground south to north
     * over the course of a round; Overpass's rotation moves it east
     * to west.</p>
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
            // ---- playable area: 320 x 320, 128 high (the halls are
            // 64-tall and the gantries sit at y=64)
            new MapDimensions(320.0f, 320.0f, 128.0f),
            // ---- three lanes A/B/C encoded as the gantry routes:
            // A is the casting-gantry, B is the foundry spine (the
            // contested middle), C is the cooling-gantry. The axis is
            // east-west because the two horizontal gantries run that
            // way; the cooling-gantry is the vertical link.
            List.of(
                new Lane("lane_a", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_a1", "Casting Gantry West", 16.0f, 80.0f),
                    new Chokepoint("cp_a2", "Casting Gantry Centre", 160.0f, 80.0f),
                    new Chokepoint("cp_a3", "Casting Gantry East", 304.0f, 80.0f)
                )),
                new Lane("lane_b", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_b1", "Foundry Spine West", 16.0f, 160.0f),
                    new Chokepoint("cp_b2", "Foundry Spine Centre", 160.0f, 160.0f),
                    new Chokepoint("cp_b3", "Foundry Spine East", 304.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Cooling Room South", 0.0f, 40.0f),
                    new Chokepoint("cp_c2", "Cooling Gantry Mid", 0.0f, 160.0f),
                    new Chokepoint("cp_c3", "Cooling Gantry South", 0.0f, 270.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west and
            // east edges of the floor, facings aimed at the foundry
            // spine (the contested middle).
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 80.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 160.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 240.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 80.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 160.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 240.0f,
                    toRadians(280.0f))
            ),
            // ---- six bot waypoints: a closed loop covering the floor
            // and the foundry spine in turn. Bots stay on the floor
            // for the smoke test; the gantry rotation is a future
            // pass.
            List.of(
                new Waypoint("wp_0", 0.0f, 0.0f, 270.0f),
                new Waypoint("wp_1", -100.0f, 0.0f, 160.0f),
                new Waypoint("wp_2", -100.0f, 0.0f, 40.0f),
                new Waypoint("wp_3", 0.0f, 0.0f, 40.0f),
                new Waypoint("wp_4", 100.0f, 0.0f, 40.0f),
                new Waypoint("wp_5", 100.0f, 0.0f, 270.0f)
            ),
            // ---- Hardpoint markers: three zones, 1800-tic (30s)
            // rotation, 1 point per tic. Activation order A, B, C
            // (cast-metal → assembly → cooling, the round opens in
            // the south hall and ends in the north).
            new MapMarkers.Hardpoint(List.of(
                new MapMarkers.HardpointZone("hp_a", "Cast-Metal Shop", 160.0f, 270.0f,
                    48.0f),
                new MapMarkers.HardpointZone("hp_b", "Assembly Floor", 160.0f, 160.0f,
                    48.0f),
                new MapMarkers.HardpointZone("hp_c", "Cooling Room", 160.0f, 40.0f, 48.0f)
            ), 1800, 1),
            // ---- asset paths
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
            // ---- playable area: 320 x 320, 96 high (the mesa top
            // is at y=32; the cave is at y=0)
            new MapDimensions(320.0f, 320.0f, 96.0f),
            // ---- three lanes A/B/C. Lane A is the desert floor on
            // the south side (the cave approach), lane B is the mesa
            // top (the contested middle), lane C is the desert floor
            // on the north side (the switchback approach).
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Cave S West", -16.0f, 270.0f),
                    new Chokepoint("cp_a2", "Cave S Centre", 160.0f, 270.0f),
                    new Chokepoint("cp_a3", "Cave S East", 304.0f, 270.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Mesa Top S West", 16.0f, 160.0f),
                    new Chokepoint("cp_b2", "Mesa Top S Centre", 160.0f, 160.0f),
                    new Chokepoint("cp_b3", "Mesa Top S East", 304.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Mesa Top N West", 16.0f, 64.0f),
                    new Chokepoint("cp_c2", "Mesa Top N Centre", 160.0f, 64.0f),
                    new Chokepoint("cp_c3", "Mesa Top N East", 304.0f, 64.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the south-west
            // and north-east edges. The cave is the first rotation
            // (RED's side) and the mesa-top north is the third
            // rotation (BLUE's side); the spawn choice trades off
            // the first rotation against the third.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 256.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 280.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 304.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 16.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 40.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 64.0f,
                    toRadians(280.0f))
            ),
            // ---- six bot waypoints: a closed loop visiting the cave,
            // the south ramp, the mesa top, and the north switchback
            // in turn. Bots stay on the path; a future pass can have
            // them drop through the mesa rim gaps.
            List.of(
                new Waypoint("wp_0", 160.0f, 0.0f, 270.0f),
                new Waypoint("wp_1", 32.0f, 0.0f, 256.0f),
                new Waypoint("wp_2", 32.0f, 32.0f, 192.0f),
                new Waypoint("wp_3", 160.0f, 32.0f, 160.0f),
                new Waypoint("wp_4", 32.0f, 32.0f, 96.0f),
                new Waypoint("wp_5", 32.0f, 0.0f, 96.0f)
            ),
            // ---- Hardpoint markers: three zones, 1800-tic (30s)
            // rotation, 1 point per tic. Activation order A, B, C
            // (cave → mesa top S → mesa top N, the round starts in
            // the cave and ends on the mesa top).
            new MapMarkers.Hardpoint(List.of(
                new MapMarkers.HardpointZone("hp_a", "Cave S", 160.0f, 270.0f, 48.0f),
                new MapMarkers.HardpointZone("hp_b", "Mesa Top S", 160.0f, 160.0f, 48.0f),
                new MapMarkers.HardpointZone("hp_c", "Mesa Top N", 160.0f, 64.0f, 48.0f)
            ), 1800, 1),
            // ---- asset paths
            new MapAssets(
                "engine/src/main/resources/maps/mesa/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Subzero (Arctic Station × Hardpoint) ----------------------------

    /**
     * The tenth shipped map. Arctic Station, Hardpoint, 6v6 sizing. A
     * small radar-research outpost on a polar ice shelf. Three low
     * sheet-metal buildings (the Generator Shed, the Operations
     * Trailer, and the Fuel Depot) are the three Hardpoint zones,
     * each a small enclosed space with one wide doorway. The
     * buildings are connected by a system of snow-walled trenches at
     * floor level, so a player who has dropped into a trench can
     * rotate between buildings without being shot from above. The
     * full design spec is in {@code docs/maps/arctic-station/02-hp-arctic.md}.
     *
     * <p>Distinct from {@link #overpass} in feel: this is the
     * smallest of the four Hardpoint maps, three buildings at the
     * corners of a 96×96 triangle connected by trenches in a Y
     * pattern. The rotation moves the contested ground east to west
     * over the course of a round (Generator Shed → Operations
     * Trailer → Fuel Depot).</p>
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
            // ---- playable area: 320 x 320, 96 high (the buildings
            // are short — 32-tall sheet metal — and the trenches
            // are 8-tall snow walls)
            new MapDimensions(320.0f, 320.0f, 96.0f),
            // ---- three lanes A/B/C encoded as the three trench
            // routes plus the open ground. Lane A is the W trench
            // (Generator Shed → Operations Trailer), lane B is the
            // open ground at the centre of the triangle, lane C is
            // the E trench (Operations Trailer → Fuel Depot).
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Generator Shed Doorway", 64.0f, 64.0f),
                    new Chokepoint("cp_a2", "W Trench Mid", 16.0f, 128.0f),
                    new Chokepoint("cp_a3", "Operations Doorway", 160.0f, 160.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "W Snow Wall", 16.0f, 160.0f),
                    new Chokepoint("cp_b2", "Open Ground", 96.0f, 160.0f),
                    new Chokepoint("cp_b3", "E Snow Wall", 16.0f, 192.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Operations Doorway E", 160.0f, 160.0f),
                    new Chokepoint("cp_c2", "E Trench Mid", 16.0f, 192.0f),
                    new Chokepoint("cp_c3", "Fuel Depot Doorway", 64.0f, 256.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west and
            // east edges, facings aimed at the trench entrances. The
            // round opens with both teams contesting the W trench
            // because the Generator Shed is the first rotation.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 64.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 160.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 256.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 64.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 160.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 256.0f,
                    toRadians(280.0f))
            ),
            // ---- six bot waypoints: a closed loop visiting the
            // three buildings and the two trenches in turn. Bots
            // stay on the path; a future pass can have them
            // navigate the snow-walled trenches.
            List.of(
                new Waypoint("wp_0", 64.0f, 0.0f, 64.0f),
                new Waypoint("wp_1", 16.0f, 0.0f, 128.0f),
                new Waypoint("wp_2", 160.0f, 0.0f, 160.0f),
                new Waypoint("wp_3", 16.0f, 0.0f, 192.0f),
                new Waypoint("wp_4", 64.0f, 0.0f, 256.0f),
                new Waypoint("wp_5", 96.0f, 0.0f, 160.0f)
            ),
            // ---- Hardpoint markers: three zones, 1800-tic (30s)
            // rotation, 1 point per tic. Activation order A, B, C
            // (Generator Shed → Operations Trailer → Fuel Depot, the
            // round opens in the north-west building and ends in the
            // south-west building).
            new MapMarkers.Hardpoint(List.of(
                new MapMarkers.HardpointZone("hp_a", "Generator Shed", 64.0f, 64.0f, 32.0f),
                new MapMarkers.HardpointZone("hp_b", "Operations Trailer", 160.0f, 160.0f,
                    32.0f),
                new MapMarkers.HardpointZone("hp_c", "Fuel Depot", 64.0f, 256.0f, 32.0f)
            ), 1800, 1),
            // ---- asset paths
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
     * The eleventh shipped map. Industrial Complex, Domination, 6v6
     * sizing. A long east-west pipeline pumping station: three
     * parallel pipelines (z=64, z=160, z=256) running across the
     * full 320-unit width, with a control valve at the centre of
     * each pipeline. Three catwalks at y=64 run north-south alongside
     * each pipeline. Two east-west underpasses at x=-100 and x=+100
     * cut 64-unit-wide gaps through the pipelines and the catwalks,
     * so a player can cross the map without being shot by a defender
     * on the catwalk. The full design spec is in
     * {@code docs/maps/industrial-complex/03-dom-pipeline.md}.
     *
     * <p>Distinct from {@link #tripoint} in feel: this is a long
     * east-west corridor with the contested ground running through
     * the centre, not a three-way intersection. The three flags are
     * the three control valves; the round opens with both teams
     * pushing toward the centre, contesting FLAG_B (Pipeline Centre)
     * first, and the winning team rolls out to FLAG_A (Pipeline
     * South) and FLAG_C (Pipeline North).</p>
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
            // ---- playable area: 320 x 320, 96 high (the pipelines
            // are 16 tall with a 16-tall control valve on top; the
            // catwalks sit at y=64)
            new MapDimensions(320.0f, 320.0f, 96.0f),
            // ---- three lanes A/B/C encoded as the three parallel
            // pipelines. Lane A is the south pipeline, lane B is the
            // centre pipeline (the contested middle), lane C is the
            // north pipeline.
            List.of(
                new Lane("lane_a", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_a1", "Pipeline South West", -100.0f, 256.0f),
                    new Chokepoint("cp_a2", "Pipeline South Centre", 160.0f, 256.0f),
                    new Chokepoint("cp_a3", "Pipeline South East", 304.0f, 256.0f)
                )),
                new Lane("lane_b", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_b1", "Pipeline Centre West", -100.0f, 160.0f),
                    new Chokepoint("cp_b2", "Pipeline Centre", 160.0f, 160.0f),
                    new Chokepoint("cp_b3", "Pipeline Centre East", 304.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_c1", "Pipeline North West", -100.0f, 64.0f),
                    new Chokepoint("cp_c2", "Pipeline North Centre", 160.0f, 64.0f),
                    new Chokepoint("cp_c3", "Pipeline North East", 304.0f, 64.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west and
            // east edges, facings aimed at Pipeline Centre.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 96.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 160.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 224.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 96.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 160.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 224.0f,
                    toRadians(280.0f))
            ),
            // ---- six bot waypoints: a closed loop covering the
            // three flag positions and the inter-pipeline floor.
            List.of(
                new Waypoint("wp_0", 160.0f, 0.0f, 64.0f),
                new Waypoint("wp_1", 240.0f, 0.0f, 64.0f),
                new Waypoint("wp_2", 240.0f, 0.0f, 160.0f),
                new Waypoint("wp_3", 160.0f, 0.0f, 256.0f),
                new Waypoint("wp_4", 80.0f, 0.0f, 256.0f),
                new Waypoint("wp_5", 80.0f, 0.0f, 160.0f)
            ),
            // ---- Domination markers: three flags, A, B, C. All
            // start neutral; the round opens with both teams pushing
            // toward the centre, contesting FLAG_B (Pipeline Centre)
            // first.
            new MapMarkers.Domination(List.of(
                new MapMarkers.Flag("flag_a", "Pipeline South", 160.0f, 256.0f, 32.0f),
                new MapMarkers.Flag("flag_b", "Pipeline Centre", 160.0f, 160.0f, 32.0f),
                new MapMarkers.Flag("flag_c", "Pipeline North", 160.0f, 64.0f, 32.0f)
            )),
            // ---- asset paths
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
            // ---- playable area: 320 x 320, 96 high (the butte
            // tops sit at y=32; the wash channels and the riverbed
            // are at y=-8)
            new MapDimensions(320.0f, 320.0f, 96.0f),
            // ---- three lanes A/B/C encoded as the three butte
            // approaches. Lane A is the south approach to FLAG_A,
            // lane B is the central approach to FLAG_B (Butte
            // Centre, the contested middle), lane C is the north
            // approach to FLAG_C.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Butte S Approach West", 80.0f, 256.0f),
                    new Chokepoint("cp_a2", "Butte S Centre", 160.0f, 256.0f),
                    new Chokepoint("cp_a3", "Butte S Approach East", 240.0f, 256.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Butte Centre West", 80.0f, 160.0f),
                    new Chokepoint("cp_b2", "Riverbed Centre", 160.0f, 160.0f),
                    new Chokepoint("cp_b3", "Butte Centre East", 240.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Butte N Approach West", 80.0f, 64.0f),
                    new Chokepoint("cp_c2", "Butte N Centre", 160.0f, 64.0f),
                    new Chokepoint("cp_c3", "Butte N Approach East", 240.0f, 64.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west and
            // east edges, facings aimed at the central butte.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 96.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 160.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 224.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 96.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 160.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 224.0f,
                    toRadians(280.0f))
            ),
            // ---- six bot waypoints: a closed loop covering the
            // three butte tops and the canyon floor in turn.
            List.of(
                new Waypoint("wp_0", 160.0f, 32.0f, 64.0f),
                new Waypoint("wp_1", 160.0f, 0.0f, 112.0f),
                new Waypoint("wp_2", 160.0f, 32.0f, 160.0f),
                new Waypoint("wp_3", 160.0f, 0.0f, 208.0f),
                new Waypoint("wp_4", 160.0f, 32.0f, 256.0f),
                new Waypoint("wp_5", 160.0f, 0.0f, 160.0f)
            ),
            // ---- Domination markers: three flags, A, B, C. The
            // flags are centred on the butte tops (y=32). All start
            // neutral; the round opens with both teams pushing
            // toward the centre, contesting FLAG_B (Butte Centre)
            // first.
            new MapMarkers.Domination(List.of(
                new MapMarkers.Flag("flag_a", "Butte South", 160.0f, 256.0f, 32.0f),
                new MapMarkers.Flag("flag_b", "Butte Centre", 160.0f, 160.0f, 32.0f),
                new MapMarkers.Flag("flag_c", "Butte North", 160.0f, 64.0f, 32.0f)
            )),
            // ---- asset paths
            new MapAssets(
                "engine/src/main/resources/maps/sandbar/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Arctic-Dom / Frostline (Arctic Station × Domination) ------------

    /**
     * The thirteenth shipped map. Arctic Station, Domination, 6v6
     * sizing. A long east-west polar ice road with three flag
     * platforms spaced 80 units apart along the road (z=80, z=160,
     * z=240). Each platform is a 16x16 raised ice block with a radar
     * mast in the centre. The full design spec is in
     * {@code docs/maps/arctic-station/03-dom-arctic.md}.
     *
     * <p>Distinct from {@link #tripoint} in feel: the contested
     * ground is the ice road (the long east-west feature), not a
     * roundabout. The three flags are the three platforms; the
     * round opens with both teams pushing toward the centre,
     * contesting FLAG_B (Centre Platform) first, and the winning
     * team rolls out to FLAG_A (South Platform) and FLAG_C (North
     * Platform). The map id is {@code "arctic-dom"}; the display
     * name is "Frostline" (per the design spec).</p>
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
            // ---- playable area: 320 x 320, 96 high (the platforms
            // are 16 tall; the radar masts rise 32 units above the
            // platform tops; the snow walls are 16 tall)
            new MapDimensions(320.0f, 320.0f, 96.0f),
            // ---- three lanes A/B/C encoded as the three
            // platform approaches along the ice road. Lane A is
            // the south approach to FLAG_A, lane B is the central
            // approach to FLAG_B (the contested middle), lane C is
            // the north approach to FLAG_C.
            List.of(
                new Lane("lane_a", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_a1", "South Platform West", 16.0f, 240.0f),
                    new Chokepoint("cp_a2", "South Platform Centre", 160.0f, 240.0f),
                    new Chokepoint("cp_a3", "South Platform East", 304.0f, 240.0f)
                )),
                new Lane("lane_b", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_b1", "Centre Platform West", 16.0f, 160.0f),
                    new Chokepoint("cp_b2", "Centre Platform", 160.0f, 160.0f),
                    new Chokepoint("cp_b3", "Centre Platform East", 304.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.EAST_WEST, List.of(
                    new Chokepoint("cp_c1", "North Platform West", 16.0f, 80.0f),
                    new Chokepoint("cp_c2", "North Platform Centre", 160.0f, 80.0f),
                    new Chokepoint("cp_c3", "North Platform East", 304.0f, 80.0f)
                ))
            ),
            // ---- six spawn points: three per team, on the west
            // and east edges, facings aimed at the centre of the
            // road. RED's spawns are spread along the road so RED
            // can pivot between FLAG_C and FLAG_A.
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 96.0f,
                    toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 160.0f,
                    toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 224.0f,
                    toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 96.0f,
                    toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 160.0f,
                    toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 224.0f,
                    toRadians(280.0f))
            ),
            // ---- six bot waypoints: a closed loop covering the
            // three platforms and the road between them in turn.
            List.of(
                new Waypoint("wp_0", 160.0f, 16.0f, 80.0f),
                new Waypoint("wp_1", 96.0f, 0.0f, 120.0f),
                new Waypoint("wp_2", 160.0f, 16.0f, 160.0f),
                new Waypoint("wp_3", 224.0f, 0.0f, 200.0f),
                new Waypoint("wp_4", 160.0f, 16.0f, 240.0f),
                new Waypoint("wp_5", 224.0f, 0.0f, 160.0f)
            ),
            // ---- Domination markers: three flags, A, B, C. The
            // flags are centred on the platform tops (y=16). All
            // start neutral; the round opens with both teams
            // pushing toward the centre, contesting FLAG_B
            // (Centre Platform) first.
            new MapMarkers.Domination(List.of(
                new MapMarkers.Flag("flag_a", "South Platform", 160.0f, 240.0f, 32.0f),
                new MapMarkers.Flag("flag_b", "Centre Platform", 160.0f, 160.0f, 32.0f),
                new MapMarkers.Flag("flag_c", "North Platform", 160.0f, 80.0f, 32.0f)
            )),
            // ---- asset paths
            new MapAssets(
                "engine/src/main/resources/maps/arctic-dom/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Storage (Industrial Complex × CTF) -----------------------------

    /**
     * The tenth shipped map. Industrial Complex, CTF, 6v6 sizing. A
     * chemical storage facility: two warehouse buildings at opposite
     * ends of the map, each with the team's flag inside, and a maze of
     * eight storage tanks in the centre. The full design spec is in
     * {@code docs/maps/industrial-complex/04-ctf-storage.md}.
     *
     * <p>Distinct from {@link #extraction} in feel: the play is
     * "carry the flag through the tank maze" with the central row of
     * tanks as the contested centre. The carrier's run is ~280 units
     * long, broken into three segments: leaving the home warehouse,
     * crossing the maze, and entering the enemy warehouse.</p>
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
            // ---- playable area: 320 x 320, 128 high (the warehouses
            // are 32-tall, the central catwalk is at y=64)
            new MapDimensions(320.0f, 320.0f, 128.0f),
            // ---- three lanes A/B/C, mirroring Refinery but with the
            // warehouse anchors on opposite ends.
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Red Warehouse", 32.0f, 32.0f),
                    new Chokepoint("cp_a2", "Red Approach", 16.0f, 80.0f),
                    new Chokepoint("cp_a3", "Red Approach East", 16.0f, 120.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "Tank Row N West", -50.0f, 140.0f),
                    new Chokepoint("cp_b2", "Tank Row N East", 50.0f, 140.0f),
                    new Chokepoint("cp_b3", "Tank Row S West", -50.0f, 220.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Tank Row S East", 50.0f, 220.0f),
                    new Chokepoint("cp_c2", "Blue Approach", 304.0f, 240.0f),
                    new Chokepoint("cp_c3", "Blue Warehouse", 288.0f, 288.0f)
                ))
            ),
            // ---- six spawn points inside the team's own warehouse
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 32.0f, toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 64.0f, toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 96.0f, toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 224.0f, toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 256.0f, toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 288.0f, toRadians(280.0f))
            ),
            // ---- six bot waypoints in a closed loop
            List.of(
                new Waypoint("wp_0", 16.0f, 0.0f, 32.0f),
                new Waypoint("wp_1", 16.0f, 0.0f, 160.0f),
                new Waypoint("wp_2", 16.0f, 0.0f, 288.0f),
                new Waypoint("wp_3", 304.0f, 0.0f, 288.0f),
                new Waypoint("wp_4", 304.0f, 0.0f, 160.0f),
                new Waypoint("wp_5", 304.0f, 0.0f, 32.0f)
            ),
            // ---- CTF markers: red's base and blue's base
            new MapMarkers.CaptureTheFlag(
                new MapMarkers.Base(Team.RED, 32.0f, 32.0f, 32.0f, 32.0f, 32.0f),
                new MapMarkers.Base(Team.BLUE, 288.0f, 288.0f, 288.0f, 288.0f, 32.0f)
            ),
            // ---- asset paths
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
            // ---- playable area: 320 x 320, 128 high
            new MapDimensions(320.0f, 320.0f, 128.0f),
            // ---- three lanes A/B/C, with the courtyard in the middle
            // and the cliff walls in lanes A and C
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Cliff N West", 16.0f, 64.0f),
                    new Chokepoint("cp_a2", "Cliff N Centre", 160.0f, 64.0f),
                    new Chokepoint("cp_a3", "Cliff N East", 304.0f, 64.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "West Gate", 16.0f, 160.0f),
                    new Chokepoint("cp_b2", "Courtyard Fountain", 160.0f, 160.0f),
                    new Chokepoint("cp_b3", "East Gate", 304.0f, 160.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Cliff S West", 16.0f, 256.0f),
                    new Chokepoint("cp_c2", "Cliff S Centre", 160.0f, 256.0f),
                    new Chokepoint("cp_c3", "Cliff S East", 304.0f, 256.0f)
                ))
            ),
            // ---- six spawn points outside the fortress
            List.of(
                new SpawnPoint("red_alpha", Team.RED, 16.0f, 0.0f, 32.0f, toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, 16.0f, 0.0f, 64.0f, toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, 16.0f, 0.0f, 96.0f, toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 304.0f, 0.0f, 224.0f, toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 304.0f, 0.0f, 256.0f, toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 304.0f, 0.0f, 288.0f, toRadians(280.0f))
            ),
            // ---- six bot waypoints in a closed loop
            List.of(
                new Waypoint("wp_0", 16.0f, 0.0f, 32.0f),
                new Waypoint("wp_1", 16.0f, 0.0f, 160.0f),
                new Waypoint("wp_2", 160.0f, 0.0f, 160.0f),
                new Waypoint("wp_3", 304.0f, 0.0f, 160.0f),
                new Waypoint("wp_4", 304.0f, 0.0f, 288.0f),
                new Waypoint("wp_5", 32.0f, 0.0f, 256.0f)
            ),
            // ---- CTF markers: red's base (west) and blue's base (east)
            new MapMarkers.CaptureTheFlag(
                new MapMarkers.Base(Team.RED, 32.0f, 32.0f, 32.0f, 32.0f, 32.0f),
                new MapMarkers.Base(Team.BLUE, 288.0f, 288.0f, 288.0f, 288.0f, 32.0f)
            ),
            // ---- asset paths
            new MapAssets(
                "engine/src/main/resources/maps/stronghold/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }

    // ----- Coldfront (Arctic Station × CTF) --------------------------------

    /**
     * The twelfth and final shipped map. Arctic Station, CTF, 6v6
     * sizing. A small polar-research base split across two sides of a
     * frozen river. The full design spec is in
     * {@code docs/maps/arctic-station/04-ctf-arctic.md}.
     *
     * <p>Distinct from {@link #extraction} in feel: the play is
     * "carry the flag across the frozen river" with the watchtowers as
     * the natural chokepoints. The river is 96 units wide and unbroken;
     * the carrier's run is ~256 units long, visible from both watchtowers
     * the entire way.</p>
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
            // ---- playable area: 320 x 320, 128 high
            new MapDimensions(320.0f, 320.0f, 128.0f),
            // ---- three lanes A/B/C, with the frozen river in the middle
            List.of(
                new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_a1", "Red Service Shed", -64.0f, 120.0f),
                    new Chokepoint("cp_a2", "Red Main Hut", -96.0f, 160.0f),
                    new Chokepoint("cp_a3", "Red Watchtower", -32.0f, 160.0f)
                )),
                new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_b1", "River N West", -32.0f, 100.0f),
                    new Chokepoint("cp_b2", "River Centre", 0.0f, 160.0f),
                    new Chokepoint("cp_b3", "River S East", 32.0f, 220.0f)
                )),
                new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                    new Chokepoint("cp_c1", "Blue Watchtower", 32.0f, 160.0f),
                    new Chokepoint("cp_c2", "Blue Main Hut", 96.0f, 160.0f),
                    new Chokepoint("cp_c3", "Blue Service Shed", 64.0f, 120.0f)
                ))
            ),
            // ---- six spawn points outside the compounds on the wall side
            List.of(
                new SpawnPoint("red_alpha", Team.RED, -144.0f, 0.0f, 128.0f, toRadians(90.0f)),
                new SpawnPoint("red_bravo", Team.RED, -144.0f, 0.0f, 160.0f, toRadians(80.0f)),
                new SpawnPoint("red_charlie", Team.RED, -144.0f, 0.0f, 192.0f, toRadians(100.0f)),
                new SpawnPoint("blue_alpha", Team.BLUE, 144.0f, 0.0f, 128.0f, toRadians(270.0f)),
                new SpawnPoint("blue_bravo", Team.BLUE, 144.0f, 0.0f, 160.0f, toRadians(260.0f)),
                new SpawnPoint("blue_charlie", Team.BLUE, 144.0f, 0.0f, 192.0f, toRadians(280.0f))
            ),
            // ---- six bot waypoints in a closed loop covering both
            // compounds and the river edges
            List.of(
                new Waypoint("wp_0", -128.0f, 0.0f, 160.0f),
                new Waypoint("wp_1", -32.0f, 0.0f, 160.0f),
                new Waypoint("wp_2", -32.0f, 0.0f, 100.0f),
                new Waypoint("wp_3", 32.0f, 0.0f, 100.0f),
                new Waypoint("wp_4", 32.0f, 0.0f, 160.0f),
                new Waypoint("wp_5", 128.0f, 0.0f, 160.0f)
            ),
            // ---- CTF markers: red's base (west) and blue's base (east)
            new MapMarkers.CaptureTheFlag(
                new MapMarkers.Base(Team.RED, -128.0f, 160.0f, -128.0f, 160.0f, 32.0f),
                new MapMarkers.Base(Team.BLUE, 128.0f, 160.0f, 128.0f, 160.0f, 32.0f)
            ),
            // ---- asset paths
            new MapAssets(
                "engine/src/main/resources/maps/coldfront/level.ofm",
                "assets/models/weapon/blaster-b.ofm",
                null
            )
        );
    }
}
