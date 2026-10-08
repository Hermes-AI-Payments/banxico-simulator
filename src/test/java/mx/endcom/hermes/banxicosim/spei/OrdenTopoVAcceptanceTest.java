package mx.endcom.hermes.banxicosim.spei;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import mx.endcom.hermes.banxicosim.spei.messages.AcuseReciboCodec;
import mx.endcom.hermes.banxicosim.spei.messages.OrdenTopoVCodec;
import mx.endcom.hermes.banxicosim.testsupport.FakeMinosClient;
import mx.endcom.hermes.banxicosim.testsupport.SimuladorHarness;

/**
 * Spec 014 -- escenarios de recepción/rechazo de {@code OrdenTopoV} que se verifican mejor
 * aislados (arnés Java, bypass de Judeca) que a través del flujo completo Core falso -&gt; Judeca
 * -&gt; minos: Judeca tiene su PROPIA detección de clave de rastreo repetida ("Clave de rastreo en
 * uso") que intercepta un duplicado ANTES de que llegue a minos/este simulador -- confirmado en
 * vivo 2026-10-08 contra minosA real intentando reproducir el criterio de aceptación de spec 014
 * §"clave duplicada" por el camino normal. Eso prueba la protección de Judeca, no la nuestra (spec
 * 014 §3) -- este test aísla y prueba la nuestra directamente.
 *
 * <p><b>Motivos 16 (tipo de operación) y 17 (tipo de cuenta) fuera de este archivo a propósito:</b>
 * {@code OrderFieldValidator} los deja sin efecto por diseño mientras su catálogo externo
 * configurable esté vacío (el default) -- ver la nota de clase de {@code OrderFieldValidator} y
 * {@code validTipoCuenta}. No son alcanzables sin antes configurar esos catálogos, que es un
 * trabajo aparte (no un bug de este test).</p>
 */
class OrdenTopoVAcceptanceTest {

	private static final String DETALLE_VALIDO = String.join("\u0000",
			"ORDENANTE PRUEBA", "40", "100000000000000001", "XAXX010101000",
			"BENEFICIARIO PRUEBA", "40", "200000000000000001", "XAXX010101001",
			"Concepto de prueba", "0.0", "0524381", "123456");

	@Test
	void claveDeRastreoRepetidaSeRechazaConMotivo30() throws Exception {
		try (SimuladorHarness harness = SimuladorHarness.start();
				FakeMinosClient minos = new FakeMinosClient(harness.fakeMinos, "127.0.0.1")) {
			minos.connectSpei(harness.speiPort);
			minos.performSpeiHandshake(harness.config.speiUser());

			String claveRastreo = "ARNES" + System.nanoTime();
			OrdenTopoVCodec.Order orden = new OrdenTopoVCodec.Order(
					(short) 1, new BigDecimal("10.00"), 1, claveRastreo, DETALLE_VALIDO);

			minos.sendOrdenTopoV(LocalDate.now(), 90646, 90999, 1, List.of(orden));
			AcuseReciboCodec.Parsed primero = minos.parseAcuseRecibo(minos.readSpeiFrame());
			assertEquals(AcuseReciboCodec.STATUS_ACCEPTED, primero.status(),
					"la primera orden con esta clave debe aceptarse");
			// Una orden aceptada dispara Cargos automático (modo inmediato, spec 014 §4) --
			// hay que consumirlo antes de seguir, si no el siguiente read se lo lleva por delante.
			Frame cargos = minos.readSpeiFrame();
			assertEquals(SpeiProtocol.OP_CARGOS, cargos.operation());

			// Misma clave de rastreo, segunda orden -- spec 014 §3: debe rechazarse con motivo 30,
			// sin importar que sea la misma sesión (la detección persiste en H2 por día operativo,
			// no en memoria de sesión -- ver H2Store.claveYaVista, por eso también sobrevive una
			// reconexión real, confirmado en vivo con el saldo de Cargos el mismo día).
			OrdenTopoVCodec.Order repetida = new OrdenTopoVCodec.Order(
					(short) 2, new BigDecimal("20.00"), 1, claveRastreo, DETALLE_VALIDO);
			minos.sendOrdenTopoV(LocalDate.now(), 90646, 90999, 2, List.of(repetida));
			AcuseReciboCodec.Parsed segundo = minos.parseAcuseRecibo(minos.readSpeiFrame());
			assertEquals(AcuseReciboCodec.STATUS_REJECTED, segundo.status(),
					"la segunda orden con la misma clave debe rechazarse");
			assertEquals(1, segundo.errors().size());
			assertEquals((char) 30, segundo.errors().get(0).errorCode(),
					"motivo 30 = clave de rastreo repetida");
		}
	}

	@Test
	void campoObligatorioVacioSeRechazaConMotivo14() throws Exception {
		try (SimuladorHarness harness = SimuladorHarness.start();
				FakeMinosClient minos = new FakeMinosClient(harness.fakeMinos, "127.0.0.1")) {
			minos.connectSpei(harness.speiPort);
			minos.performSpeiHandshake(harness.config.speiUser());

			// Mismo detalle válido pero con conceptoPg (campo obligatorio, posición 9 de 12 para
			// tipoPg 1 -- ver PaymentType.TERCERO_A_TERCERO) vacío.
			String detalleConCampoVacio = String.join("\u0000",
					"ORDENANTE PRUEBA", "40", "100000000000000001", "XAXX010101000",
					"BENEFICIARIO PRUEBA", "40", "200000000000000001", "XAXX010101001",
					"", "0.0", "0524381", "123456");
			OrdenTopoVCodec.Order orden = new OrdenTopoVCodec.Order(
					(short) 1, new BigDecimal("10.00"), 1, "ARNES" + System.nanoTime(), detalleConCampoVacio);

			minos.sendOrdenTopoV(LocalDate.now(), 90646, 90999, 1, List.of(orden));
			AcuseReciboCodec.Parsed acuse = minos.parseAcuseRecibo(minos.readSpeiFrame());
			assertEquals(AcuseReciboCodec.STATUS_REJECTED, acuse.status());
			assertEquals((char) 14, acuse.errors().get(0).errorCode(),
					"motivo 14 = falta información mandatoria (conceptoPg vacío)");
		}
	}

	@Test
	void tipoPgFueraDeCatalogoSeRechazaConMotivo15() throws Exception {
		try (SimuladorHarness harness = SimuladorHarness.start();
				FakeMinosClient minos = new FakeMinosClient(harness.fakeMinos, "127.0.0.1")) {
			minos.connectSpei(harness.speiPort);
			minos.performSpeiHandshake(harness.config.speiUser());

			// tipoPg 99 no existe en el catálogo SPEI real (0-36, sin 13 ni 14).
			OrdenTopoVCodec.Order orden = new OrdenTopoVCodec.Order(
					(short) 1, new BigDecimal("10.00"), 99, "ARNES" + System.nanoTime(), "");

			minos.sendOrdenTopoV(LocalDate.now(), 90646, 90999, 1, List.of(orden));
			AcuseReciboCodec.Parsed acuse = minos.parseAcuseRecibo(minos.readSpeiFrame());
			assertEquals(AcuseReciboCodec.STATUS_REJECTED, acuse.status());
			assertEquals((char) 15, acuse.errors().get(0).errorCode(),
					"motivo 15 = tipo de pago erróneo (99 fuera de catálogo)");
		}
	}
}
