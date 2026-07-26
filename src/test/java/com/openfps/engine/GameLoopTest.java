/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine;

import com.openfps.engine.core.GameLoop;
import com.openfps.engine.hal.adapter.nulladapter.NullInputPort;
import com.openfps.engine.hal.adapter.nulladapter.NullNetworkPort;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic smoke tests for the core game loop.
 */
class GameLoopTest
{
    private NullTimePort timePort;
    private NullInputPort inputPort;
    private NullNetworkPort networkPort;
    private GameLoop loop;
    private Thread loopThread;

    @BeforeEach
    void setUp()
    {
        timePort = new NullTimePort();
        inputPort = new NullInputPort();
        networkPort = new NullNetworkPort();

        timePort.init();
        inputPort.init();
        networkPort.init();

        loop = new GameLoop(timePort, inputPort, networkPort);
    }

    @AfterEach
    void tearDown()
    {
        if (loop.isRunning())
        {
            loop.shutdown();
        }
        if (loopThread != null)
        {
            loopThread.interrupt();
            try
            {
                loopThread.join(2000);
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
        timePort.shutdown();
        inputPort.shutdown();
        networkPort.shutdown();
    }

    @Test
    void shouldInitializeInStoppedState()
    {
        assertFalse(loop.isRunning());
        assertEquals(0, loop.currentTic());
    }

    @Test
    void shouldStopAfterShutdownRequest() throws InterruptedException
    {
        loopThread = new Thread(loop, "GameLoop-test");
        loopThread.start();

        // Give the loop a moment to start
        Thread.sleep(200);

        // Trigger shutdown from another thread
        loop.shutdown();

        // Wait up to 5 seconds for the loop to stop
        for (int i = 0; i < 50; i++)
        {
            if (!loop.isRunning())
            {
                break;
            }
            Thread.sleep(100);
        }

        assertFalse(loop.isRunning(), "Loop should have stopped after shutdown()");
        assertTrue(loop.currentTic() > 0, "Loop should have advanced at least one tic");
    }
}
