package mx.endcom.hermes.banxicosim.spei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import mx.endcom.hermes.banxicosim.spei.messages.AcuseReciboCodec;
import mx.endcom.hermes.banxicosim.spei.messages.OrdenTopoVCodec;
import mx.endcom.hermes.banxicosim.testsupport.FakeMinosClient;
import mx.endcom.hermes.banxicosim.testsupport.SimuladorHarness;

/**
 * Spec 004 (H-&gt;B) -- una {@code OrdenTopoV} con la firma de la envoltura corrupta (mismo
 * tamaño/codificación que una firma real, un byte alterado -- ver
 * {@code WireFraming.signedBlockCorrupto}) debe rechazarse con el motivo correcto, no aceptarse ni
 * tronar con una excepción no manejada.
 *
 * <p><b>Hallazgo real en el camino (2026-10-09):</b> antes de este test, {@code handleOrdenTopoV}
 * calculaba {@code unwrapped.signatureVerified()} y lo logueaba, pero nunca lo exigía -- una orden
 * con firma de envoltura inválida se procesaba exactamente igual que una válida, pudiendo quedar
 * ACEPTADA. Corregido en {@code SpeiSession.handleOrdenTopoV}: si la firma no verifica, se
 * rechazan TODAS las órdenes del folioPack con motivo 19 (Carácter inválido -- el catálogo real de
 * devoluciones no tiene un motivo específico para "firma inválida", ver nota de clase de
 * {@code MotivoRechazo}) sin correr rechazo forzado, clave duplicada ni
 * {@code OrderFieldValidator} sobre un contenido que no se puede confiar que sea auténtico.</p>
 */
class FirmaCorruptaAcceptanceTest {

	private static final String DETALLE_VALIDO = String.join("\u0000",
			"ORDENANTE PRUEBA", "40", "100000000000000001", "XAXX010101000",
			"BENEFICIARIO PRUEBA", "40", "200000000000000001", "XAXX010101001",
			"Concepto de prueba", "0.0", "0524381", "123456");

	@Test
	void ordenConFirmaDeEnvolturaCorruptaSeRechazaConMotivo19() throws Exception {
		try (SimuladorHarness harness = SimuladorHarness.start();
				FakeMinosClient minos = new FakeMinosClient(harness.fakeMinos, "127.0.0.1")) {
			minos.connectSpei(harness.speiPort);
			minos.performSpeiHandshake(harness.config.speiUser());

			OrdenTopoVCodec.Order orden = new OrdenTopoVCodec.Order(
					(short) 1, new BigDecimal("10.00"), 1, "FIRMACORRUPTA" + System.nanoTime(), DETALLE_VALIDO);

			minos.sendOrdenTopoVConFirmaCorrupta(LocalDate.now(), 90646, 90999, 1, List.of(orden));
			AcuseReciboCodec.Parsed acuse = minos.parseAcuseRecibo(minos.readSpeiFrame());

			assertEquals(AcuseReciboCodec.STATUS_REJECTED, acuse.status(),
					"una orden con firma de envoltura corrupta nunca debe aceptarse, aunque su "
							+ "contenido sea por lo demás válido");
			assertEquals(1, acuse.errors().size());
			assertEquals((char) 19, acuse.errors().get(0).errorCode(),
					"motivo 19 = Carácter inválido -- fallback genérico, el catálogo real no tiene "
							+ "un motivo específico para firma inválida (ver MotivoRechazo)");
		}
	}

	@Test
	void variasOrdenesConFirmaDeEnvolturaCorruptaSeRechazanTodas() throws Exception {
		try (SimuladorHarness harness = SimuladorHarness.start();
				FakeMinosClient minos = new FakeMinosClient(harness.fakeMinos, "127.0.0.1")) {
			minos.connectSpei(harness.speiPort);
			minos.performSpeiHandshake(harness.config.speiUser());

			OrdenTopoVCodec.Order orden1 = new OrdenTopoVCodec.Order(
					(short) 1, new BigDecimal("10.00"), 1, "FIRMACORRUPTA-A" + System.nanoTime(), DETALLE_VALIDO);
			OrdenTopoVCodec.Order orden2 = new OrdenTopoVCodec.Order(
					(short) 2, new BigDecimal("20.00"), 1, "FIRMACORRUPTA-B" + System.nanoTime(), DETALLE_VALIDO);

			minos.sendOrdenTopoVConFirmaCorrupta(LocalDate.now(), 90646, 90999, 1, List.of(orden1, orden2));
			AcuseReciboCodec.Parsed acuse = minos.parseAcuseRecibo(minos.readSpeiFrame());

			assertEquals(AcuseReciboCodec.STATUS_REJECTED, acuse.status());
			assertEquals(2, acuse.errors().size(), "la envoltura cubre el folioPack completo -- "
					+ "si no es auténtica, ninguna orden del paquete se procesa, no solo la primera");
			assertTrue(acuse.errors().stream().allMatch(e -> e.errorCode() == (char) 19));
		}
	}
}
