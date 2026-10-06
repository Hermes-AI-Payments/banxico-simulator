package mx.endcom.hermes.banxicosim.ara;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import mx.endcom.hermes.banxicosim.testsupport.FakeMinosClient;
import mx.endcom.hermes.banxicosim.testsupport.SimuladorHarness;

/**
 * Arnés de pruebas Java (spec 007, ver {@code specs/README.md} hallazgo 2026-09-21) -- a
 * diferencia de spec 003, aquí {@code AraSession.handlePideCrtNvo} ya funcionaba; lo que faltaba
 * era el escenario de prueba repetible. Ejercita los dos criterios de aceptación de
 * {@code specs/007-renovacion-certificado.md} contra una instancia real del simulador (arrancada
 * en proceso, puertos efímeros, ver {@link SimuladorHarness}) usando un "minos falso"
 * ({@link FakeMinosClient}) que hace el login ARA completo y manda {@code PideCrtNvo}.
 */
class PideCrtNvoAcceptanceTest {

	/**
	 * Criterio (a): {@code PideCrtNvo} pidiendo el número de certificado propio del simulador
	 * confirma {@code RegCrtNvoFmt} con el certificado correcto de la identidad actual (y la firma
	 * del mensaje ARA verifica contra la llave pública de esa misma identidad).
	 */
	@Test
	void pideCrtNvoConNumeroPropioConfirmaRegCrtNvoFmt() throws Exception {
		try (SimuladorHarness harness = SimuladorHarness.start();
				FakeMinosClient minos = new FakeMinosClient(harness.fakeMinos, "127.0.0.1")) {
			minos.connectAra(harness.araPort);
			minos.performAraLogin();

			AraFrame respuesta = minos.requestCertificate(harness.identity.certificateNumber());
			assertEquals(AraProtocol.OP_REG_CRT_NVO_FMT, respuesta.operation());

			FakeMinosClient.CertificadoRecibido certificado = minos.parseRegCrtNvoFmt(respuesta);
			assertEquals(harness.identity.certificatePem(), certificado.certificatePem(),
					"RegCrtNvoFmt debe traer el certificado de la identidad ACTUAL del simulador");
			assertTrue(certificado.signatureVerified(),
					"la firma de RegCrtNvoFmt debe verificar contra la llave pública de esa misma identidad");
		}
	}

	/**
	 * Criterio (b): {@code PideCrtNvo} pidiendo un número de certificado que no existe confirma
	 * {@code CrtNoExiste}, no un error no manejado.
	 */
	@Test
	void pideCrtNvoConNumeroInexistenteConfirmaCrtNoExiste() throws Exception {
		try (SimuladorHarness harness = SimuladorHarness.start();
				FakeMinosClient minos = new FakeMinosClient(harness.fakeMinos, "127.0.0.1")) {
			minos.connectAra(harness.araPort);
			minos.performAraLogin();

			AraFrame respuesta = minos.requestCertificate("9999999999-no-existe");
			assertEquals(AraProtocol.OP_CRT_NO_EXISTE, respuesta.operation());
		}
	}
}
