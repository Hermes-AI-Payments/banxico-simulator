package mx.endcom.hermes.banxicosim.testsupport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import mx.endcom.hermes.banxicosim.ara.AraServer;
import mx.endcom.hermes.banxicosim.config.SimConfig;
import mx.endcom.hermes.banxicosim.control.ControlServer;
import mx.endcom.hermes.banxicosim.crypto.SimulatorIdentity;
import mx.endcom.hermes.banxicosim.persistence.H2Store;
import mx.endcom.hermes.banxicosim.spei.RechazoForzadoRegistry;
import mx.endcom.hermes.banxicosim.spei.RedVariacionRegistry;
import mx.endcom.hermes.banxicosim.spei.SpeiServer;

/**
 * Arranca una instancia completa del simulador (servidor SPEI + servidor ARA + API de control +
 * H2) dentro del mismo proceso de la prueba, en puertos efímeros propios -- reconstrucción del
 * "arnés Java" que {@code specs/README.md} (hallazgo 2026-09-21) pide para poder marcar las specs
 * 003 y 007 como implementadas, ya que el "arnés Python" original nunca se comiteó.
 *
 * <p>Equivalente de prueba a lo que hace {@code Main.main} -- mismas piezas, mismo cableado
 * manual, pero: (a) en puertos elegidos en el momento (no los de {@code simulator.properties}),
 * (b) con una identidad "minos falsa" generada por el propio arnés en vez de depender de un
 * certificado público real de minos (ver {@link FakeMinosClient}), y (c) con directorios
 * temporales que se borran en {@link #close()} -- cada prueba arranca con estado limpio.</p>
 */
public final class SimuladorHarness implements AutoCloseable {

	public final SpeiServer speiServer;
	public final AraServer araServer;
	public final ControlServer controlServer;
	public final H2Store store;
	public final SimConfig config;
	public final SimulatorIdentity identity; // identidad propia del simulador (Banxico falso)
	public final SimulatorIdentity fakeMinos; // identidad que el arnés usa para jugar el papel de minos
	public final int speiPort;
	public final int araPort;
	public final int controlPort;

	private final Path tempDir;
	private final Thread speiThread;
	private final Thread araThread;

	private SimuladorHarness(SpeiServer speiServer, AraServer araServer, ControlServer controlServer,
			H2Store store, SimConfig config, SimulatorIdentity identity, SimulatorIdentity fakeMinos,
			int speiPort, int araPort, int controlPort, Path tempDir, Thread speiThread, Thread araThread) {
		this.speiServer = speiServer;
		this.araServer = araServer;
		this.controlServer = controlServer;
		this.store = store;
		this.config = config;
		this.identity = identity;
		this.fakeMinos = fakeMinos;
		this.speiPort = speiPort;
		this.araPort = araPort;
		this.controlPort = controlPort;
		this.tempDir = tempDir;
		this.speiThread = speiThread;
		this.araThread = araThread;
	}

	public static SimuladorHarness start() throws Exception {
		Path tempDir = Files.createTempDirectory("banxicosim-harness-");

		int speiPort = freePort();
		int araPort = freePort();
		int controlPort = freePort();

		Path propsFile = tempDir.resolve("simulator.properties");
		String props = String.join("\n",
				"spei.port=" + speiPort,
				"ara.port=" + araPort,
				"control.port=" + controlPort,
				"identity.dir=" + tempDir.resolve("identity"),
				"db.path=" + tempDir.resolve("db").resolve("banxicosim"),
				// Sin esto, el heartbeat (default 3000ms) podría intercalar un AreYouAlive entre los
				// bytes que el arnés espera comparar byte a byte para el criterio de aceptación de
				// spec 003 -- un valor grande lo saca de la ventana de cualquier prueba razonable.
				"spei.heartbeatIntervalMs=600000",
				"");
		Files.writeString(propsFile, props, StandardCharsets.UTF_8);

		SimConfig config = loadConfigFrom(propsFile);

		SimulatorIdentity identity = SimulatorIdentity.loadOrCreate(config.identityDir(), config.ownCertificateNumber());
		// "minos falso": su propia identidad RSA, generada en el momento -- igual que hacía el
		// arnés Python original (ver AGENTS.md &sect;4). Su llave pública es la que el simulador
		// recibe como "la llave pública real de minos" (minosPublicKey), exactamente como
		// Main.java se la pasa a SpeiServer/AraServer, solo que aquí no hace falta escribir un
		// archivo PEM: se pasa el objeto directamente.
		SimulatorIdentity fakeMinos = SimulatorIdentity.loadOrCreate(tempDir.resolve("fake-minos-identity"), "");

		H2Store store = H2Store.open(config.dbPath());
		RedVariacionRegistry redVariacionRegistry = new RedVariacionRegistry(config);
		RechazoForzadoRegistry rechazoForzadoRegistry = new RechazoForzadoRegistry();

		SpeiServer speiServer = new SpeiServer(config.speiPort(), identity, fakeMinos.publicKey(), config, store,
				redVariacionRegistry, rechazoForzadoRegistry);
		AraServer araServer = new AraServer(config.araPort(), identity, fakeMinos.publicKey(),
				fakeMinos.certificateNumber(), fakeMinos.certificatePem(), store);

		Thread speiThread = new Thread(speiServer, "test-spei-server");
		Thread araThread = new Thread(araServer, "test-ara-server");
		speiThread.setDaemon(true);
		araThread.setDaemon(true);
		speiThread.start();
		araThread.start();

		ControlServer controlServer = new ControlServer(config.controlPort(), speiServer, store,
				redVariacionRegistry, rechazoForzadoRegistry, config);
		controlServer.start();

		awaitListening(speiPort);
		awaitListening(araPort);
		awaitListening(controlPort);

		return new SimuladorHarness(speiServer, araServer, controlServer, store, config, identity, fakeMinos,
				speiPort, araPort, controlPort, tempDir, speiThread, araThread);
	}

	private static SimConfig loadConfigFrom(Path propsFile) throws IOException {
		String previous = System.getProperty("config");
		System.setProperty("config", propsFile.toString());
		try {
			return SimConfig.load();
		} finally {
			if (previous == null) {
				System.clearProperty("config");
			} else {
				System.setProperty("config", previous);
			}
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(true);
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}

	/** Espera (con reintentos cortos) a que el puerto acepte conexiones -- los servidores arrancan
	 *  en hilos propios, así que {@code new ServerSocket(port)} puede no haber corrido todavía justo
	 *  después de {@code Thread.start()}. */
	private static void awaitListening(int port) throws Exception {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		Exception last = null;
		while (System.nanoTime() < deadline) {
			try (Socket probe = new Socket()) {
				probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
				return;
			} catch (IOException e) {
				last = e;
				Thread.sleep(25);
			}
		}
		throw new IllegalStateException("El puerto " + port + " nunca empezó a aceptar conexiones", last);
	}

	@Override
	public void close() throws Exception {
		controlServer.stop();
		speiServer.stop();
		araServer.stop();
		speiThread.join(2000);
		araThread.join(2000);
		try {
			store.close();
		} catch (Exception ignored) {
			// apagado en curso
		}
		deleteRecursively(tempDir);
	}

	private static void deleteRecursively(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// mejor esfuerzo -- es un directorio temporal de prueba
				}
			});
		} catch (IOException ignored) {
			// mejor esfuerzo
		}
	}
}
