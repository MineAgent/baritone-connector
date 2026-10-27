// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import com.example.httpd.HttpdProvider;
import com.example.mgbc.BaritoneCommandExecutor;
import com.example.mgbc.BaritoneEndpoint;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone harness for the /bt transport layer.
 *
 * <p>Compile only the Minecraft/Baritone-free classes together with this file and MGHttpdProvider's
 * API jar, then run it. It mounts the endpoint on the real 127.0.0.1:3420 server with a fake
 * executor, checks the HTTP contract, and exits with 0 on success.</p>
 *
 * <pre>
 * javac --release 25 -encoding UTF-8 -d build/verify -cp libs/httpdprovider-1.0.jar \
 *   src/main/java/com/example/mgbc/{BaritoneCommandExecutor,BaritoneEndpoint,Help}.java \
 *   tools/VerifyServer.java
 * java -cp build/verify:libs/httpdprovider-1.0.jar VerifyServer
 * </pre>
 */
public final class VerifyServer {

	static final class FakeExecutor implements BaritoneCommandExecutor {
		final List<String> calls = new ArrayList<>();
		volatile String failure;

		@Override
		public String execute(String command) {
			synchronized (calls) {
				calls.add(command);
			}
			System.out.println("[fake] execute " + command);
			return failure;
		}
	}

	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		FakeExecutor fake = new FakeExecutor();
		HttpdProvider.register(BaritoneEndpoint.PREFIX, BaritoneEndpoint.NAME,
				BaritoneEndpoint.ENDPOINTS, new BaritoneEndpoint(fake));

		httpTests(fake);

		if (List.of(args).contains("--serve")) {
			System.out.println("READY - http://127.0.0.1:3420/bt");
			Thread.sleep(Long.MAX_VALUE);
		}

		System.out.println(failures == 0 ? "HTTP TESTS: all passed" : "HTTP TESTS: " + failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void httpTests(FakeExecutor fake) throws Exception {
		Response manual = request("GET", "/bt/", null);
		check("GET /bt/: 200", manual.status == 200, detail(manual));
		check("GET /bt/: manual title", manual.body.contains("MGBaritoneConnector"), detail(manual));
		check("GET /bt/: documents POST", manual.body.contains("POST /bt/"), detail(manual));

		Response head = request("HEAD", "/bt/", null);
		check("HEAD /bt/: 200", head.status == 200, detail(head));
		check("HEAD /bt/: empty body", head.body.isEmpty(), detail(head));

		fake.calls.clear();
		Response goal = post("goal ~ ~ ~20");
		check("POST goal: 200", goal.status == 200, detail(goal));
		check("POST goal: body", "ok: goal ~ ~ ~20".equals(goal.body.strip()), detail(goal));
		check("POST goal: reached executor", fake.calls.equals(List.of("goal ~ ~ ~20")), "calls=" + fake.calls);

		fake.calls.clear();
		Response hash = post("#stop");
		check("POST #stop: 200", hash.status == 200, detail(hash));
		check("POST #stop: strips #", fake.calls.equals(List.of("stop")), "calls=" + fake.calls);

		fake.calls.clear();
		Response prefixed = post("bt mine iron_ore");
		check("POST bt prefix: 200", prefixed.status == 200, detail(prefixed));
		check("POST bt prefix: strips bt", fake.calls.equals(List.of("mine iron_ore")), "calls=" + fake.calls);

		fake.calls.clear();
		Response loud = post("  BT   stop  ");
		check("POST BT prefix: 200", loud.status == 200, detail(loud));
		check("POST BT prefix: normalized", fake.calls.equals(List.of("stop")), "calls=" + fake.calls);

		fake.calls.clear();
		for (String empty : List.of("", "   ", "#", "bt", "  #  ")) {
			Response response = post(empty);
			check("empty command " + quote(empty) + ": 400", response.status == 400, detail(response));
		}
		check("empty commands: nothing executed", fake.calls.isEmpty(), "calls=" + fake.calls);

		Response multiline = post("goal ~ ~ ~20\nstop");
		check("multiline: 400", multiline.status == 400, detail(multiline));

		fake.failure = "unknown Baritone command: nope";
		Response failed = post("nope");
		check("executor failure: 400", failed.status == 400, detail(failed));
		check("executor failure: reason", failed.body.contains("unknown Baritone command: nope"), detail(failed));
		fake.failure = null;

		String large = "x".repeat(20_000);
		Response tooLarge = post(large);
		check("body too large: 413", tooLarge.status == 413, detail(tooLarge));

		Response missing = request("GET", "/bt/nope", null);
		check("unknown subpath: 404", missing.status == 404, detail(missing));

		Response wrongMethod = request("PUT", "/bt/", null);
		check("PUT: 405", wrongMethod.status == 405, detail(wrongMethod));
		check("PUT: Allow header", wrongMethod.allow != null && wrongMethod.allow.contains("POST"),
				"allow=" + wrongMethod.allow);

		Response options = request("OPTIONS", "/bt/", null);
		check("OPTIONS: 204", options.status == 204, detail(options));

		Response index = request("GET", "/", null);
		check("provider index: 200", index.status == 200, detail(index));
		check("provider index: lists /bt", index.body.contains("/bt"), detail(index));

		Response noSlash = request("GET", "/bt", null);
		check("GET /bt: 200", noSlash.status == 200, detail(noSlash));
	}

	private record Response(int status, String body, String allow) {
	}

	private static Response post(String body) throws Exception {
		return request("POST", "/bt/", body);
	}

	private static Response request(String method, String path, String body) throws Exception {
		HttpURLConnection connection =
				(HttpURLConnection) new URL("http://127.0.0.1:3420" + path).openConnection();
		connection.setRequestMethod(method);
		if (body != null) {
			connection.setDoOutput(true);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(body.getBytes(StandardCharsets.UTF_8));
			}
		}
		int status = connection.getResponseCode();
		InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
		String text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		return new Response(status, text, connection.getHeaderField("Allow"));
	}

	private static String detail(Response response) {
		return "status=" + response.status + " body=" + quote(response.body.strip());
	}

	private static void check(String what, boolean ok, String detail) {
		if (ok) {
			System.out.println("ok   " + what);
		} else {
			failures++;
			System.out.println("FAIL " + what + " -> " + detail);
		}
	}

	private static String quote(String value) {
		return "\"" + value.replace("\n", "\\n") + "\"";
	}
}
