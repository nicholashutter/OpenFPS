/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.gltf;

/**
 * Thrown when a glTF or GLB asset cannot be read or converted.
 *
 * Build-time only — this type is never on a runtime classpath.
 *
 * Distinct from {@code AssetBudgetException}: this means the input is
 * malformed, truncated, or uses a feature the converter does not implement.
 * A budget violation means the input is perfectly valid and simply too big.
 * Both fail the build; conflating them would make the fix ambiguous.
 */
public class GltfException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * Creates a glTF exception with a message.
     *
     * @param message human-readable description of what was wrong
     */
    public GltfException(final String message)
    {
        super(message);
    }

    /**
     * Creates a glTF exception wrapping an underlying cause.
     *
     * @param message human-readable description of what was wrong
     * @param cause the underlying failure
     */
    public GltfException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
