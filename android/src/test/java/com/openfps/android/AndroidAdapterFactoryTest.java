/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.openfps.engine.common.UserProfile;
import com.openfps.engine.hal.adapter.I_AdapterFactory;
import com.openfps.engine.hal.adapter.nulladapter.MemoryUserProfilePort;
import com.openfps.engine.hal.adapter.nulladapter.NullAdapterFactory;
import com.openfps.engine.hal.port.I_DatagramPort;
import com.openfps.engine.hal.port.I_FilePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_SystemInfoPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_UserProfilePort;
import com.openfps.engine.hal.port.I_WindowPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AndroidAdapterFactory}'s decoration of the null HAL
 * backend.
 *
 * The collaborators are injected through the factory's second constructor,
 * which exists for exactly this — the same seam {@code GdxAdapterFactoryTest}
 * uses on desktop. The profile port here is the engine's in-memory one rather
 * than the Room-backed one, so nothing opens a database, and the window is a
 * real {@link AndroidWindowPort} over a {@link FakeAndroidApplication}.
 *
 * <b>Not covered:</b> the one-argument constructor, which builds a
 * {@code RoomUserProfilePort} against a real {@code Context}. That needs the
 * framework, and substituting the profile port is the whole reason the second
 * constructor is there.
 */
class AndroidAdapterFactoryTest
{
    /** Builds a window port with no render thread behind it. */
    private static AndroidWindowPort newWindowPort()
    {
        return new AndroidWindowPort(new FakeAndroidApplication());
    }

    /** Builds a factory over the null backend and an in-memory profile store. */
    private static AndroidAdapterFactory newFactory()
    {
        return new AndroidAdapterFactory(new NullAdapterFactory(), newWindowPort(),
            new MemoryUserProfilePort());
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("a factory with nothing to delegate to is refused")
        void shouldRejectNullDelegate()
        {
            assertThatThrownBy(() ->
                new AndroidAdapterFactory(null, newWindowPort(), new MemoryUserProfilePort()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delegate");
        }

        @Test
        @DisplayName("a factory with no window is refused")
        void shouldRejectNullWindowPort()
        {
            assertThatThrownBy(() ->
                new AndroidAdapterFactory(new NullAdapterFactory(), null,
                    new MemoryUserProfilePort()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowPort");
        }

        @Test
        @DisplayName("a factory with no profile store is refused")
        void shouldRejectNullUserProfilePort()
        {
            assertThatThrownBy(() ->
                new AndroidAdapterFactory(new NullAdapterFactory(), newWindowPort(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userProfilePort");
        }
    }

    @Nested
    @DisplayName("port selection")
    class PortSelection
    {
        @Test
        @DisplayName("every port the Android track has no opinion about comes from the delegate")
        void shouldDelegateTheUnopinionatedPorts()
        {
            final I_AdapterFactory delegate = new NullAdapterFactory();
            final AndroidAdapterFactory factory = new AndroidAdapterFactory(
                delegate, newWindowPort(), new MemoryUserProfilePort());
            factory.init();
            try
            {
                assertThat(factory.getTimePort()).isSameAs(delegate.getTimePort());
                assertThat(factory.getInputPort()).isSameAs(delegate.getInputPort());
                assertThat(factory.getDatagramPort()).isSameAs(delegate.getDatagramPort());
                assertThat(factory.getFilePort()).isSameAs(delegate.getFilePort());
                assertThat(factory.getSystemInfoPort()).isSameAs(delegate.getSystemInfoPort());
            }
            finally
            {
                factory.shutdown();
            }
        }

        @Test
        @DisplayName("the window is the Android one, not the null backend's stand-in")
        void shouldSupplyTheAndroidWindow()
        {
            final I_AdapterFactory delegate = new NullAdapterFactory();
            final AndroidWindowPort window = newWindowPort();
            final AndroidAdapterFactory factory = new AndroidAdapterFactory(
                delegate, window, new MemoryUserProfilePort());
            factory.init();
            try
            {
                assertThat(factory.getWindowPort()).isSameAs(window);
                assertThat(factory.getWindowPort()).isNotSameAs(delegate.getWindowPort());
                assertThat(factory.getWindowPort().isRealWindow()).isTrue();
            }
            finally
            {
                factory.shutdown();
            }
        }

        @Test
        @DisplayName("the profile store overrides the backend's, so a profile survives the process")
        void shouldSupplyTheOverriddenProfilePort()
        {
            // On Android the underlying backend is NULL — SQLITE would fail
            // with NoClassDefFoundError because sqlite-jdbc is excluded from
            // this module. Persistence has to come from the override.
            final I_AdapterFactory delegate = new NullAdapterFactory();
            final I_UserProfilePort profile = new MemoryUserProfilePort();
            final AndroidAdapterFactory factory = new AndroidAdapterFactory(
                delegate, newWindowPort(), profile);
            factory.init();
            try
            {
                assertThat(factory.getUserProfilePort()).isSameAs(profile);
                assertThat(factory.getUserProfilePort()).isNotSameAs(delegate.getUserProfilePort());
            }
            finally
            {
                factory.shutdown();
            }
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle
    {
        @Test
        @DisplayName("init opens the profile store so the engine can read a profile at boot")
        void shouldOpenTheProfileStoreOnInit()
        {
            final I_UserProfilePort profile = new MemoryUserProfilePort();
            final AndroidAdapterFactory factory = new AndroidAdapterFactory(
                new NullAdapterFactory(), newWindowPort(), profile);
            assertThat(profile.state()).isEqualTo(I_UserProfilePort.State.UNINITIALIZED);

            factory.init();

            assertThat(profile.state()).isEqualTo(I_UserProfilePort.State.READY);
            factory.shutdown();
        }

        @Test
        @DisplayName("init leaves the window alone — the Activity owns that ordering")
        void shouldNotStartTheWindowOnInit()
        {
            // The window is initialized and created by AndroidLauncher inside
            // onCreate, and initialize() must happen there. If the factory
            // started it, the frame loop would already be claimed by the time
            // the launcher asked.
            final FakeAndroidApplication application = new FakeAndroidApplication();
            final AndroidWindowPort window = new AndroidWindowPort(application);
            final AndroidAdapterFactory factory = new AndroidAdapterFactory(
                new NullAdapterFactory(), window, new MemoryUserProfilePort());

            factory.init();

            assertThat(application.initializeCount()).isZero();
            assertThatCode(() -> window.runFrameLoop(new RecordingFrameCallback()))
                .doesNotThrowAnyException();
            factory.shutdown();
        }

        @Test
        @DisplayName("shutdown closes the profile store")
        void shouldCloseTheProfileStoreOnShutdown()
        {
            final I_UserProfilePort profile = new MemoryUserProfilePort();
            final AndroidAdapterFactory factory = new AndroidAdapterFactory(
                new NullAdapterFactory(), newWindowPort(), profile);
            factory.init();

            factory.shutdown();

            assertThat(profile.state()).isEqualTo(I_UserProfilePort.State.SHUTDOWN);
        }

        @Test
        @DisplayName("the profile is closed before the rest of the HAL, so the last save survives")
        void shouldCloseTheProfileBeforeTheDelegate()
        {
            // EngineSession.stop() saves through the profile port and only
            // then calls hal.shutdown(). Closing the database on the way past
            // anything else that might still be writing would lose that save.
            final List<String> log = new ArrayList<>();
            final AndroidAdapterFactory factory = new AndroidAdapterFactory(
                new LoggingAdapterFactory(new NullAdapterFactory(), log),
                newWindowPort(),
                new LoggingUserProfilePort(new MemoryUserProfilePort(), log));

            factory.init();
            log.clear();
            factory.shutdown();

            assertThat(log).containsExactly("profile:shutdown", "delegate:shutdown");
        }

        @Test
        @DisplayName("the window is not shut down twice — onDestroy already did it")
        void shouldLeaveTheWindowToTheActivity()
        {
            final AndroidWindowPort window = newWindowPort();
            final AndroidAdapterFactory factory = new AndroidAdapterFactory(
                new NullAdapterFactory(), window, new MemoryUserProfilePort());
            factory.init();
            window.requestClose();

            factory.shutdown();

            // shutdown() on the port clears the close flag; the factory must
            // not have called it, so the request the Activity is acting on is
            // still standing.
            assertThat(window.isCloseRequested()).isTrue();
        }
    }

    /** Records the lifecycle calls a delegate factory receives. */
    private static final class LoggingAdapterFactory implements I_AdapterFactory
    {
        /** The real factory every call is forwarded to. */
        private final I_AdapterFactory delegate;

        /** Shared ordering log. */
        private final List<String> log;

        LoggingAdapterFactory(final I_AdapterFactory delegate, final List<String> log)
        {
            this.delegate = delegate;
            this.log = log;
        }

        @Override
        public void init()
        {
            log.add("delegate:init");
            delegate.init();
        }

        @Override
        public void shutdown()
        {
            log.add("delegate:shutdown");
            delegate.shutdown();
        }

        @Override
        public I_TimePort getTimePort()
        {
            return delegate.getTimePort();
        }

        @Override
        public I_InputPort getInputPort()
        {
            return delegate.getInputPort();
        }

        @Override
        public I_DatagramPort getDatagramPort()
        {
            return delegate.getDatagramPort();
        }

        @Override
        public I_FilePort getFilePort()
        {
            return delegate.getFilePort();
        }

        @Override
        public I_SystemInfoPort getSystemInfoPort()
        {
            return delegate.getSystemInfoPort();
        }

        @Override
        public I_UserProfilePort getUserProfilePort()
        {
            return delegate.getUserProfilePort();
        }

        @Override
        public I_WindowPort getWindowPort()
        {
            return delegate.getWindowPort();
        }
    }

    /** Records the lifecycle calls a profile port receives. */
    private static final class LoggingUserProfilePort implements I_UserProfilePort
    {
        /** The real port every call is forwarded to. */
        private final I_UserProfilePort delegate;

        /** Shared ordering log. */
        private final List<String> log;

        LoggingUserProfilePort(final I_UserProfilePort delegate, final List<String> log)
        {
            this.delegate = delegate;
            this.log = log;
        }

        @Override
        public void init()
        {
            log.add("profile:init");
            delegate.init();
        }

        @Override
        public void shutdown()
        {
            log.add("profile:shutdown");
            delegate.shutdown();
        }

        @Override
        public State state()
        {
            return delegate.state();
        }

        @Override
        public Optional<UserProfile> findById(final String id)
        {
            return delegate.findById(id);
        }

        @Override
        public List<UserProfile> findAll()
        {
            return delegate.findAll();
        }

        @Override
        public void save(final UserProfile profile)
        {
            delegate.save(profile);
        }

        @Override
        public void delete(final String id)
        {
            delegate.delete(id);
        }

        @Override
        public int count()
        {
            return delegate.count();
        }

        @Override
        public String generateNewId()
        {
            return delegate.generateNewId();
        }
    }
}
