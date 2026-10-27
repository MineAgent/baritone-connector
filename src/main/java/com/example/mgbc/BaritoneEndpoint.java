// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mgbc;

import com.example.httpd.HttpdProvider;
import com.example.httpd.PathHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MGBaritoneConnector's endpoint, mounted under the shared server's {@code /bt} prefix.
 *
 * <ul>
 *   <li>{@code GET /bt/} returns the manual.</li>
 *   <li>{@code POST /bt/} runs one Baritone command (the request body).</li>
 * </ul>
 *
 * <p>The HTTP server itself (127.0.0.1:3420) belongs to MGHttpdProvider; this class only serves
 * the paths it is handed below the prefix, so {@code "/"} here means {@code /bt}.</p>
 */
public final class BaritoneEndpoint implements PathHandler {
	public static final String PREFIX = "/bt";
	public static final String NAME = "MGBaritoneConnector — Baritone 控制";

	/** What the provider's {@code GET /} index lists for this mod. */
	public static final List<HttpdProvider.Endpoint> ENDPOINTS = List.of(
			new HttpdProvider.Endpoint("GET", "/bt/", "使用说明"),
			new HttpdProvider.Endpoint("POST", "/bt/", "执行一条 Baritone 命令 (text/plain, UTF-8)"));

	private static final Logger LOG = Logger.getLogger("mgbaritoneconnector");
	private static final int MAX_BODY_BYTES = 16 * 1024;

	private final BaritoneCommandExecutor executor;

	public BaritoneEndpoint(BaritoneCommandExecutor executor) {
		this.executor = executor;
	}

	@Override
	public void handle(HttpExchange exchange, String path) throws IOException {
		try {
			if (!"/".equals(path)) {
				respond(exchange, 404, "text/plain; charset=utf-8",
						"no endpoint at /bt" + path + "\n\n" + Help.text());
				return;
			}

			switch (exchange.getRequestMethod()) {
				case "GET", "HEAD" -> respond(exchange, 200, "text/plain; charset=utf-8", Help.text());
				case "POST" -> handlePost(exchange);
				case "OPTIONS" -> {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					respond(exchange, 204, "text/plain; charset=utf-8", "");
				}
				default -> {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					respond(exchange, 405, "text/plain; charset=utf-8",
							"method not allowed: " + exchange.getRequestMethod()
									+ " (use GET for the manual, POST to run a Baritone command)\n");
				}
			}
		} catch (BodyTooLargeException e) {
			respond(exchange, 413, "text/plain; charset=utf-8",
					"request body too large (max " + MAX_BODY_BYTES + " bytes)\n");
		} catch (Exception e) {
			LOG.log(Level.WARNING, "request failed", e);
			respond(exchange, 500, "text/plain; charset=utf-8", "internal error: " + e + "\n");
		}
		// the provider closes the exchange
	}

	private void handlePost(HttpExchange exchange) throws IOException {
		String body = new String(readBody(exchange), StandardCharsets.UTF_8);
		String command = normalize(body);

		if (command.isEmpty()) {
			respond(exchange, 400, "text/plain; charset=utf-8",
					"empty Baritone command - send the command itself, e.g. 'goal ~ ~ ~20'\n");
			return;
		}
		if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
			respond(exchange, 400, "text/plain; charset=utf-8",
					"one Baritone command per request (split multiple commands into separate requests)\n");
			return;
		}

		String failure = executor.execute(command);
		if (failure != null) {
			respond(exchange, 400, "text/plain; charset=utf-8",
					"Baritone command failed: " + failure + "\n");
			return;
		}

		respond(exchange, 200, "text/plain; charset=utf-8", "ok: " + command + "\n");
	}

	/**
	 * Accepts the command with or without the chat shorthand: {@code goal ~ ~ ~20},
	 * {@code #goal ~ ~ ~20} and {@code bt goal ~ ~ ~20} are equivalent.
	 */
	private static String normalize(String body) {
		String command = body == null ? "" : body.strip();
		if (command.startsWith("#")) {
			return command.substring(1).strip();
		}
		if (command.regionMatches(true, 0, "bt", 0, 2)
				&& (command.length() == 2 || Character.isWhitespace(command.charAt(2)))) {
			return command.substring(2).strip();
		}
		return command;
	}

	private static byte[] readBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[4096];
			int total = 0;
			int read;
			while ((read = in.read(buffer)) > 0) {
				total += read;
				if (total > MAX_BODY_BYTES) {
					throw new BodyTooLargeException();
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
	}

	private static void respond(HttpExchange exchange, int status, String contentType, String body)
			throws IOException {
		respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
	}

	private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
			throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		if ("HEAD".equals(exchange.getRequestMethod()) || body.length == 0) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static final class BodyTooLargeException extends IOException {
		private static final long serialVersionUID = 1L;
	}
}
