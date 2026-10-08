package mx.endcom.hermes.banxicosim.spei;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import mx.endcom.hermes.banxicosim.persistence.H2Store;
import mx.endcom.hermes.banxicosim.spei.messages.ReenvioCodec;
import mx.endcom.hermes.banxicosim.testsupport.FakeMinosClient;
import mx.endcom.hermes.banxicosim.testsupport.SimuladorHarness;

/**
 * Arnés de pruebas Java (specs 003/007, ver {@code specs/README.md} hallazgo 2026-09-21) --
 * ejercita los dos criterios de aceptación de {@code specs/003-reenvio.md} contra una instancia
 * real del simulador (arrancada en proceso, puertos efímeros, ver {@link SimuladorHarness}) usando
 * un "minos falso" ({@link FakeMinosClient}) que hace el handshake SPEI completo y manda
 * {@code Reenvio} con un {@code processedBytes} arbitrario.
 *
 * <p><b>Corregido 2026-10-08</b> (regresión real encontrada contra minosA): los puntos de corte
 * ya NO se arman sobre el handshake (Greeting..MsjCatalogos) -- minos real no cuenta esos bytes
 * para Reenvio (ver {@code SpeiSession.OPCODES_CUENTAN_PARA_REENVIO}), así que un corte dentro del
 * handshake no tiene nada que reenviar. Los dos tests ahora disparan Abonos reales vía la API de
 * control (mismo mecanismo que un escenario real) para tener bytes de CONTENIDO que cortar.</p>
 */
class ReenvioAcceptanceTest {

	/**
	 * Criterio (a): el arnés manda {@code Reenvio} con {@code processedBytes} apuntando a un punto
	 * intermedio conocido -- justo después de un primer Abono de contenido -- y el simulador
	 * reenvía exactamente los bytes de un segundo Abono posterior, tal cual se mandó la primera
	 * vez -- verificado tanto directamente contra el socket como contra el evento registrado en
	 * {@code /test-runs/{id}/events}.
	 */
	@Test
	void reenvioDesdePuntoIntermedioReenviaBytesExactos() throws Exception {
		try (SimuladorHarness harness = SimuladorHarness.start();
				FakeMinosClient minos = new FakeMinosClient(harness.fakeMinos, "127.0.0.1")) {
			minos.connectSpei(harness.speiPort);
			minos.performSpeiHandshake(harness.config.speiUser());

			triggerAbonoValido(harness.controlPort);
			minos.readSpeiFrame(); // Abono #1 -- contenido, sí cuenta (framesDelSimulador[0])
			int cutPoint = (int) minos.offsetAfterFrames(1);

			triggerAbonoValido(harness.controlPort);
			minos.readSpeiFrame(); // Abono #2 -- lo que esperamos que se reenvíe
			byte[] expectedBytes = minos.expectedBytesFromFrame(1);
			assertTrue(expectedBytes.length > 0, "la prueba necesita que haya algo que reenviar");

			minos.sendReenvio(cutPoint);

			byte[] resent = new byte[expectedBytes.length];
			int pos = 0;
			while (pos < resent.length) {
				Frame frame = minos.readSpeiFrame();
				byte[] raw = frame.toBytes();
				System.arraycopy(raw, 0, resent, pos, raw.length);
				pos += raw.length;
			}
			assertArrayEquals(expectedBytes, resent,
					"el simulador debe reenviar exactamente los bytes posteriores al punto de corte pedido");

			Frame finReenvioFrame = minos.readSpeiFrame();
			ReenvioCodec.FinReenvio finReenvio = minos.decryptFinReenvio(finReenvioFrame);
			assertEquals(0, finReenvio.bytesServerToClient());

			// Verificación adicional contra /test-runs/{id}/events (spec 003, criterio "a"): el
			// mismo H2Store que expone ese endpoint HTTP debe tener un evento ReenvioBytes con el
			// hex exacto de lo reenviado.
			long runId = harness.speiServer.lastSession().runId();
			H2Store.TestEvent reenvioBytesEvent = harness.store.listEventsForRun(runId).stream()
					.filter(e -> "ReenvioBytes".equals(e.messageName()))
					.findFirst()
					.orElseThrow(() -> new AssertionError(
							"No se registró el evento ReenvioBytes en /test-runs/{id}/events"));
			assertEquals(toHex(expectedBytes), reenvioBytesEvent.rawHex(),
					"el evento ReenvioBytes en /test-runs/{id}/events debe registrar exactamente los bytes reenviados");
		}
	}

	/**
	 * Criterio (b): un {@code Reenvio} con {@code processedBytes} igual al total ya mandado (nada
	 * que reenviar) se responde con {@code FinReenvio} sin contenido adicional y sin error -- no
	 * debe llegar ningún frame de reenvío antes del {@code FinReenvio}. Se dispara un Abono real
	 * primero para que "el total ya mandado" sea un valor de contenido real, no solo 0 por un
	 * handshake que ya no cuenta.
	 */
	@Test
	void reenvioSinNadaPendienteRespondeFinReenvioSinError() throws Exception {
		try (SimuladorHarness harness = SimuladorHarness.start();
				FakeMinosClient minos = new FakeMinosClient(harness.fakeMinos, "127.0.0.1")) {
			minos.connectSpei(harness.speiPort);
			minos.performSpeiHandshake(harness.config.speiUser());

			triggerAbonoValido(harness.controlPort);
			minos.readSpeiFrame();
			int total = (int) minos.totalBytesDelSimulador();
			assertTrue(total > 0, "la prueba necesita que ya se haya mandado contenido real");
			minos.sendReenvio(total);

			// El siguiente frame debe ser FinReenvio directamente -- nada que reenviar primero.
			Frame finReenvioFrame = minos.readSpeiFrame();
			assertEquals(SpeiProtocol.OP_FINREENVIO, finReenvioFrame.operation());
			ReenvioCodec.FinReenvio finReenvio = minos.decryptFinReenvio(finReenvioFrame);
			assertEquals(0, finReenvio.bytesServerToClient());
		}
	}

	private static void triggerAbonoValido(int controlPort) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + controlPort + "/abonos/validos"))
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), "disparar el abono de prueba debe responder 200: " + response.body());
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(String.format("%02X", b));
		}
		return sb.toString();
	}
}
