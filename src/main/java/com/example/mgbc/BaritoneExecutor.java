// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mgbc;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.manager.ICommandManager;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs Baritone commands through Baritone's API.
 *
 * <p>Baritone is a hard dependency (see {@code fabric.mod.json}), so no "is Baritone installed"
 * check is needed here: the classes are imported directly and Fabric Loader guarantees that the
 * mod is present. This is the same call chain the chat box uses for {@code #goal ~ ~ ~20}, minus
 * the chat packet:</p>
 *
 * <pre>
 * BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(command)
 * </pre>
 *
 * <p>Commands are serialised with a lock and executed on the client (render) thread. The HTTP
 * worker waits for the outcome, so a response describes what really happened. Baritone's own
 * output still goes to the chat bar; read it with AdvancedInfoFetcher's {@code GET /aif/msg}.</p>
 */
public final class BaritoneExecutor implements BaritoneCommandExecutor {
	private static final Logger LOG = Logger.getLogger("mgbaritoneconnector");

	/** How long the HTTP thread waits for the client thread to run the command. */
	private static final long EXECUTE_TIMEOUT_MS = 10_000L;

	/** Keeps concurrent HTTP requests from interleaving on the client thread. */
	private final Object lock = new Object();

	@Override
	public String execute(String command) {
		synchronized (lock) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null) {
				return "Minecraft client is not running";
			}
			if (minecraft.isSameThread()) {
				return run(command);
			}

			CompletableFuture<String> result = new CompletableFuture<>();
			try {
				minecraft.execute(() -> {
					try {
						result.complete(run(command));
					} catch (Throwable t) {
						result.completeExceptionally(t);
					}
				});
			} catch (RuntimeException e) {
				LOG.log(Level.FINE, "could not queue the Baritone command (game shutting down?)", e);
				return "Minecraft client is not accepting input: " + e;
			}

			try {
				return result.get(EXECUTE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				return "the client thread did not answer within " + EXECUTE_TIMEOUT_MS + " ms";
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "interrupted";
			} catch (ExecutionException e) {
				Throwable cause = e.getCause();
				return cause == null ? String.valueOf(e) : String.valueOf(cause);
			}
		}
	}

	/** Runs on the client thread. */
	private static String run(String command) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return "Minecraft client is not running";
		}
		if (minecraft.player == null) {
			return "no world loaded (join a world first)";
		}

		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			if (baritone == null) {
				return "Baritone has no primary instance yet (join a world first)";
			}
			ICommandManager commandManager = baritone.getCommandManager();
			if (commandManager == null) {
				return "Baritone has no command manager";
			}
			boolean handled = commandManager.execute(command);
			return handled ? null : "unknown Baritone command: " + command;
		} catch (RuntimeException | LinkageError e) {
			LOG.log(Level.WARNING, "Baritone API call failed: " + command, e);
			return "the Baritone API call failed: " + e;
		}
	}
}
