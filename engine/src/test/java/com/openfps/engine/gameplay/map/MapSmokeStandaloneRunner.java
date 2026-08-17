/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.BotPattern;
import com.openfps.engine.gameplay.BotRng;
import com.openfps.engine.gameplay.BotSkill;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.MatchState;

/**
 * A standalone runner for the map smoke test that does not depend on
 * JUnit and can be executed as a regular main() method. The companion
 * {@code MapSmokeMapFixTest} has the same assertions but is unable to
 * run when other test files are in a half-converted state and break
 * {@code :engine:compileTestJava}. This runner sidesteps the JUnit
 * dependency so the smoke test for the four Pass 2 maps can still be
 * verified in isolation.
 *
 * <p>Exit code 0 on success (every map: 7 bots alive at tic 120,
 * player still has positive health, match still in progress). Exit
 * code 1 on the first failure, with a printed summary of every
 * (map, check) pair so a CI log shows the whole picture at a glance.</p>
 */
public final class MapSmokeStandaloneRunner
{
    /** How many tics the runner runs each map for. */
    private static final int MAX_TICS = 120;

    private MapSmokeStandaloneRunner()
    {
        // utility
    }

    /**
     * Runs the smoke test for the four Pass 2 maps and reports the result.
     *
     * @param args unused
     */
    public static void main(final String[] args)
    {
        MapLibrary.registerDefaults();

        int failures = 0;

        for (final String mapId : new String[] {"cornerstone", "overpass", "tripoint", "extraction"})
        {
            final Result r = runOne(mapId);

            System.out.println(r.summaryLine());

            if (!r.passed)
            {
                failures = failures + 1;
            }
        }

        if (failures > 0)
        {
            System.err.println("FAIL: " + failures + " maps failed the smoke test");

            System.exit(1);
        }

        System.out.println("PASS: all 4 maps clean (8/8 bots alive, player > 0 hp, match in progress)");
    }

    /**
     * Builds and ticks a match for one map id, then captures the
     * diagnostic numbers the test wants to assert on.
     *
     * @param mapId the id of the map to run; must be registered
     * @return the result of one map's smoke run, with a summary line
     */
    private static Result runOne(final String mapId)
    {
        final MapSpec spec = MapLibrary.get(mapId);

        final int waypointCount = spec.botWaypoints().size();

        final int botCount = Math.min(waypointCount, Match.DEFAULT_BOT_COUNT);

        final Bot[] roster = new Bot[botCount];

        for (int index = 0; index < botCount; index++)
        {
            final Waypoint wp = spec.botWaypoints().get(index);

            roster[index] = new Bot(Match.FIRST_BOT_ENTITY_ID + index, wp.x(), wp.y(), wp.z(),
                BotPattern.SENTRY, 0.0f, 60, index);
        }

        final Match match = new Match(roster, new BotRng(), BotSkill.DUMB,
            Match.UNLIMITED_DEATHS, spec);

        for (int tic = 0; tic < MAX_TICS; tic++)
        {
            match.tick(tic, 0.0f, 0.0f, 0.0f);
        }

        final int alive = match.livingBots();

        final int health = match.playerHealth();

        final MatchState state = match.state();

        final boolean passed = (alive == botCount)
            && (health > 0)
            && (state == MatchState.IN_PROGRESS);

        return new Result(mapId, botCount, alive, health, state, passed);
    }

    /**
     * One map's smoke-test result. {@code passed} is the conjunction of
     * every check; the individual fields are exposed for the summary line.
     */
    private static final class Result
    {
        private final String mapId;

        private final int botCount;

        private final int alive;

        private final int health;

        private final MatchState state;

        private final boolean passed;

        Result(final String mapId, final int botCount, final int alive, final int health,
            final MatchState state, final boolean passed)
        {
            this.mapId = mapId;
            this.botCount = botCount;
            this.alive = alive;
            this.health = health;
            this.state = state;
            this.passed = passed;
        }

        String summaryLine()
        {
            final String status;

            if (passed)
            {
                status = "PASS ";
            }
            else
            {
                status = "FAIL ";
            }

            return status
                + mapId + ": bots alive = " + alive + "/" + botCount
                + ", player health = " + health
                + ", state = " + state;
        }
    }
}
