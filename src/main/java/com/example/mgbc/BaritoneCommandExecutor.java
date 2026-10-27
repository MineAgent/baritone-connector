// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mgbc;

/**
 * Runs one Baritone command on the client.
 *
 * <p>The HTTP layer only depends on this interface, so the transport and the command
 * normalisation can be tested without launching Minecraft or loading Baritone.</p>
 */
public interface BaritoneCommandExecutor {
	/**
	 * Runs a Baritone command (without the leading {@code #}).
	 *
	 * @return {@code null} when the command was accepted, otherwise a human readable reason
	 */
	String execute(String command);
}
