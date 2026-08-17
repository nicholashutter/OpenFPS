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
import com.openfps.engine.gameplay.port.I_GameplayPort;
import com.openfps.engine.hal.port.I_InputPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A headless {@link I_GameplayPort} that loads a {@link MapSpec} from
 * {@link MapLoader}, constructs a {@link Match} for it, and ticks the match
 * until the round ends or {@link #MAX_TICS} tics have run.
 *
 * <p>This is the smoke-test path the headless engine uses when
 * {@code --map=<id>} is on the command line. It exists so a fresh clone with
 * no models and no audio can still verify that the map library, the
 * {@code Match} mode dispatch and the per-tic plumbing all wire up correctly
 * without a window, an input device, or a network peer. The actual
 * geometry is irrelevant — only the simulation state is exercised.</p>
 *
 * <p>The port is final and non-instantiable from outside the package; the
 * factory {@link #create(I_InputPort, String)} is the one entry point.</p>
 */
public final class MapSmokeGameplayPort implements I_GameplayPort
{
    /**
     * How many tics the smoke test runs before giving up.
     *
     * <p>Long enough for a Hardpoint zone rotation to fire at least once
     * (Hardpoint's default rotation is 1800 tics — too long — so this is
     * really the upper bound for the no-state-change assertion) and short
     * enough that the headless test does not feel slow. 600 tics at 60 Hz
     * is ten seconds.</p>
     */
    public static final int MAX_TICS = 600;

    private static final Logger LOG = LoggerFactory.getLogger(MapSmokeGameplayPort.class);

    private final MapSpec spec;
    private final Match match;

    private MapSmokeGameplayPort(final MapSpec spec, final Match match)
    {
        this.spec = spec;

        this.match = match;
    }

    /**
     * Builds a smoke-test port for the given map id, or a no-op port if the
     * id is unknown.
     *
     * <p>An unknown id is logged at WARN, not thrown, because the engine's
     * startup path wants to fail gracefully — a typo in {@code --map=} is
     * a user error, not a build error, and the engine boots either way.</p>
     *
     * @param inputPort the input port (unused; the smoke test reads no
     *                  input)
     * @param mapId     the id of the map to load
     * @return a port that exercises the map's match layer
     */
    public static I_GameplayPort create(final I_InputPort inputPort, final String mapId)
    {
        final MapSpec spec = MapLoader.load(mapId);

        if (spec == null)
        {
            LOG.warn("MapSmokeGameplayPort: unknown map id '{}', using cornerstone fallback",
                mapId);

            return create(inputPort, "cornerstone");
        }

        // A small, deterministic bot roster: one sentry at the centre of
        // each lane, walking closed-form routes. Skill is DUMB so the
        // smoke test is not flaky — we are not measuring hit rates here,
        // we are measuring that the engine can run a spec'd match.
        final Bot[] roster = botsFromSpec(spec);

        final Match match = new Match(roster, new BotRng(), BotSkill.DUMB,
            Match.UNLIMITED_DEATHS, spec);

        return new MapSmokeGameplayPort(spec, match);
    }

    /**
     * Builds a smoke-test port for a given spec, bypassing the lookup.
     *
     * @param spec the spec to run; must not be null
     * @return a port that exercises the spec's match layer
     */
    public static I_GameplayPort forSpec(final MapSpec spec)
    {
        final Bot[] roster = botsFromSpec(spec);

        final Match match = new Match(roster, new BotRng(), BotSkill.DUMB,
            Match.UNLIMITED_DEATHS, spec);

        return new MapSmokeGameplayPort(spec, match);
    }

    @Override
    public void init()
    {
        LOG.info("MapSmokeGameplayPort: loaded map '{}' ({} x {}, mode={})", spec.id(),
            spec.dimensions().width(), spec.dimensions().depth(), spec.mode());
    }

    @Override
    public void shutdown()
    {
        // Nothing to release — the spec is immutable and the match is
        // owned by this port for its lifetime.
    }

    @Override
    public boolean loadMap(final String mapName)
    {
        // The smoke test loads one map at construction; subsequent
        // loadMap calls are a no-op.
        return true;
    }

    @Override
    public void tick(final int ticIndex)
    {
        // Cap the run at MAX_TICS so a misconfigured test does not run
        // forever. The engine itself caps at GameConfig.maxTics; this is
        // belt and braces.
        if (ticIndex >= MAX_TICS)
        {
            return;
        }

        // Player stands at the world origin; the bots patrol the waypoints
        // declared in the spec. The actual positions are deterministic —
        // a function of ticIndex — and the smoke test does not assert on
        // them.
        match.tick(ticIndex, 0.0f, 0.0f, 0.0f);

        if (ticIndex == 0 || ticIndex == 119 || ticIndex == MAX_TICS - 1)
        {
            LOG.info("Tic {}: match state = {}, player health = {}, bots alive = {}/{}",
                ticIndex, match.state(), match.playerHealth(), match.livingBots(),
                match.botCount());
        }
    }

    /**
     * Returns a small bot roster derived from the spec's waypoints.
     *
     * <p>One bot per waypoint, up to {@link Match#DEFAULT_BOT_COUNT}, all
     * walking a sentry pattern at the waypoint's position. The smoke test
     * is not measuring the bots' behaviour — it is measuring that
     * {@code Match} can be constructed against the spec and ticked without
     * throwing, and a roster of waypoint-bound sentries is the minimum
     * the spec needs to support that.</p>
     *
     * @param spec the spec to derive a roster from
     * @return a non-null, non-empty bot array
     */
    private static Bot[] botsFromSpec(final MapSpec spec)
    {
        final int count = Math.min(spec.botWaypoints().size(), Match.DEFAULT_BOT_COUNT);

        if (count == 0)
        {
            // No waypoints: a single sentry at the world origin. An
            // empty match is WON by construction and tests nothing
            // mode-dispatch-related, so we always want at least one
            // body.
            return new Bot[]
            {
                new Bot(Match.FIRST_BOT_ENTITY_ID, 0.0f, 0.0f, 0.0f,
                    BotPattern.SENTRY, 0.0f, 60, 0)
            };
        }

        final Bot[] bots = new Bot[count];

        for (int index = 0; index < count; index++)
        {
            final Waypoint wp = spec.botWaypoints().get(index);

            bots[index] = new Bot(Match.FIRST_BOT_ENTITY_ID + index, wp.x(), wp.y(), wp.z(),
                BotPattern.SENTRY, 0.0f, 60, index);
        }

        return bots;
    }
}
