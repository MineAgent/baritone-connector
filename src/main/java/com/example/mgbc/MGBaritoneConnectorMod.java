// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mgbc;

import com.example.httpd.HttpdProvider;
import net.fabricmc.api.ClientModInitializer;

/**
 * Client entrypoint: mounts the Baritone command endpoint under {@code /bt} on MGHttpdProvider's
 * shared server (127.0.0.1:3420).
 *
 * <p>Baritone is a hard dependency declared in {@code fabric.mod.json}; this mod only registers
 * the endpoint. There is no long-lived thread of its own.</p>
 */
public class MGBaritoneConnectorMod implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HttpdProvider.register(BaritoneEndpoint.PREFIX, BaritoneEndpoint.NAME,
				BaritoneEndpoint.ENDPOINTS, new BaritoneEndpoint(new BaritoneExecutor()));
	}
}
