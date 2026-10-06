package mx.endcom.hermes.banxicosim.spei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Regresión del bug encontrado 2026-10-06 verificando spec 005 contra minosA real: un throttling
 * de bytes/segundo bajo (p.ej. 5 B/s) colgaba para siempre cualquier mensaje más grande que esa
 * tasa (un {@code Abonos} de 896 bytes nunca completó, ni tras 25+ minutos reales) -- el tope de
 * acumulación de tokens de {@link ThrottledOutputStream.TokenBucket} era igual a la tasa
 * configurada, así que nunca podía juntar más que eso. Si el bug reaparece, este test cuelga (no
 * falla con un mensaje claro) hasta que el timeout de JUnit lo mate -- es intencional: un `assert`
 * normal no puede distinguir "tardó mucho" de "nunca iba a terminar" sin esperar para siempre.
 */
class ThrottledOutputStreamTest {

	@Test
	void consumirUnMensajeMasGrandeQueLaTasaNoSeCuelgaParaSiempre() {
		ThrottledOutputStream.TokenBucket bucket = new ThrottledOutputStream.TokenBucket(0); // sin límite inicial
		bucket.setBytesPorSegundo(5); // tasa baja, como en la prueba real contra minosA

		// 10 bytes a 5 B/s: con el bug, tokens quedaba topado en 5 para siempre y esto nunca
		// retornaba. Corregido, debe completar en ~1s (margen generoso: 3s).
		assertTimeoutPreemptively(Duration.ofSeconds(3), () -> bucket.consumir(10));
	}

	@Test
	void consumirVariosMensajesGrandesSeguidosSigueAvanzando() {
		ThrottledOutputStream.TokenBucket bucket = new ThrottledOutputStream.TokenBucket(0);
		bucket.setBytesPorSegundo(20);

		// Tres mensajes de 30 bytes (mayores a la tasa) uno tras otro -- ninguno debe colgarse,
		// y el total debe tardar lo esperado (90 bytes / 20 B/s ~= 4.5s), no mucho más.
		long inicio = System.nanoTime();
		assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
			bucket.consumir(30);
			bucket.consumir(30);
			bucket.consumir(30);
		});
		double segundos = (System.nanoTime() - inicio) / 1_000_000_000.0;
		assertEquals(4.5, segundos, 2.5); // margen generoso para jitter del scheduler de hilos
	}
}
