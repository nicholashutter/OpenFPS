/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.openfps.engine.render.adapter.ModelFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The model set the first-person demo draws, loaded from disk with an honest
 * fallback.
 *
 * <h2>Why a fallback exists at all</h2>
 *
 * <p>{@code docs/ASSETS.md} § 6 keeps upstream art out of git, so
 * {@code assets/models/} is gitignored and <b>a fresh clone does not have
 * it</b>. Three outcomes are possible and all three are reported plainly,
 * because the failure mode this class exists to prevent is a demo that quietly
 * shows generated greybox while everyone believes they are looking at the real
 * kit:</p>
 *
 * <ol>
 *   <li><b>{@link Source#KENNEY_KIT}</b> — the eight Prototype Kit level pieces
 *       are present. Logged at {@code INFO}.</li>
 *   <li><b>{@link Source#GENERATED_ROOM}</b> — they are not, but
 *       {@code generated-room.ofm} is. Logged at <b>{@code WARN}</b>, naming
 *       which pieces were missing, so nobody mistakes the greybox room for
 *       Kenney art.</li>
 *   <li><b>Neither</b> — {@link DemoAssetException}, whose message names
 *       {@link #REGENERATE_COMMAND}. There is no fourth outcome in which the
 *       demo starts with nothing to stand on.</li>
 * </ol>
 *
 * <p><b>There is deliberately no procedural third tier here.</b> The generator
 * ({@code com.openfps.tools.model.ProceduralRoom}) is build-time tooling and
 * the root project's {@code verifyToolsIsolation} task fails the build if
 * {@code :tools} ever reaches a shipped runtime classpath. The generated room
 * therefore arrives as a <i>file</i> that {@code :tools} wrote, never as code
 * the engine runs — which is the same one-way edge, honoured rather than
 * worked around.</p>
 *
 * <h2>The weapon is independent of the level</h2>
 *
 * <p>{@link #weapon()} may be null while a level is present, and is reported
 * separately at {@code WARN}. The two come from different CC0 packs and one can
 * be staged without the other; a missing viewmodel is a degraded demo, not a
 * dead one.</p>
 *
 * <h2>Threading and lifetime</h2>
 *
 * <p>Immutable once loaded, and every {@link ModelFormat} it holds is immutable
 * too, so one instance is safe to share with the render workers. Loading reads
 * files and allocates; it happens once, at startup, never per frame.</p>
 */
public final class DemoModels
{
    /** Which of the two asset paths a {@link DemoModels} actually came from. */
    public enum Source
    {
        /** Real Kenney Prototype Kit and Blaster Kit art — the intended set. */
        KENNEY_KIT,

        /** The generated greybox room. A floor to stand on, not third-party art. */
        GENERATED_ROOM
    }

    /** Subdirectory of the model root holding the weapon. */
    public static final String WEAPON_DIRECTORY = "weapon";

    /** Subdirectory of the model root holding the level kit. */
    public static final String LEVEL_DIRECTORY = "level";

    /** Subdirectory of the model root holding the character models. */
    public static final String CHARACTER_DIRECTORY = "character";

    /** File name of the viewmodel. */
    public static final String WEAPON_MODEL = "blaster-b.ofm";

    /**
     * File name of the weapon the <b>bots</b> carry — and it is not the
     * player's.
     *
     * <p>Both come from Kenney's Blaster Kit, so there is no new attribution to
     * make ({@code NOTICE} already credits the pack), but they are deliberately
     * two different models. {@code blaster-b} is a compact pistol, 0.42 units
     * long and predominantly orange; {@code blaster-p} is a long two-handed
     * carbine, <b>0.86</b> units long and predominantly green. Twice the length
     * and a different colour is a silhouette the player can tell apart at the
     * far side of the room, which is the entire requirement — an opponent
     * holding what looks like your own gun tells you nothing.</p>
     *
     * <p>Chosen off the pack's own preview renders rather than by picking the
     * next letter: several of the eighteen blasters are near-duplicates of
     * {@code blaster-b} with a different grip, and any of those would have been
     * a change nobody could see. {@code blaster-p} is also the largest model in
     * either demo pack at 882 triangles ({@code docs/DEMO_ASSETS.md} § 4), which
     * is 59% of the per-model budget and the reason that figure is worth
     * knowing: seven of them is 6,174 triangles beside a room that already
     * submits tens of thousands.</p>
     */
    public static final String BOT_WEAPON_MODEL = "blaster-p.ofm";

    /** File name of the generated fallback room. Matches {@code DemoAssetsMain}. */
    public static final String FALLBACK_MODEL = "generated-room.ofm";

    /** The command that produces every model this class looks for. */
    public static final String REGENERATE_COMMAND =
        "gradlew :tools:regenerateDemoAssets -PkenneyRaw=<dir>";

    private static final Logger LOG = LoggerFactory.getLogger(DemoModels.class);

    /** Level kit file names, in the order {@link #loadKit} assigns them. */
    private static final String[] KIT_FILES =
    {
        "floor-square.ofm", "wall.ofm", "wall-doorway.ofm", "column.ofm",
        "crate.ofm", "stairs.ofm", "shape-slope.ofm",
    };

    /**
     * The character models the bots wear — seven visibly different people from
     * the Kenney Blocky Characters pack, one per opponent.
     *
     * <p><b>Seven, matching {@code Match.DEFAULT_BOT_COUNT}</b>, so no two bots
     * in a room look alike. That is not decoration. The bots move, they shoot
     * from different places, and they die individually; telling one from another
     * at a glance is how a player knows which of them they already hit, and how
     * anyone reading a screenshot knows whether the outline pass has drawn two
     * silhouettes or merged two bodies into one.</p>
     *
     * <p><b>Seven rather than all eighteen</b> because each one costs a private
     * 1.4 MB copy of a 512x512 atlas — {@code ModelFormat} has no shared-texture
     * concept yet, so the whole pack would be about 25 MB of largely duplicated
     * pixels. Seven is 9.8 MB, which is the price of one distinct opponent each
     * and nothing spent on models nobody sees.</p>
     *
     * <p>Spread across the alphabet rather than taken as the first seven,
     * because the pack is authored in loose families and adjacent letters tend
     * to share a palette.</p>
     */
    private static final String[] CHARACTER_FILES =
    {
        "character-a.ofm", "character-d.ofm", "character-h.ofm", "character-k.ofm",
        "character-n.ofm", "character-q.ofm", "character-r.ofm",
    };

    private final Source source;
    private final ModelFormat floor;
    private final ModelFormat wall;
    private final ModelFormat doorway;
    private final ModelFormat column;
    private final ModelFormat crate;
    private final ModelFormat stairs;
    private final ModelFormat slope;
    private final ModelFormat room;
    private final ModelFormat weapon;
    private final ModelFormat botWeapon;
    private final boolean realBotWeapon;
    private final ModelFormat[] characters;

    // Takes ownership of an already-validated set. One of `floor` (kit) and
    // `room` (fallback) is non-null and the other is null; `source` says which.
    private DemoModels(final Source modelSource, final ModelFormat[] kit,
        final ModelFormat fallbackRoom, final ModelFormat viewmodel,
        final ModelFormat opponentWeapon, final boolean opponentWeaponIsReal,
        final ModelFormat[] people)
    {
        this.source = modelSource;
        this.room = fallbackRoom;
        this.weapon = viewmodel;
        this.botWeapon = opponentWeapon;
        this.realBotWeapon = opponentWeaponIsReal;
        this.characters = people;
        if (kit == null)
        {
            this.floor = null;
            this.wall = null;
            this.doorway = null;
            this.column = null;
            this.crate = null;
            this.stairs = null;
            this.slope = null;
            return;
        }
        this.floor = kit[0];
        this.wall = kit[1];
        this.doorway = kit[2];
        this.column = kit[3];
        this.crate = kit[4];
        this.stairs = kit[5];
        this.slope = kit[6];
    }

    /**
     * Loads the demo's models from a directory, preferring the real kit.
     *
     * <p>The root is the directory {@code :tools:regenerateDemoAssets} writes,
     * normally {@code assets/models}. It is expected to contain
     * {@code level/} and {@code weapon/} subdirectories, or a
     * {@link #FALLBACK_MODEL} at the top level, or both.</p>
     *
     * @param root the model root directory; must not be null
     * @return the loaded set, never null
     * @throws DemoAssetException if neither the level kit nor the generated
     *     room is readable — the message names {@link #REGENERATE_COMMAND}
     */
    public static DemoModels load(final Path root)
    {
        if (root == null)
        {
            throw new IllegalArgumentException("root must not be null");
        }
        return load(new DirectoryModelSource(root));
    }

    /**
     * Loads the demo's models from anywhere, preferring the real kit.
     *
     * <p>The overload that lets the same demo run on a phone: an APK's
     * {@code assets/} entries are zip entries with no filesystem path, so
     * Android supplies a {@link ModelSource} over its asset manager rather than
     * a directory. Every decision below — which of the two asset paths is
     * taken, what is reported, what degrades and what throws — is identical on
     * both, which is the point of taking the source rather than the path.</p>
     *
     * @param source where to read model files from; must not be null
     * @return the loaded set, never null
     * @throws DemoAssetException if neither the level kit nor the generated
     *     room is readable — the message names {@link #REGENERATE_COMMAND}
     */
    public static DemoModels load(final ModelSource source)
    {
        if (source == null)
        {
            throw new IllegalArgumentException("source must not be null");
        }

        final ModelFormat viewmodel = loadWeapon(source);
        final boolean realCarbine = source.has(botWeaponPath());
        final ModelFormat opponentWeapon = loadBotWeapon(source);
        final ModelFormat[] people = loadCharacters(source);
        final List<String> missing = missingKitFiles(source);
        if (missing.isEmpty())
        {
            LOG.info("Demo level: REAL Kenney Prototype Kit, {} pieces from {}",
                KIT_FILES.length, source.describe(LEVEL_DIRECTORY));
            return new DemoModels(Source.KENNEY_KIT, loadKit(source), null, viewmodel,
                opponentWeapon, realCarbine, people);
        }

        if (!source.has(FALLBACK_MODEL))
        {
            throw new DemoAssetException("The demo has no geometry to stand on. Neither the"
                + " Kenney level kit under " + source.describe(LEVEL_DIRECTORY)
                + " (missing: " + String.join(", ", missing) + ") nor the generated fallback "
                + source.describe(FALLBACK_MODEL) + " exists. Produce them with: "
                + REGENERATE_COMMAND);
        }

        LOG.warn("Demo level: FALLBACK generated greybox room from {} — this is GENERATED"
            + " geometry, not Kenney art. The real kit is missing {} piece(s): {}. For the"
            + " intended demo run: {}", source.describe(FALLBACK_MODEL), missing.size(),
            String.join(", ", missing), REGENERATE_COMMAND);
        return new DemoModels(Source.GENERATED_ROOM, null, read(source, FALLBACK_MODEL),
            viewmodel, opponentWeapon, realCarbine, people);
    }

    // Where the bots' carbine lives under a source. One definition, because
    // `load` asks whether it is there and `loadBotWeapon` asks for it, and two
    // copies of a path is how those two come to disagree.
    private static String botWeaponPath()
    {
        return WEAPON_DIRECTORY + "/" + BOT_WEAPON_MODEL;
    }

    // The weapon the bots hold: the real carbine, or a generated stand-in.
    //
    // NEVER null, and that is a change from every other model in this class. The
    // rule everywhere else is "absent art degrades the demo rather than ending
    // it", and it holds here too — what changed is what "degraded" costs. It used
    // to cost the whole feature: no model meant no scene instance, which measured
    // as ZERO pixels of weapon and was reported twice as "the carbines are not
    // visible". Both reports were correct. See BlockCarbine for the measurement.
    //
    // Incoming fire is specified to come out of a weapon, so a bot with nothing
    // in its hands has nowhere to put a muzzle. The substitute occupies the real
    // model's box exactly, so BOT_WEAPON_WORLD_SCALE, the muzzle flip and
    // BOT_WEAPON_MUZZLE_UNITS all keep working against either one.
    private static ModelFormat loadBotWeapon(final ModelSource source)
    {
        final String path = botWeaponPath();
        if (!source.has(path))
        {
            LOG.warn("Demo bot weapon: GENERATED BLOCK CARBINE, not Kenney art. {} is missing,"
                + " so the opponents carry {} boxes of arithmetic instead — visibly armed,"
                + " visibly not the real model. Do NOT record this against an upstream"
                + " source. For the intended demo run: {}", source.describe(path),
                BlockCarbine.partCount(), REGENERATE_COMMAND);
            return BlockCarbine.model();
        }
        LOG.info("Demo bot weapon: REAL Kenney Blaster Kit model from {} — deliberately a"
            + " different blaster from the player's {}", source.describe(path), WEAPON_MODEL);
        return read(source, path);
    }

    // The viewmodel, or null with a WARN. Absent art degrades the demo rather
    // than ending it, so this never throws for a missing file.
    private static ModelFormat loadWeapon(final ModelSource source)
    {
        final String path = WEAPON_DIRECTORY + "/" + WEAPON_MODEL;
        if (!source.has(path))
        {
            LOG.warn("Demo weapon: NONE. {} is missing, so the first-person viewmodel will not"
                + " be drawn. Produce it with: {}", source.describe(path), REGENERATE_COMMAND);
            return null;
        }
        LOG.info("Demo weapon: REAL Kenney Blaster Kit model from {}", source.describe(path));
        return read(source, path);
    }

    /**
     * The character models available as targets, never null, possibly empty.
     *
     * <p>Empty means the character pack was never staged. That degrades the
     * demo to an empty room rather than ending it, exactly as a missing weapon
     * does — the level and the characters come from different CC0 packs and
     * either can be staged without the other.</p>
     *
     * @return a fresh array so a caller cannot mutate the shared set
     */
    public ModelFormat[] characters()
    {
        return characters.clone();
    }

    /**
     * True when there is at least one character model to stand up as a target.
     *
     * @return whether {@link #characters()} has any entries
     */
    public boolean hasCharacters()
    {
        return characters.length > 0;
    }

    // The character models, or an empty array with a WARN. Absent art degrades
    // the demo rather than ending it, so this never throws for a missing file.
    // All-or-nothing per file: a partially staged pack loads what it has, since
    // one target is enough to see the outline and hitscan working.
    private static ModelFormat[] loadCharacters(final ModelSource source)
    {
        final List<ModelFormat> found = new ArrayList<>();
        for (final String name : CHARACTER_FILES)
        {
            final String path = CHARACTER_DIRECTORY + "/" + name;
            if (source.has(path))
            {
                found.add(read(source, path));
            }
        }
        if (found.isEmpty())
        {
            LOG.warn("Demo characters: NONE. No character model found under {}, so the demo"
                + " will have nothing to shoot at and the outline pass will not run."
                + " Produce them with: {}", source.describe(CHARACTER_DIRECTORY),
                REGENERATE_COMMAND);
            return new ModelFormat[0];
        }
        LOG.info("Demo characters: {} of {} Kenney Blocky Characters models from {}",
            found.size(), CHARACTER_FILES.length, source.describe(CHARACTER_DIRECTORY));
        return found.toArray(new ModelFormat[0]);
    }

    // Which of the kit's files the source does not have, in declared order.
    private static List<String> missingKitFiles(final ModelSource source)
    {
        final List<String> missing = new ArrayList<>();
        for (final String name : KIT_FILES)
        {
            if (!source.has(LEVEL_DIRECTORY + "/" + name))
            {
                missing.add(name);
            }
        }
        return missing;
    }

    // Reads every kit piece. Only called once every file is known to exist, so
    // anything that fails here is a corrupt model rather than an absent one.
    private static ModelFormat[] loadKit(final ModelSource source)
    {
        final ModelFormat[] kit = new ModelFormat[KIT_FILES.length];
        for (int index = 0; index < KIT_FILES.length; index++)
        {
            kit[index] = read(source, LEVEL_DIRECTORY + "/" + KIT_FILES[index]);
        }
        return kit;
    }

    // One model, with the location in the failure message. A file that exists
    // but does not parse is a real error and is never downgraded to the
    // fallback: silently substituting greybox for a corrupt asset would hide
    // the corruption.
    private static ModelFormat read(final ModelSource source, final String path)
    {
        try
        {
            return ModelFormat.read(source.read(path));
        }
        catch (final IOException e)
        {
            throw new DemoAssetException("Cannot read " + source.describe(path)
                + " — regenerate with: " + REGENERATE_COMMAND, e);
        }
        catch (final RuntimeException e)
        {
            throw new DemoAssetException(source.describe(path)
                + " does not parse as a model: " + e.getMessage()
                + " — regenerate with: " + REGENERATE_COMMAND, e);
        }
    }

    /** Returns which asset path this set came from. Never null. */
    public Source source()
    {
        return source;
    }

    /** Returns true if these are the real Kenney models rather than the fallback. */
    public boolean isRealArt()
    {
        return source == Source.KENNEY_KIT;
    }

    /**
     * Returns the 1x1 floor tile, or null when {@link #source()} is
     * {@link Source#GENERATED_ROOM}.
     *
     * @return the floor tile model, or null
     */
    public ModelFormat floor()
    {
        return floor;
    }

    /**
     * Returns the plain wall segment, or null for the fallback set.
     *
     * @return the wall model, or null
     */
    public ModelFormat wall()
    {
        return wall;
    }

    /**
     * Returns the doorway wall segment, or null for the fallback set.
     *
     * @return the doorway model, or null
     */
    public ModelFormat doorway()
    {
        return doorway;
    }

    /**
     * Returns the column prop, or null for the fallback set.
     *
     * @return the column model, or null
     */
    public ModelFormat column()
    {
        return column;
    }

    /**
     * Returns the crate prop, or null for the fallback set.
     *
     * @return the crate model, or null
     */
    public ModelFormat crate()
    {
        return crate;
    }

    /**
     * Returns the staircase, or null for the fallback set.
     *
     * @return the stairs model, or null
     */
    public ModelFormat stairs()
    {
        return stairs;
    }

    /**
     * Returns the ramp, or null for the fallback set.
     *
     * @return the slope model, or null
     */
    public ModelFormat slope()
    {
        return slope;
    }

    /**
     * Returns the whole generated room as one model, or null when
     * {@link #source()} is {@link Source#KENNEY_KIT}.
     *
     * @return the fallback room model, or null
     */
    public ModelFormat room()
    {
        return room;
    }

    /**
     * Returns the first-person weapon, or null when it was not staged.
     *
     * <p>Null here is a reported, survivable condition — see the class
     * Javadoc — so a caller must check rather than assume.</p>
     *
     * @return the viewmodel, or null
     */
    public ModelFormat weapon()
    {
        return weapon;
    }

    /**
     * Returns the weapon the bots carry. <b>Never null.</b>
     *
     * <p>Never the same model as {@link #weapon()} — see
     * {@link #BOT_WEAPON_MODEL}. The one model in this class that cannot be
     * absent: when {@code blaster-p.ofm} was not staged this is
     * {@link BlockCarbine#model()}, generated in code, logged loudly as generated.
     * {@link #hasRealBotWeapon()} is how a caller tells the two apart; nothing
     * needs to, because both occupy the same model-space box.</p>
     *
     * @return the bots' carbine, real or generated, never null
     */
    public ModelFormat botWeapon()
    {
        return botWeapon;
    }

    /**
     * Returns whether the bots' carbine is the real Kenney model rather than the
     * generated stand-in.
     *
     * <p>Exposed for the reason {@link #isRealArt()} is: a test, a log line or a
     * screenshot caption should be able to say <i>which</i> payload it is looking
     * at rather than inferring it. Nothing in the demo behaves differently either
     * way — that is the point of authoring the substitute to the same box.</p>
     *
     * @return true when {@code blaster-p.ofm} was staged and loaded
     */
    public boolean hasRealBotWeapon()
    {
        return realBotWeapon;
    }

    /** Returns a debug rendering of which models were loaded. */
    @Override
    public String toString()
    {
        return "DemoModels{source=" + source + ", weapon=" + (weapon != null)
            + ", botWeapon=" + realBotWeapon + "}";
    }
}
