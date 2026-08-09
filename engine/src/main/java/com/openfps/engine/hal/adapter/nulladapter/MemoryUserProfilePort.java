/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.common.UserProfile;
import com.openfps.engine.hal.port.I_UserProfilePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory implementation of {@link I_UserProfilePort}.
 * Used for tests and the default headless adapter.
 *
 * Stores profiles in a {@link LinkedHashMap} so iteration order is
 * insertion order. Not thread-safe; tests must use it from a single
 * thread.
 */
public final class MemoryUserProfilePort implements I_UserProfilePort
{
    private static final Logger LOG = LoggerFactory.getLogger(MemoryUserProfilePort.class);

    private final Map<String, UserProfile> profiles = new LinkedHashMap<>();
    private volatile State state;

    public MemoryUserProfilePort()
    {
        this.state = State.UNINITIALIZED;
    }

    @Override
    public void init()
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("init() called from state SHUTDOWN");
        }

        if (state == State.READY)
        {
            throw new IllegalStateException("init() called from state READY — already initialized");
        }

        state = State.READY;

        LOG.info("MemoryUserProfilePort initialized (in-memory)");
    }

    @Override
    public void shutdown()
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("shutdown() called from state SHUTDOWN");
        }

        profiles.clear();

        state = State.SHUTDOWN;

        LOG.info("MemoryUserProfilePort shut down");
    }

    @Override
    public State state()
    {
        return state;
    }

    @Override
    public Optional<UserProfile> findById(final String id)
    {
        requireReady();

        return Optional.ofNullable(profiles.get(id));
    }

    @Override
    public List<UserProfile> findAll()
    {
        requireReady();

        return new ArrayList<>(profiles.values());
    }

    @Override
    public void save(final UserProfile profile)
    {
        requireReady();

        if (profile == null)
        {
            throw new IllegalArgumentException("profile must not be null");
        }

        profiles.put(profile.id(), profile);
    }

    @Override
    public void delete(final String id)
    {
        requireReady();

        if (id != null)
        {
            profiles.remove(id);
        }
    }

    @Override
    public int count()
    {
        requireReady();

        return profiles.size();
    }

    @Override
    public String generateNewId()
    {
        return UUID.randomUUID().toString();
    }

    private void requireReady()
    {
        if (state != State.READY)
        {
            throw new IllegalStateException("operation called from state " + state
                + " — only valid from READY");
        }
    }
}
