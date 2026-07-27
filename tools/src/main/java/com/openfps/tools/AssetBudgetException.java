/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools;

/**
 * Thrown when an asset violates a {@code docs/ASSETS.md} § 5 budget.
 *
 * Build-time only — this type is never on a runtime classpath.
 *
 * <h2>Why this fails the build instead of fixing the asset</h2>
 *
 * The obvious alternative is for the converter to decimate an over-budget mesh
 * or downscale an over-budget texture and carry on. It is rejected
 * deliberately. The budgets exist to surface a problem — that an asset does
 * not fit the measured per-frame budget in {@code docs/ASSETS.md} § 2 — and
 * silently repairing the asset hides exactly the thing the budget was written
 * to make visible. A build that fails naming the file and the actual value
 * gets fixed at import time, which is the only cheap time to fix it.
 *
 * Every message names the source file and the measured value, because a
 * budget failure that does not say which asset and by how much is a failure
 * the reader has to reproduce by hand.
 */
public class AssetBudgetException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * Creates a budget violation.
     *
     * @param message description naming the offending asset and the actual value
     */
    public AssetBudgetException(final String message)
    {
        super(message);
    }
}
