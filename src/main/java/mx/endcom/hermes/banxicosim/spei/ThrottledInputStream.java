package mx.endcom.hermes.banxicosim.spei;

import java.io.IOException;
import java.io.InputStream;

/**
 * Spec 005 (capa A) -- ver {@link ThrottledOutputStream}, misma idea del lado de lectura.
 * {@code DataInputStream.readFully} (usado por {@code Frame.read}) llama {@link #read(byte[],
 * int, int)} en bucle hasta llenar el buffer -- nunca byte a byte -- por eso solo ese método
 * necesita pacing real.
 *
 * <p>El pacing se aplica DESPUÉS de leer los bytes reales del socket, no antes: capa A no
 * controla el socket TCP real (eso es capa B, {@code netem}), así que no puede retrasar la
 * llegada física de los bytes -- solo cuándo el resto del protocolo los ve. Suficiente para
 * degradar el timing de la sesión de forma observable (ver criterio de aceptación de
 * throttling).</p>
 */
final class ThrottledInputStream extends InputStream {

	private final InputStream real;
	private final ThrottledOutputStream.TokenBucket limite;

	ThrottledInputStream(InputStream real, ThrottledOutputStream.TokenBucket limite) {
		this.real = real;
		this.limite = limite;
	}

	@Override
	public int read() throws IOException {
		byte[] uno = new byte[1];
		int leidos = read(uno, 0, 1);
		return leidos < 0 ? -1 : (uno[0] & 0xFF);
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int leidos = real.read(b, off, len);
		if (leidos > 0) {
			limite.consumir(leidos);
		}
		return leidos;
	}

	@Override
	public void close() throws IOException {
		real.close();
	}
}
